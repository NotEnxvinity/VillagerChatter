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
