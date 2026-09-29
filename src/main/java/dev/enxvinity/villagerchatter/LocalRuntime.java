package dev.enxvinity.villagerchatter;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The built-in AI: no Ollama needed.
 *
 * On first launch it downloads two things into .minecraft/villagerchatter/:
 *   1. llama.cpp's "llama-server" for this OS (~12-35 MB, from the official GitHub release)
 *   2. the Qwen3-4B model file (~2.5 GB, from Qwen's official Hugging Face page)
 * Both are checked against pinned SHA-256 hashes before use. Then it runs llama-server in the
 * background on a random local port (only reachable from this computer) and stops it when the game closes.
 */
public final class LocalRuntime {
	private static final String BUILD = "b11242";
	private static final String RELEASE_URL = "https://github.com/ggml-org/llama.cpp/releases/download/" + BUILD + "/";

	private record Asset(String file, String sha256) {}

	private static final Asset MAC_ARM = new Asset("llama-" + BUILD + "-bin-macos-arm64.tar.gz", "76142db728d264a56da04592d69e4f3bdf798049183ca7c9b0511fbb561458e8");
	private static final Asset MAC_X64 = new Asset("llama-" + BUILD + "-bin-macos-x64.tar.gz", "4b3453bed8258b67fdc887ade03cd4c2d0cb49dc48bacd5f281c01d41e063822");
	private static final Asset WIN_VULKAN = new Asset("llama-" + BUILD + "-bin-win-vulkan-x64.zip", "1bb9815c5077fc745513e0b05a8fe83f32505ae2edcd0cef09a4c944ca741198");
	private static final Asset WIN_CPU = new Asset("llama-" + BUILD + "-bin-win-cpu-x64.zip", "851fb7a4070a14789e9b4f617d0f1a58369e295b6466e518441d5067327a5320");
	private static final Asset WIN_ARM = new Asset("llama-" + BUILD + "-bin-win-cpu-arm64.zip", "5d5d3fcf3cca6fc6ec11724f0994c43bcd4c1ceeb160a13701ad0446b3c57a50");
	private static final Asset LINUX_VULKAN = new Asset("llama-" + BUILD + "-bin-ubuntu-vulkan-x64.tar.gz", "59c70261de224ec34edd642388ccf0807258ae4b58d3427d941f425644faf08b");
	private static final Asset LINUX_CPU = new Asset("llama-" + BUILD + "-bin-ubuntu-x64.tar.gz", "30d1cd591a875b4f4a96ac1f6b7b35e5de25fdb8527047290e48e067890b2d18");
	private static final Asset LINUX_ARM = new Asset("llama-" + BUILD + "-bin-ubuntu-arm64.tar.gz", "dd5fd13df272736d3060071b35674d7236a95ebb221251884aa4ee599b22659c");

	public static final String MODEL_FILE = "Qwen3-4B-Q4_K_M.gguf";
	private static final String MODEL_URL = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/" + MODEL_FILE;
	private static final String MODEL_SHA256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5";

	public enum State { IDLE, DOWNLOADING, STARTING, READY, FAILED }

	private Path home;
	private final HttpClient web = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.proxy(ProxySelector.getDefault())
			.connectTimeout(Duration.ofSeconds(20))
			.build();
	private final HttpClient local = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

	private volatile State state = State.IDLE;
	private volatile String status = "";
	private volatile int port = -1;
	private volatile Process process;

	/** For tests only: point at an already-running llama-server. */
	void attachForTest(Process p, int port) { this.process = p; this.port = port; this.state = State.READY; }

	public State state() { return state; }
	public String status() { return status; }
	public boolean ready() { return state == State.READY && process != null && process.isAlive(); }
	public String baseUrl() { return "http://127.0.0.1:" + port; }

	/** Download what's missing and start the server, all on a background thread. Safe to call repeatedly. */
	public synchronized void startAsync() {
		if (state != State.IDLE && state != State.FAILED) return;
		state = State.DOWNLOADING;
		Thread t = new Thread(this::run, "VillagerChatter-AI");
		t.setDaemon(true);
		t.start();
	}

	private void run() {
		try {
			home = FabricLoader.getInstance().getGameDir().resolve("villagerchatter");
			Files.createDirectories(home);
			killLeftoverServer();
			Path model = ensureModel();
			List<Asset> candidates = assetsForThisComputer();
			for (int i = 0; i < candidates.size(); i++) {
				Asset asset = candidates.get(i);
				Path server = ensureServer(asset);
				state = State.STARTING;
				if (launch(server, model)) {
					state = State.READY;
					status = "ready";
					VillagerChatter.LOGGER.info("Built-in AI is ready ({} on port {}).", asset.file(), port);
					VillagerChatter.instance().announce("Villagers are now fully awake (AI ready).");
					return;
				}
				VillagerChatter.LOGGER.warn("llama-server ({}) didn't start{}", asset.file(),
						i + 1 < candidates.size() ? ", trying the next build…" : ".");
			}
			fail("the AI server couldn't start on this computer (see villagerchatter/llama-server.log)");
		} catch (Exception e) {
			fail(e.toString());
		}
	}

	private void fail(String why) {
		state = State.FAILED;
		status = "failed: " + why;
		VillagerChatter.LOGGER.warn("Built-in AI unavailable: {}. Villagers will use hand-written lines.", why);
	}

	// ---------------- Platform ----------------

	private static List<Asset> assetsForThisComputer() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm");
		if (os.contains("mac")) return List.of(arm ? MAC_ARM : MAC_X64);
		if (os.contains("win")) return arm ? List.of(WIN_ARM) : List.of(WIN_VULKAN, WIN_CPU);
		return arm ? List.of(LINUX_ARM) : List.of(LINUX_VULKAN, LINUX_CPU);
	}

	private static String exe(String name) {
		return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name;
	}

	// ---------------- Downloads ----------------

	private Path ensureModel() throws Exception {
		Path models = Files.createDirectories(home.resolve("models"));
		Path file = models.resolve(MODEL_FILE);
		Path ok = models.resolve(MODEL_FILE + ".verified");
		if (Files.exists(file) && Files.exists(ok)) return file;
		download(MODEL_URL, file, MODEL_SHA256, "villager brain (" + MODEL_FILE + ", ~2.5 GB)");
		Files.writeString(ok, MODEL_SHA256);
		return file;
	}

	private Path ensureServer(Asset asset) throws Exception {
		Path dir = home.resolve("runtime").resolve(asset.file().replace(".tar.gz", "").replace(".zip", ""));
		Optional<Path> existing = findServer(dir);
		if (existing.isPresent() && Files.exists(dir.resolve(".verified"))) return existing.get();

		Path archive = Files.createDirectories(home.resolve("downloads")).resolve(asset.file());
		download(RELEASE_URL + asset.file(), archive, asset.sha256(), "AI runtime (" + asset.file() + ")");
		Files.createDirectories(dir);
		// Every supported OS ships a "tar" that can unpack both .tar.gz and .zip (Windows 10+ included).
		Process p = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", dir.toString())
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		if (p.waitFor() != 0) throw new IOException("couldn't unpack " + asset.file() + ": " + out);
		Path server = findServer(dir).orElseThrow(() -> new IOException("llama-server not found in " + asset.file()));
		server.toFile().setExecutable(true);
		try (Stream<Path> files = Files.list(server.getParent())) {
			files.forEach(f -> f.toFile().setExecutable(true));
		}
		Files.writeString(dir.resolve(".verified"), asset.sha256());
		Files.deleteIfExists(archive);
		return server;
	}

	private static Optional<Path> findServer(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) return Optional.empty();
		try (Stream<Path> walk = Files.walk(dir, 4)) {
			return walk.filter(p -> p.getFileName().toString().equals(exe("llama-server"))).findFirst();
		}
	}

	/** Streams a file to disk with progress in the log, then checks its SHA-256. Resumes nothing; retries next launch. */
	private void download(String url, Path target, String sha256, String what) throws Exception {
		VillagerChatter.LOGGER.info("Downloading {} from {}", what, URI.create(url).getHost());
		status = "downloading " + what;
		VillagerChatter.instance().announce("Downloading the " + what + " — villagers use simple lines until it's done.");
		Path part = target.resolveSibling(target.getFileName() + ".part");
		HttpResponse<InputStream> resp = web.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
		long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		long done = 0;
		int lastPct = -10;
		try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(part)) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				digest.update(buf, 0, n);
				done += n;
				if (total > 0) {
					int pct = (int) (done * 100 / total);
					if (pct >= lastPct + 10) {
						lastPct = pct - pct % 10;
						status = "downloading " + what + " " + pct + "%";
						VillagerChatter.LOGGER.info("  {} — {}%", what, pct);
					}
				}
			}
		}
		String got = HexFormat.of().formatHex(digest.digest());
		if (!got.equalsIgnoreCase(sha256)) {
			Files.deleteIfExists(part);
			throw new IOException("checksum mismatch for " + what + " (download corrupted or tampered with)");
		}
		Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
		VillagerChatter.LOGGER.info("Downloaded and verified {}.", what);
	}

	// ---------------- Running the server ----------------

	private boolean launch(Path server, Path model) throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		ProcessBuilder pb = new ProcessBuilder(
				server.toString(),
				"-m", model.toString(),
				"--host", "127.0.0.1",
				"--port", String.valueOf(port),
				"-c", "4096",           // 2 slots x 2048 tokens: ambient line + conversation at the same time
				"-np", "2",
				"-ngl", "99",           // use the GPU (Metal on Mac, Vulkan on Windows/Linux) when there is one
				"--reasoning", "off");  // Qwen3: skip the hidden thinking step
		pb.directory(server.getParent().toFile());
		String libDir = server.getParent().toString();
		pb.environment().merge("LD_LIBRARY_PATH", libDir, (a, b) -> b + java.io.File.pathSeparator + a);
		pb.environment().merge("DYLD_LIBRARY_PATH", libDir, (a, b) -> b + java.io.File.pathSeparator + a);
		pb.redirectErrorStream(true);
		pb.redirectOutput(home.resolve("llama-server.log").toFile());
		status = "starting the AI";
		Process p = pb.start();
		process = p;
		Files.writeString(home.resolve("llama-server.pid"), String.valueOf(p.pid()));
		Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "VillagerChatter-AI-stop"));

		// Wait up to 2 minutes for the model to load.
		long deadline = System.currentTimeMillis() + 120_000;
		while (System.currentTimeMillis() < deadline) {
			if (!p.isAlive()) return false;
			try {
				HttpResponse<String> r = local.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/health"))
						.timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
				if (r.statusCode() == 200) return true;
			} catch (IOException ignored) {
				// not listening yet
			}
			Thread.sleep(500);
		}
		stop();
		return false;
	}

	public void stop() {
		Process p = process;
		if (p != null && p.isAlive()) {
			p.destroy();
			try {
				if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
			} catch (InterruptedException ignored) {
				p.destroyForcibly();
			}
		}
		try {
			if (home != null) Files.deleteIfExists(home.resolve("llama-server.pid"));
		} catch (IOException ignored) {
		}
	}

	/** If the game crashed last time, the old server may still be running: stop it. */
	private void killLeftoverServer() {
		try {
			Path pid = home.resolve("llama-server.pid");
			if (!Files.exists(pid)) return;
			long id = Long.parseLong(Files.readString(pid).trim());
			ProcessHandle.of(id).ifPresent(h -> {
				if (h.info().command().map(c -> c.contains("llama-server")).orElse(false)) {
					VillagerChatter.LOGGER.info("Stopping a leftover AI server from last session (pid {}).", id);
					h.destroy();
				}
			});
			Files.deleteIfExists(pid);
		} catch (Exception ignored) {
		}
	}
}
