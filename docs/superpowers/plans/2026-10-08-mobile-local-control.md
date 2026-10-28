# Mobile-local control implementation plan

**9 October update:** version 0.1.1 adds in-app model downloads/imports, Android offline-language readiness and error-13 recovery, stale-error cleanup and phone-layout fixes. 60 Flutter tests and 7 Kotlin unit tests pass; the real Whisper Tiny download was verified. See [the update report](../../testing/2026-10-09-speech-model-fixes.md) for builds and remaining phone validation.

## Latest verified checkpoint — 2026-10-08

See [build status](../../testing/2026-10-08-build-status.md) for the current evidence and APKs. The original task checkboxes and foundation checkpoints below are historical planning records; they are not the current completion count.

| Stage | Current status |
|---|---|
| Android foundation | Debug and release-mode ARM64 APKs built; analysis clean; 52 Flutter tests pass |
| Dry-run command workflow | Implemented and tested; confirmations remain dry runs even with simulator telemetry connected |
| Offline speech backends | Native/Whisper integration implemented; phone measurements and runtime validation pending |
| LFM proposals | Native inference implemented; known semantic failures remain; flight execution locked |
| MAVLink simulator path | Native EKF decoder fixed; shared-codec host test completed takeoff, move, hold and landing; full phone-to-SITL testing pending |
| Device evaluation | No phone connected; chipset/language/memory/thermal matrix remains unverified |

The earlier ACK 176 timeout did not reproduce in a fresh disposable simulator. Its cause remains unestablished. Native wire tests independently reproduced the missing EKF decoder; that regression is fixed. See the report for the distinction between Dart/UDP tests with a codec double, shared native codec/SITL tests, and the untested Android integration path.

> Execute inline with `superpowers:executing-plans`. Coding-model constraint: Astra/Sol only; do not launch Claude-model subagents. Do not commit/push without permission.

**Goal:** Run offline speech recognition and LiquidAI inference entirely on Android, validate tool proposals locally, and exercise direct ArduPilot control against SITL before hardware testing.

**Architecture:** Flutter retains the speech/intent boundaries. Pinned native Whisper and llama.cpp integrations provide offline inference. A deterministic confirmation/execution layer separates model output from MAVLink transport; dry-run is the default.

**Tech stack:** Flutter/Dart, Android Kotlin/JNI/C++, whisper.cpp, llama.cpp, generated/maintained MAVLink codec, ArduPilot SITL.

**Spec:** `docs/superpowers/specs/2026-10-08-mobile-local-control-design.md`.

## Global constraints

- Android API 26+/ARM64 CPU baseline; Android native on-device recognition only when API 31+ and its service are available.
- 2 GiB available RAM eligibility target, with dynamic memory-pressure handling; no universal compatibility claim.
- LFM2.5-VL-450M-GGUF Q4_K_M, optional Q8_0 comparison, no vision projector; 2,048-token context and 256-token output limit.
- Whisper multilingual tiny/base and native on-device recognition both remain testable; never fall back to cloud inference.
- One bounded operation at a time; tool proposal validation and expiring confirmation before dispatch. No real hardware commands in development testing.
- Existing Edge demo/tests remain legacy; not part of the mobile-local runtime.

## Execution checklist

### 1. Buildable Android foundation

- [x] Inspect actual implementation checkout and preserve clean baseline.
- [x] Run Python baseline: 43 passed.
- [x] Locate Android Studio, bundled JDK and SDK; no attached phone.
- [x] Fetch official Flutter stable source into ignored `.worktrees/tooling/flutter`.
- [x] Record the revised architecture in the spec above.
- [x] Initialize the downloaded Flutter SDK after permission mode was changed.
- [x] Record Flutter 3.47.6/Dart 3.13.5; Android build installed NDK 28.2.13676358 and platform 36 using existing accepted licenses.
- [x] Generate Android host in staging and copy missing host files without overwriting handwritten Dart files.
- [x] Fix baseline constant-shadowing and ambiguous ConnectionState imports; latest check: 35 Flutter tests pass, analysis clean, ARM64 debug APK built.
- [x] Update README with build commands, current behavior and unverified device/runtime limits.

### 2. Safe dry-run command workflow

Files: new `app/lib/features/flight/domain/flight_command.dart`, `command_policy.dart`, `app/lib/features/flight/application/command_controller.dart`; modify `app/lib/main.dart`, voice screen and history integration.

- [ ] Write failing policy/controller/widget tests first: unknown tools/keys, missing altitude, non-finite parameters, no confirmation, expired confirmation, double submission and cancelled inference.
- [ ] Implement one typed proposal for get_status/takeoff/land/hold/move_relative; reject extra calls and incomplete output.
- [ ] Implement simulator-only takeoff 1–10 m and move >0–10 m policy. Missing arguments produce clarification, not defaults.
- [ ] Replace immediate send in VoiceScreen with preview/confirmation and a dry-run result explicitly labeled NOT SENT.
- [ ] Bind 15-second confirmation to an immutable proposal and invalidate on new input/lifecycle changes. Run tests green.

### 3. Offline speech backends

Files: Android host speech bridge, `android_speech_engine.dart`, `whisper_speech_engine.dart`, existing SpeechEngine/Transcript interface.

- [ ] Pin whisper.cpp; implement Android capture/permission/cancellation and transient mono PCM with 15-second recording bound.
- [ ] Implement native on-device recognition with service/OS/language checks; unsupported means explicit error, not network fallback.
- [ ] Import supported model files via document picker into private storage; check hashes, sizes and storage errors.
- [ ] Exercise both SpeechEngine implementations and test permission denial, no speech, missing model and lifecycle cleanup.

### 4. Local LFM tool proposals

Files: `lfm_intent_engine.dart`, `core/local_inference.dart`, native Android inference/memory bridge.

- [ ] Pin compatible llama.cpp revision and use upstream LFM chat-template/tool-parsing APIs; isolate ggml symbols from Whisper.
- [ ] Add model import, text-only Q4_K_M/Q8_0 selection, bounded input/context/output, structured tool result validation and clarification handling.
- [ ] Run inference off UI thread with native cancellation; sequential ASR/LFM loading and memory-pressure handling.
- [ ] Test malformed/multiple/truncated output, cancellation/late results, allocation failure and at least one real model inference where supported.

### 5. MAVLink simulator path

Files: `features/flight/data/mavlink_transport.dart`, telemetry/executor, connection UI and SITL checks.

- [ ] Use generated or maintained ArduPilot-capable MAVLink codec, not handmade framing/checksum.
- [ ] Implement explicitly configured UDP peer/vehicle filtering, heartbeat freshness and readiness checks.
- [ ] Add deterministic confirmed takeoff/move/hold/land sequence without bypassing pre-arm checks. STOP never means disarm.
- [ ] Correlate ACKs; verify completion using telemetry; mark uncertain outcomes UNKNOWN, never blindly replay flight commands.
- [ ] Test complete flow on SITL, plus wrong target, lost ACK, stale telemetry, duplicate submission and reconnect. Keep dry-run default.

### 6. Device evaluation

- [ ] Record local-only backend/model/device/ABI/version, sampled memory, latency and supported thermal measurements.
- [ ] Export only on user action; no automatic analytics or raw audio retention.
- [ ] Evaluate reviewed English/Hindi/Telugu/Tamil/Kannada/Malayalam commands, negation, ambiguity, code switching and noise.
- [ ] Compare Whisper/native, tiny/base, Q4_K_M/Q8_0 and residency modes on supplied Snapdragon/Exynos/MediaTek phones.
- [ ] Publish measured compatibility matrix; distinguish unsupported/unverified from passed.

## Verification

- Regression: `python -m pytest edge/tests -v`; inside `app`, `flutter analyze` and `flutter test`.
- Build: `flutter build apk --debug`, then release ARM64 build; check packaged native libraries and Android native page-size compatibility.
- Offline: provision models first, block internet and verify no speech/inference requests leave the device. Local simulator networking is separate from internet access.
- Safety: no output without validation/confirmation; expired or cancelled proposals never dispatch; ACK alone never becomes completed.
- Hardware: no phone was connected at kickoff. Real-device speech/memory/thermal and live-flight claims remain unverified until separately tested.

## Integration checkpoint after native validation

Implemented since the foundation checkpoint: both native speech paths (Android service and Whisper JNI), official hash-checked model imports, Q4/Q8 and tiny/base selection, local LFM generation with explicit upstream LFM parser, sequential model allocation/cancellation, startup memory checks, typed proposal validation, confirmation UI, generated MAVLink codec, selected-peer UDP transport and simulator executor. Diagnostics and device-testing instructions are present. No physical-phone native inference has been run.

Fresh native host tests sharing the app's prompt/tools exposed unsafe semantic behavior: `Do not take off to 3 metres` produces takeoff at 3 m, and compound requests silently become one action. Simple takeoff/land/movement cases work with the explicit parser. The native check now exits nonzero for unsafe negation/compound proposals. Model-generated flight execution is intentionally locked in the UI; it is not complete or flight-ready.

SITL check is also incomplete: the disposable subprocess test failed awaiting ACK 176 (mode change). Do not mark the simulator stage complete from a real-UDP test with a codec double. Physical device memory/thermal/Whisper quality checks are still outstanding. The earlier auto-parser model smoke script is retained as a reproduction; the explicit native check is the relevant prompt/parser path.

## Foundation checkpoint (historical)

The SDK permission blocker is resolved. Flutter tests, analysis and the ARM64 debug build now pass. The active app is a separate local dry-run harness; legacy VoiceScreen remains inactive. Immutable tool validation and one-use/expiring confirmation are implemented with tests, along with native Android speech capture and a separate transcript-only UI. No real model inference or MAVLink integration exists yet, and no phone has been used for validation. Memory reporting exists in the Android bridge but model eligibility/pressure enforcement is pending.

Official llama.cpp and whisper.cpp sources have been fetched into the ignored tooling directory for the next integration step; they are not yet linked or packaged into the APK. Continue with model provisioning and pinned native integration, then simulator transport. Do not mark the full offline pipeline complete from the foundation build.
