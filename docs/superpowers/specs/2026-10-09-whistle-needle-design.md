# Whistle and Needle integration

The user requested downloading, evaluating, planning and implementing `Cactus-Compute/whistle`. The existing Flutter/Android app uses local Whisper speech, editable transcript review, local LFM interpretation and dry-run confirmation. Preserve that working path and existing model files.

## Evidence and chosen approach

- Whistle revision `b358ddadd89b7a713b5aa131f23032d3cca1b251` supports English, German, French, Spanish, Italian, Dutch and Polish. It does not support Telugu. Its single deployable `.cact` is 16,919,407 bytes.
- Tool calling requires the companion Needle model. Needle revision `2ae11323dc000f5e70c49f7403efa6af12ba9e67` supplies a 35,335,380-byte model plus Android ARM64 static library and C header. Both repositories use Apache-2.0.
- Replacing Whisper entirely would lose Telugu. Using Whistle with LFM alone would leave the requested Needle tool-calling capability untested. Add optional Whistle speech and optional Needle interpretation, retaining both existing backends.

## Interface and behavior

Add English Whistle to the speech dropdown and Needle 3 to Local AI. Selecting Whistle explicitly selects English with a visible explanation. Selecting a non-English language while using Whistle returns to Whisper. The Android bridge also rejects unsupported Whistle language requests before capture. Needle is offered for English tool proposals in this release; LFM remains available for other language evaluation.

Use the existing 16 kHz mono recorder, 15-second capture limit, session-aware Finish, cancellation generation and transcript review. Call `needle_transcribe` with drone vocabulary keywords, then show its original text for editing. Only after the user requests interpretation call `needle_complete` with reviewed text and the five existing tool schemas. Do not use the combined audio-to-tools API to skip review. Validate the result through the existing single-tool allowlist, argument ranges and dry-run confirmation. Preserve empty/refused, malformed, multiple-call and cancelled outcomes as non-executable.

## Runtime and model lifecycle

Pin the native archive, header and model URLs/checksums. Link the archive into a separate native library opened lazily for Cactus operations. Serialize all access and retain one stable mapped model per kind for the process lifetime. Load each kind once and reuse its model slot; reset text conversation state before each reviewed command. Android reclaims the Cactus runtime at process exit. A failed initialization prevents further Cactus load attempts until restart; other backends remain usable. The published C API has no cancellation hook. Cancellation invalidates the result immediately but work remains busy until the bounded inference call returns; it never calls a reset concurrently with inference.

The first design attempted per-operation library unloading. Actual host testing and ARM64 symbol inspection showed persistent worker threads/TLS and raw global model contexts without unload cleanup, so the final design follows the upstream persistent lifecycle. Six alternating speech/text calls are checked for stable heap allocation. The supplied pinned model files have fixed content hashes: repairing or atomically replacing one with identical verified bytes may reuse its cached mapping. A different file path for an already loaded kind requires a process restart.

Reuse verified atomic model download/import storage with explicit URLs for `.cact` files. Add Whistle and Needle as optional profiles. No automatic download and no minimum available-RAM gate. Preserve Whisper/LFM selection and model detection behavior. The native C runtime is used directly; no Python telemetry code or cloud inference is included. Actual model evaluation also found a 100-to-1 altitude substitution and an unrelated weather query mapped to telemetry. The adapter independently checks numeric arguments against reviewed text and requires telemetry wording for status calls. These conservative checks do not certify full semantic accuracy or enable flight execution.

## Verification and delivery

Run downloaded Windows models against local synthetic English speech and tool requests; label acoustic results as synthetic host evidence. Add platform-channel and widget regressions for the new profiles, English routing, unsupported Telugu, missing-model setup, reviewed text dispatch and validation. Test the JNI adapter's C API behavior on the host where practical and build ARM64 Android. Check APK version, signatures and 16 KB ZIP/ELF alignment. Preserve the existing signing identity and provide a versioned APK plus a report of measured results and limits. Actual phone microphone accuracy and latency remain unverified without a device.
