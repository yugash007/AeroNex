# Speech and model setup fixes — 9 October 2026

Version: **0.1.1**, Android version code **2**. Package ID and development signing configuration are unchanged so this build can update the previous evaluation APK while retaining private models.

Both ARM64 builds succeeded. The release APK is **25.1 MiB** (26,285,139 bytes); the debug APK is **95.5 MiB** (100,170,796 bytes). Both pass 16 KB ZIP alignment. The release signature verifies and matches the previous debug APK's signing certificate. Both package manifests report version 0.1.1/code 2. Hashes are recorded in [the APK manifest](2026-10-09-apk-manifest.json).

## Issues reproduced from the screenshots

| Symptom | Cause | Change |
|---|---|---|
| `SPEECH_13` after selecting Android on-device speech | Android defines error 13 as a supported language that is currently unavailable, for example an offline pack not downloaded | Check offline support on API 33+ where supported; provide language download, speech settings and explicit Whisper recovery |
| `Import the selected model first: whisper-tiny` | Missing local model; recording was allowed before checking readiness and setup required external documentation | Open in-app setup before capture; offer official downloads and file import with size/hash verification |
| Whisper error still visible after typing an LFM command | Screen-level microphone errors were not cleared by the typed-input/controller path | Clear old errors when input/backend/language changes or a new operation starts; typed commands require only LFM |
| Dropdown layout on a small phone with larger text | Dropdowns measured the widest option without expanding within the available width | Constrain dropdowns to available width and add bottom safe-area spacing |
| Selected model differs after app restart | UI defaults did not reflect the native persisted selection | Restore the current model selection from native status |

Raw `PlatformException(...)` strings are replaced by actionable messages. Missing-model and Android speech errors have setup/recovery controls. Downloads only start when the user presses Download; inference and recognition remain offline.

## Install and use

1. Install the new `app/build/app/outputs/flutter-apk/app-release.apk` over the existing app. Do not uninstall if you want to retain imported models. This is still an evaluation APK signed with the development key.
2. For Whisper voice commands, open **Models and device readiness → Set up**, or tap **Speak a command** to open the required-model panel. Download **LFM Q4** (218.7 MiB) and **Whisper Tiny** (30.7 MiB). Existing matching files can be imported instead.
3. Close setup after the models show installed, then tap **Speak a command** again. Setup does not automatically activate the microphone afterward. Typed commands need LFM only.
4. For Android on-device speech, tap **Check Android language**. If its offline pack is missing, use **Download Android language**. If the provider cannot install it, use **Speech settings** or switch to **Whisper**.

Android pack-download requests may require internet or an OS approval prompt. API 33 requests and provider-scheduled downloads are explicitly shown as requested/pending; they are never treated as installed without confirmation. Android 12 lacks support-check/download APIs, so settings or Whisper are the recovery path there.

## Verification

- Regression tests were first run against the old screen and failed on missing-model capture, stale errors and absent error-13 recovery.
- `flutter test --reporter expanded`: **60 passed**, including a 360×800 screen with text scaled to 130%, explicit download gating, restored model selection, and both screenshot error paths.
- `flutter analyze`: **no issues found**.
- `gradlew :app:testDebugUnitTest`: **7 passed**. Covers offline-language classification, exact locale handling, correct model installation, corrupt/incomplete/oversized input, cancellation cleanup and preservation of existing models. The optional network test is skipped in the normal suite.
- Explicit `ModelDownloadSmokeTest`: **passed** against the official HTTPS Whisper Tiny URL using the same `VerifiedModelStore` download/install implementation as Android. Downloaded **32,152,673 bytes**, verified SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`. This is a host JVM check, not an Android runtime measurement.

The downloader permits HTTPS redirects only, bounds transfer size and network timeouts, removes partial files on failure/cancellation, and publishes a model atomically after verification. It does not replace a working model with a failed transfer.

No phone is attached over ADB. Actual Samsung/Google speech-provider behavior, Android model-download prompts, microphone quality and native inference on the user's phone still need verification after installation. The app's model-generated flight execution lock remains in place; this update addresses setup and speech error handling.

## Reproduction commands

From `app`:

```powershell
& ../../tooling/flutter/bin/flutter.bat test
& ../../tooling/flutter/bin/flutter.bat analyze
& ../../tooling/flutter/bin/flutter.bat build apk --release --target-platform android-arm64
& ../../tooling/flutter/bin/flutter.bat build apk --debug --target-platform android-arm64
```

From `app/android`, with the configured Android Studio JDK:

```powershell
.\gradlew.bat :app:testDebugUnitTest
# Optional explicit network check; stores a verified model in the chosen directory:
$env:AERONEX_MODEL_SMOKE_DIR = 'C:\Users\yugas\Downloads\Drone-app\.worktrees\tooling\models'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.aeronex.app.ModelDownloadSmokeTest'
```

Earlier Edge and native simulator results are recorded in [the 8 October report](2026-10-08-build-status.md); those unchanged subsystems were not retested for this update.
