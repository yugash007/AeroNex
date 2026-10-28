# Independent microphone/decoder concurrency review — 0.1.7+8

Reviewed `BufferedSpeechDecoder.kt`, the bridge and test changes against `.superpowers/audits/2026-10-10-emulator-qa/before/`, and the version update. Adjacent reads were limited to the recording loop, `SpeechSession`, Vosk recognizer ownership, and cancellation/shutdown behavior needed to assess this change. No production code or Git state was changed, and no tests or builds were run during this review.

## Strengths

- **The producer no longer calls native decoding.** `LocalInferenceBridge.kt:187` routes captured chunks to `BufferedSpeechDecoder.append`, while the consumer invokes `recognizer.accept` on its own thread. `BufferedSpeechDecoder.kt:50` calls native code outside the producer's monitor. A slow decoder therefore cannot hold the microphone reader behind a native invocation, which directly addresses the reproduced truncation mechanism.
- **Memory and sample ownership are bounded.** `BufferedSpeechDecoder.kt:13` allocates 240,000 PCM16 samples, or 480,000 bytes, plus a 1,024-sample consumer chunk. The existing recorder limits each read to the remaining space and stops at 240,000 samples or 15 seconds (`LocalInferenceBridge.kt:353`). The buffer is a bounded append-only recording, not an unbounded queue. `append` copies data while holding the monitor before the microphone's reusable array can change. The consumer copies samples to its own chunk, advances its position in order, and completes each decode before reusing that chunk.
- **Shared state has a consistent synchronization boundary.** Written/consumed positions, completion/abandonment flags, and the recorded decoder failure are accessed under the same monitor. Cancellation reads the existing `AtomicBoolean`. Thread startup safely publishes the recognizer and buffer; `FutureTask.get()` makes consumer work visible before the bridge calls `recognizer.finish()` on the original worker.
- **Completion drains captured speech.** `LocalInferenceBridge.kt:190` ends microphone capture and moves the session to transcribing before waiting for `decoder.finish()`. The consumer exits normally only after it has consumed all written samples. The final recognizer result is requested afterward, so a manual stop does not discard the queued tail of the utterance.
- **Cancellation and native ownership remain safe.** `BufferedSpeechDecoder.close()` marks queued work abandoned, wakes an idle consumer, and waits for any active native accept call to return. The outer recognizer `use` block and directory lease remain active until that wait finishes. Thus the decoder cannot use JNA pointers after recognizer/model closure, nor continue reading a model after its filesystem lease is released. A cancellation prevents the bridge from finalizing or returning a transcript. Interruption while waiting also abandons work, continues waiting for safe closure, and restores the caller's interrupt status.
- **Decoder exceptions preserve their type.** The consumer records and rethrows the original throwable. Producer calls notice an existing failure; completion unwraps `ExecutionException` to its cause. Consequently the bridge's existing `LinkageError` classification, recoverable inference failures, microphone errors, and cancellation handling remain effective. Recorder cleanup occurs before the buffer and recognizer are closed.
- **The regressions exercise the failure mode and lifecycle.** The slow-decoder test gates `accept()` while requiring later microphone reads to proceed, then verifies every captured sample in order and exactly one finalization/close. The cancellation test checks that an active native call is not closed underneath the decoder, that no final result is requested, and that the next recording can succeed.

## Issues

### Critical — none

### Important — none

### Minor — none

No actionable sample loss, data race, unbounded-memory, cancellation, exception-propagation, or recognizer-lifetime defect was found in the scoped change.

## Verification reviewed

- `docs/testing/2026-10-10-emulator-qa/slow-decoder-red.log` records the new deterministic regression failing on the old implementation at the microphone-read progress assertion.
- `slow-decoder-green.log` records the regression passing after buffering was introduced.
- `native-tests.log` now ends with `BUILD SUCCESSFUL`. The matching native XML reports contain 76 tests: 75 passed, one optional model-download smoke test skipped, and zero failures/errors. All 34 bridge tests, including both new concurrency cases, passed.
- `app/pubspec.yaml` identifies the delivery as `0.1.7+8`.

This review verifies the concurrency design and saved native test evidence. The post-fix Android/emulator recognition comparison belongs to the ongoing QA run; this report does not claim a measured improvement in speech accuracy or elimination of all possible OS-level capture delays. Waiting for an in-flight native decoder to return before closing its resources is intentional; the change does not claim to interrupt an indefinitely stuck native call.

## Assessment

**Ready: Yes, for the reviewed concurrency change.** The implementation removes synchronous decoder backpressure from microphone capture, preserves sample order and ownership, and drains or safely abandons decoding before native resource closure. There are no open findings from this review. No preexisting application behavior, flight execution, or model-selection logic was broadened by this assessment.
