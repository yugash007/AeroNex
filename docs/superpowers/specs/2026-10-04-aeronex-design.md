# AeroNex — Design Spec (Full Skeleton, Approach A)

**Date:** 2026-10-04
**Source:** `AeroNex_SRS_v1.0.md` v1.0 (Final)
**Locked decisions:** full-skeleton scope; Approach A (Edge-verified now + Flutter hand-written);
Python Edge comm server; C++ ruling below.

## 1. Goal

Offline multilingual voice → canonical JSON command → local-Wi-Fi WebSocket → drone Edge AI,
with ACK/status/heartbeat/reconnect. This session builds the full skeleton with fakes:
typed text stands in for mic/STT, manual IP stands in for mDNS.

## 2. Non-goals (this session)

Real on-device STT (whisper.cpp/ONNX), mDNS discovery working end-to-end, MAVLink/flight
control, autonomy/vision, cloud services, audio streaming to Edge.

## 3. Architecture

```
Typed text (= mic stand-in) → FakeSpeechEngine → RuleBasedIntentEngine → canonical COMMAND
  → WebSocketEdgeTransport → local Wi-Fi → Python Edge server → ACK + COMMAND_STATUS
  → command history UI. PING/PONG heartbeat + latency in parallel.
```

- `protocol/schema.json` is the single contract both sides code against (envelope v1).
- `edge/` (Python FastAPI + WebSocket, per SRS §37–38) is the verified reference; tested here.
- `app/` (Flutter, per SRS §26) mirrors protocol + intent in Dart; compiled on a Flutter machine.
- Transport, speech, and intent layers are abstracted per SRS §27–29 so WebSocket/STT/intent
  implementations can be replaced without touching callers.

## 4. C++ ruling (measured 2026-10-04)

Python `json.dumps` 0.0025 ms / `json.loads` 0.0033 ms vs SRS serialization budget 50 ms:
comm-server language saves ~0.002 ms on a 100 ms+ voice-to-ACK path. C++ belongs in the
STT runtime (whisper.cpp plugin behind `SpeechEngine`) and optionally a future binary
transport behind `EdgeTransport` — not in the JSON relay server. Edge server stays Python.

## 5. Protocol (v1 envelope, SRS §18–23)

```json
{"version": 1, "message_id": "cmd-001", "type": "COMMAND",
 "timestamp": 1791123456789, "payload": {"command": "MOVE",
 "parameters": {"direction": "FORWARD", "distance_m": 10}, "confidence": 0.96}}
```

- Mobile→Edge: `PING`, `COMMAND`, `STATUS_REQUEST`, `CANCEL`. Edge→Mobile: `PONG`, `ACK`,
  `COMMAND_STATUS`, `STATUS`, `ERROR`.
- `ACK.payload = {"request_id": "<cmd id>", "status": "RECEIVED"}` — receipt only, not execution.
- Command lifecycle: `RECEIVED → PROCESSING → ACCEPTED/REJECTED → EXECUTING → COMPLETED/FAILED/CANCELLED`.
- Duplicate rule: Edge keys on `message_id`; retry of a seen ID returns existing state, never re-executes.
- Connection states: `DISCONNECTED → DISCOVERING → CONNECTING → CONNECTED → READY`,
  loss path `READY → CONNECTION_LOST → RECONNECTING → CONNECTED → READY` with bounded
  backoff. `CONNECTED` requires live heartbeat-confirmed WebSocket, never Wi-Fi presence alone.

## 6. Command set (skeleton)

`MOVE` (direction + distance_m), `TAKEOFF`, `LAND`, `HOVER`, `STOP`, plus `PING`/`CANCEL`.
Rule-based intent covers EN/TE/HI (SRS MVP acceptance languages); TA/KN/ML patterns stubbed
for extension. Example: "10 మీటర్లు ముందుకు వెళ్ళు" → `MOVE/FORWARD/10m`.

## 7. File maps

- `protocol/schema.json`; `edge/app/{server,websocket,protocol,discovery,commands,state,config,intent}.py`,
  `edge/tests/test_*.py`, `edge/requirements.txt`, `edge/README.md`.
- `app/pubspec.yaml`, `app/lib/{core/constants,errors,logging,network,features/voice,features/language,features/intent,features/edge_connection,features/command_history,shared/models,shared/widgets,main.dart}`,
  `app/test/*_test.dart` (written here, run on Flutter machine).

## 8. Acceptance (maps to SRS §44)

Phone connects over local Wi-Fi with manual IP; PING/PONG < 100 ms on LAN; typed EN/TE/HI
commands parse to canonical JSON; Edge ACKs with request ID; duplicate retry returns existing
state; reconnect after server restart; full loop works offline (no internet dependency).
Flutter compiles + Dart tests pass on a Flutter machine (deferred verification, recorded).
