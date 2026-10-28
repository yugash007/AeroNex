# Intent Full Coverage — Design Spec (Approach A: Rule-Table Extension)

**Date:** 2026-10-07
**Source:** `AeroNex_SRS_v1.0.md` v1.0 + `docs/superpowers/specs/2026-10-04-aeronex-design.md`
**Scope:** SRS Phase 4 slice — rule-based intent for ta/kn/ml + script-based language detection. V1 protocol frozen.
**Branch:** `feat/aeronex-skeleton` (work in `.worktrees/aeronex-skeleton`)
**Status:** Provisional vocab — ta/kn/ml tables ship marked PROVISIONAL pending native-speaker review.

## 1. Goal

Extend the Milestone 1 intent loop (typed text → intent → envelope → WS → ACK) from 3 languages
(en/hi/te) to 6 (add ta/kn/ml) and add offline automatic language identification, with zero new
dependencies and no protocol change.

Success:
- Canonical `MOVE/FORWARD/10` parses in each of ta/kn/ml (exact fixture strings are
  illustrative in this spec — e.g. Tamil below — and pinned in tests at implementation time).
- `parse_transcript(text, "auto")` detects the correct language by script and parses.
- Explicit code always overrides detection.
- All existing en/hi/te cases keep passing verbatim; `pytest edge/tests/` fully green.
- Flutter `RuleBasedIntentEngine` mirrors Python verbatim; Dart tests mirror new cases.

Non-goals (this slice): real STT wiring, mDNS real bind, MAVLink/flight, cloud services,
schema changes (no ROTATE/altitude/speed), ML classifiers, transliteration pipelines.

## 2. Architecture

```
typed text (+ optional lang hint)
  → detect_language(text)          [new, pure]
  → resolve code (explicit overrides auto)
  → _PER_LANG[code] vocab lookup
  → simples (STOP-first) | verb+direction+number+unit → MOVE
  → {"command","parameters","confidence"} → existing new_envelope → WS
```

- `edge/app/intent.py` extended in place. `SUPPORTED = ("en","hi","te","ta","kn","ml")`,
  `STUBBED = ()`. New exports: `detect_language(text) -> str`, `AUTO_ALIASES = ("auto","")`.
- `parse_transcript(transcript, language="auto")` keeps backward-compat signature
  (`language` required today; default added, existing callers unaffected).
- `edge/app/commands.py`, `protocol.py`, `schema.json`, envelope shape: untouched (v1 frozen).
- Dart mirror: `rule_intent_engine.dart` gains `taMoveVerbs`, `taDirections`, …,
  `taNumbers`, `_detectLanguage()`, `parse(transcript, language)` with `"auto"` support;
  `language_codes.dart` unchanged (already lists 6 codes).
- Offline, no audio streaming, no internet. Transport/speech/intent abstractions untouched.

## 3. Language detection (script blocks)

Count characters per Unicode block, pick max; ties → explicit hint wins, else Latin/en fallback:

| Code | Block | Range |
|---|---|---|
| te | Telugu | U+0C00–U+0C7F |
| hi | Devanagari | U+0900–U+097F |
| ta | Tamil | U+0B80–U+0BFF |
| kn | Kannada | U+0C80–U+0CFF |
| ml | Malayalam | U+0D00–U+0D7F |
| en | Latin/ASCII digits | default when no Indic block chars counted |

Rules:
- Digits (ASCII + all native digits below) do not vote; units/verbs decide.
- Mixed-script input (e.g. `"10 மீட்டர் முன்னால்"`) detects via the Indic words present.
- Pure-digit / punctuation-only input with `"auto"` → `"en"` fallback (number parsing is
  language-independent for digits; word-numerals then fail per-language as today).
- `detect_language` never raises; unknown → `"en"`. `parse_transcript` raises
  `UnsupportedLanguageError` only for codes outside the 6 + aliases.

Dart `_detectLanguage` uses identical `codeUnit` range checks (no Unicode property escapes).

## 4. Vocabulary tables (provisional)

Per new language (`ta`, `kn`, `ml`), mirroring en/hi/te structure exactly:

- `*_MOVE_VERBS`: 5–8 imperative motion verbs.
- `*_DIRECTIONS`: 6 canonical keys FORWARD/BACKWARD/LEFT/RIGHT/UP/DOWN, 1–4 forms each.
- `*_UNITS`: 2–4 distance-unit forms (meter equivalents).
- `*_SIMPLE`: 4 entries in priority order STOP, TAKEOFF, LAND, HOVER.
- Native digits added to `_NATIVE_DIGITS`: Tamil U+0BE6–U+0BEF, Kannada U+0CE6–U+0CEF,
  Malayalam U+0D66–U+0D6F → ASCII.
- Numeral words 1–100 + tens-composition parallel to `te` (e.g. Tamil `இருபத்து ஒன்று`
  style two-token tens+unit where the language uses it; otherwise flat 1–100 map like `hi`).
- Confidence unchanged: 0.9 exact canonical phrasing, 0.7 fallback (extra filler,
  word-numerals, missing unit). Negative (`minus` equivalent per language + `-N` digits)
  and `distance_m` outside `0 < d ≤ 500` → `IntentError`, never sent.

All new tables carry a `PROVISIONAL (2026-10-07): needs native-speaker review` marker in
both Python docstrings and the Dart file header. Do not expand en/hi/te tables in this slice.

## 5. Data flow + error handling

`parse_transcript` order (unchanged, extended to 6 langs):
1. Empty transcript → `IntentError("empty transcript")`.
2. Resolve `language` via aliases + `"auto"` → `detect_language(transcript)`.
3. Unknown code → `UnsupportedLanguageError`.
4. Simples scan (STOP first) → immediate return.
5. Require explicit motion verb → else `IntentError` (no phantom MOVE; `"hello drone…"`
   still fails as pinned).
6. Require direction → else `IntentError`.
7. Require distance (digits win over words) → else `IntentError`; validate range.
8. Compute confidence from residue / word-numerals / missing unit.

`UnsupportedLanguageError` subclasses `IntentError` (unchanged) so old callers catching
`IntentError` keep working.

## 6. Testing

Python (`edge/tests/test_intent.py`, extended; existing 8 tests untouched):
- `test_<ta,kn,ml>_move_forward_10m` — canonical MOVE fixture per language.
- `test_<ta,kn,ml>_simples` — takeoff/land/hover/stop one case each.
- `test_detect_language_<te,hi,ta,kn,ml,en>` + `test_auto_detect_parses_tamil`.
- `test_explicit_overrides_auto`, `test_gibberish_<ta,kn,ml>_raises`,
  `test_negative_distance_<ta>_rejected`, `test_all_six_supported`.
- Full suite: `python -m pytest edge/tests/ -v` must pass (22 → ~35).

Dart (`app/test/intent_test.dart`, mirrored cases; run on Flutter machine):
- Same MOVE/simples/detection/negative cases via `RuleBasedIntentEngine().parse`.
- Structural check for new symbols (verbs/directions/numbers per lang + detect helper).

Range/hardware testing, WER/intent-accuracy datasets (SRS §41.2 100-utterance sets),
and STT integration remain out of scope for this slice.

## 7. Files touched

- Edit: `edge/app/intent.py` (tables + digits + numerals + `detect_language` + auto param).
- Edit: `edge/tests/test_intent.py` (new cases only; no edits to existing 8 assertions).
- Edit: `app/lib/features/intent/data/rule_intent_engine.dart` (verbatim mirror).
- Edit: `app/test/intent_test.dart` (mirror).
- Edit: `edge/app/demo_client.py` `--lang` help text (`en|hi|te|ta|kn|ml|auto`) — argparse
  choices unchanged (free string), default stays `en`.
- No changes: `protocol/*`, `commands.py`, `websocket.py`, `server.py`, `state.py`,
  `config.py`, `discovery.py`, transport/speech layers, `schema.json`.

## 8. Acceptance

- [ ] `SUPPORTED == ("en","hi","te","ta","kn","ml")`; old stub test updated (ta/kn/ml parse).
- [ ] Script detection correct on at least one fixture per language + mixed digit+Indic input.
- [ ] `pytest edge/tests/ -v` green; demo CLI `--lang ta|kn|ml|auto` prints intent→ACK→STATUS.
- [ ] Dart file compiles on Flutter machine; `flutter test` green (deferred record if no SDK here).
- [ ] No protocol/command-schema diff; `git diff` shows only the 5 files above.
