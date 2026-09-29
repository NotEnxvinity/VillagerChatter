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
- [ ] Stage 5 — Bundle the model inside the mod (java-llama.cpp)

## First-time setup (Mac)
1. Install **JDK 25** (Temurin: https://adoptium.net) and **IntelliJ IDEA Community**.
2. In IntelliJ: Settings → Plugins → install **Minecraft Development**.
3. File → Open → select this `VillagerChatter` folder. Let Gradle sync (first time downloads Minecraft, takes a few minutes).
   - If asked for a JDK, pick 25.
4. Gradle panel (elephant icon, right side) → Tasks → fabric → **runClient**.
5. Make a creative world, find a village, and walk up to villagers. Lines show up in chat.

## Turning on the AI
1. Install Ollama for Mac (https://ollama.com/download) and open it once.
2. In Terminal: `ollama pull smollm2:1.7b`
3. Launch the game. Settings live in `.minecraft/config/villagerchatter.properties` (in Prism: instance → Folder → config).
   Set `aiEnabled=false` for hand-written lines only, or `model=smollm2:360m` for the tinier model.
   Other switches: `showBubbles`, `showInChat`, `particles`, `dialogueEnabled`, `smallTalkChance`.
4. The game log shows `[AI]` or `[hand-written]` next to each line so you can tell which is which.

## Where to tinker
- `ChatterLines.java` — what villagers know (`Situation`) + hand-written fallback lines.
- `AiLines.java` — the prompt, examples, and output filter for the AI.
- `ChatterConfig.java` — settings file.
- `DESIGN.md` — ideas and decisions for what to build next.
- `VillagerChatter.java` — tuning knobs at the top: range, talk chance, cooldowns.

## Build a jar for Prism Launcher
Gradle panel → Tasks → build → **build**. The mod jar is in `build/libs/`.
Drop it (plus Fabric API) into a Fabric 26.3 instance's `mods` folder in Prism.
