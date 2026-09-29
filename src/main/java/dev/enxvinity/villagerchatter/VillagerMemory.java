package dev.enxvinity.villagerchatter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * What each villager remembers about each player. Saved with the villager in the world file,
 * so it survives restarts. Combined with vanilla's gossip reputation for the villager's attitude.
 */
public final class VillagerMemory {
	private VillagerMemory() {}

	/** One villager's memory of one player. */
	public record Rel(int trades, int hits, String last) {
		public static final Rel NONE = new Rel(0, 0, "");
		public static final Codec<Rel> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.optionalFieldOf("trades", 0).forGetter(Rel::trades),
				Codec.INT.optionalFieldOf("hits", 0).forGetter(Rel::hits),
				Codec.STRING.optionalFieldOf("last", "").forGetter(Rel::last)
		).apply(i, Rel::new));

		public Rel withTrade() { return new Rel(trades + 1, hits, last); }
		public Rel withHit() { return new Rel(trades, hits + 1, last); }
		public Rel withLast(String line) {
			return new Rel(trades, hits, line.length() > 90 ? line.substring(0, 90) + "…" : line);
		}
	}

	/** Player UUID (as text) -> memory. */
	public static final AttachmentType<Map<String, Rel>> TYPE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(VillagerChatter.MOD_ID, "memory"),
			b -> b.persistent(Codec.unboundedMap(Codec.STRING, Rel.CODEC)).initializer(Map::of));

	public static void init() {
		// Referencing TYPE registers it; called from the mod initializer.
		VillagerChatter.LOGGER.debug("Villager memory attachment: {}", TYPE);
	}

	public static Rel get(Villager v, Player p) {
		Map<String, Rel> all = v.getAttached(TYPE);
		return all == null ? Rel.NONE : all.getOrDefault(p.getUUID().toString(), Rel.NONE);
	}

	public static void update(Villager v, Player p, UnaryOperator<Rel> change) {
		Map<String, Rel> all = v.getAttached(TYPE);
		Map<String, Rel> copy = all == null ? new HashMap<>() : new HashMap<>(all);
		copy.put(p.getUUID().toString(), change.apply(copy.getOrDefault(p.getUUID().toString(), Rel.NONE)));
		v.setAttached(TYPE, Map.copyOf(copy)); // new instance => marked dirty and saved
	}

	/** A sentence or two for the AI about how this villager feels about this player. */
	public static String describe(Villager v, Player p) {
		Rel r = get(v, p);
		int rep = v.getPlayerReputation(p); // vanilla gossip: negative if hurt, positive from trading/hero
		StringBuilder sb = new StringBuilder();
		if (r.hits() > 0 && rep < 0) {
			sb.append("This player has hit you ").append(r.hits()).append(r.hits() == 1 ? " time" : " times")
					.append(". You don't trust them and you're a bit rude to them.");
		} else if (r.trades() >= 10 || rep >= 30) {
			sb.append("This player is one of your best customers (").append(r.trades())
					.append(" trades). You're happy to see them and treat them like a friend.");
		} else if (r.trades() > 0) {
			sb.append("You've traded with this player ").append(r.trades()).append(r.trades() == 1 ? " time" : " times").append(" before.");
		} else if (!r.last().isEmpty()) {
			sb.append("You've chatted with this player before but never traded.");
		} else {
			sb.append("You've never met this player before.");
		}
		// Note: we deliberately do NOT quote the last line back to the model; small models just repeat it.
		return sb.toString();
	}
}
