# Whistle and Needle implementation plan

**Goal:** Add working offline English Whistle recognition and Needle tool proposals without regressing Telugu/Whisper, transcript review or installed-model recovery.

**Spec:** `docs/superpowers/specs/2026-10-09-whistle-needle-design.md`

**Stack:** Flutter/Dart, Kotlin method channels, Android NDK C++17, pinned Cactus ARM64 C API. Execute inline in the existing implementation worktree; preserve unrelated uncommitted work.

## 1. Download and evaluate

Download the pinned Whistle/Needle `.cact` models, Windows CLI, ARM64 archive and header to the sibling tooling cache; verify SHA-256 against Hugging Face LFS metadata. Save the actual five drone schemas in `app/android/app/src/main/cpp/needle_tools.h`, usable by both JNI and the host check. Run English command, ambiguity, negation, compound and unsupported requests through the actual Needle model; run locally synthesized 16 kHz English speech through Whistle. Record raw outputs, never invoke tools. Confirm the native archive's runtime dependencies before linking it.

## 2. Model store and channel routing

Files: `VerifiedModelStore.kt`, `LocalInferenceBridge.kt`, `LocalInferenceBridgeTest.kt`, `VerifiedModelStoreTest.kt`.

Extend `LocalModelProfile` with an optional explicit download URL; existing three-argument construction remains valid. Add `whistle` and `needle3` profiles with pinned hash/size/URL. Add `whistle` capture method and `needle3` to Local AI choices, preserving the existing `lfm` wire field for compatibility. Both speech methods use the same recorder/session lifecycle. Reject non-English Whistle before microphone access and reject non-English Needle proposals with guidance to use LFM. Tests first assert profile URLs, Whistle routing, persisted preferences, unsupported-language rejection and Needle selection; then implement the minimal channel changes and rerun all host regressions.

## 3. Native runtime adapter

Files: `CMakeLists.txt`, `inference.cpp`, new `cactus_jni.cpp`, `needle_tools.h`, pinned `vendor/needle/needle.h` and license/notice.

Build `aeronex_cactus` from the pinned archive and wrapper; keep its model state in one persistent library. Existing `NativeInference` routes `.cact` to the wrapper via lazy `dlopen`/`dlsym`, with serialized access and one mapped model slot per kind. Load each kind once, reset text conversation before completion, and let Android reclaim the runtime at process exit. Existing JNI cancellation is checked before and after the call. Transcribe English with keyword biasing or initialize the exact drone schemas and complete reviewed text. Copy output into caller-owned storage; reject negative returns, unterminated/invalid JSON, suppressed/ungrounded calls and malformed calls. Cross-check numeric arguments against reviewed text and require telemetry wording for status calls. Poison Cactus before initialization and clear that state only after successful loading/type validation. Test repeated text/speech calls, failed load isolation and real-model regressions before Android delivery. This replaces the original unload plan based on measured upstream lifetime behavior.

## 4. Flutter settings, setup and review

Files: `local_models.dart`, `local_inference.dart`, `local_control_screen.dart`, `model_setup_sheet.dart`, and their tests.

Expose `transcribe(..., backend: 'whistle')`, add model metadata and readiness routing, and include optional Whistle/Needle choices. Add failing widget tests for Whistle -> English, Telugu -> Whisper, installing only the selected required model, and reviewed text reaching selected Needle. Preserve original transcript, correction flow, Finish behavior, fixed-RAM-gate removal and dry-run confirmation. Use neutral “local AI” button text when Needle is selected. Test empty, malformed, multiple, unknown and out-of-range tool outputs at the existing validator boundary.

## 5. Verify and publish local artifacts

Run `flutter test`, `flutter analyze`, Android host tests, real-model checks and release/debug ARM64 builds. Inspect runtime symbols and packaged ELF/ZIP alignment. Bump to 0.1.4+5, retain the signing certificate, write SHA-256 manifest, and update READMEs/device guide with exact download sizes and the Telugu limitation. Request independent review, address actionable findings, and deliver the release APK with the plan and measured verification report.
