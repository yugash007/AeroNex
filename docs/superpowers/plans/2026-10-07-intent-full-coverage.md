# Intent Full Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend rule-based intent from en/hi/te to ta/kn/ml plus script-based auto language detection, with v1 protocol frozen.

**Architecture:** Extend `edge/app/intent.py` in place (pure functions, no server imports); `detect_language()` counts Unicode-block chars and `parse_transcript()` gains an `"auto"` default with explicit-override. Dart `rule_intent_engine.dart` mirrors Python verbatim.

**Tech Stack:** Python 3.12, pytest 8; Flutter/Dart (hand-mirrored, SDK absent here — structural check only).

**Spec:** `docs/superpowers/specs/2026-10-07-intent-full-coverage-design.md` (source: `AeroNex_SRS_v1.0.md` + `docs/superpowers/specs/2026-10-04-aeronex-design.md`)

## Global Constraints

- Normal operation uses no internet, no cloud STT/translation/LLM, no remote API.
- V1 envelope and command schema frozen — no changes to `protocol/*`, `edge/app/commands.py`, `edge/app/websocket.py`, `edge/app/server.py`, `edge/app/state.py`, `edge/app/config.py`, `edge/app/discovery.py`.
- Phone sends structured commands only; never streams raw audio to Edge.
- `SUPPORTED` becomes `("en","hi","te","ta","kn","ml")`; `STUBBED` becomes `()`.
- MOVE validation: explicit motion verb + direction + distance required; `0 < distance_m <= 500` else `IntentError`; never emit phantom MOVE.
- Confidence: 0.9 exact canonical phrasing, 0.7 fallback (extra filler, word-numerals, missing unit).
- Simples priority order: STOP, TAKEOFF, LAND, HOVER.
- All new ta/kn/ml tables carry `PROVISIONAL (2026-10-07): needs native-speaker review` markers in Python + Dart; do not expand en/hi/te tables in this slice.
- Dart mirrors Python verbatim (same values, same order, same confidence numbers).
- `UnsupportedLanguageError` subclasses `IntentError`; unknown codes raise, detection never raises.
- No MAVLink, flight control, autonomy, or vision code.

## Review Focus

- Transliterated code-mix (`"move munduku 10 meters"`, Latin-script Telugu) detects `en` and must raise `IntentError`, never a phantom `MOVE` — pinned in Task 2 code-mix test.
- Pure-digit auto input (`"10"`) falls back to `en` then fails verb check with `IntentError` — pinned in Task 1 digits-fallback test.
- Two-token tens+unit composition in new languages (e.g. Tamil 21 two-word form) must parse like `te` tens composition — pinned in Task 2 tens-composition tests.
- Tamil/Kannada/Malayalam punctuation around words (e.g. `।`, `॥`, `‘’“”–—`) must strip identically in Python and Dart — pinned in Task 3 Dart mirror cases + Task 2 punctuation test.
- Explicit hint conflicting with script (`parse_transcript(tamil_text, "hi")`) must use `hi` and raise `IntentError` (no Tamil verb in `hi`), never silently auto-correct — pinned in Task 1 override test.

---

### Task 1: Script detection + auto parameter (Python, no new vocab)

**Files:**
- Modify: `edge/app/intent.py`
- Test: `edge/tests/test_intent.py` (append only; do not edit the existing 8 tests)

**Interfaces:**
- Consumes: existing `parse_transcript(transcript: str, language: str) -> dict`, `SUPPORTED == ("en","hi","te")`, `STUBBED == ("ta","kn","ml")`.
- Produces: `detect_language(text: str) -> str` (returns one of `"en"|"hi"|"te"|"ta"|"kn"|"ml"`, never raises; digits/punctuation do not vote; no Indic chars → `"en"`); `parse_transcript(transcript: str, language: str = "auto") -> dict` where `"auto"`/`""`/whitespace-only resolves via `detect_language`, explicit code (or alias) overrides detection; `AUTO_HINTS = ("auto", "")`.

- [ ] **Step 1: Write the failing tests** — append to `edge/tests/test_intent.py`: `test_detect_language_telugu_block`, `test_detect_language_hindi_block`, `test_detect_language_tamil_block`, `test_detect_language_kannada_block`, `test_detect_language_malayalam_block`, `test_detect_language_latin_fallback`, `test_auto_detect_parses_english_move`, `test_explicit_overrides_auto`, `test_pure_digits_auto_fallback_raises_intent_error`. Detection asserts exact codes for one fixture per script (existing en/hi/te MOVE fixtures reused for hi/te/en blocks); override asserts `parse_transcript("<tamil MOVE fixture>", "hi")` raises `IntentError`; digits asserts `parse_transcript("10", "auto")` raises `IntentError`.
- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest edge/tests/test_intent.py -v`
Expected: FAIL (9 new tests error on missing `detect_language`; existing 8 pass).

- [ ] **Step 3: Implement `detect_language` + auto plumbing in `edge/app/intent.py`** — block ranges Telugu U+0C00–U+0C7F, Devanagari U+0900–U+097F, Tamil U+0B80–U+0BFF, Kannada U+0C80–U+0CFF, Malayalam U+0D00–U+0D7F (count chars, pick max; tie → first-seen wins, deterministic by fixed block order te,hi,ta,kn,ml); add `language: str = "auto"` default and `AUTO_HINTS`; keep `SUPPORTED`/`STUBBED` unchanged in this task.
- [ ] **Step 4: Run tests**

Run: `python -m pytest edge/tests/test_intent.py -v`
Expected: PASS, 17/17 (8 existing + 9 new).

- [ ] **Step 5: Commit**

```bash
git add edge/app/intent.py edge/tests/test_intent.py
git commit -m "feat: add script-based detect_language with auto hint"
```

### Task 2: Provisional ta/kn/ml tables + numerals (Python reference)

**Files:**
- Modify: `edge/app/intent.py`
- Test: `edge/tests/test_intent.py` (append only)

**Interfaces:**
- Consumes: `detect_language(text: str) -> str`, `parse_transcript(..., language="auto")` from Task 1.
- Produces: `SUPPORTED == ("en","hi","te","ta","kn","ml")`, `STUBBED == ()`; `_PER_LANG` gains `ta`/`kn`/`ml` entries with `verbs`, `directions` (6 keys), `units`, `simple` (STOP-first); `_NATIVE_DIGITS` gains Tamil U+0BE6–U+0BEF, Kannada U+0CE6–U+0CEF, Malayalam U+0D66–U+0D6F; numeral maps `TA_NUMBERS`/`KN_NUMBERS`/`ML_NUMBERS` (1–100) + tens-composition sets parallel to `te`.

- [ ] **Step 1: Write the failing tests** — append: `test_ta_move_forward_10m`, `test_kn_move_forward_10m`, `test_ml_move_forward_10m` (each asserts `{"command":"MOVE","parameters":{"direction":"FORWARD","distance_m":10},"confidence":0.9}` for the canonical fixture chosen with the table), `test_ta_simples`, `test_kn_simples`, `test_ml_simples` (takeoff/land/hover/stop one assertion each), `test_ta_kn_ml_tens_composition_21` (one 21-value assertion per language exercising two-token tens+unit where applicable), `test_gibberish_ta_kn_ml_raise`, `test_negative_distance_ta_rejected`, `test_all_six_supported_and_no_stubs`, `test_transliterated_codemix_raises` (`"move munduku 10 meters"` auto → `IntentError`), `test_tamil_punctuation_strips` (leading/trailing `।`/`""` around canonical Tamil MOVE still 0.9).
- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest edge/tests/test_intent.py -v`
Expected: FAIL (new tests raise `UnsupportedLanguageError` for ta/kn/ml; ~12 errors, 17 pass).

- [ ] **Step 3: Implement ta/kn/ml tables in `edge/app/intent.py`** — add `TA_*`/`KN_*`/`ML_*` vocab constants with `PROVISIONAL` marker comments, wire into `_PER_LANG`, extend `_NATIVE_DIGITS` and `_match_number` with `ta`/`kn`/`ml` branches mirroring `te` (tens+unit composition) or `hi` (flat map) per language structure, flip `SUPPORTED`/`STUBBED`; no changes to envelope/commands/transport.
- [ ] **Step 4: Run full edge suite**

Run: `python -m pytest edge/tests/ -v`
Expected: PASS (protocol 5 + server 8 + intent ~29 + e2e 1; total grows from 22, zero regressions).

- [ ] **Step 5: Commit**

```bash
git add edge/app/intent.py edge/tests/test_intent.py
git commit -m "feat: add provisional ta kn ml intent tables with numerals"
```

### Task 3: Dart mirror + demo help (Flutter hand-mirror, verified later)

**Files:**
- Modify: `app/lib/features/intent/data/rule_intent_engine.dart`
- Modify: `app/test/intent_test.dart` (append mirror cases only)
- Modify: `edge/app/demo_client.py` (`--lang` help string only: `en|hi|te|ta|kn|ml|auto`)

**Interfaces:**
- Consumes: Task 1–2 semantics — `detect_language` block ranges, `SUPPORTED` 6-tuple, per-language vocab values, 0.9/0.7 confidences, `MAX_DISTANCE_M = 500`.
- Produces: Dart `supportedLanguages == ['en','hi','te','ta','kn','ml']`, `String _detectLanguage(String text)` (same ranges via `codeUnit` checks), `RuleBasedIntentEngine.parse(transcript, language)` accepting `"auto"`/`""` (explicit overrides), verbatim `ta/kn/ml` const tables with `PROVISIONAL` header note; demo `--lang` default stays `en`.

- [ ] **Step 1: Mirror the tables + detector in `rule_intent_engine.dart`** — add `taMoveVerbs`/`knMoveVerbs`/`mlMoveVerbs`, `taDirections`/`knDirections`/`mlDirections`, `taUnits`/`knUnits`/`mlUnits`, `taSimple`/`knSimple`/`mlSimple`, `taNumbers`/`knNumbers`/`mlNumbers` (+ tens sets), native-digit branches in `_normalizeDigits`, `ta`/`kn`/`ml` branches in `_matchNumber`, `_detectLanguage` + auto resolution in `_parse`; keep en/hi/te lines byte-identical.
- [ ] **Step 2: Mirror the tests in `app/test/intent_test.dart`** — add ta/kn/ml MOVE-10m, one simple per language, auto-detect Tamil, override-conflict, gibberish-ta, negative-ta, transliterated code-mix, punctuation-strip cases mirroring Task 1–2 assertions.
- [ ] **Step 3: Update demo help** — `--lang` help becomes `"Spoken language code: en | hi | te | ta | kn | ml | auto"`; no behavior change.
- [ ] **Step 4: Verify (no Flutter SDK here)** — run Python suite plus Dart structural check:

Run: `python -m pytest edge/tests/ -v`
Expected: PASS (same totals as Task 2; proves no Python regression from Task 3 edits).

Run: `python -c "import os,sys; need='lib/features/intent/data/rule_intent_engine.dart test/intent_test.dart'.split(); missing=[p for p in need if not os.path.exists(os.path.join('app',p))]; sys.exit(1 if missing else 0)"`
Expected: exit 0. Record in commit message: `flutter test deferred — no SDK on build machine`.

- [ ] **Step 5: Commit**

```bash
git add app/lib/features/intent/data/rule_intent_engine.dart app/test/intent_test.dart edge/app/demo_client.py
git commit -m "feat: mirror ta kn ml intent plus auto-detect in dart (unverified: no SDK)"
```
