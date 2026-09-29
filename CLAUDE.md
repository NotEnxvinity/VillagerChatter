# Villager Chatter — notes for Claude Code

Fabric mod for Minecraft Java 26.3 (Mojang's unobfuscated names, no Yarn). Java 25, Gradle 9.7.1 wrapper, Loom 1.18.
Owner: Enx — freshman engineering student, knows JavaScript/Swift, new to Java and Fabric. Explain changes briefly as you go.

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

## Commands
- Build: `./gradlew build` → `build/libs/villagerchatter-<version>.jar`
- Test in game: `./gradlew runClient`

## Roadmap
- [x] Stage 1–2: project + hand-written lines (builds successfully)
- [x] Stage 3: prompt tested (few-shot, see AiLines.SHOTS)
- [x] Stage 4 (done, v0.2.0): mod calls Ollama over HTTP (localhost:11434) on a background thread; NEVER block the server tick; keep a small cache of pre-generated lines per villager; fall back to hand-written lines if the model is unavailable; filter output (one short sentence, no weird/unsafe text)
- [ ] Stage 5: bundle the model in-mod with java-llama.cpp (de.kherud:llama) so players don't need Ollama

## Gotchas
- 26.x renamed things: e.g. Villager is `net.minecraft.world.entity.npc.villager.Villager`; Fabric's tick events are `START/END_LEVEL_TICK` (not WORLD). Check real class/method names in the Gradle cache rather than guessing from older-version tutorials.
