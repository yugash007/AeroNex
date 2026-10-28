# Model detection and startup fixes — 9 October 2026

Version **0.1.2**, Android version code **3**. This update addresses the reported RAM warning/startup block and installed models continuing to appear as needing setup.

Both release and debug ARM64 APKs built successfully. The release APK is **26,350,675 bytes (25.1 MiB)** and the debug APK is **100,191,340 bytes**. Both report package `com.aeronex.app`, version 0.1.2/code 3, minimum API 26 and target API 36. Both pass signature verification and 16 KB ZIP alignment checks. The signing certificate matches the previous build, preserving update compatibility. The existing development signing key is used.

Artifacts: `app/build/app/outputs/flutter-apk/app-release.apk` and `app/build/app/outputs/flutter-apk/app-debug.apk`. Exact hashes and signing certificate digest are recorded in the [0.1.2 APK manifest](2026-10-09-0.1.2-apk-manifest.json).

## Changes and reproduced causes

| Reported behavior | Reproduced cause | Result |
|---|---|---|
| App refuses to run below 2 GiB available RAM | Native bridge checked both `availMem >= 2147483648` and `!lowMemory` before inference and transcription | Both checks and the warning are removed. The runtime attempts model loading; actual allocation failures remain errors that permit retry. |
| Installed files are shown as missing | Readiness required an independent `verified-<id>` preference flag | Actual file content is authoritative. Size/SHA-256 verification runs off the Android main thread, recovers existing files without flags, and caches the result until the file changes. |
| A missing default opens setup despite another installed model | Q4/Tiny defaults or saved choices were not reconciled against inventory | An installed alternative in the same family is selected and persisted. An installed saved choice remains selected. |
| Every model says Set up | The row button was unconditional | Ready rows show **Manage**; optional alternatives show **Add**. A ready management panel says **Offline models** and does not offer downloads. |
| Slow startup can briefly look like missing files | No explicit loading state | The UI shows **Checking installed models…** and waits for verification before deciding whether setup is needed. Concurrent screen status requests are coalesced. |
| Cancelling an optional model selection can leave the wrong dropdown value | The form field retained an uninstalled selection internally | Install before switching; restore the real selected model if setup is cancelled. Drain older status reads before applying a selection. |

The broad `onTrimMemory` cancellation override was also removed. It treated several numeric Android trim levels, including ordinary UI-hidden signals, as a reason to cancel work. Explicit cancellation and foreground lifecycle handling remain. Cancelling while startup verification is pending cannot start a microphone capture afterward.

Checksum verification is retained. A corrupt file with an old valid marker is rejected and labeled for repair. Transfers still publish files atomically, and failed imports retain the previous file. Models must be in the app's private model storage; external downloads can be brought in through the existing file importer.

## Verification

The regression commands were run before the fixes:

```powershell
# From app:
& ../../tooling/flutter/bin/flutter.bat test test/local_control_screen_test.dart --plain-name 'installed models do not ask for setup or a RAM minimum' --reporter expanded
# Expected no Set up widgets; observed 4.

# From app/android:
.\gradlew.bat :app:testDebugUnitTest --tests '*LocalInferenceBridgeTest*' --console=plain
# 5 tests, 4 failed: missing marker, ignored alternative, RAM prerequisite,
# and corrupt file accepted solely because it had a marker.
```

After the fixes:

- **67 Flutter tests pass**, including ready/missing model flows, optional installation and cancellation, delayed startup checks, cancellation during verification, and both ready/setup panels at 360×800 with 130% text.
- **Flutter analysis reports no issues.**
- **15 Kotlin/Android host tests pass**, including 8 Robolectric platform-channel regressions. These exercise Android private storage and preferences, cold restart, installed-alternative fallback, atomic replacement invalidating cached verification, operation cancellation, and recovery after allocation failure.
- The native bridge test with **512 MiB available and Android's low-memory flag set** reaches the injected inference engine successfully. This proves the fixed threshold is gone; it is not an actual on-phone JNI memory measurement.
- **One additional real-model inventory test passes** against the existing official Q4 LFM (229,313,568 bytes) and Whisper Tiny (32,152,673 bytes) files, without preference metadata. On this host, the first verification took **704 ms** and the next cached status read took **less than 1 ms**. No files were modified or downloaded by this check.
- The optional network-download smoke test is skipped in this run; its earlier successful result is recorded in the [0.1.1 report](2026-10-09-speech-model-fixes.md).

Reproduce the Android suite plus existing-file check from `app/android`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:AERONEX_EXISTING_MODEL_DIR = 'C:\Users\yugas\Downloads\Drone-app\.worktrees\tooling\models'
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

## Updating the phone

Install 0.1.2 over the existing app to preserve its models. Keep the existing installation and app data. Open the app and let its local file check finish; valid installed models require no new download. Use **Manage** to replace a model if desired. Only a genuinely missing or damaged required file should need setup or repair.

No phone is connected over ADB, so microphone/provider behavior and actual native inference on the user's device remain unverified. The Robolectric tests replace the JNI engine with a controlled test implementation. Model-generated flight confirmations remain dry runs because the previously recorded negation/compound-command failures are outside this setup/startup fix.
