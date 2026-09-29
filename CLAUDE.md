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
