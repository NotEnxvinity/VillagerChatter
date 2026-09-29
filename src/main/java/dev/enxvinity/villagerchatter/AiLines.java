package dev.enxvinity.villagerchatter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a small language model (running in Ollama on this computer) for a villager line.
 *
 * Everything here runs OFF the game thread: {@link #requestLine} returns immediately with a
 * CompletableFuture, and the game picks the answer up on a later tick. If the model is slow,
 * missing, or says something weird, the future completes with Optional.empty() and the mod
 * falls back to the hand-written lines.
 */
public final class AiLines {
	private static final String SYSTEM = "You write one short line of dialogue for a Minecraft villager. "
			+ "The line is under 12 words, in character, a little funny, and fits the job and situation. "
			+ "Output only the line.";

	// Few-shot examples: show the model exactly what a good answer looks like.
	private static final String[][] SHOTS = {
			{"Job: farmer. Biome: plains. Time: day. Weather: raining. A player walked up.", "Rain again. At least the wheat is happy."},
			{"Job: librarian. Biome: taiga. Time: night. A zombie is nearby!", "Shh! Maybe if we're quiet it'll go read somewhere else."},
			{"Job: nitwit. Biome: desert. Time: day. A player walked up.", "I don't have a job. I have a lifestyle."},
			{"Job: cleric. Biome: plains. Time: day. The player nearby is badly hurt.", "You look awful. Want me to pray over that?"},
	};

	private static final Pattern STAGE_DIRECTIONS = Pattern.compile("\\*[^*]*\\*|\\([^)]*\\)|\\[[^\\]]*\\]");
	private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[.!?])(\\s|$)");
	private static final Pattern BLOCKED = Pattern.compile(
			"\\b(ai|assistant|language model|chatbot|hell|damn|shit|fuck\\w*|bitch|sex\\w*|kill yourself|die)\\b",
			Pattern.CASE_INSENSITIVE);

	private final ChatterConfig config;
	private final HttpClient http;
	/** If Ollama isn't reachable, stop asking for a minute instead of spamming it. */
	private volatile long offlineUntilMs = 0;
	private volatile boolean warnedOffline = false;

	public AiLines(ChatterConfig config) {
		this.config = config;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(1500))
				.proxy(HttpClient.Builder.NO_PROXY)
				.build();
	}

	public boolean available() {
		return config.aiEnabled && System.currentTimeMillis() >= offlineUntilMs;
	}

	public CompletableFuture<Optional<String>> requestLine(ChatterLines.Situation s) {
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model);
		body.addProperty("stream", false);
		body.addProperty("keep_alive", "30m");

		JsonArray messages = new JsonArray();
		messages.add(msg("system", SYSTEM));
		for (String[] shot : SHOTS) {
			messages.add(msg("user", shot[0]));
			messages.add(msg("assistant", shot[1]));
		}
		messages.add(msg("user", s.toPrompt()));
		body.add("messages", messages);

		JsonObject options = new JsonObject();
		options.addProperty("temperature", 0.9);
		options.addProperty("top_p", 0.95);
		options.addProperty("num_predict", 32);
		options.addProperty("repeat_penalty", 1.15);
		JsonArray stop = new JsonArray();
		stop.add("\n");
		options.add("stop", stop);
		body.add("options", options);

		HttpRequest req = HttpRequest.newBuilder(URI.create(config.ollamaUrl + "/api/chat"))
				.timeout(Duration.ofMillis(config.aiTimeoutMs))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					if (resp.statusCode() != 200) {
						VillagerChatter.LOGGER.warn("Ollama returned HTTP {}: {}", resp.statusCode(), resp.body());
						if (resp.statusCode() == 404) markOffline("model '" + config.model + "' not found — run: ollama pull " + config.model);
						return Optional.<String>empty();
					}
					String raw = JsonParser.parseString(resp.body()).getAsJsonObject()
							.getAsJsonObject("message").get("content").getAsString();
					Optional<String> cleaned = clean(raw);
					if (cleaned.isEmpty()) VillagerChatter.LOGGER.debug("Rejected model output: {}", raw);
					warnedOffline = false;
					return cleaned;
				})
				.exceptionally(err -> {
					Throwable cause = err.getCause() != null ? err.getCause() : err;
					if (cause instanceof java.net.http.HttpTimeoutException) {
						// Slow (usually the model still loading) — just use a hand-written line this time.
						VillagerChatter.LOGGER.info("AI took longer than {} ms, using a hand-written line.", config.aiTimeoutMs);
					} else {
						markOffline("couldn't reach Ollama at " + config.ollamaUrl + " (" + cause.getClass().getSimpleName() + ")");
					}
					return Optional.empty();
				});
	}

	/** Load the model into memory at startup so the first real line isn't slow. */
	public void warmUp() {
		if (!config.aiEnabled) return;
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model);
		body.addProperty("keep_alive", "30m");
		HttpRequest req = HttpRequest.newBuilder(URI.create(config.ollamaUrl + "/api/generate"))
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
				.whenComplete((r, e) -> VillagerChatter.LOGGER.info(e == null
						? "AI model " + config.model + " is loaded and ready."
						: "Couldn't warm up the AI model (is Ollama running?) — hand-written lines until it is."));
	}

	private void markOffline(String why) {
		offlineUntilMs = System.currentTimeMillis() + 60_000;
		if (!warnedOffline) {
			VillagerChatter.LOGGER.warn("AI lines paused for 60s: {}. Using hand-written lines meanwhile.", why);
			warnedOffline = true;
		}
	}

	/** Turn whatever the model said into one clean, short, safe line — or reject it. */
	static Optional<String> clean(String raw) {
		String s = STAGE_DIRECTIONS.matcher(raw).replaceAll("");
		s = s.replace("\"", "").replace("“", "").replace("”", "").trim();
		Matcher m = FIRST_SENTENCE.matcher(s);
		if (m.find()) s = m.group(1);
		s = s.replaceAll("\\s+", " ").trim();
		if (s.isEmpty() || s.split(" ").length > 16 || !s.matches(".*[a-zA-Z].*") || BLOCKED.matcher(s).find()) {
			return Optional.empty();
		}
		return Optional.of(s);
	}

	private static JsonObject msg(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}
}
