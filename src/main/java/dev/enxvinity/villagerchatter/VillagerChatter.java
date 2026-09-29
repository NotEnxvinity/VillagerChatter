package dev.enxvinity.villagerchatter;

import dev.enxvinity.villagerchatter.net.DialogueChoiceC2S;
import dev.enxvinity.villagerchatter.net.DialogueStateS2C;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.item.trading.MerchantOffer;
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
	private final Map<UUID, Long> lastOuch = new HashMap<>();
	private final Queue<Delivery> ready = new ConcurrentLinkedQueue<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	/** Players waiting on an AI line (so a slow reply can't stack up with the next one). */
	private final java.util.Set<UUID> awaiting = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private ChatterConfig config;
	private AiLines ai;
	private final SpeechBubbles bubbles = new SpeechBubbles();
	private static DialogueManager dialogue;

	private static VillagerChatter instance;

	public static VillagerChatter instance() {
		return instance;
	}

	public static DialogueManager dialogue() {
		return dialogue;
	}

	@Override
	public void onInitialize() {
		instance = this;
		config = ChatterConfig.load();
		VillagerMemory.init();
		ai = new AiLines(config);
		ai.warmUp();
		PayloadTypeRegistry.clientboundPlay().register(DialogueStateS2C.TYPE, DialogueStateS2C.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DialogueChoiceC2S.TYPE, DialogueChoiceC2S.CODEC);
		dialogue = new DialogueManager(config, ai);
		dialogue.register();
		LOGGER.info("Villager Chatter loaded — AI {} (model {}).",
				config.aiEnabled ? "on" : "off", config.model);
		ServerTickEvents.END_LEVEL_TICK.register(this::onLevelTick);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		// Tell players joining while the AI is still downloading why villagers sound simple.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
			LocalRuntime rt = ai.runtime();
			if (config.aiEnabled && !"ollama".equalsIgnoreCase(config.backend)
					&& (rt.state() == LocalRuntime.State.DOWNLOADING || rt.state() == LocalRuntime.State.STARTING)) {
				handler.player.sendSystemMessage(tag().append(Component.literal("Villager AI is " + rt.status() + "…")));
			}
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			if (!blocked && entity instanceof Villager v && source.getEntity() instanceof ServerPlayer p) onHit(p, v);
		});
		// Clean up any bubble left behind in a saved world (e.g. the game closed mid-sentence).
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display.TextDisplay td && td.entityTags().contains(SpeechBubbles.TAG) && !bubbles.isOurs(td)) {
				td.discard();
			}
		});
	}

	private void onLevelTick(ServerLevel level) {
		deliverReadyLines(level);
		runScenes(level);
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
			if (ai.available() && random.nextFloat() < config.smallTalkChance && tryStartSmallTalk(level, player, speaker, now)) continue;
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

	// ---------------- Small talk between two villagers ----------------

	private record SceneLine(Villager speaker, Villager listener, String text, long at) {}
	private final java.util.List<SceneLine> sceneLines = new java.util.ArrayList<>();
	private final Queue<java.util.List<SceneLine>> readyScenes = new ConcurrentLinkedQueue<>();

	private boolean tryStartSmallTalk(ServerLevel level, ServerPlayer player, Villager a, long now) {
		List<Villager> partners = level.getEntitiesOfClass(Villager.class, a.getBoundingBox().inflate(4.0),
				v -> v != a && v.isAlive() && !v.isTrading() && !v.isSleeping());
		if (partners.isEmpty() || a.isSleeping()) return false;
		Villager b = partners.get(level.getRandom().nextInt(partners.size()));
		ChatterLines.Situation sa = ChatterLines.describe(level, a, player);
		ChatterLines.Situation sb = ChatterLines.describe(level, b, player);
		String scene = "A: " + job(sa) + ". B: " + job(sb) + ". " + capitalize(sa.biome().replace('_', ' ')) + " village. Time: "
				+ (sa.night() ? "night" : "day") + ". Weather: " + (sa.thundering() ? "thunderstorm" : sa.raining() ? "raining" : "clear") + "."
				+ (sa.raid() ? " The village is being raided!" : sa.monsterNearby() ? " A zombie is nearby!" : "");
		UUID playerId = player.getUUID();
		long hold = 20L * 30; // keep both busy while the scene is generated and played
		villagerNextTalk.put(a.getUUID(), now + hold);
		villagerNextTalk.put(b.getUUID(), now + hold);
		playerNextHear.put(playerId, now + 20L * config.playerCooldownSeconds);
		awaiting.add(playerId);
		ai.requestSmallTalk(scene).whenComplete((result, err) -> {
			awaiting.remove(playerId);
			if (err != null || result == null || result.isEmpty()) return; // just skip; someone else will talk later
			java.util.List<SceneLine> lines = new java.util.ArrayList<>();
			for (AiLines.TalkLine t : result.get()) {
				lines.add(new SceneLine(t.firstSpeaker() ? a : b, t.firstSpeaker() ? b : a, t.text(), 0));
			}
			readyScenes.add(lines);
		});
		LOGGER.info("[small talk] {} and {} are chatting…", sa.displayName(), sb.displayName());
		return true;
	}

	private void runScenes(ServerLevel level) {
		long now = level.getGameTime();
		java.util.List<SceneLine> scene;
		while ((scene = readyScenes.poll()) != null) {
			if (scene.get(0).speaker().level() != level) { readyScenes.add(scene); break; }
			long t = now;
			for (SceneLine l : scene) {
				sceneLines.add(new SceneLine(l.speaker(), l.listener(), l.text(), t));
				t += Math.min(110, 45 + l.text().length() * 3L / 2); // give each line time to be read
			}
		}
		java.util.Iterator<SceneLine> it = sceneLines.iterator();
		while (it.hasNext()) {
			SceneLine l = it.next();
			if (l.speaker().level() != level) continue;
			if (!l.speaker().isAlive() || !l.listener().isAlive()) { it.remove(); continue; }
			l.speaker().getLookControl().setLookAt(l.listener());
			l.listener().getLookControl().setLookAt(l.speaker());
			if (now < l.at()) continue;
			it.remove();
			if (config.showBubbles) bubbles.show(level, l.speaker(), l.text());
			String name = ChatterLines.displayName(l.speaker());
			if (config.showInChat || !config.showBubbles) {
				for (ServerPlayer p : level.players()) {
					if (p.distanceToSqr(l.speaker()) < 24 * 24) {
						p.sendSystemMessage(Component.literal("<" + name + "> ").withStyle(ChatFormatting.GREEN)
								.append(Component.literal(l.text()).withStyle(ChatFormatting.WHITE)));
					}
				}
			}
			LOGGER.info("[small talk] <{}> {}", name, l.text());
		}
	}

	private static String job(ChatterLines.Situation s) {
		return s.baby() ? "baby villager" : s.profession().equals("none") ? "unemployed villager" : s.profession();
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private volatile MinecraftServer server;

	private static net.minecraft.network.chat.MutableComponent tag() {
		return Component.literal("[Villager Chatter] ").withStyle(ChatFormatting.GOLD);
	}

	/** Short status message to everyone in the world (e.g. "AI ready"). Safe to call from any thread. */
	public void announce(String text) {
		MinecraftServer s = server;
		if (s == null) return;
		s.execute(() -> s.getPlayerList().getPlayers().forEach(p ->
				p.sendSystemMessage(tag().append(Component.literal(text).withStyle(ChatFormatting.GRAY)))));
	}

	/** A player hit a villager: remember it, and react right away (skips the normal cooldown). */
	private void onHit(ServerPlayer player, Villager v) {
		VillagerMemory.update(v, player, VillagerMemory.Rel::withHit);
		ServerLevel level = (ServerLevel) v.level();
		long now = level.getGameTime();
		if (now < lastOuch.getOrDefault(v.getUUID(), 0L) || !v.isAlive()) return;
		lastOuch.put(v.getUUID(), now + 40);
		String line = ChatterLines.OUCH.get(level.getRandom().nextInt(ChatterLines.OUCH.size()));
		if (config.showBubbles) bubbles.show(level, v, line);
		else player.sendSystemMessage(Component.literal("<Villager> ").withStyle(ChatFormatting.GREEN).append(Component.literal(line)));
		if (config.particles) level.broadcastEntityEvent(v, EntityEvent.VILLAGER_ANGRY);
		villagerNextTalk.put(v.getUUID(), now + 20L * config.villagerCooldownSeconds);
	}

	/** A trade completed: remember it, sparkle, and let the trade-screen conversation react. */
	public void onTrade(ServerPlayer player, Villager v, MerchantOffer offer) {
		VillagerMemory.update(v, player, VillagerMemory.Rel::withTrade);
		if (config.particles) v.level().broadcastEntityEvent(v, EntityEvent.VILLAGER_HAPPY);
		dialogue.onTraded(player, v, offer);
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
