package dev.enxvinity.villagerchatter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
	private static final String SYSTEM = "Respond in JSON with a 'line' field. You write one short line of dialogue for a Minecraft villager. "
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

	/** Shared request fields. Same num_ctx everywhere so Ollama never reloads the model between request types. */
	private JsonObject baseBody() {
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model);
		body.addProperty("stream", false);
		body.addProperty("keep_alive", "30m");
		if (config.model.startsWith("qwen3")) body.addProperty("think", false); // skip the hidden "reasoning" step
		return body;
	}

	private static JsonObject baseOptions(double temperature, int maxTokens) {
		JsonObject o = new JsonObject();
		o.addProperty("temperature", temperature);
		o.addProperty("num_predict", maxTokens);
		o.addProperty("num_ctx", 2048); // small context = much less memory
		return o;
	}

	public boolean available() {
		return config.aiEnabled && System.currentTimeMillis() >= offlineUntilMs;
	}

	public CompletableFuture<Optional<String>> requestLine(ChatterLines.Situation s) {
		JsonObject body = baseBody();
		body.add("format", JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"line\":{\"type\":\"string\"}},\"required\":[\"line\"]}"));

		JsonArray messages = new JsonArray();
		messages.add(msg("system", SYSTEM));
		for (String[] shot : SHOTS) {
			messages.add(msg("user", shot[0]));
			JsonObject ans = new JsonObject();
			ans.addProperty("line", shot[1]);
			messages.add(msg("assistant", ans.toString()));
		}
		messages.add(msg("user", s.toPrompt()));
		body.add("messages", messages);

		JsonObject options = baseOptions(0.9, 48);
		options.addProperty("top_p", 0.95);
		options.addProperty("repeat_penalty", 1.15);
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
					String content = JsonParser.parseString(resp.body()).getAsJsonObject()
							.getAsJsonObject("message").get("content").getAsString();
					String raw = JsonParser.parseString(content).getAsJsonObject().get("line").getAsString();
					Optional<String> cleaned = clean(raw).filter(l -> l.split(" ").length >= 2);
					if (cleaned.isEmpty()) VillagerChatter.LOGGER.debug("Rejected model output: {}", raw);
					warnedOffline = false;
					return cleaned;
				})
				.exceptionally(err -> {
					Throwable cause = err.getCause() != null ? err.getCause() : err;
					if (cause instanceof java.net.http.HttpTimeoutException) {
						// Slow (usually the model still loading) — just use a hand-written line this time.
						VillagerChatter.LOGGER.info("AI took longer than {} ms, using a hand-written line.", config.aiTimeoutMs);
					} else if (!(cause instanceof java.io.IOException)) {
						VillagerChatter.LOGGER.debug("Bad AI output: {}", cause.toString());
					} else {
						markOffline("couldn't reach Ollama at " + config.ollamaUrl + " (" + cause.getClass().getSimpleName() + ")");
					}
					return Optional.empty();
				});
	}

	/**
	 * Load the model into memory at startup so the first real line isn't slow.
	 * If Ollama doesn't have the model yet, download it automatically (one time).
	 */
	public void warmUp() {
		if (!config.aiEnabled) return;
		JsonObject body = baseBody();
		body.remove("stream");
		body.add("options", baseOptions(0.1, 1));
		HttpRequest req = HttpRequest.newBuilder(URI.create(config.ollamaUrl + "/api/generate"))
				.timeout(Duration.ofSeconds(90))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
			if (e != null) {
				VillagerChatter.LOGGER.info("Couldn't reach Ollama at {} (is it running?) — hand-written lines until it is.", config.ollamaUrl);
			} else if (r.statusCode() == 404) {
				pullModel();
			} else {
				VillagerChatter.LOGGER.info("AI model {} is loaded and ready.", config.model);
			}
		});
	}

	private void pullModel() {
		VillagerChatter.LOGGER.info("Ollama doesn't have {} yet — downloading it now (one time, a few GB). Hand-written lines until it's done.", config.model);
		offlineUntilMs = Long.MAX_VALUE;
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model);
		body.addProperty("stream", false);
		HttpRequest req = HttpRequest.newBuilder(URI.create(config.ollamaUrl + "/api/pull"))
				.timeout(Duration.ofMinutes(45))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
			offlineUntilMs = 0;
			if (e == null && r.statusCode() == 200) {
				VillagerChatter.LOGGER.info("Downloaded {}. Loading it…", config.model);
				warmUp();
			} else {
				VillagerChatter.LOGGER.warn("Couldn't download {}: {}", config.model, e != null ? e.toString() : r.body());
			}
		});
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

	// ------------------------------------------------------------------
	// Trade-screen dialogue: one villager line + 3 things the player could say back.
	// ------------------------------------------------------------------

	public record Turn(String line, List<String> replies) {}

	/** One message in a conversation: villager or player. */
	public record Said(boolean byVillager, String text, List<String> replies) {}

	private static String dialogueSystem(String scene) {
		return "You are roleplaying ONE Minecraft villager talking to a player at your trading stall.\n"
				+ "WHO YOU ARE: " + scene + "\n"
				+ "Stay in character as exactly this villager. Never claim a different job.\n"
				+ "Minecraft facts: villagers trade items for emeralds; emeralds are money, not treasure. "
				+ "Only talk about things that exist in the Minecraft world (no real-world books, places, or people).\n"
				+ "Respond in JSON. 'line' = what you say next: one or two short sentences, in character, a little funny, "
				+ "reacting to what the player just said. Never repeat something you already said. 'replies' = exactly 3 short things the PLAYER could say back, "
				+ "each 2 to 5 words, all different (one friendly, one curious, one cheeky).";
	}

	private static final String DIALOGUE_EX_EVENT = "The player just opened your trades.";
	private static final String DIALOGUE_EX_ANSWER = "{\"line\":\"Well, look who it is! Browsing or buying?\","
			+ "\"replies\":[\"Just browsing.\",\"What's popular today?\",\"Buying, if it's cheap.\"]}";

	/** Asks for a turn; if the villager just repeats itself, tries once more with a hotter temperature. */
	public CompletableFuture<Optional<Turn>> requestDialogue(String scene, String event, List<Said> history) {
		return requestDialogueOnce(scene, event, history, 0.8).thenCompose(first -> {
			if (first.isPresent() && !isRepeat(first.get().line(), history)) return CompletableFuture.completedFuture(first);
			VillagerChatter.LOGGER.info("Villager repeated itself, retrying.");
			return requestDialogueOnce(scene, event, history, 1.1)
					.thenApply(second -> second.filter(t -> !isRepeat(t.line(), history)));
		});
	}

	private static boolean isRepeat(String line, List<Said> history) {
		String norm = line.toLowerCase().replaceAll("[^a-z ]", "").trim();
		for (Said s : history) {
			if (s.byVillager() && s.text().toLowerCase().replaceAll("[^a-z ]", "").trim().equals(norm)) return true;
		}
		return norm.equals("well look who it is browsing or buying");
	}

	private CompletableFuture<Optional<Turn>> requestDialogueOnce(String scene, String event, List<Said> history, double temperature) {
		JsonObject body = baseBody();
		body.add("format", JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"line\":{\"type\":\"string\"},"
				+ "\"replies\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":3},\"minItems\":3,\"maxItems\":3}},"
				+ "\"required\":[\"line\",\"replies\"]}"));

		JsonArray messages = new JsonArray();
		messages.add(msg("system", dialogueSystem(scene)));
		messages.add(msg("user", event));
		for (Said said : history) {
			if (said.byVillager()) {
				JsonObject o = new JsonObject();
				o.addProperty("line", said.text());
				JsonArray r = new JsonArray();
				said.replies().forEach(r::add);
				o.add("replies", r);
				messages.add(msg("assistant", o.toString()));
			} else {
				messages.add(msg("user", said.text().startsWith("[") ? said.text() : "The player says: " + said.text()));
			}
		}
		body.add("messages", messages);

		body.add("options", baseOptions(temperature, 220));

		HttpRequest req = HttpRequest.newBuilder(URI.create(config.ollamaUrl + "/api/chat"))
				.timeout(Duration.ofMillis(Math.max(config.aiTimeoutMs, 8000)))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					if (resp.statusCode() != 200) return Optional.<Turn>empty();
					String content = JsonParser.parseString(resp.body()).getAsJsonObject()
							.getAsJsonObject("message").get("content").getAsString();
					JsonObject o = JsonParser.parseString(content).getAsJsonObject();
					Optional<String> line = cleanDialogueLine(o.get("line").getAsString());
					if (line.isEmpty()) return Optional.<Turn>empty();
					List<String> replies = new ArrayList<>();
					for (var el : o.getAsJsonArray("replies")) {
						cleanReply(el.getAsString()).filter(r -> !replies.contains(r)).ifPresent(replies::add);
						if (replies.size() == 3) break;
					}
					if (replies.size() < 3) VillagerChatter.LOGGER.info("Some AI replies were rejected. Raw: {}", content);
					return Optional.of(new Turn(line.get(), replies));
				})
				.exceptionally(err -> {
					VillagerChatter.LOGGER.info("Dialogue AI failed ({}), using a fallback line.", err.toString());
					return Optional.empty();
				});
	}

	/** Up to two sentences, max ~30 words. */
	static Optional<String> cleanDialogueLine(String raw) {
		String s = STAGE_DIRECTIONS.matcher(raw).replaceAll("").replace("\"", "").replaceAll("\\s+", " ").trim();
		Matcher m = Pattern.compile("^(.+?[.!?])(\\s+.+?[.!?])?(\\s|$)").matcher(s);
		if (m.find()) s = (m.group(1) + (m.group(2) == null ? "" : m.group(2))).trim();
		// Reject fragments like "I love" (the model sometimes breaks off mid-sentence).
		if (s.isEmpty() || s.split(" ").length < 3 || s.split(" ").length > 30 || !s.matches(".*[.!?]$")
				|| BLOCKED.matcher(s).find()) return Optional.empty();
		return Optional.of(s);
	}

	/** Player reply buttons: short and clean. */
	static Optional<String> cleanReply(String raw) {
		String s = STAGE_DIRECTIONS.matcher(raw).replaceAll("").replace("\"", "").replaceAll("\\s+", " ").trim();
		// Long, multi-sentence replies: keep just the first sentence ("I'll take two! How much?" -> "I'll take two!").
		if (s.split(" ").length > 8) {
			Matcher m = FIRST_SENTENCE.matcher(s);
			if (m.find()) s = m.group(1).trim();
		}
		if (s.isEmpty() || s.split(" ").length < 2 || s.split(" ").length > 16 || s.length() > 100
				|| s.matches(".*[,:;]$") || BLOCKED.matcher(s).find()) return Optional.empty();
		return Optional.of(s);
	}

	// ------------------------------------------------------------------
	// Small talk: a tiny overheard conversation between two villagers.
	// ------------------------------------------------------------------

	/** One overheard line: firstSpeaker = villager A. */
	public record TalkLine(boolean firstSpeaker, String text) {}

	private static final String SMALLTALK_SYSTEM = "You write a tiny overheard conversation between two Minecraft villagers, A and B. "
			+ "2 to 4 short lines total, alternating A and B, starting with A. Each line under 14 words, in character for their job, "
			+ "a little funny. Only mention things that exist in Minecraft. Output JSON.";
	private static final String SMALLTALK_EX_USER = "A: librarian. B: farmer. Plains village. Time: day. Weather: raining.";
	private static final String SMALLTALK_EX_ANSWER = "{\"lines\":[{\"who\":\"A\",\"text\":\"Your crops are drowning, you know.\"},"
			+ "{\"who\":\"B\",\"text\":\"Drowning? They're thriving! Unlike your book sales.\"},{\"who\":\"A\",\"text\":\"Hrmm. Rude.\"}]}";

	public CompletableFuture<Optional<List<TalkLine>>> requestSmallTalk(String scene) {
		JsonObject body = baseBody();
		body.add("format", JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"lines\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":4,"
				+ "\"items\":{\"type\":\"object\",\"properties\":{\"who\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"]},"
				+ "\"text\":{\"type\":\"string\"}},\"required\":[\"who\",\"text\"]}}},\"required\":[\"lines\"]}"));
		JsonArray messages = new JsonArray();
		messages.add(msg("system", SMALLTALK_SYSTEM));
		messages.add(msg("user", SMALLTALK_EX_USER));
		messages.add(msg("assistant", SMALLTALK_EX_ANSWER));
		messages.add(msg("user", scene));
		body.add("messages", messages);
		body.add("options", baseOptions(0.9, 200));

		HttpRequest req = HttpRequest.newBuilder(URI.create(config.ollamaUrl + "/api/chat"))
				.timeout(Duration.ofMillis(Math.max(config.aiTimeoutMs, 8000)))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					if (resp.statusCode() != 200) return Optional.<List<TalkLine>>empty();
					String content = JsonParser.parseString(resp.body()).getAsJsonObject()
							.getAsJsonObject("message").get("content").getAsString();
					List<TalkLine> out = new ArrayList<>();
					boolean expectA = true;
					for (var el : JsonParser.parseString(content).getAsJsonObject().getAsJsonArray("lines")) {
						JsonObject o = el.getAsJsonObject();
						Optional<String> text = cleanDialogueLine(o.get("text").getAsString()).or(() -> clean(o.get("text").getAsString()));
						if (text.isEmpty()) break; // stop at the first bad line so the exchange still makes sense
						out.add(new TalkLine(expectA, text.get())); // force strict alternation
						expectA = !expectA;
						if (out.size() == 4) break;
					}
					return out.size() >= 2 ? Optional.of(out) : Optional.<List<TalkLine>>empty();
				})
				.exceptionally(err -> Optional.empty());
	}

	private static JsonObject msg(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}
}
