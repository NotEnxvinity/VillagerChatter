# Villager Chatter

**Minecraft villagers that actually talk.** A Fabric mod for Minecraft Java 26.3 where villagers say things that fit who they are and what's happening around them. The lines are written live by a small AI model that runs **entirely on your own computer**. No account, no API key, no internet needed after the first download.

## What it does
- **Speech bubbles.** Villagers comment on their job, the biome, the time, the weather, nearby monsters, raids, and how hurt you look. Only one talks at a time, with vanilla mood particles (happy, angry, sweating, hearts).
- **Talk to them while trading.** Open a villager's trades and a conversation panel appears above the window: the villager's line, three reply options written for that moment, and "Let's just trade." Hover over a cut-off reply to read all of it.
- **They know their shop.** Conversations use the villager's real trades ("I'll take 32 rotten flesh for one emerald").
- **They remember you.** Trades, hits, and vanilla reputation are saved per villager. Regulars get greeted like friends; hit one and it holds a grudge.
- **Trade reactions.** Finish a trade mid-conversation and the villager comments on it.
- **Instant reactions.** Punch a villager and hear about it right away. Punch one awake and it's just grumpy, not holding a grudge.
- **Jobless villagers talk too.** Nitwits, unemployed villagers and kids have nothing to trade, so right-clicking them gets a line instead of just a head shake.
- **World reactions.** Villagers panic when a raid starts, celebrate (or mourn) when it ends, react when someone rings the village bell, and mutter a goodnight as they climb into bed.
- **Villager small talk.** Two villagers standing together sometimes have a short overheard exchange, and they look at each other while they talk.
- **Villager sounds.** Every line comes with the villager's own "hrmm": a happy one for good news, a grumpy one when they're upset or scared.
- **In-game settings.** Chattiness, small talk, bubble size, bubbles or chat, sounds, particles and the AI itself, all on one screen (see below).
- **Always works.** If the AI is off, loading, or says something strange, villagers fall back to hand-written lines.

## Install
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3, plus [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `villagerchatter-<version>.jar` in your `mods` folder (build it yourself, see below, or grab a release).
3. Optional: install [Mod Menu](https://modrinth.com/mod/modmenu) for a Configure button.
4. Launch. The first time, the mod downloads its AI (see below). Villagers use simple lines until chat says **"Villagers are now fully awake."**

Upgrading? Delete the old `villagerchatter-*.jar` from your `mods` folder first.

**Tested on macOS (Apple Silicon) and SteamOS (Linux).** Windows builds of the AI are included and should work the same way.

The mod needs to be on the server, or in your singleplayer game, for villagers to talk. It needs to be on the client for the trade-screen conversation panel. Speech bubbles show up even for players without the mod.

## How the AI works
On first launch the mod downloads two things into one folder that **all your Minecraft instances share**, so the 2.5 GB model is only downloaded once:

| OS | Folder |
|---|---|
| macOS | `~/Library/Application Support/VillagerChatter/` |
| Windows | `%LOCALAPPDATA%\VillagerChatter\` |
| Linux / SteamOS | `~/.local/share/villagerchatter/` (Flatpak launchers like Prism keep it inside their own sandbox, still shared by all their instances) |

You can pick a different folder in the settings (AI Files Folder). Upgrading from 0.9 or older? Your existing download in `.minecraft/villagerchatter/` is moved over automatically, no re-download.

What gets downloaded:

| File | From | Size |
|---|---|---|
| `llama-server` (llama.cpp, pinned build b11242) for your OS | official [llama.cpp GitHub release](https://github.com/ggml-org/llama.cpp/releases/tag/b11242) | ~12–35 MB |
| `Qwen3-4B-Q4_K_M.gguf` | official [Qwen Hugging Face page](https://huggingface.co/Qwen/Qwen3-4B-GGUF) | ~2.5 GB |

Both are checked against pinned SHA-256 hashes before use. The server runs in the background on a random port that **only listens on your own computer (127.0.0.1)**, uses your GPU when it can (Metal on Mac, Vulkan on Windows/Linux, falling back to CPU), and shuts down when the game closes. Nothing you do in game is sent anywhere.

Plan on about 3 GB of free RAM for the AI (switching the AI off in the settings frees it). Its log is at `.minecraft/villagerchatter/llama-server.log`.

Prefer Ollama? Set `backend=ollama` in the config and the mod will use your Ollama install instead (model set by `model=`, default `qwen3:4b`).

## Settings
Open them from **Mod Menu** (Configure button) or type **`/villagerchatter settings`** in chat.

| Setting | Default | What it does |
|---|---|---|
| Chattiness | 15% | how likely a nearby villager is to speak each second |
| Small Talk | 35% | how often villagers chat with each other instead |
| Trade Conversations | On | the conversation panel in the trading screen |
| Show Lines | Bubbles | bubbles, chat, or both |
| Bubble Size | 100% | speech bubble size, 50–200% |
| Villager Sounds | On | the "hrmm" with each line |
| Mood Particles | On | sparkles, angry clouds, sweat drops, hearts |
| Villager AI | On | off = hand-written lines only, and the AI's memory is freed |
| AI Files Folder | shared | where the model is kept |

Changes apply right away in singleplayer and LAN worlds. On a dedicated server, edit the server's `config/villagerchatter.properties` (same names, plus `backend`, `hearingRange`, `villagerCooldownSeconds`, `playerCooldownSeconds`, `aiTimeoutMs`).

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
- [x] In-game settings screen, bubble size, chattiness slider, "hrmm" sounds (v0.10)
- [x] Shared model folder across instances (v0.10)
- [ ] Modrinth release

See [DESIGN.md](DESIGN.md) for the design notes and the model comparison.

## Credits
- AI engine: [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT)
- Model: [Qwen3-4B](https://huggingface.co/Qwen/Qwen3-4B-GGUF) by the Qwen team (Apache-2.0)
- Built on [Fabric](https://fabricmc.net/)

## License
[MIT](LICENSE) © 2026 Enxvinity
