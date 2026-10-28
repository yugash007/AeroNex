# Vosk integration — 9 October 2026

Version 0.1.5+6 adds offline Vosk speech with Indian English, Telugu and Hindi packs. Release and debug APKs are built and verified. The final integration review approved local evaluation delivery with no blocking findings; all measured results below are from the completed builds and tests.

## Behavior

- English is labeled **Indian English** and uses `en-IN`; saved `en-US` preferences migrate. Whistle and Needle also accept the new English locale. Telugu remains the initial spoken-language selection.
- Vosk uses a separate official pack for each supported language. Existing explicit backend choices are retained. Without a saved backend, an installed matching Vosk pack is preferred, then an installed Whisper model, before offering a new download.
- Tamil, Kannada, Malayalam and automatic detection use Whisper because the official Vosk catalog does not provide matching packs. Selecting one of these while using Vosk changes to Whisper with an explanation.
- Vosk consumes the existing 16 kHz mono PCM16 recording stream and shows partial text. A model and its recognizers are opened on the worker for one session and closed on success, failure or cancellation. Recording remains bounded to 15 seconds.
- English has an optional second decoder with drone phrases. Its output appears only as **Use Vosk suggestion**. The original **Heard** text stays visible; applying a suggestion requires a tap, and interpretation requires a separate action. Suggestions containing unknown tokens, changing numeric/negation tokens, or lacking a drone action are suppressed.
- A failed Finish platform call can return the active capture to a retryable state. Late failures cannot replace a completed review or affect a new recording. Cancellation after model loading prevents microphone startup.
- Verified installation covers every extracted file, uses a read lease during inference, and recovers a valid backup after an interrupted replacement. Failed/cancelled installs retain the previous usable model. Archive traversal, unexpected entries and symbolic-link cleanup are covered by regressions.
- Existing file-based readiness, typed-input model independence, Telugu script checks, editable review and the removal of the fixed 2 GiB RAM gate remain. All flight confirmations remain **dry runs / NOT SENT**.

## Packs and actual model evaluation

The official archives were downloaded from [Vosk's model catalog](https://alphacephei.com/vosk/models), cross-checked with its catalog MD5 and pinned by SHA-256 along with every extracted file. All three packs use Apache 2.0. Full metadata is in [vosk-models.json](2026-10-09-vosk-models.json); large downloaded files are outside app source in `../tooling/vosk`.

| Language | Pack | Download bytes | Expanded bytes |
|---|---|---:|---:|
| Indian English | `vosk-model-small-en-in-0.4` | 37,573,330 | 56,700,192 |
| Telugu | `vosk-model-small-te-0.42` | 60,544,249 | 127,872,241 |
| Hindi | `vosk-model-small-hi-0.22` | 44,458,845 | 82,084,166 |

The production Kotlin `VoskSpeechRecognizer` was compiled and exercised with these actual models on Windows. Silence, repeated Finish, repeated close, and a failed model load followed by successful retry passed. Eight synthetic English clips produced partial updates and final transcripts. Examples:

| Synthetic speech | Authoritative transcript | Optional suggestion |
|---|---|---|
| Hey drone fly | he drowns why | hey drone fly |
| Hey drone take off to three meters | hey drown take off to three meters | hey drone take off to three meters |
| Move left two meters | will do a few meters | Suppressed: changes numeric tokens |
| Do not take off | do not take off | None needed |
| Land the drone | your own | land the drone |
| What is the weather in Delhi | what is the weather in delhi | Suppressed |

These clips use the default Windows SAPI voice. They are not an Indian-accent or physical-phone accuracy benchmark. The host test uses Vosk desktop **0.3.45** with the production Kotlin adapter; Android packages **0.3.75**, whose newer native API cannot be substituted with the older desktop DLL. See [adapter results](2026-10-09-vosk-adapter-evaluation.json), [adapter log](2026-10-09-vosk-adapter-evaluation.log), [initial open-vocabulary results](2026-10-09-vosk-host-evaluation.json), and [grammar evaluation](2026-10-09-vosk-grammar-evaluation.json).

The official Telugu model lists **87.9 WER on Fleurs**. Its vocabulary contains Telugu drone terms but lacks English `drone`, `fly` and `hold`; mixed Telugu/English commands can therefore work poorly. Whisper remains selectable for Telugu and mixed speech. Neither model choice guarantees accurate recognition.

## Verification

- Model installer/inventory review: 31 covering host tests passed, including real downloaded models, restart recovery and Windows symbolic-link checks. Findings from the initial review were fixed and the scoped rereview approved them.
- Production adapter: three actual models loaded and finalized silence; eight synthetic English clips completed; load failure followed by retry passed.
- Existing native codec/Cactus checks: all three CMake/CTest checks passed after rebuilding. No C++ implementation changes were made for Vosk.
- Full Flutter suite: **91 passed**; `flutter analyze` reports **No issues found**. See [Flutter log](2026-10-09-vosk-speech-flutter-all.log) and [analysis log](2026-10-09-vosk-speech-analysis.log).
- Final full Android host suite with actual Vosk, Cactus, Whisper and LFM model directories: **72 tests, 71 passed, 1 optional network-download smoke skipped, zero failures/errors**. See [native log](2026-10-09-vosk-native-final.log) and [suite summary](2026-10-09-vosk-native-final-summary.json). An earlier integration pass measured real Vosk inventory at 537 ms cold and 11 ms cached on this host; these timings do not measure a phone.
- Review fixes were reproduced before correction: negative contractions and variant apostrophes now remain protected in suggestions; Flutter's inherited ABI set is replaced with ARM64 so dependency libraries cannot add unsupported architectures. The focused fix suite passed 35 tests and the final full suite above passed afterward. The initial packaging failure is preserved in [ABI inspection](2026-10-09-vosk-initial-abi-failure.log); corrected packaging is verified below.
- Retained tooling notices include Gradle/AGP migration and JVM native-access notices plus a deprecated Robolectric test API. The new unnecessary Kotlin null-assertion warning was removed. These are build/test tooling notices, separate from app startup; Flutter analysis is clean.
- `adb devices -l` returned no devices. Phone microphone capture, Indian-accent and Telugu accuracy, latency, memory and thermal behavior remain unmeasured.

Final code review: **approved for local evaluation APK delivery, no blocking findings**. The task reviews and final review covered installer/runtime locking, readiness/defaults, recording/Finish recovery, suggestion semantics and packaging. See the [final review report](2026-10-09-vosk-final-review.md). Existing uncommitted work and the implementation worktree are preserved; no commits, merges or pushes were made.

## Verified APKs

Both packages identify as `com.aeronex.app`, version `0.1.5`, version code `6`, minimum SDK `26`, target SDK `36`, with **ARM64 only**. `apksigner verify --verbose --print-certs` passes with the existing certificate SHA-256 `6313b3f87ccfd799e2c4a96d2de6796836f3559fce9a0b4ab3974456b9d243c1`. `zipalign -c -P 16 -v 4` passes, every native library has compatible ELF LOAD alignment, and the Vosk/JNA/model license asset is included.

| Build | File | Bytes | SHA-256 |
|---|---|---:|---|
| Release | `app/build/app/outputs/flutter-apk/app-release.apk` | 38,001,985 | `c9cf4472e709d438c73d1f0f20645a21e43e7a2a9c8b4d626ac55111b6365f43` |
| Debug | `app/build/app/outputs/flutter-apk/app-debug.apk` | 111,911,202 | `881164491d30371fc32758a0e04f1867d69c4af8b3f7592a6671d610eac5a641` |

The release APK is 36.2 MiB. See the [APK manifest](2026-10-09-0.1.5-apk-manifest.json), [release build](2026-10-09-vosk-release-build.log), [debug build](2026-10-09-vosk-debug-build.log), [release verification](2026-10-09-0.1.5-release-apk-verification.log) and [debug verification](2026-10-09-0.1.5-debug-apk-verification.log). Vosk and JNA are present alongside Flutter and both existing native backends. These checks establish package integrity and alignment; they do not execute Android JNI on a phone.

## Install and use

Install the verified 0.1.5 APK over the existing installation to retain private model files. Wait for **Checking installed models…** to finish. Already valid Whisper/LFM/Whistle/Needle models remain available; Vosk requires its own language pack only when selected.

Choose **Vosk (offline)** and **Indian English**, **Telugu** or **Hindi**, then download or import that pack from model setup. Other language packs and alternative backends are optional. For mixed Telugu/English, compare the Vosk result with Whisper using your own recordings. Speak after **Listening**, tap **Finish recording**, review the text, and use a suggestion only when it matches what you intended.

The APK continues to use the existing development signing certificate and is an evaluation build. Model weights are downloaded separately. The app's model-generated flight execution stays locked.

## Reproducing host checks

Run Flutter checks from `app` with the SDK in `../../tooling/flutter/bin`. Run Android tests from `app/android` with Android Studio's JBR and these environment variables pointing to the verified model directories:

```powershell
$env:AERONEX_VOSK_MODEL_DIR='C:/Users/yugas/Downloads/Drone-app/.worktrees/tooling/vosk'
$env:AERONEX_CACTUS_MODEL_DIR='C:/Users/yugas/Downloads/Drone-app/.worktrees/tooling/cactus'
$env:AERONEX_EXISTING_MODEL_DIR='C:/Users/yugas/Downloads/Drone-app/.worktrees/tooling/models'
./gradlew.bat :app:testDebugUnitTest --rerun --console=plain
```

`tools/check_vosk_models.py` reproduces official download/catalog/manifest checks. `tools/CheckVoskAdapter.java` exercises compiled production Kotlin classes from `app/build/app/tmp/kotlin-classes/debug`; compile/run it with Kotlin stdlib, desktop Vosk 0.3.45, JNA 5.18.1 and a desktop `org.json` JAR. Supply the `../tooling/vosk` root and an output JSON path. The model root contains the three extracted packs and `samples/0.wav` through `7.wav` with `samples/texts.json`. Desktop runtime JARs and generated SAPI clips are host fixtures, not APK assets. Do not substitute the Android 0.3.75 classes into the older desktop native runtime.

## Implementation decisions

- Preserved existing uncommitted work and used scoped file snapshots for review. This avoids sweeping earlier work into commits; review records therefore remain in the local plan workspace.
- Treated “all languages” as the app's current language choices, using official Vosk packs for three and Whisper for the rest. Expanding to additional languages needs further model/catalog work.
- Kept open-vocabulary Heard text authoritative and made constrained English decoding an explicit suggestion. This adds decoding work and suppresses useful corrections when they change protected numeric tokens, but avoids silently rewriting unrelated speech.
- Added recovery for an interrupted model replacement. This adds a small transaction/recovery protocol so a valid saved backup remains usable after restart.
- Kept Telugu as the fresh-install default and changed the English variety to Indian English. A user whose primary language differs can select and save it explicitly.
