# Villager Chatter — design notes

Open questions Enx raised, with a recommendation for each. Nothing here is final.

## 1. Where does the text show up?
| Option | Pros | Cons |
|---|---|---|
| Chat (current) | Easy, works on servers with no client mod, history is scrollable | Doesn't feel like the villager is talking; spammy with many villagers |
| Speech bubble above the head | Feels alive; you can see *who* is talking | Needs rendering work (text display entity, or a client-side renderer) |
| Both (setting) | Players pick | More to maintain |

**Recommendation:** next visual step is an overhead bubble that fades after ~4 s, using a server-side text display entity riding the villager (works without the mod on the client). Keep chat as an option in the config.

## 2. Should the player be able to reply (e.g. in the trading screen)?
Tempting, but it changes the project from "ambient chatter" into "chatbot NPCs." A 1.7B model holds a one-liner well; it does not hold a conversation well, and a reply box needs client-side UI.

**Recommendation:** not yet. Middle ground first: a one-line greeting/goodbye when you open/close the trade screen, and a reaction after a trade ("Pleasure doing business"). Revisit real replies once the ambient part feels great.

## 3. React to more than just players walking up?
Yes — this is the cheapest way to make villagers feel alive, because the model just needs a different sentence in its prompt.

Already in the prompt: job, biome, day/night, rain/thunder, raid in progress, monster nearby, villager hurt, player badly hurt.

Good next events (in rough order of payoff):
1. **Villager gets hit** → immediate reaction, skipping the cooldown ("OW! What was that for?")
2. **Trade completed** → reaction to the trade
3. **Raid starts / ends** → panic lines, then relief lines
4. **Iron golem / cat nearby, bell rung, villager going to bed**
5. **Villager-to-villager chatter** when no player is near but one can overhear
6. **Memory:** a villager remembers you (hit them before? traded a lot?) and it goes into the prompt

## 4. Model choice
Tested in a Linux VM on CPU:
- `smollm2:360m` — ~0.1–0.2 s per line, kept 24/24 lines after filtering, but bland and repetitive ("Rain again.")
- `smollm2:1.7b` — ~0.3 s per line, noticeably funnier and more specific. **Default.**
Cold start (first line after the model loads) takes a few seconds; the mod falls back to a hand-written line if the AI takes longer than `aiTimeoutMs`.

## 5. Safety
Output passes a filter: strip stage directions/quotes, keep the first sentence, reject >16 words or blocked words. Rejected output falls back to a hand-written line. Worth expanding the blocklist as you see weird output.

## 6. Model comparison (v0.7, Sep 2026)
Same cleric conversation, 4 turns, CPU-only VM:
- `smollm2:1.7b` — confused about trades ("32 Rotten Flesh for 3 emeralds"), long rambling replies, parroted examples/memory.
- `nemotron-mini` (NVIDIA's 4B on-device roleplay model from ACE, 2.7 GB) — coherent but bland; replies drifted into "I'm sure you'll enjoy it."
- `qwen3:1.7b` — better voice, but repeated the same reply sets.
- **`qwen3:4b` — winner.** Quotes real trades correctly, short player replies (2–5 words as asked), actually funny. ~1–3 s per turn on CPU, faster on Apple GPU. NVIDIA ACE itself now ships Qwen3 (8B) for on-device game characters.
Memory: all requests use `num_ctx=2048` (Ollama's larger default was the main reason llama-server used ~5.8 GB).
