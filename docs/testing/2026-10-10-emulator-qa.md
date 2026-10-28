# Android emulator QA and recording fix — 0.1.7+8

The release APK now runs through real Android model loading, microphone capture and transcript review in an isolated Android 15 emulator. Testing 0.1.6 exposed an additional capture defect: native Vosk decoding blocked microphone reads. Version **0.1.7+8** moves decoding to a separate thread and drains the captured audio on Finish. A deterministic regression reproduces the old failure and passes with the fix. Speech recognition remains imperfect on some test phrases; this report records those outcomes explicitly.

## Installable artifacts

Install the [0.1.7 ARM64 release APK](../../app/build/app/outputs/flutter-apk/app-release.apk) over the existing app and reopen it. The [debug APK](../../app/build/app/outputs/flutter-apk/app-debug.apk) is also built. Both use the existing development signing certificate, package `com.aeronex.app`, version code **8**, minimum Android API **26**, target API **36**. These are evaluation builds; model weights remain separate.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Release | 38,116,797 (36.4 MiB) | `0a44607aa90fd1d06bf286136e557f6d4671bde1102102c74df9a1c9328b7832` |
| Debug | 111,912,498 | `0e51ea3b9993b41ac892adc81b6f5168297aefc3b9d78efc2f53ce03aa57f06e` |

The 0.1.6 → 0.1.7 emulator upgrade succeeded in place, retaining model files and saved Indian English/Vosk settings. Clearing app data, uninstalling or redownloading models is unnecessary for these fixes. The fixed 2 GiB free-RAM gate remains removed. An observed emulator `MemAvailable` reading was approximately 1.4 GiB while the app remained usable; this is a point-in-time reading, not a memory benchmark.

The historical [0.1.6 release](../../../tooling/android-qa/aeronex-0.1.6-release.apk) and [debug](../../../tooling/android-qa/aeronex-0.1.6-debug.apk) artifacts are archived for comparison. Their hashes and earlier verification are in the [release-runtime audit](2026-10-10-release-runtime-audit.md).

## Environment and method

- Dedicated AVD **AeroNex_QA_API35**, serial **emulator-5580**, Android 15/API 35 Google APIs image revision 9; 3 GB RAM, two cores, WHPX and SwiftShader.
- The image is x86_64 and supports the ARM64 APK through `libndk_translation.so`. The display is 1080×1920 at density 420. [Device properties](2026-10-10-emulator-qa/device-properties.json) are saved.
- Real Vosk Telugu, Vosk Indian English, Whisper Tiny and LFM Q4 files were imported through the Android file picker. Wi-Fi and mobile data were disabled for inference. The real host microphone was explicitly disabled.
- Existing Windows SAPI WAV fixtures were injected as mono PCM16 at 16 kHz through the emulator's authenticated local microphone API. These fixtures are synthetic English, **not Indian-accent or Telugu benchmarks**.
- All taps and swipes were derived from captured accessibility trees. Step-specific JSON/XML, text summaries, PNGs and [action records](2026-10-10-emulator-qa/actions.jsonl) are saved in the [evidence directory](2026-10-10-emulator-qa/).
- The Android CLI's `layout --no-idle --full` was needed because recording updates prevented stock UIAutomator from reaching idle. The [QA helpers](../../.superpowers/audits/2026-10-10-emulator-qa/) retain the driver, paced audio injection, sample runner and review.

Virtual-input calibration captured the negation WAV directly through the emulator PCM device, with normalized correlation **0.983900** against the original fixture. This verifies that the source roughly survives virtual injection; it does not certify AudioRecord under model load or real microphone acoustics. See the [calibration report](2026-10-10-emulator-qa/audio-calibration.json). Temporary adb root used for that calibration was restored to unroot before the 0.1.7 tests.

## Defect found and correction

The Vosk path previously called `recognizer.accept()` synchronously from the microphone loop. Android's small AudioRecord buffer could overrun while native decoding was busy. The comparative Whisper path captured first and decoded afterward. A host regression deliberately stalled Vosk decoding and required capture to keep reading; it failed on the original implementation.

[BufferedSpeechDecoder](../../app/android/app/src/main/kotlin/com/aeronex/app/BufferedSpeechDecoder.kt) now holds at most 240,000 PCM16 samples: 15 seconds at 16 kHz, or 480,000 bytes. Capture copies samples into that bounded array; a separate worker calls Vosk outside the capture lock. Finish stops the microphone, enters Transcribing and drains every queued sample before finalization. Cancellation abandons pending samples and waits for active native decoding before closing JNA resources or releasing the model-directory lease. Original exception types are preserved.

The new [bridge regressions](../../app/android/app/src/test/kotlin/com/aeronex/app/LocalInferenceBridgeTest.kt) verify continued capture during slow decoding, ordered samples despite a reused capture array, draining on Finish, safe cancellation and a subsequent successful recording. The [independent review](2026-10-10-emulator-qa/buffer-review.md) found no actionable issue in this scoped change. It does not claim to interrupt a native call that never returns.

A second defect appeared when inspecting upgraded models: the Models panel used a separate six-entry list that omitted all three Vosk profiles, even though their metadata and native status existed. The [panel](../../app/lib/features/flight/presentation/local_control_screen.dart) now uses the full model catalog. Indian English, Telugu and Hindi packs therefore expose their real readiness and normal Manage/Set up/Add actions. Two widget regressions first failed because these rows were missing and then passed with the fix. The native concurrency review above predates this small UI correction and is scoped to capture.

Speech cases numbered 51–69 used the intermediate capture-only 0.1.7 build, whose native code matches the final release. Its [release package report](2026-10-10-emulator-qa/capture-only-release-apk.json) and [archived APK](../../../tooling/android-qa/aeronex-0.1.7-capture-only-release.apk) preserve its exact identity. The final APK adds the Vosk panel correction and was rebuilt after the full Flutter suite and analysis.

## Actual speech results

The early 0.1.6 runs used unpaced injection and only 0.5 seconds of leading silence. Later runs used paced 20 ms packets, two seconds of leading silence and 0.5 seconds of trailing silence. Do not treat results from different injection methods as a controlled accuracy comparison. The negation case below was also repeated with the same paced method before and after the fix.

| Version / input method | Spoken fixture | Original Heard | Review behavior |
| --- | --- | --- | --- |
| 0.1.6 Vosk, unpaced | Hey drone fly | `he drowns why` | Explicit `hey drone fly` suggestion |
| 0.1.6 Vosk, unpaced | Move left two meters | `move a few minutes` | No suggestion |
| 0.1.6 Vosk, unpaced | Do not take off | `pick off` | Offered `take off`; shows the risk when recognition loses negation |
| 0.1.6 Vosk, paced | Do not take off | `during our core` | No suggestion |
| 0.1.6 Whisper Tiny, paced | Move left two meters | `Move left two meters` | Exact transcript |
| 0.1.7 Vosk, paced | Do not take off | `do not take off` | Negation blocks interpretation; exact in two runs, including retry after cancellation |
| 0.1.7 Vosk, paced | Move left two meters | `move a few meters` | Still incorrect; no numeric correction offered |
| 0.1.7 Vosk, paced | Hey drone fly | `heat rounds are` | Explicit `hey drone fly` suggestion; applying it preserves Heard and requires an altitude |
| 0.1.7 Vosk, paced | Hey drone take off to three meters | `he drowned take off his readers` | Still incorrect; manual correction needed |
| Final 0.1.7 Vosk, paced rerun | Do not take off | `these are take off` | Incorrect; negation was lost and the text gate cannot recover it. Interpretation was not requested. |

Evidence: [paced negation before](2026-10-10-emulator-qa/48-vosk-paced-negation-result-00.txt), [negation after](2026-10-10-emulator-qa/51-fixed-vosk-negation-result-01.txt), [retry](2026-10-10-emulator-qa/59-retry-negation-result-01.txt), [move](2026-10-10-emulator-qa/52-fixed-vosk-move-result-00.txt), [suggestion](2026-10-10-emulator-qa/54-fixed-suggestion-applied.txt), [takeoff](2026-10-10-emulator-qa/55-fixed-vosk-takeoff-result-00.txt), and [Whisper](2026-10-10-emulator-qa/45-whisper-move-result-11.txt).

All six completed 0.1.7 Vosk fixture runs reached review after an explicit Finish. The [final rerun](2026-10-10-emulator-qa/72-final-vosk-negation-result-02.txt) confirms that the earlier exact negation results are not consistently repeatable in this emulator. The original JNA peer-field/LibVosk startup failure did not recur. The buffer regression proves that decoding no longer blocks the microphone reader; these speech results do not prove all OS-level capture delays are eliminated or recognition is reliable. Inspect the original Heard text and edit before interpreting. No automatic flight execution was enabled.

## Flow coverage

| Flow | Observed result |
| --- | --- |
| First launch, no models | Telugu default; selecting capture opens setup for the required speech model only |
| Real model imports | Telugu/Indian English ZIPs, Whisper Tiny and LFM Q4 verify and become available |
| Microphone permission denied then granted | Friendly denial/retry message; a later capture works |
| Telugu Vosk startup and silence | Native model initializes; silent recording finishes with a no-speech message and retry enabled; spoken Telugu accuracy was not tested |
| Repeated recording and Finish | Completes to review on the release APK; no native startup crash |
| Explicit correction | Changes the editor only; original Heard remains visible; “fly” requires altitude |
| Reviewed text editing | Corrected `hold position` becomes normalized `hold`; interpretation remains a separate tap |
| Capture cancellation and retry on 0.1.7 | Cancellation returns idle; next negation recording completes exactly |
| LFM Q4 interpretation and cancellation | `hold position` was still computing after 184 seconds under ARM translation; cancellation recovered in the next snapshot. No completed proposal or dry-run confirmation was measured in this emulator run. [Before](2026-10-10-emulator-qa/65-lfm-before-cancel.txt), [after](2026-10-10-emulator-qa/66-lfm-cancelled.txt) |
| Model/settings restart and upgrade | Selected models remain ready and saved Vosk/Indian English returns after verification |
| Final Vosk model management | Indian English and Telugu display **ready / Manage**; missing Hindi displays **Optional alternative / Add**. Both installed packs open **Installed and verified** panels. [Rows](2026-10-10-emulator-qa/74-final-vosk-rows.txt), [Telugu](2026-10-10-emulator-qa/75-final-telugu-management.txt), [English](2026-10-10-emulator-qa/77-final-english-management.txt) |
| Final Telugu preference after restart | Telugu and Vosk remain selected and **Models ready** returns; [evidence](2026-10-10-emulator-qa/85-final-telugu-ready.txt) |
| Larger text on actual Android | At font scale 1.3, Vosk rows remain reachable and Telugu management is readable; [screenshot](2026-10-10-emulator-qa/80-larger-text-telugu-management.png). Font scale restored to 1.0 afterward. |
| Whisper alternative | Offline Tiny capture/Finish returns exact move fixture on 0.1.6 |

## Automated verification

| Check | Result / evidence |
| --- | --- |
| Slow-decoder regression on original implementation | Expected failure in [red log](2026-10-10-emulator-qa/slow-decoder-red.log) |
| Same regression with buffered capture | Pass in [green log](2026-10-10-emulator-qa/slow-decoder-green.log) |
| Missing Vosk management rows | Both new regressions fail before the fix and pass afterward; [red](2026-10-10-emulator-qa/vosk-management-red.log), [green](2026-10-10-emulator-qa/vosk-management-green.log) |
| Full Flutter suite and analysis | **96 tests pass; no analysis issues**; [tests](2026-10-10-emulator-qa/flutter-tests.log), [analysis](2026-10-10-emulator-qa/flutter-analysis.log) |
| Full Android host suite | **76 total: 75 passed, one optional network smoke skipped, zero failures/errors**; [summary](2026-10-10-emulator-qa/native-summary.json), [log](2026-10-10-emulator-qa/native-tests.log) |
| Release and debug builds | Both 0.1.7+8 succeed; [release log](2026-10-10-emulator-qa/0.1.7-release-build.log), [debug log](2026-10-10-emulator-qa/0.1.7-debug-build.log) |
| Both delivered APKs | Expected version/certificate, ARM64, ZIP/ELF 16 KiB alignment and JNA/Vosk 17-class/52-member contract pass; [release report](2026-10-10-emulator-qa/0.1.7-release-apk.json), [debug report](2026-10-10-emulator-qa/0.1.7-debug-apk.json) |
| Release installation over 0.1.6 | [Initial update succeeds](2026-10-10-emulator-qa/install-0.1.7.log); [final build also installs](2026-10-10-emulator-qa/install-0.1.7-final.log). Installed APK SHA-256 matches the final release report; [environment](2026-10-10-emulator-qa/final-installed-environment.json). |
| Final crash and error inspection | Crash buffer empty; no watched JNA/Vosk startup error, fatal exception/signal, app ANR or Flutter overflow line; [summary](2026-10-10-emulator-qa/final-log-audit.json), [logcat](2026-10-10-emulator-qa/85-final-logcat.txt) |
| Previous C++ verification | Three CMake/CTest checks on unchanged code; not rerun for 0.1.7 |

## Limits and reproducibility

No physical phone was connected. This run does not measure real microphone noise, native Indian English/Telugu recognition, phone speed, peak memory, thermal behavior, background-process survival or the complete phone-to-SITL path. Hindi, Tamil, Kannada, Malayalam, Android service recognition, and Whistle/Needle were not exercised in this emulator session. Their existing integrations and host evidence remain documented separately. Every app confirmation remains a dry run that reports **NOT SENT**.

Early SDK installation and automation failures are retained as evidence: installation before Android boot completed failed in the storage service; running stock UIAutomator alongside the Android CLI layout server caused a tooling-process crash. These were not AeroNex crashes. A debug package-check attempt redirected output to the checker's own log and hit a Windows file lock; rerunning without that redirect passed. Final package JSON reports are authoritative. Existing Gradle/JVM compatibility notices were not suppressed.

The dedicated emulator was [shut down](2026-10-10-emulator-qa/emulator-shutdown.json) after evidence collection; its AVD and model files are retained for reproduction. [Delivery checks](2026-10-10-emulator-qa/delivery-checks.json) confirm the saved test results, artifact hashes and document links. The [final UI assertions](2026-10-10-emulator-qa/final-ui-assertions.json) separate working management/recording flows from the failing recognition-accuracy result. Use the [device evaluation guide](mobile-device-matrix.md) for physical-phone acceptance. Existing unrelated Git changes and model downloads were preserved; no commit, reset, merge or push was performed.
