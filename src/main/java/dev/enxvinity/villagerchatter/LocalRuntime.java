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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * On first launch it downloads two things into a folder shared by all your Minecraft instances
 * (see {@link #defaultDataDir()}; older versions used .minecraft/villagerchatter/ and those files are moved over):
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

	/** Shared across instances: the model and llama-server. */
	private Path home;
	/** Per instance: the log and the PID file (so one instance never stops another's server). */
	private Path instanceDir;
	private boolean hookAdded;
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
			instanceDir = Files.createDirectories(FabricLoader.getInstance().getGameDir().resolve("villagerchatter"));
			home = Files.createDirectories(dataDir());
			VillagerChatter.LOGGER.info("AI files folder: {}", home);
			killLeftoverServer();
			Path model = locked(() -> {
				moveOldDownloads();
				return ensureModel();
			});
			List<Asset> candidates = assetsForThisComputer();
			for (int i = 0; i < candidates.size(); i++) {
				Asset asset = candidates.get(i);
				Path server = locked(() -> ensureServer(asset));
				if (!ChatterConfig.get().aiEnabled) { // turned off in the settings while we were downloading
					state = State.IDLE;
					status = "off";
					return;
				}
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
			fail("the AI server couldn't start on this computer (see .minecraft/villagerchatter/llama-server.log)");
		} catch (Exception e) {
			fail(e.toString());
		}
	}

	private void fail(String why) {
		state = State.FAILED;
		status = "failed: " + why;
		VillagerChatter.LOGGER.warn("Built-in AI unavailable: {}. Villagers will use hand-written lines.", why);
	}

	// ---------------- Where the files live ----------------

	/**
	 * One folder for every Minecraft instance, so the 2.5 GB model is only downloaded once:
	 *   macOS:   ~/Library/Application Support/VillagerChatter
	 *   Windows: %LOCALAPPDATA%\VillagerChatter
	 *   Linux:   $XDG_DATA_HOME/villagerchatter (usually ~/.local/share/villagerchatter).
	 *            Flatpak launchers (Prism on SteamOS) point XDG_DATA_HOME inside their sandbox, which is still shared by all their instances.
	 */
	public static Path defaultDataDir() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		Path userHome = Path.of(System.getProperty("user.home"));
		if (os.contains("mac")) return userHome.resolve("Library").resolve("Application Support").resolve("VillagerChatter");
		if (os.contains("win")) {
			String local = System.getenv("LOCALAPPDATA");
			return (local != null && !local.isBlank() ? Path.of(local) : userHome.resolve("AppData").resolve("Local")).resolve("VillagerChatter");
		}
		String xdg = System.getenv("XDG_DATA_HOME");
		Path base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute() ? Path.of(xdg) : userHome.resolve(".local").resolve("share");
		return base.resolve("villagerchatter");
	}

	/** The folder in use: the custom one from the settings, or the shared default. */
	public static Path dataDir() {
		ChatterConfig c = ChatterConfig.get();
		String custom = c == null ? "" : c.modelFolder.trim();
		if (custom.isEmpty()) return defaultDataDir();
		if (custom.equals("~") || custom.startsWith("~/") || custom.startsWith("~\\")) {
			custom = System.getProperty("user.home") + custom.substring(1);
		}
		return Path.of(custom).toAbsolutePath().normalize();
	}

	private interface IoTask<T> { T run() throws Exception; }

	/** Only one Minecraft instance downloads or moves files at a time; the others wait, then reuse them. */
	private <T> T locked(IoTask<T> task) throws Exception {
		try (FileChannel ch = FileChannel.open(home.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock lock = ch.tryLock();
			if (lock == null) {
				status = "waiting for another Minecraft window to finish downloading";
				VillagerChatter.LOGGER.info("Another Minecraft instance is setting up the AI files — waiting for it.");
				lock = ch.lock();
			}
			try {
				return task.run();
			} finally {
				lock.release();
			}
		}
	}

	/**
	 * Before v0.10 every instance kept its own copy in .minecraft/villagerchatter/. Move those into the shared
	 * folder instead of downloading again, and delete copies the shared folder already has.
	 * If a custom folder is set, files in the shared default folder are moved there too.
	 */
	private void moveOldDownloads() {
		moveFrom(instanceDir, true);
		Path shared = defaultDataDir();
		if (!sameFile(shared, home)) moveFrom(shared, false);
	}

	private void moveFrom(Path source, boolean deleteDuplicates) {
		try {
			if (!Files.isDirectory(source) || sameFile(source, home)) return;
			// The model (+ its ".verified" marker).
			Path oldModel = source.resolve("models").resolve(MODEL_FILE);
			Path oldOk = source.resolve("models").resolve(MODEL_FILE + ".verified");
			Path newModels = home.resolve("models");
			if (Files.exists(oldModel) && Files.exists(oldOk)) {
				if (Files.exists(newModels.resolve(MODEL_FILE + ".verified"))) {
					if (deleteDuplicates) {
						Files.delete(oldModel);
						Files.delete(oldOk);
						VillagerChatter.LOGGER.info("Deleted a duplicate copy of the model from {}", source);
					}
				} else {
					status = "moving the villager brain to the shared folder";
					VillagerChatter.LOGGER.info("Moving the model from {} to {} (no re-download needed)…", source, home);
					Files.createDirectories(newModels);
					Files.move(oldModel, newModels.resolve(MODEL_FILE), StandardCopyOption.REPLACE_EXISTING);
					Files.move(oldOk, newModels.resolve(MODEL_FILE + ".verified"), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			// Unpacked llama-server builds.
			Path oldRuntime = source.resolve("runtime");
			if (Files.isDirectory(oldRuntime)) {
				try (Stream<Path> builds = Files.list(oldRuntime)) {
					for (Path build : builds.toList()) {
						if (!Files.exists(build.resolve(".verified"))) continue;
						Path target = home.resolve("runtime").resolve(build.getFileName().toString());
						if (Files.exists(target.resolve(".verified"))) {
							if (deleteDuplicates) deleteTree(build);
						} else {
							deleteTree(target); // half-unpacked leftovers
							Files.createDirectories(target.getParent());
							moveTree(build, target);
						}
					}
				}
			}
			// Tidy up the old folders if they're empty now (the instance folder keeps the log).
			for (String sub : List.of("models", "runtime", "downloads")) {
				try {
					Files.deleteIfExists(source.resolve(sub));
				} catch (IOException notEmpty) {
					// leave it
				}
			}
		} catch (Exception e) {
			// Not fatal: worst case we download again.
			VillagerChatter.LOGGER.warn("Couldn't move the old AI files from {}: {}", source, e.toString());
		}
	}

	private static boolean sameFile(Path a, Path b) {
		try {
			return Files.exists(a) && Files.exists(b) ? Files.isSameFile(a, b) : a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
		} catch (IOException e) {
			return false;
		}
	}

	/** Rename if possible (same drive), otherwise copy then delete. Keeps the "executable" bit. */
	private static void moveTree(Path from, Path to) throws IOException {
		try {
			Files.move(from, to);
			return;
		} catch (IOException sameDriveOnly) {
			// different drive: fall through to copy
		}
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path src : walk.toList()) {
				Path dst = to.resolve(from.relativize(src).toString());
				if (Files.isDirectory(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(dst);
				else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES, java.nio.file.LinkOption.NOFOLLOW_LINKS);
			}
		}
		deleteTree(from);
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
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
		pb.redirectOutput(instanceDir.resolve("llama-server.log").toFile());
		status = "starting the AI";
		Process p = pb.start();
		process = p;
		Files.writeString(instanceDir.resolve("llama-server.pid"), String.valueOf(p.pid()));
		if (!hookAdded) {
			hookAdded = true;
			Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "VillagerChatter-AI-stop"));
		}

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
			if (instanceDir != null) Files.deleteIfExists(instanceDir.resolve("llama-server.pid"));
		} catch (IOException ignored) {
		}
	}

	/** AI switched off in the settings: stop the server to free its memory. {@link #startAsync()} brings it back. */
	public synchronized void shutdown() {
		if (state == State.DOWNLOADING) return; // the download thread checks the setting before starting the server
		stop();
		process = null;
		state = State.IDLE;
		status = "off";
	}

	/** If the game crashed last time, the old server may still be running: stop it. */
	private void killLeftoverServer() {
		try {
			Path pid = instanceDir.resolve("llama-server.pid");
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
