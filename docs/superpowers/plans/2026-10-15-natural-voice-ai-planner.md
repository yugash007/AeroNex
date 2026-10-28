# Natural voice commands (v0.4.0) — implementation plan

Spec: [2026-10-15-natural-voice-ai-planner.md](../specs/2026-10-15-natural-voice-ai-planner.md). Builds on 0.3.0+12 (architecture v2). TDD per task, reviews per task, lock wrapper for Flutter/Gradle, no commits. Version target **0.4.0+13**.

Local assets (no new downloads needed for the build):
- llama.cpp source: `C:/Users/yugas/Downloads/Drone-app/.worktrees/tooling/llama.cpp`
- Cactus: `C:/Users/yugas/Downloads/Drone-app/.worktrees/tooling/cactus/` (`android-arm64-libneedle.a`, `linux-x86_64-libneedle.a`, `needle.h`, `needle3.cact`, `drone-tools.json`)
- LFM: `C:/Users/yugas/Downloads/Drone-app/.worktrees/tooling/models/LFM2.5-VL-450M-Q4_K_M.gguf`
- 0.2.0 native code (reference): `.superpowers/sdd/v2/t13-deleted/cpp/` and `kotlin-main/LocalInferenceBridge.kt`, `VerifiedModelStore.kt` (model URLs + sha256)

## Groups

**G1 (parallel)**
- **N1 Number words + synonyms.** Grammar YAML + generator + GrammarTables + Kotlin parser: decimal and fraction words ("two point five", "two and a half", "one and a half", "half a metre"), verb/direction synonyms (climb/ascend/rise/go higher → up; descend/drop/lower → down; advance/ahead → forward; reverse/retreat/back up → backward; "to the left", "towards the right", "by 2 metres"), Vosk en phrase list regenerated. Golden vectors: a documented delta file for every vector whose expectation changes (only "error → accepted" for these phrasings; any other change is a defect).
- **N2 AI runtime (arm64).** CMake restored for `aeronex_ai` (llama.cpp from the local source tree, LFM planner JNI) and `aeronex_cactus` (Needle text tool calls from the local `libneedle.a`, sha-checked), arm64-v8a only; armv7 builds carry no AI libs. Kotlin package `ai/`: `LfmPlanner`, `NeedleToolCaller`, `AiModels` (lfm-q4, needle3 profiles: URL + size + sha256; download + import), `AiAvailability` (ABI, tier). Planner output is grammar-constrained JSON `{"steps":[...]}` (≤ 5 short English single-command sentences). Host eval harness `tools/ai-eval/` (WSL, linux libneedle.a + host llama.cpp) on a natural-command corpus → `docs/testing/2026-10-15-ai-planner-eval.md`. Package checker updated.
- **N5 Voice packs in-app + Telugu CTC.** `ModelPackStore` HTTPS download (progress, cancel, SHA-256, single-flight install with a visible state), Models screen rows for ta/kn/ml/te packs (download / installed / not for this phone), Telugu tier rules (CTC on M/H, Vosk-te stop spotter on H, KWS on M), lazy CTC load with pre-warm on language select, parser warm-up.

**G2**
- **N3 Gloss + AI routing + UI.** `command/Gloss` (hi/te/ta/kn/ml → English via GrammarTables), `StepSource.AI`, AI routing in the session per spec A1–A6 (rules first; safety rejections final; AI thread with budget and cancellation; grounding check; plan source AI with one CONFIRM), snapshot `ai` section + latency keys (`aiGloss`, `aiPlan`, `aiTools`, `aiTotal`), Flutter: "Thinking…" with Cancel, AI chip, "Heard / Understood as" lines, Models rows for LFM + Needle (download / import / remove).

**G3**
- **N6 Verification + 0.4.0.** All suites once; SITL suite; host AI eval on the final code; release APKs (fat + splits) + package checker; emulator end-to-end (x86_64: AI unavailable message, rules path, pack download UI via import); docs (README, test report `docs/testing/2026-10-15-v0.4.0.md`); QA APKs `aeronex-0.4.0-*.apk`; phone test steps for the AI path.
