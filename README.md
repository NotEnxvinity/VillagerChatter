# Villager Chatter

A Fabric mod for Minecraft 26.3 where villagers say random, situation-aware lines when you walk near them.

## Roadmap
- [x] Stage 1 — Fabric project set up
- [x] Stage 2 — Villagers say hand-written lines (by job, rain, baby, player hurt)
- [x] Stage 3 — Tested prompts with SmolLM2 (few-shot prompt + output filter)
- [x] Stage 4 — Mod asks Ollama for lines on a background thread, falls back to hand-written lines
- [x] v0.3 — Speech bubbles above heads + mood particles
- [x] v0.4 — Conversations in the trading screen (villager line + 3 AI replies + "Let's just trade.")
- [x] v0.5 — Villager memory (trades, hits, last thing said; persists), trade reactions, instant hit reactions
- [x] v0.6 — Villagers chat with each other (overheard 2–4 line exchanges)
- [x] v0.7 — Switched to qwen3:4b (much better dialogue), JSON output everywhere, 2k context (less RAM), auto-download of the model
- [x] v0.8 — Built-in AI: mod downloads and runs llama.cpp + Qwen3-4B itself; Ollama optional
- [ ] In-game settings screen, Modrinth page

## First-time setup (Mac)
1. Install **JDK 25** (Temurin: https://adoptium.net) and **IntelliJ IDEA Community**.
2. In IntelliJ: Settings → Plugins → install **Minecraft Development**.
3. File → Open → select this `VillagerChatter` folder. Let Gradle sync (first time downloads Minecraft, takes a few minutes).
   - If asked for a JDK, pick 25.
4. Gradle panel (elephant icon, right side) → Tasks → fabric → **runClient**.
5. Make a creative world, find a village, and walk up to villagers. Lines show up in chat.

## The AI (no setup needed)
Since v0.8 the mod runs its own AI. On first launch it downloads, into `.minecraft/villagerchatter/`:
- llama.cpp's `llama-server` for your OS (official GitHub release, pinned build b11242, ~12–35 MB)
- the Qwen3-4B model (`Qwen3-4B-Q4_K_M.gguf`, official Qwen Hugging Face page, ~2.5 GB)

Both are checked against pinned SHA-256 hashes. Villagers use hand-written lines until it's ready; you'll get a chat message when it is.
The server only listens on 127.0.0.1 and stops when the game closes. Log: `villagerchatter/llama-server.log`.

Settings live in `.minecraft/config/villagerchatter.properties` (in Prism: instance → Folder → config).
- `backend=builtin` (default) or `backend=ollama` to use an Ollama install instead (`model=` picks the Ollama model)
- `aiEnabled=false` for hand-written lines only
- Other switches: `showBubbles`, `showInChat`, `particles`, `dialogueEnabled`, `smallTalkChance`.
- The game log shows `[AI]` or `[hand-written]` next to each line.

## Where to tinker
- `ChatterLines.java` — what villagers know (`Situation`) + hand-written fallback lines.
- `AiLines.java` — the prompt, examples, and output filter for the AI.
- `ChatterConfig.java` — settings file.
- `DESIGN.md` — ideas and decisions for what to build next.
- `VillagerChatter.java` — tuning knobs at the top: range, talk chance, cooldowns.

## Build a jar for Prism Launcher
Gradle panel → Tasks → build → **build**. The mod jar is in `build/libs/`.
Drop it (plus Fabric API) into a Fabric 26.3 instance's `mods` folder in Prism.
