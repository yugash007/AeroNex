# Whistle and Needle integration — 9 October 2026

Version **0.1.4**, Android version code **5**. Downloaded, evaluated and integrated the official Cactus Whistle speech model and its Needle 3 tool-calling companion. The integration is optional and preserves existing Telugu/Whisper, Android speech, LFM, transcript review, installed-model detection and the removal of the fixed 2 GiB RAM requirement.

## Findings from the upstream models

- [Whistle](https://huggingface.co/Cactus-Compute/whistle) supports English, German, French, Spanish, Italian, Dutch and Polish. **It does not support Telugu.** This release exposes English Whistle; other existing language selections use Whisper or the Android provider.
- Whistle alone provides transcription, word timestamps and speech embeddings. [Needle 3](https://huggingface.co/Cactus-Compute/needle3) supplies tool calling. Their C API also supports a combined audio-to-tools call, which was evaluated locally. The app keeps an editable review between transcription and interpretation.
- The runtime provides Android ARM64 builds. Its API is process-global and not thread-safe, with no cancellation or model-unload operation. The app serializes access, loads each model kind once, and retains the runtime until Android reclaims the process. Cancelling invalidates the result but cannot interrupt the current Cactus decode.
- Both repositories use Apache-2.0. The runtime header/license attribution is recorded in `app/android/app/src/main/cpp/vendor/needle`; the APK includes the license through the app's info icon.

Pinned downloads:

| File | Repository revision | Bytes | SHA-256 |
|---|---|---:|---|
| `whistle.cact` | Whistle `b358ddadd89b7a713b5aa131f23032d3cca1b251` | 16,919,407 | `b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb` |
| `needle3.cact` | Needle `2ae11323dc000f5e70c49f7403efa6af12ba9e67` | 35,335,380 | `c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38` |
| Android ARM64 `libneedle.a` | Same Needle revision | 2,172,494 | `4522a16eebaaf569ebacd0ac682712a8d91acc88a2a50a44fd65f332c8ebfdbb` |

The downloaded model files are in the sibling tooling cache, `../tooling/cactus`. They are also available through the app's verified download/import flow. Model weights are not bundled in the APK, and the app starts no model download automatically.

## App behavior

1. Select **Whistle (English, offline)**. The app explicitly selects English and explains that Telugu requires Whisper. Changing to Telugu switches speech back to Whisper.
2. Choose **Needle 3 (English tools)** under **Local AI** to evaluate reviewed English commands with Needle. LFM remains available for the existing multilingual path.
3. Whistle uses the shared 16 kHz mono recorder, session-aware Finish, 15-second capture limit and cancellation flow. Recognition includes a fixed vocabulary of drone command words, including “hey drone” and “take off”.
4. The original transcript appears in **Review transcript**. Only **Interpret reviewed command** submits the edited text to the selected Local AI. Speech setup requires only the chosen speech model; interpretation requires the selected AI model.
5. Needle's failed, suppressed, ungrounded and negated results cannot become proposals. A source-value check also rejects numeric arguments that differ from the explicit reviewed number, including leading-dot/signed decimals. A status proposal requires telemetry wording. Existing Dart validation still rejects unknown tools, multiple calls, malformed arguments and out-of-range values.

All confirmations remain **dry runs / NOT SENT**. These checks cover measured failures; they are not comprehensive multilingual or semantic validation.

## Real-model evaluation

The [saved evaluation](2026-10-09-cactus-model-evaluation.json) contains complete raw outputs from the downloaded Windows runtime. No model-generated tool was executed. Speech input was a locally synthesized Windows SAPI voice, not a recording of the user's voice or a phone microphone.

| Probe | Observed result |
|---|---|
| Synthetic “Hey drone, take off to three meters”, no vocabulary hints | `HADROWN. TAKE OF TO THREE METERS.` |
| Same audio with the app's vocabulary hints | `Hey drone, take off to three meters.` |
| Combined audio-to-tools API on that clip | Correct transcript and `takeoff(altitude_m: 3)` |
| Reviewed text: take off to 3 meters | Correct `takeoff(3)` |
| Move left 2 meters | Correct `move_relative(left, 2)` |
| Hold / land / show drone status | Correct corresponding single tool |
| Fly | Empty dispatched list with a guessed altitude suppressed by the engine |
| Do not take off | Runtime reported truncated tool output; no call dispatched |
| Take off to 100 meters | Raw model incorrectly emitted altitude 1; the added source-value check refuses it |
| Take off to 3 meters and land | Two calls; the app's single-tool validator rejects them |
| What is the weather in Delhi | Raw model incorrectly emitted `get_status`; the added telemetry-wording check refuses it |

This establishes that the models and vocabulary biasing work on the test host, while also documenting concrete limitations. It does not establish that Whistle always outperforms Whisper, and it provides no Telugu accuracy evidence.

## Runtime lifecycle and review corrections

The original plan attempted library unloading after each operation. A real host check failed that assumption. Linux loader diagnostics and independent ARM64 inspection showed persistent worker threads/TLS and raw global model contexts without model cleanup on unload. The final adapter therefore uses a persistent runtime with one stable model slot per kind. It checks model-kind bits so both speech and text remain usable after both are loaded, and it resets the text conversation before each reviewed command.

The runtime is marked unusable before starting model initialization and becomes usable only after loading and type validation complete. A throwing-load regression failed before this correction and now proves that partial initialization cannot be retried. Cactus errors do not disable Whisper or LFM. Repairs with identical pinned model content may reuse existing mappings; a different path for a loaded kind requires restarting the app.

The same C API wrapper and cache used by Android were linked to the pinned Linux model runtime. Six alternating Needle and silent-Whistle calls passed. Allocated heap after warm-up stayed near **78.9 million bytes**, with only 1,856 bytes difference between iterations 2 and 6 in the recorded run. This is a host allocator measurement, not Android peak PSS or a phone RAM requirement. The runtime check used libc++ 22 extracted from verified LLVM repository packages to satisfy the Linux archive's ABI; Android linked against the project's NDK 28.2 toolchain successfully.

Independent review identified the initialization exception edge and a leading-decimal grounding edge; both received failing regressions and fixes. The runtime cache, language routing, transcript review and non-executable result handling were also reviewed.

## Verification

- **81 Flutter tests pass.** New widget coverage checks Whistle-to-English routing, Telugu-to-Whisper routing, and Whistle/Needle operating through review without LFM or Whisper installed.
- **Flutter analysis reports no issues.**
- **30 Android host tests pass:** 20 bridge tests, 5 model-store tests, 3 language-support tests and 2 real-file inventory checks. The optional network-download smoke is skipped. The inventory checks verify both the existing Q4/Tiny files and downloaded Whistle/Needle files.
- **All three CMake/CTest checks pass:** existing MAVLink codec, Cactus result validation, and partial-load failure handling.
- The separate real Cactus runtime repetition check passes with both downloaded models.
- Release/debug ARM64 APKs build successfully. Both retain the expected package/signature, include `libaeronex_cactus.so` and license attribution, and pass **16 KiB ZIP and native ELF load-segment alignment** checks.
- `adb devices` reports no connected phone. On-phone JNI behavior, microphone accuracy, latency, thermal behavior and memory use remain unverified.

Evidence:

- [Flutter tests](2026-10-09-cactus-flutter-tests.log) and [analysis](2026-10-09-cactus-analysis.log)
- [Android host tests](2026-10-09-cactus-channel-tests.log)
- [C++ checks](2026-10-09-cactus-cpp-tests.log) and [real runtime repetitions](2026-10-09-cactus-native-runtime.log)
- [Raw model evaluation](2026-10-09-cactus-model-evaluation.json)
- [Release build](2026-10-09-cactus-release-build.log) and [debug build](2026-10-09-cactus-debug-build.log)
- [Release APK verification](2026-10-09-0.1.4-release-apk-verification.log) and [debug APK verification](2026-10-09-0.1.4-debug-apk-verification.log)

Reproduce the model evaluation from the implementation root:

```powershell
./tools/create_cactus_sample.ps1 -OutputPath ../tooling/cactus/hey-drone.wav
python tools/check_cactus_models.py --directory ../tooling/cactus --wav ../tooling/cactus/hey-drone.wav --output docs/testing/cactus-evaluation.json
```

Run `flutter test` and `flutter analyze` from `app`. Run `:app:testDebugUnitTest --rerun` through Gradle from `app/android`; set `AERONEX_EXISTING_MODEL_DIR` and `AERONEX_CACTUS_MODEL_DIR` to enable the two real-file inventory checks. Run CMake/CTest in Linux/WSL using `tools/CMakeLists.txt` and the pinned dependencies described in the README.

## APK and installation

The release APK is **27,703,000 bytes (26.4 MiB)**; the debug APK is **123,400,408 bytes**. Both are version 0.1.4/code 5, ARM64, minimum API 26 and target API 36. The existing development signing key is retained. Exact digests are in the [0.1.4 APK manifest](2026-10-09-0.1.4-apk-manifest.json).

Install the release APK over the existing app to preserve models. Add/import Whistle and Needle once, select their English options, then speak, finish, review and interpret. For Telugu use Whisper and LFM. Continue the phone checks in the [device evaluation guide](mobile-device-matrix.md).

The [design](../superpowers/specs/2026-10-09-whistle-needle-design.md) and [implementation plan](../superpowers/plans/2026-10-09-whistle-needle.md) record the final integration and the evidence-driven lifecycle change.
