# Build and verification status — 8 October 2026

## Current result

Android debug and release-mode evaluation APKs build successfully. The legacy Python suite passes, Flutter analysis is clean, all 52 Flutter tests pass, and the shared native MAVLink codec completes a disposable ArduPilot SITL takeoff/move/hold/land check.

The active app supports offline speech/model evaluation, validated proposals, dry-run confirmation and simulator telemetry. **Model-generated flight execution remains locked.** Prior real-model tests found takeoff proposed for negated speech and one action selected from a compound request. This session did not change or requalify the model. No physical phone was available.

## Work completed in this continuation

1. Located and continued the existing `feat/aeronex-skeleton` worktree without overwriting its uncommitted Android/local-control implementation. Added a locator README in the main checkout.
2. Reproduced the failing Flutter transport test: its telemetry fixture did not include the newly required EKF readiness message. Updated the fixture and expanded command/failure-path coverage.
3. Reproduced a separate native defect with actual generated MAVLink bytes: `EKF_STATUS_REPORT` was dropped by the decoder. Extracted the codec into a shared header used by both Android JNI and the host checker, then added the missing flags. The regression failed before the decoder fix and passes after it.
4. Fixed an asynchronous UDP connection race: disconnecting during socket binding now closes the obsolete socket rather than reviving a cancelled session. A regression verifies no packet is encoded after cancellation.
5. Kept confirmations as dry runs in simulator telemetry mode. The screen now reports `NOT SENT` instead of presenting an executable simulator confirmation and then failing with an unknown outcome.
6. Added CMake/CTest native checks and expanded the disposable SITL harness to use the shared codec, maintain GCS heartbeat during waits, filter vehicle/ACK targets, and verify movement, hold and completed landing.
7. Rebuilt both ARM64 APKs and checked their packaged native code, alignment and release signature. Consolidated the README; previous foundation notes are retained as history.

## Verification evidence

| Check | Result | What it establishes |
|---|---|---|
| `python -m pytest edge/tests -q` | 43 passed | Legacy protocol, Edge server, intent and demo regression |
| `flutter test --reporter expanded` | 52 passed | Dart contracts, controller, UI, legacy transport and simulator transport |
| `flutter analyze` | No issues | Static analysis of the current app |
| `ctest --test-dir /home/drone/aeronex-codec-build --output-on-failure` | 1 native suite passed | Real generated frames: EKF flags, identity, ACK targets, corrupt/truncated packets and forced-arming rejection |
| `check_sitl.py … --codec …/check-mavlink` | Passed | Shared native codec with real disposable Copter simulator: 3 m takeoff, 2 m forward move, BRAKE hold, landing confirmed by telemetry |
| `flutter build apk --debug --target-platform android-arm64` | Built | Current debug APK |
| `flutter build apk --release --target-platform android-arm64` | Built | Current release-mode APK using development signing |
| `zipalign -c -P 16 -v 4 <apk>` | Both verified | ZIP alignment, including 16 KB native-library placement |
| ELF load-segment inspection | All libraries at least 16 KB aligned | Structural native page-size compatibility; no 16 KB phone runtime test |
| `apksigner verify --verbose <release-apk>` | Signature verifies, v2 | Integrity/signature of the release-mode evaluation APK |
| `aapt dump badging <release-apk>` | API 26 minimum, target 36, ARM64 | Packaged platform requirements |
| `adb devices` | No devices | Phone-dependent validation unavailable |

The seven Dart MAVLink tests cover takeoff/move/hold/land sequencing, unready EKF, lost ACK, wrong-target ACK, overlapping execution, disconnect/reconnect without replay, and disconnect while binding. They use real local UDP with a codec double. The native checker and native/SITL harness separately cover the real codec. Neither is the complete Android JNI → phone UDP → SITL path.

The previously recorded mode-change ACK timeout did not reproduce in the initial legacy harness run. Its cause is not established. The missing native EKF decoder was independently reproduced and fixed. An intermediate simulator run using the older codec correctly failed at the position-readiness gate; the rebuilt codec completed the sequence.

## APKs

| File | Bytes | Approximate size |
|---|---:|---:|
| `app/build/app/outputs/flutter-apk/app-debug.apk` | 115,899,891 | 110.5 MiB |
| `app/build/app/outputs/flutter-apk/app-release.apk` | 26,285,139 | 25.1 MiB |

Package: `com.aeronex.app`, version `0.1.0` / code `1`. Model weights are not bundled. Both packages contain the updated native EKF decoder. SHA-256 hashes and native-library alignments are recorded in [the APK manifest](2026-10-08-apk-manifest.json).

Release signing remains the development key configured in Gradle. Distribution signing is not configured.

## Simulator reproduction

From WSL using the existing local tools:

```bash
cd /mnt/c/Users/yugas/Downloads/Drone-app/.worktrees/aeronex-skeleton
cmake -S tools -B /home/drone/aeronex-codec-build
cmake --build /home/drone/aeronex-codec-build -j 2
ctest --test-dir /home/drone/aeronex-codec-build --output-on-failure
/home/drone/venv-ardupilot/bin/python tools/check_sitl.py \
  /mnt/c/Users/yugas/Downloads/AeroNex_One/ardupilot/build/sitl/bin/arducopter \
  /mnt/c/Users/yugas/Downloads/AeroNex_One/ardupilot/Tools/autotest/default_params/copter.parm \
  --codec /home/drone/aeronex-codec-build/check-mavlink
```

Final high-signal output:

```text
PASS: native EKF, identity, CRC, truncation, ACK target and arming checks
Simulator heartbeat verified
Telemetry stream requests acknowledged
Takeoff altitude confirmed
Native telemetry streams verified
Forward movement completed
BRAKE hold confirmed
Landing completed
PASS: native codec / real SITL takeoff, move, hold and land
```

The existing simulator executable was used read-only; only a new temporary simulator instance was controlled. No parameters were changed to bypass arming checks. The harness cleans up its own simulator and native-codec processes. Source/binary identity is recorded by hash because the existing executable's build revision was not independently established:

- ArduCopter binary SHA-256: `8ea2df6a8a84f010df906fb93ca56066564c4255aebc6ef194dee0f34f73b67d`
- Defaults SHA-256: `5e01345b45d1c6190b28bece5638bbdd4cf1cce35e05bbbf480ab24d2b51aa0e`

## Remaining work

- Resolve model semantic failures on negation, compound requests, ambiguity and numbers using reviewed multilingual evaluation data. Keep the execution lock until the complete pipeline is validated.
- Run real Android model import, permission, microphone, Whisper/LFM JNI, cancellation and offline network checks. Measure memory pressure, peak PSS, latency and thermal behavior on supplied phones.
- Validate the full phone-to-SITL connection and executor, including delayed/lost ACKs and stale telemetry on that path. Host checks establish a narrower scope.
- Validate English/Hindi/Telugu/Tamil/Kannada/Malayalam accuracy with native-speaker-reviewed examples; selectable languages are not verified support.
- Configure distribution signing if a distributable release is needed. iOS, live flight integration and authenticated hardware transport remain deferred by the active design.

No commits, pushes, merges, hardware flight commands or model-execution unlocks were performed in this continuation.
