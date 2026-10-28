# Historical foundation notes

Superseded by the root README and the 2026-10-08 build status. Claims below describe earlier checkpoints, not current acceptance evidence.

# AeroNex

## Current integration status (2026-10-08)

Whisper and LFM native inference, hash-verified model imports, sequential model residency, memory checks, local tool validation, and generated MAVLink codec/UDP executor are implemented. **Model-generated flight execution is locked in the active UI** after real-model validation found unsafe behavior. Simulator telemetry connections and dry-run proposals remain available.

The explicit upstream LFM parser fixes the VL template autodetection/grammar failure. A CPU host test sharing the app's prompt/tool definitions returned correct takeoff/land/forward/backward tools, but also returned `takeoff(altitude_m=3)` for **“Do not take off to 3 metres”** and silently selected one action from a compound request. Hindi examples did not yield usable tools. Parameter validation alone cannot catch a semantically wrong but valid call. Do not unlock execution on the strength of an APK build.

Android debug and release-mode builds have succeeded during integration; rebuild after edits for the current artifact. Physical-phone Whisper/LFM/JNI performance is **unverified**. Local UDP executor tests use a codec double, not real ArduPilot. The disposable SITL check reached protocol testing but failed awaiting the mode-change ACK, so full flight integration is **not validated**.

Provisioning and testing: [device matrix](docs/testing/mobile-device-matrix.md). Native model reproduction: `tools/check_lfm_native.cpp` (returns nonzero for unsafe negation/compound proposals). Setup models are imported locally; model weights are not bundled in the APK.

The previous foundation notes below are historical; the remaining blockers are validation/reliability, not missing foundation scaffolding.

## Foundation development build (2026-10-08)

The active architecture is **phone microphone → offline STT → local LFM → validated tool proposal → confirmation → MAVLink → ArduPilot**. Implementation is incremental; this build does **not** implement the complete pipeline yet.

Implemented and build-checked:
- Android host, API 26 minimum; ARM64 debug APK.
- Native Android on-device speech capture (API 31+ with a compatible installed service/language), explicit microphone permission, bounded capture and cancellation. No online-recognizer fallback.
- Separate manual tool-call test harness: validates a single allowlisted proposal, requires confirmation within 15 seconds and reports **NOT SENT — dry run**.
- No network flight executor is wired into the active app. Recognized speech is displayed only, not silently converted using the legacy rule parser.

**Pending:** whisper.cpp integration, model imports, local LFM inference, operational memory eligibility checks, MAVLink/SITL and physical-device measurements. The native speech adapter's Dart contract is tested with a platform-channel double; actual Android recognition/offline behavior is not yet device-tested. The six language choices are requests to the recognition service, not verified language support.

Toolchain used: Flutter 3.47.6 / Dart 3.13.5, Android NDK 28.2.13676358, Android platform 36, Gradle 9.3.1. Flutter's generated Android configuration selected the existing Android Studio JDK. Release signing is still a development configuration, not a distribution credential.

```bash
cd app
flutter test
flutter analyze
flutter build apk --debug --target-platform android-arm64
```

APK: `app/build/app/outputs/flutter-apk/app-debug.apk`.

Manual test fixture:
```json
[{"name":"takeoff","arguments":{"altitude_m":3}}]
```

Tap **Preview test proposal**, then **Confirm dry run**. This is a manually supplied fixture, not model output; no packet is sent. The microphone button tests native speech separately.

Design: `docs/superpowers/specs/2026-10-08-mobile-local-control-design.md`.
Progress: `docs/superpowers/plans/2026-10-08-mobile-local-control.md`.

## Legacy Edge prototype

The sections below describe the original Edge demo, **not the active mobile-local app**. Historical checklist claims are not proof of phone/LAN/flight performance. The Python suite currently has 43 tests; counts below belong to the original milestone.

Multilingual voice-control mobile app for drone Edge AI — Milestone 1:
typed-text command loop over a local WebSocket, fully offline.

Flutter mobile app ↔ Python edge server (`edge/`). Typed text stands in for
the on-device STT output until the voice pipeline lands; everything else in
the loop (intent → envelope → WebSocket → ACK → status) is real.

Spec: `AeroNex_SRS_v1.0.md`. Edge details: `edge/README.md`.

## Offline quickstart

No internet required. Two terminals on the same machine (or phone + edge
computer on the same Wi-Fi — use the edge's LAN IP as `--host`).

```bash
# 1. Install (edge server + demo client)
pip install -r edge/requirements.txt

# 2. Start the edge server (terminal 1)
uvicorn edge.app.server:app --host 0.0.0.0 --port 8000

# 3. Send a typed command (terminal 2) — intent→envelope→WS→ACK→STATUS
python -m edge.app.demo_client --host 127.0.0.1 --text "move forward 10 meters" --lang en
python -m edge.app.demo_client --host 127.0.0.1 --text "10 మీటర్లు ముందుకు వెళ్ళు" --lang te
python -m edge.app.demo_client --host 127.0.0.1 --text "10 मीटर आगे जाओ" --lang hi

# 4. Run the full test suite (22 tests, no server needed)
python -m pytest edge/tests/ -v
```

Expected demo output per command:

```text
text   : '10 మీటర్లు ముందుకు వెళ్ళు' (te)
intent : {"command": "MOVE", "parameters": {"direction": "FORWARD", "distance_m": 10}, "confidence": 0.9}
send   : COMMAND id=demo-<...>
recv   : ACK request_id=demo-<...>              # receipt only
recv   : COMMAND_STATUS request_id=demo-<...> status=EXECUTING
recv   : STATUS edge=READY ai_status=STUB
```

## How the loop fits together

```text
typed text (--text)
  ↓  edge/app/intent.py :: parse_transcript        (rule-based en/hi/te)
canonical intent {command, parameters, confidence}
  ↓  edge/app/protocol.py :: new_envelope          (v1 envelope)
COMMAND over ws://<edge>:8000/ws
  ↓  edge/app/server.py + websocket.py             (FastAPI, ACK + COMMAND_STATUS,
                                                    duplicate suppression by message_id)
ACK {request_id} → COMMAND_STATUS → STATUS {edge: READY}
```

## Layout

```text
edge/app/server.py       FastAPI WebSocket endpoint /ws
edge/app/websocket.py    ACK-then-COMMAND_STATUS dispatch, ERROR on malformed
edge/app/protocol.py     v1 envelope: new_envelope + validate_envelope
edge/app/intent.py       rule-based parse_transcript (en/hi/te)
edge/app/commands.py     command routing / validation (no MAVLink)
edge/app/demo_client.py  typed-text demo CLI (--host --text --lang) + in-process helper
edge/app/config.py       host/port/WS path/identity single source of truth
edge/app/discovery.py    mDNS advertisement (stub: no network bind yet)
edge/app/state.py        per-instance command registry (restart = fresh state)
edge/tests/              protocol (5) + edge server (8) + intent (8) + e2e (1)
app/                     Flutter mobile mirror (fake speech + rule intent)
```

## SRS §44 acceptance checklist (Milestone 1)

| §44 criterion | Status | Evidence |
|---|---|---|
| Phone discovers the Edge computer | Partial — manual IP fallback | `edge/README.md` manual-IP steps; mDNS still stub (`discovery.py`) |
| Phone connects over local Wi-Fi | Pass (localhost-verified; LAN is same socket path) | demo CLI `ws://<host>:8000/ws`; `test_reconnect_after_server_restart` |
| Connection survives normal operation | Pass | PING→PONG heartbeat; `test_ping_pong_under_100ms` |
| Reconnection works after temporary disconnection | Pass | fresh `create_app()` reconnect test |
| English speech recognized locally | Partial — typed text stands in for STT | `parse_transcript("move forward 10 meters", "en")` |
| Telugu speech recognized locally | Partial — typed text stands in for STT | `parse_transcript("10 మీటర్లు ముందుకు వెళ్ళు", "te")` |
| Hindi speech recognized locally | Partial — typed text stands in for STT | `parse_transcript("10 मीटर आगे जाओ", "hi")` |
| “Move forward 10 meters” → MOVE/FORWARD/10 | Pass | `test_en_move_forward_10m`, te/hi equivalents |
| Structured command reaches the Edge | Pass | `test_typed_telugu_command_reaches_edge_and_acks` |
| Edge returns ACK containing the request ID | Pass | ACK `request_id` echo asserted live + in-process |
| Network round trip meets latency target | Pass (localhost) | PING/PONG < 100 ms asserted; field range TBD on hardware |
| Complete workflow works without Internet | Pass | server + demo + all 22 tests run with no network |

## Known limits (by design, this milestone)

- Microphone/STT not wired yet: `--text` is the transcript input.
- `ai_status` reports `STUB`; `COMMAND_STATUS` verdicts are
  routing outcomes (`EXECUTING`/`REJECTED`/`CANCELLED`) — no flight
  controller, no MAVLink (out of scope per SRS §5).
- Duplicate `message_id` returns existing state, never re-executes.
