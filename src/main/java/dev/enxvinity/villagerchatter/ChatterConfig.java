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
 * Edit that file (then restart the game) to change them.
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

	public static ChatterConfig load() {
		ChatterConfig c = new ChatterConfig();
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
		c.save(file);
		return c;
	}

	private void save(Path file) {
		Properties p = new Properties();
		p.setProperty("configVersion", "3");
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
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				p.store(w, "Villager Chatter settings. aiEnabled=false uses only hand-written lines. backend=builtin needs nothing installed (downloads Qwen3-4B once). backend=ollama uses Ollama with the model below: qwen3:4b (best), smollm2:1.7b (lighter), nemotron-mini.");
			}
		} catch (IOException e) {
			VillagerChatter.LOGGER.warn("Couldn't write {}", file, e);
		}
	}

	private static int parseInt(Properties p, String k, int d) {
		try { return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim()); } catch (NumberFormatException e) { return d; }
	}

	private static double parseDouble(Properties p, String k, double d) {
		try { return Double.parseDouble(p.getProperty(k, String.valueOf(d)).trim()); } catch (NumberFormatException e) { return d; }
	}
}
