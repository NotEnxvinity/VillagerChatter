package dev.enxvinity.villagerchatter;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings, saved in .minecraft/config/villagerchatter.properties.
 * Most of them can be changed in game (Mod Menu, or /villagerchatter settings); the rest by editing the file.
 */
public final class ChatterConfig {
	public boolean aiEnabled = true;
	/** "builtin" = the mod runs its own AI (no setup). "ollama" = use an Ollama install. */
	public String backend = "builtin";
	public String ollamaUrl = "http://127.0.0.1:11434";
	public String model = "qwen3:4b";
	public int aiTimeoutMs = 4000;
	public double hearingRange = 6.0;
	public float talkChance = 0.15f;
	public int villagerCooldownSeconds = 30;
	public int playerCooldownSeconds = 8;
	public boolean showBubbles = true;
	public boolean showInChat = false;
	public boolean particles = true;
	public boolean dialogueEnabled = true;
	public float smallTalkChance = 0.35f;
	/** Play vanilla villager "hrmm" sounds when a villager says something. */
	public boolean sounds = true;
	/** Speech bubble size in percent (100 = the original size). */
	public int bubbleSize = 100;
	/** Where the AI files live. Empty = the shared folder for this OS (see LocalRuntime.defaultDataDir()). */
	public String modelFolder = "";

	private Path file;

	private static ChatterConfig current;

	/** The loaded settings (the same object the game uses, so changes apply right away). */
	public static ChatterConfig get() {
		return current;
	}

	public static ChatterConfig load() {
		ChatterConfig c = new ChatterConfig();
		current = c;
		Path file = FabricLoader.getInstance().getConfigDir().resolve("villagerchatter.properties");
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				p.load(r);
			} catch (IOException e) {
				VillagerChatter.LOGGER.warn("Couldn't read {}, using defaults", file, e);
			}
		}
		c.aiEnabled = Boolean.parseBoolean(p.getProperty("aiEnabled", String.valueOf(c.aiEnabled)));
		c.backend = p.getProperty("backend", c.backend).trim();
		c.ollamaUrl = p.getProperty("ollamaUrl", c.ollamaUrl);
		c.model = p.getProperty("model", c.model);
		// v0.7 upgrade: move people off the old default model to the better one.
		if (!p.containsKey("configVersion") && c.model.equals("smollm2:1.7b")) c.model = "qwen3:4b";
		c.aiTimeoutMs = parseInt(p, "aiTimeoutMs", c.aiTimeoutMs);
		c.hearingRange = parseDouble(p, "hearingRange", c.hearingRange);
		c.talkChance = (float) parseDouble(p, "talkChance", c.talkChance);
		c.villagerCooldownSeconds = parseInt(p, "villagerCooldownSeconds", c.villagerCooldownSeconds);
		c.playerCooldownSeconds = parseInt(p, "playerCooldownSeconds", c.playerCooldownSeconds);
		c.showBubbles = Boolean.parseBoolean(p.getProperty("showBubbles", String.valueOf(c.showBubbles)));
		c.showInChat = Boolean.parseBoolean(p.getProperty("showInChat", String.valueOf(c.showInChat)));
		c.particles = Boolean.parseBoolean(p.getProperty("particles", String.valueOf(c.particles)));
		c.dialogueEnabled = Boolean.parseBoolean(p.getProperty("dialogueEnabled", String.valueOf(c.dialogueEnabled)));
		c.smallTalkChance = (float) parseDouble(p, "smallTalkChance", c.smallTalkChance);
		c.sounds = Boolean.parseBoolean(p.getProperty("sounds", String.valueOf(c.sounds)));
		c.bubbleSize = Math.max(50, Math.min(200, parseInt(p, "bubbleSize", c.bubbleSize)));
		c.modelFolder = p.getProperty("modelFolder", c.modelFolder).trim();
		c.talkChance = clamp01(c.talkChance);
		c.smallTalkChance = clamp01(c.smallTalkChance);
		c.file = file;
		c.save();
		return c;
	}

	/** Puts everything on the settings screen back to how a fresh install has it (file-only settings are kept). */
	public void resetToDefaults() {
		ChatterConfig d = new ChatterConfig();
		aiEnabled = d.aiEnabled;
		talkChance = d.talkChance;
		smallTalkChance = d.smallTalkChance;
		dialogueEnabled = d.dialogueEnabled;
		showBubbles = d.showBubbles;
		showInChat = d.showInChat;
		bubbleSize = d.bubbleSize;
		particles = d.particles;
		sounds = d.sounds;
		modelFolder = d.modelFolder;
		save();
	}

	public void save() {
		if (file == null) return;
		Properties p = new Properties();
		p.setProperty("configVersion", "4");
		p.setProperty("aiEnabled", String.valueOf(aiEnabled));
		p.setProperty("backend", backend);
		p.setProperty("ollamaUrl", ollamaUrl);
		p.setProperty("model", model);
		p.setProperty("aiTimeoutMs", String.valueOf(aiTimeoutMs));
		p.setProperty("hearingRange", String.valueOf(hearingRange));
		p.setProperty("talkChance", String.valueOf(talkChance));
		p.setProperty("villagerCooldownSeconds", String.valueOf(villagerCooldownSeconds));
		p.setProperty("playerCooldownSeconds", String.valueOf(playerCooldownSeconds));
		p.setProperty("showBubbles", String.valueOf(showBubbles));
		p.setProperty("showInChat", String.valueOf(showInChat));
		p.setProperty("particles", String.valueOf(particles));
		p.setProperty("dialogueEnabled", String.valueOf(dialogueEnabled));
		p.setProperty("smallTalkChance", String.valueOf(smallTalkChance));
		p.setProperty("sounds", String.valueOf(sounds));
		p.setProperty("bubbleSize", String.valueOf(bubbleSize));
		p.setProperty("modelFolder", modelFolder);
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				p.store(w, "Villager Chatter settings. aiEnabled=false uses only hand-written lines. backend=builtin needs nothing installed (downloads Qwen3-4B once). backend=ollama uses Ollama with the model below: qwen3:4b (best), smollm2:1.7b (lighter), nemotron-mini. modelFolder empty = the shared folder for all your instances.");
			}
		} catch (IOException e) {
			VillagerChatter.LOGGER.warn("Couldn't write {}", file, e);
		}
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static int parseInt(Properties p, String k, int d) {
		try { return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim()); } catch (NumberFormatException e) { return d; }
	}

	private static double parseDouble(Properties p, String k, double d) {
		try { return Double.parseDouble(p.getProperty(k, String.valueOf(d)).trim()); } catch (NumberFormatException e) { return d; }
	}
}
