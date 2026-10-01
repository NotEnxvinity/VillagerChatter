# Villager Chatter — notes for Claude Code

Fabric mod for Minecraft Java 26.3 (Mojang's unobfuscated names, no Yarn). Java 25, Gradle 9.7.1 wrapper, Loom 1.18.
Maintainer: Enxvinity (comfortable with JavaScript/Swift, newer to Java and Fabric) — explain changes briefly as you go.

## Goal
Villagers say short, situation-aware lines when a player is near. Hand-written lines now; a small local language model later.

## Layout
- `AiLines.java` — async Ollama /api/chat client (Java HttpClient + Gson), few-shot prompt, output filter, 60s backoff when offline.
- `ChatterConfig.java` — config/villagerchatter.properties.
- See DESIGN.md for next-step ideas (overhead bubbles, event reactions, trade lines).
- `VillagerChatter.java` — entrypoint; `ServerTickEvents.END_LEVEL_TICK` checks for nearby villagers once a second, handles cooldowns, sends the chat line.
- `ChatterLines.java` — `Situation` record (profession, baby, raining, player hurt) + `pick()` from hand-written pools. The `Situation` is meant to become the AI prompt.

## v0.3–0.4 additions
- `SpeechBubbles.java` — server-side TEXT_DISPLAY entity kept above the villager; tagged `villagerchatter_bubble`, leftovers discarded on load. Needs `mixin/DisplayAccessor` + `TextDisplayAccessor` (private setters).
- `DialogueManager.java` — trade-screen conversations. Hooked via `mixin/AbstractVillagerMixin` (setTradingPlayer). 60 s greeting cooldown per villager+player (reopen = resume), 5 min memory, 3 AI replies + fixed exit. Ollama structured JSON output (`format` schema).
- `net/` — DialogueStateS2C, DialogueChoiceC2S payloads.
- `client/` — `DialoguePanel` draws above MerchantScreen via Fabric screen API (`ScreenEvents.afterExtract`; 26.x GUI uses GuiGraphicsExtractor). Client mixin `AbstractContainerScreenAccessor`.
- Mod is now required on the client too (for the trade panel). Bubbles work for vanilla clients.

## v0.5–0.6 additions
- `VillagerMemory.java` — persistent Fabric data attachment on each villager: player UUID -> (trades, hits, last line). `describe()` mixes it with vanilla `getPlayerReputation` for prompts.
- `mixin/AbstractVillagerTradeMixin` — notifyTrade -> sparkle + DialogueManager.onTraded (AI reacts, max every 5 s).
- `ServerLivingEntityEvents.AFTER_DAMAGE` — instant hand-written OUCH bubble, angry particles, hit remembered.
- Small talk: `AiLines.requestSmallTalk` (JSON lines A/B) scheduled as alternating bubbles in `VillagerChatter.runScenes`; villagers look at each other.

## v0.7–0.8
- Model: Qwen3-4B (see DESIGN.md §6 for the comparison). All requests use JSON-schema output.
- `LocalRuntime.java` — built-in backend: downloads pinned llama.cpp release (b11242) for the OS + Qwen3-4B-Q4_K_M.gguf from HF, verifies SHA-256, unpacks with `tar`, runs `llama-server --reasoning off -c 4096 -np 2 -ngl 99` on a random 127.0.0.1 port, PID file to clean up after crashes. Win/Linux x64 try Vulkan build then CPU build.
- `AiLines.chat()` translates Ollama-style bodies to llama-server's OpenAI API (`response_format: json_schema`) and back, so request code is backend-agnostic. `config.backend` = builtin | ollama.
- Tested: all three request types against a source-built llama-server b11242 (official Linux binaries need glibc 2.38; the VM has 2.35).

## v0.9 world reactions
- `VillagerChatter.speakEvent()` makes one villager react to an event (AI line via `AiLines.requestLine(situation, event)`, hand-written fallback lists in ChatterLines). Throttled to once per 10 s per villager and only when a player is within 32 blocks.
- Raids: `checkRaids()` checks `getRaidAt(player pos)` each second (WeakHashMap tracks start/end). Bell: `BellBlockMixin` on `attemptToRing` RETURN. Bedtime: `LivingEntitySleepMixin` on `startSleeping` (35% chance, player within 16 blocks).

## v0.10 settings, sounds, shared folder
- `ChatterConfig.get()` is the single live instance (client screen and integrated server share the JVM, so changes apply instantly in singleplayer). New: `sounds`, `bubbleSize` (percent, x0.6 base scale), `modelFolder`. `resetToDefaults()` mutates in place (other objects hold the reference) and only touches on-screen settings.
- `client/ChatterSettingsScreen` (OptionsSubScreen, vanilla widgets) + `client/ModelFolderScreen` (EditBox, Open Folder via `Blaze3D.openPath`, Use Shared Folder). Mod Menu via `compat/ModMenuIntegration` (compileOnly + localRuntime). `/villagerchatter settings` client command uses `client.schedule(...)` so the chat screen closes first. 26.x: screens open with `minecraft.gui.setScreen`.
- `removed()` -> `VillagerChatter.applySettings()` -> save + `AiLines.onSettingsChanged()` (AI on: warmUp; off: `LocalRuntime.shutdown()` frees RAM).
- Sounds: `VillagerChatter.hrmm(v, sound)` = `v.playSound(sound, 1, v.getVoicePitch())` (server-side, broadcast). AMBIENT normally, NO when hurt/raid/monster/woken/sweat events, YES for happy events, TRADE when replying in the trade screen. Not on OUCH (vanilla hurt sound plays).
- Shared folder: `LocalRuntime.defaultDataDir()`/`dataDir()`. Model + runtime live there; log + PID stay per instance (`<gameDir>/villagerchatter`) so one instance never kills another's server. Downloads/moves run under a `FileChannel` lock on `<dataDir>/.lock`. `moveOldDownloads()` moves verified files from the old per-instance folder (deletes duplicates) and from the default folder into a custom one; empty old dirs are removed.
- Tested in a dev client (Xvfb + lavapipe Vulkan, `ALSOFT_DRIVERS=null` + subtitles to see sounds): screen, command, Mod Menu, save/reset, bubble size, sounds ("Villager agrees" on a jobless right-click), AI off/on, migration with no re-download, leftover-server kill.

## NEXT UP (agreed with Enx after v0.9.2)
Items 1–3 done in v0.10. Do these in order, test on both his Mac and his Steam Machine (SteamOS, Prism Launcher), then release:
1. **Settings screen**: vanilla widgets only (no Cloth Config/YACL). Open it from Mod Menu (optional dependency via `modmenu` entrypoint, `ConfigScreenFactory`) and via a `/villagerchatter settings` client command as a fallback. No button in vanilla Options. Settings: chattiness (talkChance), bubble size (scale), bubbles vs chat, particles, small-talk chance, AI on/off, model folder location.
2. **"hrmm" sounds**: play vanilla villager ambient/yes/no sounds when a bubble appears (respect a sounds toggle).
3. **Shared model folder** across instances: macOS `~/Library/Application Support/VillagerChatter/`, Windows `%LOCALAPPDATA%\VillagerChatter\`, Linux/SteamOS `$XDG_DATA_HOME` or `~/.local/share/villagerchatter/` (Flatpak Prism remaps this into its sandbox; still shared across its instances). On first run, MOVE an existing `<gameDir>/villagerchatter/` download there instead of re-downloading; keep SHA-256 checks; configurable override path.
4. **Modrinth release**: icon, screenshots (Enx has a butcher trade-screen screenshot he likes: "I've got a knife and a lot of patience."), description from README.

Workflow notes: build in the VM copy ($HOME/vc) because the Documents mount can't delete files; JDK 25 at $HOME/tools/jdk-25.0.4.1+1 (set JAVA_HOME). Commit/push via GitHub Desktop (give Enx a heads-up before taking the screen). Publish releases on GitHub; the release.yml workflow attaches the jar + .sha256. Tell him to remove the old jar when upgrading.
26.x gotcha: UseEntityCallback always gets an EntityHitResult now (interact packets merged), so never filter on `hit == null`.

## Commands
- Build: `./gradlew build` → `build/libs/villagerchatter-<version>.jar`
- Test in game: `./gradlew runClient`

## Roadmap
- [x] Stage 1–2: project + hand-written lines (builds successfully)
- [x] Stage 3: prompt tested (few-shot, see AiLines.SHOTS)
- [x] Stage 4 (done, v0.2.0): mod calls Ollama over HTTP (localhost:11434) on a background thread; NEVER block the server tick; keep a small cache of pre-generated lines per villager; fall back to hand-written lines if the model is unavailable; filter output (one short sentence, no weird/unsafe text)
- [x] Stage 5 (v0.8.0): built-in llama-server download (LocalRuntime) so players don't need Ollama

## Gotchas
- 26.x renamed things: e.g. Villager is `net.minecraft.world.entity.npc.villager.Villager`; Fabric's tick events are `START/END_LEVEL_TICK` (not WORLD). Check real class/method names in the Gradle cache rather than guessing from older-version tutorials.
