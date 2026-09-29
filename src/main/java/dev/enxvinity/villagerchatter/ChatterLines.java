package dev.enxvinity.villagerchatter;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a villager knows about the moment ({@link Situation}), plus the hand-written
 * fallback lines used when the AI is off, offline, or says something weird.
 */
public final class ChatterLines {
	private ChatterLines() {}

	public record Situation(
			String profession,
			String displayName,
			String biome,
			boolean baby,
			boolean night,
			boolean raining,
			boolean thundering,
			boolean raid,
			boolean monsterNearby,
			boolean villagerHurt,
			boolean playerHurt,
			String relationship
	) {
		/** The prompt the AI sees, e.g. "Job: farmer. Biome: desert. Time: day. Weather: clear. A player walked up." */
		public String toPrompt() {
			StringBuilder sb = new StringBuilder();
			sb.append("Job: ").append(baby ? "baby villager" : (profession.equals("none") ? "unemployed" : profession)).append(". ");
			sb.append("Biome: ").append(biome.replace('_', ' ')).append(". ");
			sb.append("Time: ").append(night ? "night" : "day").append(". ");
			sb.append("Weather: ").append(thundering ? "thunderstorm" : raining ? "raining" : "clear").append(". ");
			if (raid) sb.append("The village is being RAIDED by pillagers! ");
			else if (monsterNearby) sb.append("A zombie is nearby! ");
			if (villagerHurt) sb.append("You were just hurt. ");
			if (playerHurt) sb.append("The player nearby is badly hurt.");
			else sb.append("A player walked up.");
			if (!relationship.isEmpty()) sb.append(" ").append(relationship);
			return sb.toString();
		}
	}

	public static Situation describe(ServerLevel level, Villager villager, ServerPlayer player) {
		String profession = shortName(villager.getVillagerData().profession().getRegisteredName());
		String biome = shortName(level.getBiome(villager.blockPosition()).getRegisteredName());
		boolean baby = villager.isBaby();
		String displayName = baby ? "Baby Villager"
				: (profession.equals("none") ? "Villager" : capitalize(profession.replace('_', ' ')));

		boolean monsterNearby = !level.getEntitiesOfClass(Monster.class, villager.getBoundingBox().inflate(12.0), Monster::isAlive).isEmpty();

		return new Situation(
				profession,
				displayName,
				biome,
				baby,
				level.isDarkOutside(),
				level.isRaining(),
				level.isThundering(),
				level.isRaided(villager.blockPosition()),
				monsterNearby,
				villager.getHealth() < villager.getMaxHealth(),
				player.getHealth() < player.getMaxHealth() * 0.5f,
				VillagerMemory.describe(villager, player)
		);
	}

	public static String displayName(Villager villager) {
		String profession = shortName(villager.getVillagerData().profession().getRegisteredName());
		return villager.isBaby() ? "Baby Villager"
				: (profession.equals("none") ? "Villager" : capitalize(profession.replace('_', ' ')));
	}

	public static String pick(Situation s, RandomSource random) {
		List<String> pool = new ArrayList<>();

		if (s.baby()) {
			pool.addAll(BABY);
		} else {
			pool.addAll(PROFESSION.getOrDefault(s.profession(), GENERIC));
			pool.addAll(GENERIC);
		}
		// Situation lines get added a few times so they're more likely to be picked.
		if (s.raining()) { pool.addAll(RAIN); pool.addAll(RAIN); }
		if (s.monsterNearby() || s.raid()) { pool.addAll(DANGER); pool.addAll(DANGER); pool.addAll(DANGER); }
		if (s.playerHurt()) { pool.addAll(HURT); pool.addAll(HURT); pool.addAll(HURT); }

		return pool.get(random.nextInt(pool.size()));
	}

	private static String shortName(String id) {
		return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// ---------------- Hand-written fallback lines (add your own!) ----------------

	private static final List<String> GENERIC = List.of(
			"Hrmm.",
			"Nice day to stand around, isn't it?",
			"Have you seen my bed? I swear someone took my bed.",
			"The iron golem's been staring at me again.",
			"If you're not buying anything, at least wave.",
			"I heard the zombies have been getting braver lately."
	);

	private static final List<String> BABY = List.of(
			"Hi! Hi! HI!",
			"Wanna play tag? You're it!",
			"My dad says I can be a librarian when I grow up.",
			"Why are you so tall?"
	);

	private static final List<String> RAIN = List.of(
			"Rain again… at least the crops are happy.",
			"I'd go inside, but I forgot which door is mine.",
			"Don't track mud into the village, please."
	);

	private static final List<String> DANGER = List.of(
			"Hrmm! HRMM! Something's out there!",
			"Where's that iron golem when you need him?",
			"I'm hiding. You didn't see me."
	);

	public static final List<String> RAID_START = List.of(
			"PILLAGERS! Everybody inside!", "Hrmm! HRMM! The raid is here!", "Ring the bell! Hide the emeralds!",
			"Not again. I just fixed my door.");
	public static final List<String> RAID_WIN = List.of(
			"We did it! The village is safe!", "Hero! Discounts for you, probably.", "Hrmm! Victory!",
			"They're gone! I can finally come out.");
	public static final List<String> RAID_LOSS = List.of(
			"They took everything...", "Hrmm... the village will rebuild.", "Where was the iron golem?!");
	public static final List<String> BELL = List.of(
			"The bell! What happened?", "Who's ringing that? I was busy.", "Hrmm? Trouble?", "Everybody inside!");
	public static final List<String> BEDTIME = List.of(
			"Goodnight. Don't steal my bed.", "Zzz... hrmm...", "Finally, sleep.", "Wake me if the zombies knock.");

	public static final List<String> OUCH = List.of(
			"OW! What was that for?!",
			"Hey! I'm telling the iron golem!",
			"Hrmm! HRMM!!",
			"Rude. Very rude.",
			"Is this how you treat all your merchants?",
			"I'll remember that, you know."
	);

	private static final List<String> HURT = List.of(
			"You look terrible. Have you tried… not getting hit?",
			"There's a cleric around here if you need patching up.",
			"Whatever did that to you, please don't bring it here."
	);

	private static final Map<String, List<String>> PROFESSION = Map.ofEntries(
			Map.entry("farmer", List.of(
					"The wheat's coming in nicely this year.",
					"Bread, carrots, potatoes — I've got it all. Mostly potatoes.",
					"Please stop jumping on my farmland.")),
			Map.entry("librarian", List.of(
					"Shh. Some of us are reading.",
					"I might have Mending. Might. Depends how nice you are.",
					"Books don't organize themselves, you know.")),
			Map.entry("cleric", List.of(
					"Redstone, lapis, the occasional rotten flesh… it's a living.",
					"The End is closer than you think. Literally, it's under that stronghold.")),
			Map.entry("armorer", List.of(
					"Diamond armor? Bring emeralds. Lots of emeralds.",
					"This blast furnace runs hotter than your furnace.")),
			Map.entry("weaponsmith", List.of(
					"A sharp axe solves most problems.",
					"Mind the grindstone, it bites.")),
			Map.entry("toolsmith", List.of(
					"You break it, I sell you a new one.",
					"A good pickaxe is a miner's best friend.")),
			Map.entry("fisherman", List.of(
					"Caught a boot again. Third one this week.",
					"Patience. The fish can smell desperation.")),
			Map.entry("shepherd", List.of(
					"Every color of wool you could want. Except pink. Pink's sold out.",
					"Don't punch the sheep. They have feelings.")),
			Map.entry("fletcher", List.of(
					"Sticks for emeralds — best deal in the world.",
					"Arrows don't fletch themselves.")),
			Map.entry("cartographer", List.of(
					"I've got a map to a mansion. Nobody ever comes back happy.",
					"The world's bigger than you think. I'd know.")),
			Map.entry("butcher", List.of(
					"Fresh porkchops! Don't ask where from.",
					"I've got a smoker going if you're hungry.")),
			Map.entry("leatherworker", List.of(
					"Leather horse armor? Very fashionable. Trust me.",
					"Cauldron's full of dye, don't drink it.")),
			Map.entry("mason", List.of(
					"Quartz, terracotta, you name it.",
					"Stone is stone. Until it's polished.")),
			Map.entry("nitwit", List.of(
					"…",
					"I don't have a job. I have a lifestyle.",
					"Hehe. Dirt."))
	);
}
