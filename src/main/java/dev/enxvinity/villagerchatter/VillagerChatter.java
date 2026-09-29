package dev.enxvinity.villagerchatter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
	private record Delivery(UUID playerId, String speakerName, String line, boolean fromAi) {}

	private final Map<UUID, Long> villagerNextTalk = new HashMap<>();
	private final Map<UUID, Long> playerNextHear = new HashMap<>();
	private final Queue<Delivery> ready = new ConcurrentLinkedQueue<>();
	private final AtomicInteger inFlight = new AtomicInteger();

	private ChatterConfig config;
	private AiLines ai;

	@Override
	public void onInitialize() {
		config = ChatterConfig.load();
		ai = new AiLines(config);
		ai.warmUp();
		LOGGER.info("Villager Chatter loaded — AI {} (model {}).",
				config.aiEnabled ? "on" : "off", config.model);
		ServerTickEvents.END_LEVEL_TICK.register(this::onLevelTick);
	}

	private void onLevelTick(ServerLevel level) {
		deliverReadyLines(level);

		long now = level.getGameTime();
		if (now % CHECK_EVERY_TICKS != 0) return;

		RandomSource random = level.getRandom();
		long villagerCooldown = 20L * config.villagerCooldownSeconds;
		long playerCooldown = 20L * config.playerCooldownSeconds;

		for (ServerPlayer player : level.players()) {
			if (now < playerNextHear.getOrDefault(player.getUUID(), 0L)) continue;

			List<Villager> nearby = level.getEntitiesOfClass(
					Villager.class,
					player.getBoundingBox().inflate(config.hearingRange),
					v -> v.isAlive() && now >= villagerNextTalk.getOrDefault(v.getUUID(), 0L)
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
				ai.requestLine(situation).whenComplete((result, err) -> {
					inFlight.decrementAndGet();
					boolean gotAi = err == null && result != null && result.isPresent();
					ready.add(new Delivery(playerId, situation.displayName(), gotAi ? result.get() : fallback, gotAi));
				});
			} else {
				ready.add(new Delivery(playerId, situation.displayName(), fallback, false));
			}
		}

		// Housekeeping so the maps don't grow forever.
		if (now % (20 * 60 * 5) == 0) {
			villagerNextTalk.values().removeIf(t -> t < now);
			playerNextHear.values().removeIf(t -> t < now);
		}
	}

	private void deliverReadyLines(ServerLevel level) {
		Delivery d;
		while ((d = ready.poll()) != null) {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(d.playerId());
			if (player == null) continue; // they logged off while the AI was thinking
			player.sendSystemMessage(
					Component.literal("<" + d.speakerName() + "> ").withStyle(ChatFormatting.GREEN)
							.append(Component.literal(d.line()).withStyle(ChatFormatting.WHITE))
			);
			LOGGER.info("[{}] <{}> {}", d.fromAi() ? "AI" : "hand-written", d.speakerName(), d.line());
		}
	}
}
