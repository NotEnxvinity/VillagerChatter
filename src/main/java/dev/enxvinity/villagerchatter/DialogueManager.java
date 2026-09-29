package dev.enxvinity.villagerchatter;

import dev.enxvinity.villagerchatter.net.DialogueChoiceC2S;
import dev.enxvinity.villagerchatter.net.DialogueStateS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Conversations in the trade screen.
 *
 * Rules (from the design talk with Enx):
 *  - Opening trades starts (or resumes) a conversation with that villager.
 *  - A NEW greeting can only be generated once per {@link #GREETING_COOLDOWN_TICKS} per villager+player;
 *    reopening sooner just resumes where you left off (no spam, no dead panel).
 *  - The villager remembers the conversation for {@link #MEMORY_TICKS} after the last message.
 *  - 3 AI-written replies + a fixed "Let's just trade." option.
 *  - While the AI is thinking, the buttons are disabled (the client shows "…").
 */
public final class DialogueManager {
	private static final long GREETING_COOLDOWN_TICKS = 20 * 60;   // 60 s
	private static final long MEMORY_TICKS = 20 * 60 * 5;          // 5 min
	private static final int MAX_HISTORY = 8;                      // messages kept in the prompt
	/** If the AI fails mid-conversation, stay conversational instead of blurting an ambient line. */
	private static final List<String> FALLBACK_LINES = List.of(
			"Hrmm. Lost my train of thought.", "Sorry, what were we talking about?", "Hmm? Oh, right. Where was I?",
			"Hrmm… it's been a long day.", "Let me think about that one.");
	private static final List<String> FALLBACK_REPLIES = List.of(
			"What's good today?", "How's village life?", "Nice outfit.",
			"Any gossip?", "Got a discount for me?", "What do you do all day?");

	private static final class Conversation {
		final UUID villagerId;
		Villager villager;
		final List<AiLines.Said> history = new ArrayList<>();
		String speaker = "Villager";
		String line = "";
		List<String> replies = List.of();
		boolean thinking = false;
		long lastActive;
		long lastGreeting = Long.MIN_VALUE / 2;
		long lastChoice = Long.MIN_VALUE / 2;
		long lastTradeReaction = Long.MIN_VALUE / 2;
		String opener = "The player just opened your trades.";

		Conversation(UUID villagerId) { this.villagerId = villagerId; }
	}

	private final ChatterConfig config;
	private final AiLines ai;
	private final Map<String, Conversation> conversations = new HashMap<>();
	/** Which conversation each player is currently in (while their trade screen is open). */
	private final Map<UUID, String> activeByPlayer = new HashMap<>();

	public DialogueManager(ChatterConfig config, AiLines ai) {
		this.config = config;
		this.ai = ai;
	}

	public void register() {
		ServerPlayNetworking.registerGlobalReceiver(DialogueChoiceC2S.TYPE,
				(payload, context) -> context.server().execute(() -> onChoice(context.player(), payload.index())));
	}

	private static String key(ServerPlayer p, Villager v) {
		return p.getUUID() + "|" + v.getUUID();
	}

	public void onTradeStart(ServerPlayer player, Villager villager) {
		if (!config.dialogueEnabled) return;
		ServerLevel level = (ServerLevel) villager.level();
		long now = level.getGameTime();
		String key = key(player, villager);
		Conversation conv = conversations.get(key);
		if (conv != null && now - conv.lastActive > MEMORY_TICKS) conv = null; // forgot you
		if (conv == null) {
			conv = new Conversation(villager.getUUID());
			conversations.put(key, conv);
		}
		conv.villager = villager;
		activeByPlayer.put(player.getUUID(), key);

		ChatterLines.Situation s = ChatterLines.describe(level, villager, player);
		conv.speaker = s.displayName();

		if (conv.thinking || now - conv.lastGreeting < GREETING_COOLDOWN_TICKS) {
			send(player, conv); // resume where we left off
			return;
		}
		conv.lastGreeting = now;
		conv.lastActive = now;
		boolean returning = !conv.history.isEmpty();
		conv.opener = returning ? "The player came back and opened your trades again." : "The player just opened your trades.";
		String fallback = ChatterLines.pick(s, level.getRandom());
		generate(player, villager, conv, context(s, villager, player), conv.opener, fallback);
	}

	private void onChoice(ServerPlayer player, int index) {
		String key = activeByPlayer.get(player.getUUID());
		Conversation conv = key == null ? null : conversations.get(key);
		if (conv == null || conv.thinking) return;

		ServerLevel level = (ServerLevel) player.level();
		Villager villager = conv.villager;
		if (villager == null || !villager.isAlive() || villager.getTradingPlayer() != player) return;
		long now = level.getGameTime();
		if (now - conv.lastChoice < 10) return; // half-second anti-spam
		conv.lastChoice = now;
		conv.lastActive = now;

		if (index == 3 || index < 0 || index >= conv.replies.size()) {
			conv.line = "Alright, happy trading!";
			conv.replies = List.of();
			send(player, conv);
			return;
		}

		String said = conv.replies.get(index);
		conv.history.add(new AiLines.Said(false, said, List.of()));
		ChatterLines.Situation s = ChatterLines.describe(level, villager, player);
		String fallback = FALLBACK_LINES.get(level.getRandom().nextInt(FALLBACK_LINES.size()));
		generate(player, villager, conv, context(s, villager, player), conv.opener, fallback);
	}

	private void generate(ServerPlayer player, Villager villager, Conversation conv, String scene, String event, String fallbackLine) {
		conv.thinking = true;
		send(player, conv);

		List<AiLines.Said> history = new ArrayList<>(conv.history);
		var future = ai.available()
				? ai.requestDialogue(scene, event, history)
				: java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.<AiLines.Turn>empty());

		future.whenComplete((result, err) -> player.level().getServer().execute(() -> {
			AiLines.Turn turn = (err == null && result != null && result.isPresent())
					? result.get()
					: new AiLines.Turn(fallbackLine, List.of());
			List<String> replies = fillReplies(turn.replies(), villager);
			conv.line = turn.line();
			conv.replies = replies;
			conv.thinking = false;
			conv.history.add(new AiLines.Said(true, turn.line(), replies));
			if (villager.isAlive()) VillagerMemory.update(villager, player, r -> r.withLast(turn.line()));
			while (conv.history.size() > MAX_HISTORY) conv.history.remove(0);
			VillagerChatter.LOGGER.info("[dialogue] <{}> {} | replies: {}", conv.speaker, conv.line, replies);

			if (config.particles && villager.isAlive()) {
				villager.level().broadcastEntityEvent(villager, EntityEvent.VILLAGER_HAPPY);
			}
			// Only send if they're still looking at this villager's trades.
			String key = activeByPlayer.get(player.getUUID());
			if (key != null && conversations.get(key) == conv && villager.getTradingPlayer() == player) {
				send(player, conv);
			}
		}));
	}

	private static List<String> fillReplies(List<String> fromAi, Villager villager) {
		Set<String> out = new LinkedHashSet<>(fromAi);
		List<String> pool = new ArrayList<>(FALLBACK_REPLIES);
		java.util.Collections.shuffle(pool);
		for (String r : pool) {
			if (out.size() >= 3) break;
			out.add(r);
		}
		return new ArrayList<>(out).subList(0, 3);
	}

	/** Who the villager is, what it sells, and what's going on — the AI's "scene". */
	private static String context(ChatterLines.Situation s, Villager villager, ServerPlayer player) {
		// Describe real trades from the villager's point of view, e.g.
		// "You buy 20 Wheat for 1 Emerald. You sell 6 Bread for 1 Emerald."
		List<String> trades = new ArrayList<>();
		for (MerchantOffer offer : villager.getOffers()) {
			String t = tradeText(offer);
			trades.add(t + (offer.isOutOfStock() ? " (sold out)" : ""));
			if (trades.size() >= 4) break;
		}
		String job = s.baby() ? "baby villager" : (s.profession().equals("none") ? "unemployed villager" : s.profession());
		StringBuilder sb = new StringBuilder();
		sb.append("a ").append(job).append(" in a ").append(s.biome().replace('_', ' ')).append(" village. ");
		if (!trades.isEmpty()) sb.append("Your trades: ").append(String.join(". ", trades)).append(". Emeralds are the currency. ");
		sb.append("Time: ").append(s.night() ? "night" : "day").append(". ");
		sb.append("Weather: ").append(s.thundering() ? "thunderstorm" : s.raining() ? "raining" : "clear").append(".");
		if (s.raid()) sb.append(" The village is being raided!");
		if (s.playerHurt()) sb.append(" The player is badly hurt.");
		sb.append(" ").append(VillagerMemory.describe(villager, player));
		return sb.toString();
	}

	/** "You buy 20 Wheat for 1 Emerald" / "You sell 6 Bread for 1 Emerald" (villager's point of view). */
	public static String tradeText(MerchantOffer offer) {
		var cost = offer.getCostA();
		var costB = offer.getCostB();
		var result = offer.getResult();
		String costText = cost.getCount() + " " + cost.getHoverName().getString()
				+ (costB.isEmpty() ? "" : " and " + costB.getCount() + " " + costB.getHoverName().getString());
		return result.getItem() == Items.EMERALD
				? "You buy " + costText + " for " + result.getCount() + " Emerald"
				: "You sell " + result.getCount() + " " + result.getHoverName().getString() + " for " + costText;
	}

	/** A trade just completed while the screen is open: have the villager react (at most every 5 s). */
	public void onTraded(ServerPlayer player, Villager villager, MerchantOffer offer) {
		String key = activeByPlayer.get(player.getUUID());
		Conversation conv = key == null ? null : conversations.get(key);
		if (conv == null || conv.villager != villager || conv.thinking) return;
		ServerLevel level = (ServerLevel) villager.level();
		long now = level.getGameTime();
		if (now - conv.lastTradeReaction < 100) return;
		conv.lastTradeReaction = now;
		conv.lastActive = now;
		String what = tradeText(offer).replace("You buy ", "you bought ").replace("You sell ", "you sold ");
		conv.history.add(new AiLines.Said(false, "[The player just completed a trade: " + what + ". React to it.]", List.of()));
		ChatterLines.Situation s = ChatterLines.describe(level, villager, player);
		generate(player, villager, conv, context(s, villager, player), conv.opener, "Pleasure doing business!");
	}

	private static void send(ServerPlayer player, Conversation conv) {
		ServerPlayNetworking.send(player, new DialogueStateS2C(conv.speaker, conv.line, conv.replies, conv.thinking));
	}

	/** Housekeeping: forget old conversations. */
	public void tick(long now) {
		conversations.values().removeIf(c -> !c.thinking && now - c.lastActive > MEMORY_TICKS * 2);
	}
}
