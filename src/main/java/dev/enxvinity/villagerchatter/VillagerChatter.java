package dev.enxvinity.villagerchatter;

import dev.enxvinity.villagerchatter.net.DialogueChoiceC2S;
import dev.enxvinity.villagerchatter.net.DialogueStateS2C;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Villager Chatter.
 *
 * Every second, for each player, look for villagers nearby. Now and then one of them
 * decides to talk. If the AI is available we ask it for a line in the background
 * (the game never waits for it); when the answer comes back, it's delivered on a
 * later tick. If the AI is off/offline/weird, a hand-written line is used instead.
 */
public class VillagerChatter implements ModInitializer {
	public static final String MOD_ID = "villagerchatter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int CHECK_EVERY_TICKS = 20; // 20 ticks = 1 second
	private static final int MAX_IN_FLIGHT = 4;       // max AI requests running at once

	/** A line that's ready to be said (filled in by the AI thread, delivered by the game thread). */
	private record Delivery(UUID playerId, Villager speaker, ChatterLines.Situation situation, String line, boolean fromAi) {}

	private final Map<UUID, Long> villagerNextTalk = new HashMap<>();
	private final Map<UUID, Long> playerNextHear = new HashMap<>();
	private final Queue<Delivery> ready = new ConcurrentLinkedQueue<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	/** Players waiting on an AI line (so a slow reply can't stack up with the next one). */
	private final java.util.Set<UUID> awaiting = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private ChatterConfig config;
	private AiLines ai;
	private final SpeechBubbles bubbles = new SpeechBubbles();
	private static DialogueManager dialogue;

	public static DialogueManager dialogue() {
		return dialogue;
	}

	@Override
	public void onInitialize() {
		config = ChatterConfig.load();
		ai = new AiLines(config);
		ai.warmUp();
		PayloadTypeRegistry.clientboundPlay().register(DialogueStateS2C.TYPE, DialogueStateS2C.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DialogueChoiceC2S.TYPE, DialogueChoiceC2S.CODEC);
		dialogue = new DialogueManager(config, ai);
		dialogue.register();
		LOGGER.info("Villager Chatter loaded — AI {} (model {}).",
				config.aiEnabled ? "on" : "off", config.model);
		ServerTickEvents.END_LEVEL_TICK.register(this::onLevelTick);
		// Clean up any bubble left behind in a saved world (e.g. the game closed mid-sentence).
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display.TextDisplay td && td.entityTags().contains(SpeechBubbles.TAG) && !bubbles.isOurs(td)) {
				td.discard();
			}
		});
	}

	private void onLevelTick(ServerLevel level) {
		deliverReadyLines(level);
		bubbles.tick(level);

		long now = level.getGameTime();
		if (now % CHECK_EVERY_TICKS != 0) return;

		RandomSource random = level.getRandom();
		long villagerCooldown = 20L * config.villagerCooldownSeconds;
		long playerCooldown = 20L * config.playerCooldownSeconds;

		for (ServerPlayer player : level.players()) {
			if (now < playerNextHear.getOrDefault(player.getUUID(), 0L)) continue;
			// One speaker at a time: wait until nobody nearby is mid-sentence (plus a short pause),
			// and until this player's last AI request has come back.
			if (awaiting.contains(player.getUUID())) continue;
			if (bubbles.anyActiveNear(level, player.getX(), player.getY(), player.getZ(), config.hearingRange * 3, 60)) continue;

			List<Villager> nearby = level.getEntitiesOfClass(
					Villager.class,
					player.getBoundingBox().inflate(config.hearingRange),
					v -> v.isAlive() && !v.isTrading() && now >= villagerNextTalk.getOrDefault(v.getUUID(), 0L)
			);
			if (nearby.isEmpty() || random.nextFloat() > config.talkChance) continue;

			Villager speaker = nearby.get(random.nextInt(nearby.size()));
			ChatterLines.Situation situation = ChatterLines.describe(level, speaker, player);
			// Pick the fallback now, on the game thread (RandomSource isn't thread-safe).
			String fallback = ChatterLines.pick(situation, random);
			UUID playerId = player.getUUID();

			villagerNextTalk.put(speaker.getUUID(), now + villagerCooldown);
			playerNextHear.put(playerId, now + playerCooldown);

			if (ai.available() && inFlight.get() < MAX_IN_FLIGHT) {
				inFlight.incrementAndGet();
				awaiting.add(playerId);
				ai.requestLine(situation).whenComplete((result, err) -> {
					inFlight.decrementAndGet();
					awaiting.remove(playerId);
					boolean gotAi = err == null && result != null && result.isPresent();
					ready.add(new Delivery(playerId, speaker, situation, gotAi ? result.get() : fallback, gotAi));
				});
			} else {
				ready.add(new Delivery(playerId, speaker, situation, fallback, false));
			}
		}

		// Housekeeping so the maps don't grow forever.
		if (now % (20 * 60 * 5) == 0) {
			dialogue.tick(now);
			villagerNextTalk.values().removeIf(t -> t < now);
			playerNextHear.values().removeIf(t -> t < now);
		}
	}

	private void deliverReadyLines(ServerLevel level) {
		Delivery d;
		while ((d = ready.poll()) != null) {
			Villager v = d.speaker();
			if (v.level() != level) { ready.add(d); break; } // belongs to another dimension's tick
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(d.playerId());
			String name = d.situation().displayName();

			if (config.showBubbles && v.isAlive()) bubbles.show(level, v, d.line());
			if ((config.showInChat || !config.showBubbles) && player != null) {
				player.sendSystemMessage(
						Component.literal("<" + name + "> ").withStyle(ChatFormatting.GREEN)
								.append(Component.literal(d.line()).withStyle(ChatFormatting.WHITE))
				);
			}
			if (config.particles && v.isAlive()) playMood(level, v, d.situation());
			LOGGER.info("[{}] <{}> {}", d.fromAi() ? "AI" : "hand-written", name, d.line());
		}
	}

	/** Vanilla villager particles: angry puff, sweat drops, hearts, or green sparkles. */
	private static void playMood(ServerLevel level, Villager v, ChatterLines.Situation s) {
		RandomSource r = level.getRandom();
		if (s.villagerHurt() || s.raid()) {
			level.broadcastEntityEvent(v, EntityEvent.VILLAGER_ANGRY);
		} else if (s.monsterNearby()) {
			level.broadcastEntityEvent(v, EntityEvent.VILLAGER_SWEAT);
		} else if (s.baby() && r.nextFloat() < 0.4f) {
			level.broadcastEntityEvent(v, EntityEvent.LOVE_HEARTS);
		} else if (r.nextFloat() < 0.3f) {
			level.broadcastEntityEvent(v, EntityEvent.VILLAGER_HAPPY);
		}
	}
}
