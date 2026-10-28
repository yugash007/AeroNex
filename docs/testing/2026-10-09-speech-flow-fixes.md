# Recording and transcript fixes — 9 October 2026

Version **0.1.3**, Android version code **4**. This update addresses recording failures, lost speech settings, and the reported Telugu/Arabic and “hey drone fly”/“headrooms slide” transcription problems through explicit language selection, installed-model preference, and transcript review. It retains the earlier RAM-gate removal and installed-model detection fixes.

## APKs

Release and debug ARM64 builds completed successfully. Both report package `com.aeronex.app`, version 0.1.3/code 4, minimum API 26 and target API 36. Both pass signature verification and 16 KB ZIP alignment checks. Their signing certificate matches 0.1.2, so they are compatible updates that retain the existing app's private model storage. The release uses the existing development signing key.

| Artifact | Bytes | Use |
|---|---:|---|
| `app/build/app/outputs/flutter-apk/app-release.apk` | 26,350,751 | Release-mode evaluation build, about 25.1 MiB |
| `app/build/app/outputs/flutter-apk/app-debug.apk` | 115,172,718 | Debugging build |

Exact SHA-256 digests and signing information are in the [0.1.3 APK manifest](2026-10-09-0.1.3-apk-manifest.json). Package/signature/alignment output is saved for the [release](2026-10-09-0.1.3-release-apk-verification.log) and [debug](2026-10-09-0.1.3-debug-apk-verification.log) APKs.

## Changes and evidence

| Report or reproduced failure | Change |
|---|---|
| Finish could be tapped during preparation or repeatedly | Both native speech paths share session ID/phase state. Finish is accepted only for the active listening session, once. The main Speak button becomes Finish in place when listening starts. |
| A zero-byte microphone read could become “Microphone read failed” | Capture uses nonblocking reads. A temporary zero-byte read retries within a bounded recording loop; negative read errors are reported separately. The recorder is always stopped/released. |
| Recording error could leave the screen stuck on Finish | Capture cleanup clears its session and restores the idle state. A regression reproduces the failure after a listening poll, then verifies that a second recording succeeds. |
| Very short or silent recordings could reach recognition | Clips below 4,000 samples return `RECORDING_TOO_SHORT`; low-RMS audio returns `NO_SPEECH` without invoking the engine. These thresholds are regression-tested with synthetic audio, not tuned on the user's microphone. |
| Telugu/backend selection was not reliably retained | Speech language/backend are persisted through the platform channel. Telugu and offline Whisper are the defaults. Explicit Telugu is passed to Whisper as `te`; translation remains disabled. |
| Tiny could remain selected despite installed Base | Telugu prefers verified installed Whisper Base unless the user explicitly selects another Whisper model. Changing LFM does not mark Tiny as an explicit choice. A saved Whisper choice survives fallback and is restored after its file is repaired. |
| Telugu speech appeared in Arabic script | Explicit Telugu avoids automatic language detection for these captures. If an Arabic-script result still arrives in Telugu mode, it remains visible but interpretation is blocked until the transcript is edited or re-recorded. This is a review safeguard, not evidence that every Telugu utterance is now recognized correctly. |
| “Hey drone fly” appeared as “headrooms slide” | That exact reported mishearing offers an explicit **Use “Hey drone, fly”** correction. It never silently changes the action. Bare “fly” then asks for an altitude. |
| Transcription immediately entered local AI | Speech now opens editable **Review transcript**. **Interpret reviewed command** explicitly submits the reviewed text; the original **Heard** text remains visible. Capture requires only the speech model, and LFM readiness is checked at interpretation. |
| Cancelling verification could still open missing-model setup | The screen checks the operation generation after the status response and stops cancelled work before opening setup. |
| Switching from failing Android speech to Whisper could revert | The recovery action persists the backend through the same speech-settings path. |

Conservative normalization handles a recognized initial English wake phrase, takeoff/take off, metre/meter spellings, exact hold synonyms, and simple number words in clear unit contexts. Compound numbers, decimals, signs and direction are preserved. Known English and a small set of Telugu negation forms request clarification. This is not a complete multilingual intent validator. Existing model failures for negation and compound commands remain unresolved; app confirmations remain dry runs.

Android recognizer callbacks are tied to their originating request so late events from a destroyed recognizer cannot change a new capture. The Android service path still depends on a compatible installed offline recognizer/language pack. No cloud service or replacement STT engine was introduced, and this pass did not change the C++ Whisper decoder.

## Verification

- **79 Flutter tests pass**, including editable review before inference, original transcript retention, Telugu restoration, unexpected Arabic output, explicit correction and altitude clarification, repeated Finish, microphone-error retry, cancellation during missing-model verification, existing model readiness, and small-screen layouts.
- **Flutter analysis reports no issues.**
- **25 Kotlin/Android host tests pass**: 17 bridge tests, 3 language-support tests, 4 model-store tests, and 1 inventory smoke check against the real cached official LFM Q4 and Whisper Tiny files. The separate optional network-download smoke is skipped.
- Bridge tests use the real platform-channel handler and an AudioRecord shadow, substituting the JNI inference engine. They verify that PCM and explicit `te` reach that engine, a temporary empty read succeeds, wrong/early/repeated Finish requests are rejected, valid Finish succeeds, cancellation releases the recorder, silence/short clips avoid recognition, and preferences survive bridge recreation.
- Existing tests still prove the bridge attempts inference with 512 MiB available and Android's low-memory flag set, and that installed models recover without legacy verification flags.
- Both final APKs build successfully, retain the expected package/signing certificate, and pass the recorded package/signature/ZIP-alignment checks.
- Independent read-only review identified the explicit-choice/fallback preference issues; both were fixed and reviewed again. A final review of the microphone retry reset found no additional issue.

The additional preference, cancellation, and retry regressions were observed failing before their fixes. Saved results:

- [Full Flutter test output](2026-10-09-speech-flow-flutter-tests.log)
- [Flutter analysis](2026-10-09-speech-flow-analysis.log)
- [Full Android host test output](2026-10-09-speech-flow-native-tests.log)
- [LFM-change/Whisper-selection regression before fix](2026-10-09-speech-selection-red.log)
- [Explicit Whisper preference recovery before fix](2026-10-09-speech-preference-red.log)
- [Cancelled setup regression before fix](2026-10-09-speech-cancel-red.log)
- [Microphone retry regression before fix](2026-10-09-speech-retry-red.log)
- [Release build](2026-10-09-speech-flow-release-build.log) and [debug build](2026-10-09-speech-flow-debug-build.log)

To reproduce from `app`:

```powershell
& ../../tooling/flutter/bin/flutter.bat test --reporter expanded
& ../../tooling/flutter/bin/flutter.bat analyze
& ../../tooling/flutter/bin/flutter.bat build apk --release --target-platform android-arm64
& ../../tooling/flutter/bin/flutter.bat build apk --debug --target-platform android-arm64
```

From `app/android`, with Java 17+ and the existing cached model directory available:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:AERONEX_EXISTING_MODEL_DIR = 'C:\Users\yugas\Downloads\Drone-app\.worktrees\tooling\models'
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

## Install and try

Install 0.1.3 over the existing app; keep the installation and app data to preserve downloaded models. Wait for the installed-file check to complete. Choose **Whisper (offline)**, **Telugu**, and **Base multilingual Q5_1** if Base is installed. Tiny remains usable when explicitly chosen or when Base is unavailable. No model download starts automatically.

Tap **Speak a command**, wait for **Listening**, speak, then tap **Finish recording** once. Review and edit the result before tapping **Interpret reviewed command**. For an entirely English command such as “hey drone, take off to three meters,” select **English**. For a mishearing, use **Record again**, edit the transcript, or apply an offered correction only when it matches the intended words.

`adb devices` reported no connected phone during verification. Actual microphone quality, Telugu/mixed-language word accuracy, Android provider behavior, inference latency, and memory/thermal behavior on the user's phone remain unverified. The host tests and builds do not establish perfect recognition. Use the [device evaluation guide](mobile-device-matrix.md) to record those results. Flight confirmations continue to display **NOT SENT**.
