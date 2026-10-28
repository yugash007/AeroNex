# AeroNex mobile-local control design

## Architecture change

The active direction supersedes the drone-side Edge AI architecture in the original SRS. The phone performs all speech recognition and language inference, then a deterministic executor communicates with ArduPilot using MAVLink. No drone-side Python server is required. The existing Edge example remains a legacy regression/demo, not the flight executor.

```
microphone → offline SpeechEngine → transcript → local LFM
→ validated tool proposal → preview/confirmation → executor
→ MAVLink → ArduPilot → acknowledgement and telemetry
```

ArduPilot retains stabilization, motor control, navigation and failsafes. Model output is untrusted input; it never directly generates motor signals, executable code or arbitrary MAVLink packets.

## Mobile target

- Android 8/API 26+ and ARM64 initially, with CPU-only native inference and no mandatory vendor GPU/NPU extensions. Native on-device recognition needs API 31+ and an installed compatible service.
- At least 2 GiB available system RAM at model startup; this is an eligibility target, not reserved memory or a compatibility guarantee. Memory pressure is monitored during use.
- LiquidAI/LFM2.5-VL-450M-GGUF, Q4_K_M initially; compare Q8_0. Text-only inference omits the vision projector. Context is bounded to 2,048 tokens and output to 256 tokens.
- Speech options remain selectable: whisper.cpp with multilingual tiny/base models and Android's explicitly on-device recognizer. No cloud fallback; unsupported native recognition is reported.
- Initially use sequential ASR/model residency to reduce overlapping memory. Compare dual residency only on measured capable devices.
- Provision models before offline operation. Imports use private app storage; runtime inference never downloads or uploads user audio/text.

## Language contract

Support is measured, not inferred from model size or multilingual marketing. Evaluate English, Hindi, Telugu, Tamil, Kannada and Malayalam with reviewed examples. The LFM card does not document all these languages. Speech accuracy and intent accuracy are separate gates. Ambiguous or unsupported speech produces clarification/rejection rather than a guessed flight action.

## Command contract

Initial allowlist: `get_status`, `takeoff(altitude_m)`, `land`, `hold`, `move_relative(direction, distance_m)`. Exactly one action per proposal. Arguments must have exact names and types, be finite and satisfy the active policy. Unknown/extra keys, multiple calls and incomplete model output are rejected. STOP means request a hold, never disarm the motors.

The simulator test profile allows takeoff altitude 1–10 m and relative distance >0–10 m. These are not certified real-flight limits. Missing height/distance is not silently supplied. Requests such as “fly” require clarification.

Confirmation is bound to an immutable proposal and expires after 15 seconds. New input, cancellation, backgrounding, disconnect or changed vehicle readiness invalidates it. Revalidate just before dispatch; permit one operation at a time. Model confidence is not a safety authorization.

Dry-run is the default: it cannot send packets and reports NOT SENT. Flight results distinguish sending, acknowledged, completed, rejected and unknown. An ACK is not proof of completion. Lost results do not trigger blind retries or replay after reconnect.

## Implementation boundaries

Reuse the Flutter SpeechEngine/IntentEngine boundaries and HistoryStore. Replace the screen's current immediate Edge send with the preview/confirmation flow. Preserve legacy rule parsing and WebSocket code as explicit demos/tests, never a silent command fallback.

Use Android platform channels and pinned upstream llama.cpp/whisper.cpp native bindings. Apply the actual LFM chat template and upstream tool parser; no eval, generated code or permissive regular-expression parser. Native inference runs away from the UI thread and supports cancellation. Isolate duplicate ggml symbols between runtime libraries.

## Validation order

1. Buildable Android host and existing regression tests.
2. Validated local proposals and confirmation with a dry-run executor.
3. Real speech backends, model imports and memory checks.
4. Local LFM tool proposals using real model fixtures.
5. Direct MAVLink integration tested against ArduPilot Copter SITL.
6. Physical-phone matrix covering chipsets, OS versions, RAM pressure, latency, heat, speech and semantic accuracy.

SITL runs are development tests, not live flight approval. Real hardware use requires explicit configuration, authenticated/trusted transport, verified flight limits, preflight checks and independent pilot override. A UI simulator label does not prove a connected peer is a simulator.

## Deferred

Wake-word detection, camera input, iOS, GPU-specific optimization, arbitrary missions, generated code, USB/radio-specific adapters and live hardware commands. No physical-device or flight compatibility claim until tested.

## Sources

- https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF
- https://huggingface.co/LiquidAI/LFM2.5-VL-450M
- https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile
- https://docs.liquid.ai/deployment/on-device/llama-cpp/function-calling
- https://github.com/ggml-org/whisper.cpp
- https://developer.android.com/reference/android/speech/SpeechRecognizer
