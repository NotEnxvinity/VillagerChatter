# Villager Chatter

**Minecraft villagers that actually talk.** A Fabric mod for Minecraft Java 26.3 where villagers say things that fit who they are and what's happening around them. The lines are written live by a small AI model that runs **entirely on your own computer**. No account, no API key, no internet needed after the first download.

## What it does
- **Speech bubbles.** Villagers comment on their job, the biome, the time, the weather, nearby monsters, raids, and how hurt you look. Only one talks at a time, with vanilla mood particles (happy, angry, sweating, hearts).
- **Talk to them while trading.** Open a villager's trades and a conversation panel appears above the window: the villager's line, three reply options written for that moment, and "Let's just trade." Hover over a cut-off reply to read all of it.
- **They know their shop.** Conversations use the villager's real trades ("I'll take 32 rotten flesh for one emerald").
- **They remember you.** Trades, hits, and vanilla reputation are saved per villager. Regulars get greeted like friends; hit one and it holds a grudge.
- **Trade reactions.** Finish a trade mid-conversation and the villager comments on it.
- **Instant reactions.** Punch a villager and hear about it right away.
- **World reactions.** Villagers panic when a raid starts, celebrate (or mourn) when it ends, react when someone rings the village bell, and mutter a goodnight as they climb into bed.
- **Villager small talk.** Two villagers standing together sometimes have a short overheard exchange, and they look at each other while they talk.
- **Always works.** If the AI is off, loading, or says something strange, villagers fall back to hand-written lines.

## Install
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3, plus [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `villagerchatter-<version>.jar` in your `mods` folder (build it yourself, see below, or grab a release).
3. Launch. The first time, the mod downloads its AI (see below). Villagers use simple lines until chat says **"Villagers are now fully awake."**

**Tested on macOS (Apple Silicon) and SteamOS (Linux).** Windows builds of the AI are included and should work the same way.

The mod needs to be on the server, or in your singleplayer game, for villagers to talk. It needs to be on the client for the trade-screen conversation panel. Speech bubbles show up even for players without the mod.

## How the AI works
On first launch the mod downloads two things into `.minecraft/villagerchatter/`:

| File | From | Size |
|---|---|---|
| `llama-server` (llama.cpp, pinned build b11242) for your OS | official [llama.cpp GitHub release](https://github.com/ggml-org/llama.cpp/releases/tag/b11242) | ~12–35 MB |
| `Qwen3-4B-Q4_K_M.gguf` | official [Qwen Hugging Face page](https://huggingface.co/Qwen/Qwen3-4B-GGUF) | ~2.5 GB |

Both are checked against pinned SHA-256 hashes before use. The server runs in the background on a random port that **only listens on your own computer (127.0.0.1)**, uses your GPU when it can (Metal on Mac, Vulkan on Windows/Linux, falling back to CPU), and shuts down when the game closes. Nothing you do in game is sent anywhere.

Plan on about 3 GB of free RAM for the AI. Its log is at `villagerchatter/llama-server.log`.

Prefer Ollama? Set `backend=ollama` in the config and the mod will use your Ollama install instead (model set by `model=`, default `qwen3:4b`).

## Config
`.minecraft/config/villagerchatter.properties`

| Setting | Default | What it does |
|---|---|---|
| `aiEnabled` | `true` | `false` = hand-written lines only |
| `backend` | `builtin` | `builtin` or `ollama` |
| `showBubbles` / `showInChat` | `true` / `false` | where lines appear |
| `particles` | `true` | mood particles |
| `dialogueEnabled` | `true` | trade-screen conversations |
| `smallTalkChance` | `0.35` | how often villagers chat with each other |
| `talkChance`, `hearingRange`, `villagerCooldownSeconds`, `playerCooldownSeconds` | | how chatty villagers are |

## Building
Requires JDK 25.
```
./gradlew build        # jar ends up in build/libs/
./gradlew runClient    # test in a dev Minecraft
```

## Roadmap
- [x] Ambient AI lines, speech bubbles, mood particles
- [x] Trade-screen conversations with reply options
- [x] Villager memory, trade and hit reactions, villager small talk
- [x] Built-in AI (no Ollama needed)
- [x] World reactions: raids starting/ending, the bell, bedtime
- [ ] In-game settings screen, bubble size, chattiness slider, "hrmm" sounds
- [ ] Shared model folder across instances, Modrinth release

See [DESIGN.md](DESIGN.md) for the design notes and the model comparison.

## Credits
- AI engine: [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT)
- Model: [Qwen3-4B](https://huggingface.co/Qwen/Qwen3-4B-GGUF) by the Qwen team (Apache-2.0)
- Built on [Fabric](https://fabricmc.net/)

## License
[MIT](LICENSE) © 2026 Enxvinity
