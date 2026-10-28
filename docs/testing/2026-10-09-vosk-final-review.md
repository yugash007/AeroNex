# Final Vosk integration review

**Verdict: Approved for local evaluation APK delivery. No blocking findings.**

Reviewed the complete 30-file `final-review.diff` in bounded passes against `docs/superpowers/specs/2026-10-09-vosk-design.md`, `docs/superpowers/plans/2026-10-09-vosk.md`, the progress ledger, and the prior scoped rereviews. The supplied snapshot is the review boundary because the app contains pre-existing uncommitted work; no Git diff was derived. The outer workspace README is the `WORKSPACE-README.md` entry in that snapshot.

## Strengths

- **Installation and inference share a coherent lifetime contract.** `VerifiedModelStore.kt:133` recovers a verified target or backup under the directory write lock, and `:233` serializes publication. `LocalModelInventory.kt:22` recovers before taking its read lock and avoids upgrading an existing runtime lease. `LocalInferenceBridge.kt:159` takes the same canonical-directory read lock after initial inventory recovery, revalidates readiness under it, and releases it only after the streaming recognizer's `use` scope closes. This prevents replacement from removing files used by Vosk and prevents inventory from observing the rename gap.
- **Readiness is based on verified contents.** The archive size/hash and complete extracted manifests agree with the checked official metadata. Extraction rejects unsafe, duplicate, unexpected, missing and oversized entries. Inventory rejects extra runtime graph files and links as well as corrupt required files. The deterministic backup protocol handles interruption between publication renames, and `VerifiedModelStore.kt:56` deletes links as leaves. Tests exercise replacement cancellation, failed transfer, restart recovery, queued readers/writers, and both nested/root symbolic links.
- **The recording adapter owns and releases its resources.** Model initialization occurs on the worker before Listening; cancellation is checked again before microphone startup. Both English recognizers share one model and close before the runtime lease ends. The bounded 16 kHz PCM16 recorder releases AudioRecord on errors and cancellation; final text aggregation appends endpoints once and caches Finish. Blank/unknown-only results cannot become reviewed commands. Failure/retry tests cover loading, microphone reads, chunk decoding and final decoding.
- **Language selection and setup match the agreed behavior.** Telugu remains the fresh-install language. English preferences migrate from `en-US` to `en-IN`; Whistle and Needle accept the new locale. `LocalInferenceBridge.kt:258` retains explicit backend choices and otherwise prefers an installed matching Vosk pack, then installed Whisper. Unsupported Vosk languages fall back to Whisper with an explanation in the UI. `local_control_screen.dart:186` and `:316` keep speech capture independent of interpretation-model readiness and request the matching pack only.
- **Suggestions remain explicit review choices.** `VoskSpeechRecognizer.kt:54` suppresses unknown, blank, actionless, identical, number-changing and negation-changing candidates, including the corrected contraction/apostrophe cases. The open-vocabulary transcript stays authoritative. The button at `local_control_screen.dart:710` changes only the review editor, preserves Heard, and does not interpret. The separate interpretation action still precedes confirmation. Finish error recovery at `:250` checks both session identity and phase, preventing a late failed Finish from replacing completed review.
- **Packaging and documentation are consistent.** The dependency/version/license changes match the two saved APK manifests. The effective ABI filter clears Flutter's inherited set before retaining ARM64. The documentation explains separate pack installation, high upstream Telugu word error, explicit suggestions, desktop-runtime limits and the evaluation signing certificate without claiming phone accuracy.

## Issues

### Critical — must fix

None found.

### Important — should fix

None found.

### Minor — nice to have

None raised. The inherited Gradle/AGP, JVM native-access and deprecated test-tool notices are already recorded and do not establish a new defect in this change.

## Verification assessed

No suites, builds, model downloads or packaging checks were rerun during this review. The saved evidence resolves the integration questions without an additional focused execution check:

- `2026-10-09-vosk-speech-flutter-all.log:113`: 91 Flutter tests passed; `2026-10-09-vosk-speech-analysis.log:9`: no issues found.
- `2026-10-09-vosk-native-final-summary.json`: 72 native tests, 71 passed, one optional network smoke skipped, zero failures/errors; the corresponding final log reports BUILD SUCCESSFUL.
- `2026-10-09-vosk-native-build.log` and `2026-10-09-vosk-native-tests.log`: existing native targets rebuilt and all three CTests passed.
- `tools/CheckVoskAdapter.java` and its saved JSON/log exercise the production Kotlin adapter with all three actual packs, silence/finalization, repeated Finish/close, eight synthetic English recordings and load failure followed by retry. These are desktop Vosk 0.3.45 checks. Android packages 0.3.75; the harness does not execute Android JNI or measure a phone microphone, Indian accent, or Telugu speech accuracy.
- Both release and debug build logs report successful APK creation. Their verification logs confirm package `com.aeronex.app`, version `0.1.5`/code `6`, ARM64 only, the same existing certificate, and successful ZIP alignment verification. The manifest records minimum API 26, the Vosk/JNA libraries and license asset, and ELF LOAD alignments of at least 16 KiB for every packaged native library.

To resolve the named cross-component dry-run risk, I inspected the active screen's controller construction and the existing controller default: `local_control_screen.dart:61` constructs `CommandController()` and `command_controller.dart:15`/`:32` return NOT SENT through the default dry-run executor. No flight-execution wiring or fixed 2 GiB RAM gate is introduced in this snapshot. Other reads beyond diff hunks were limited to the surrounding readiness, cancellation and worker completion paths needed to trace the integration.

## Recommendations

Use the existing device matrix when a phone is available to measure Android JNI startup, real recording/Finish/retry behavior, Indian-accent and Telugu/mixed speech accuracy, latency, memory and thermal behavior. These documented measurement limits do not block the authorized local evaluation delivery. Keep the current dry-run behavior and do not present host recognition or package alignment checks as device certification.

## Assessment

**Ready to merge? Yes, from the scoped technical review; no merge is authorized or performed.**

**Ready for local evaluation APK delivery? Yes.** The implementation matches the agreed design, the installer/runtime and Flutter/native interfaces compose correctly, and the completed validation supports the stated evaluation scope. No outstanding code fixes were identified; actual Android JNI and physical-phone speech behavior remain unmeasured as documented.

Only this review report was written. No implementation, index, HEAD, branch, APK or other evidence file was modified, and no commit or push was made.
