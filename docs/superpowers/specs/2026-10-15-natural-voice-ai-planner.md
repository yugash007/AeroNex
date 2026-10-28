# Natural voice commands: rules first, LFM planner + Needle tool calls (v0.4.0)

Amends [architecture v2](2026-10-11-voice-drone-architecture-v2.md) section 4.9 ("strict template parser, no LLM") at the operator's request (2026-10-15). Everything else in v2 stays: the Kotlin service owns link, safety and speech; STOP never waits for speech or AI.

## Operator decisions (2026-10-15)

| Topic | Decision |
|---|---|
| When AI runs | **Rules first, AI fallback.** Known phrasings parse with the exact rules (< 10 ms). Only an utterance the rules cannot read goes to the AI path. |
| Planner | **LFM2.5-VL-450M Q4_K_M** (already on disk, 229,313,568 B, sha256 `1093f133…ab68`) via llama.cpp, as in 0.2.0. |
| Tool caller | **Cactus Needle** (`needle3.cact`, 35,335,380 B, sha256 `c9d915ec…0c38`, runtime `libneedle.a` android-arm64 sha256 `4522a16e…dbb`). Needle takes one command at a time, so LFM splits the utterance into single-command steps and Needle turns each step into one tool call. |
| Translation | **Lexicon gloss on every phone.** Recognised hi/te/ta/kn/ml words are mapped through the six-language command dictionary (GrammarTables) to English before planning. No neural MT model. |
| Languages | **In-app download** for the ta/kn/ml/te IndicConformer packs (Models screen, HTTPS + SHA-256), and **Telugu gets IndicConformer alongside Vosk**, memory-optimised. |
| Number words | Spoken numbers are normalised on the phone (Kotlin tables generated from the grammar YAML; the Python `word2number`-style idea, done natively): "three", "twenty three", "two point five", "two and a half", "one and a half", "half a metre". |

## Command path

```
speech (Vosk grammar / IndicConformer CTC) or typed text
  -> rules parser (GrammarTables, golden-gated)            -- accepted: plan (source rules)
  -> rejected for a SAFETY reason (negation, negative or out-of-range number, unit, already flying, ...): final, never sent to AI
  -> "not understood" / ai_required only:
       gloss to English (non-English languages)            -- deterministic, dictionary-based
       LFM planner: ordered list of 1-5 short English single-command steps (JSON, grammar-constrained)
       per step: rules parser first; if it cannot read the step -> Needle tool call (one call per step)
       grounding check: every number and direction in a tool call must appear in the (glossed) utterance
       FlightPlan rules (1-5 steps) + PlanValidator (vehicle state, home distance <= 30 m)
  -> pending plan, source AI, "Understood as: ..." shown, ONE CONFIRM, 15 s expiry
```

## Safety rules (each has a test)

- **A1** STOP, HOLD and status never go through AI. The voice stop spotter, partial-token STOP, buttons, Volume-Down and the notification are unchanged.
- **A2** AI is only consulted when the rules report "not understood" (`ai_required` or the generic not-understood rejection) or "say a plain number" (more than one number in a step, e.g. "after taking off to 6 meters move left 4 meters"; amended after N6). A rule rejection for negation, a negative, zero or out-of-range number, a unit symbol, two commands or two directions in one step, an unknown word (a qualifier such as "quickly" must not be dropped), a missing distance or direction, or a vehicle-state reason is final. A self-correction ("mean", "meant", "actually", "sorry", "instead", "rather", "scratch", "correction") is never sent to the AI. Discourse fillers ("um", "uh", "hmm", "basically", "like", "should", "please", "okay", "can you") are read past by the rules themselves.
- **A3** AI output is never executed directly: it becomes a normal `FlightPlan` (source `ai`, shown as "AI") that passes the structural rules and `PlanValidator` and needs one CONFIRM. The supervisor accepts source `ai` only from the in-process AI pipeline, never from the channel (a Dart `submitPlan` with `ai` steps is still refused).
- **A4** Grounding: a tool call whose number (after number-word normalisation) or direction does not appear in the utterance or its gloss is rejected ("I could not match that to what you said"). The AI cannot invent distances, heights or directions.
- **A5** The AI runs on its own thread with a total budget (default 6 s; LFM ≤ 4.5 s, Needle ≤ 0.5 s per step). STOP, a new PTT press, new typed input or disconnect cancels it, and a late result is discarded (request token).
- **A6** AI availability: arm64 only (Cactus ships android-arm64 only); Tier M/H by default; models load lazily on first use and unload after 120 s idle and on `onTrimMemory`. 32-bit and Tier L phones stay rules-only and say so in plain words.

## Telugu with IndicConformer, optimised

- Tier M/H: IndicConformer-te CTC is the command recogniser; on Tier H Vosk-te stays resident as the streaming stop spotter (native Telugu voice STOP); on Tier M the GigaSpeech KWS is the spotter (English "stop") and the Flight screen says so.
- Tier L: Vosk-te only (as in 0.3.0).
- Optimisations: lazy CTC load at first PTT of the session (pre-warm when the language is selected), one CTC model resident at a time, decode threads per tier, parser warm-up at load, single-flight pack import with a visible "Installing voice pack" state.

## Out of scope

Neural MT, armv7 AI, cloud models, real-hardware unlock (spec 8.4 still applies).
