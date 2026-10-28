# Software Requirements Specification (SRS)

# AeroNex
## Multilingual Voice-Control Mobile Application for Drone Edge AI

**Document Version:** 1.0  
**Status:** Final  
**Platform:** Android + iOS  
**Framework:** Flutter  
**Primary Mobile Runtime:** Local / On-device AI  
**Primary Communication:** Local Wi-Fi  
**Application Boundary:** Mobile Application ↔ Edge AI Computer on Drone

---

## 1. Purpose

AeroNex is a cross-platform mobile application for Android and iOS that allows a user to communicate with a drone's Edge AI computer using natural-language voice commands.

The application is responsible for:

1. Capturing the user's voice.
2. Performing speech-to-text locally.
3. Supporting multiple spoken languages.
4. Translating/normalizing recognized language into a common command representation.
5. Establishing a direct local wireless connection with the Edge AI computer mounted on the drone.
6. Sending compact structured commands to the Edge AI.
7. Receiving acknowledgements, responses, and connection status from the Edge AI.
8. Operating without Internet access during normal operation.

The application does **not** directly implement flight-controller communication in this phase.

---

# 2. Scope

The system scope is strictly:

```text
USER
  ↓
Flutter Mobile App
  ↓
Local Multilingual STT
  ↓
Language / Intent Processing
  ↓
Canonical Command
  ↓
Local Wi-Fi
  ↓
Edge AI Computer on Drone
```

The following are outside the current application boundary:

```text
Edge AI → MAVLink
MAVLink → Flight Controller
Flight Controller → Motors
Vision-based navigation
Flight stabilization
Low-level flight control
```

Those components belong to the drone-side system and will consume commands received from this application.

---

# 3. Product Vision

The user should be able to speak naturally in their preferred language while the drone-side Edge AI receives a language-independent structured command.

Example:

### English

> Move forward 10 meters.

### Telugu

> 10 మీటర్లు ముందుకు వెళ్ళు

### Hindi

> 10 मीटर आगे जाओ

The mobile application converts these into an equivalent structured representation:

```json
{
  "version": 1,
  "command": "MOVE",
  "parameters": {
    "direction": "FORWARD",
    "distance_m": 10
  }
}
```

The Edge AI does not need to process the original human language.

---

# 4. Goals

## 4.1 Primary Goals

The application shall:

- Support Android and iOS.
- Be implemented using Flutter.
- Perform local speech recognition.
- Support multilingual voice input.
- Detect or identify the spoken language.
- Translate/normalize speech into a common command representation.
- Generate structured drone commands.
- Establish a direct local connection to the Edge AI computer.
- Operate without Internet access.
- Provide low-latency command transmission.
- Provide connection status and latency information.
- Provide reliable message acknowledgement.
- Automatically reconnect after temporary connection loss.
- Prevent duplicate command transmission at the protocol level.
- Provide a clean abstraction so the transport protocol can be changed later.

---

# 5. Non-Goals

The current version shall NOT:

- Control motors directly.
- Implement flight stabilization.
- Implement MAVLink.
- Implement ArduPilot/PX4 communication.
- Perform drone-side command validation.
- Perform obstacle avoidance.
- Perform autonomous navigation.
- Run computer vision.
- Replace the Edge AI system.
- Require cloud AI for normal operation.
- Stream continuous audio to the Edge AI unless explicitly added as a future feature.

The phone sends **structured commands**, not a continuous audio stream.

---

# 6. System Architecture

```text
                         USER
                           │
                           │ Speech
                           ▼
              ┌─────────────────────────┐
              │      FLUTTER APP        │
              │                         │
              │  Microphone             │
              │      ↓                  │
              │  Local STT              │
              │      ↓                  │
              │  Language Processing    │
              │      ↓                  │
              │  Intent Extraction      │
              │      ↓                  │
              │  Canonical Command      │
              │      ↓                  │
              │  Wi-Fi Transport        │
              └────────────┬────────────┘
                           │
                           │ Local Wi-Fi
                           │
                           ▼
              ┌─────────────────────────┐
              │   DRONE EDGE COMPUTER   │
              │                         │
              │  Wi-Fi Access Point     │
              │      ↓                  │
              │  Communication Server   │
              │      ↓                  │
              │  Command Receiver       │
              │      ↓                  │
              │  Edge AI                │
              └─────────────────────────┘
```

---

# 7. Physical Communication Model

The Edge AI computer is physically mounted on the drone.

The mobile phone communicates directly with the drone through a local Wi-Fi network.

Recommended architecture:

```text
                   15–30 m target range
                         │
             ┌───────────┴───────────┐
             │                       │
             ▼                       ▼
        ┌──────────┐           ┌──────────────┐
        │  PHONE   │           │    DRONE     │
        │          │           │              │
        │ Flutter  │◄─────────►│ Wi-Fi AP     │
        │          │   Wi-Fi   │              │
        │ STT      │           │ Edge Computer│
        │ Intent   │ WebSocket │              │
        └──────────┘           └──────────────┘
```

The drone's Edge AI computer should create or expose the local Wi-Fi network.

The mobile device joins the drone's network.

Internet connectivity is not required.

---

# 8. Wireless Requirements

## 8.1 Frequency

The initial implementation should target 2.4 GHz Wi-Fi because the application requires low bandwidth and benefits from longer practical range.

5 GHz may be supported later.

## 8.2 Target Operating Distance

The initial engineering target is:

```text
15–30 meters
approximately 50–100 feet
```

This is a target for the communication system, not a guaranteed RF range.

Actual range shall be measured using the final:

- Edge computer
- Wi-Fi chipset
- Antenna
- Drone frame
- Phone
- Power configuration

## 8.3 Bandwidth

The application shall not stream voice continuously to the Edge AI.

Typical communication shall be:

```text
Speech
 ↓
Local processing
 ↓
Small structured JSON message
 ↓
Wi-Fi
```

Therefore command bandwidth requirements are low.

---

# 9. Network Architecture

## 9.1 Recommended MVP Transport

Use:

**WebSocket over local Wi-Fi.**

Reasons:

- Persistent connection.
- Bidirectional communication.
- Simple Flutter implementation.
- Simple Edge implementation.
- Suitable latency for voice commands.
- Easy debugging.
- Supports acknowledgements and streaming status.

Possible future transports:

- UDP
- QUIC
- Custom binary protocol

The application-level protocol shall remain transport-independent.

---

# 10. Edge Network Discovery

The user should not have to manually enter an IP address in the final system.

Recommended flow:

```text
Drone Edge Computer boots
        ↓
Wi-Fi Access Point starts
        ↓
Communication Server starts
        ↓
AeroNex Edge service advertised
        ↓
Phone joins network
        ↓
Flutter discovers Edge
        ↓
WebSocket connection established
```

Recommended discovery technology:

**mDNS / Zeroconf**

Example service:

```text
_aeronex-edge._tcp.local
```

The MVP may initially allow manual IP configuration for development.

---

# 11. Connection Lifecycle

The Flutter application shall maintain an explicit connection state.

```text
DISCONNECTED
      ↓
DISCOVERING
      ↓
CONNECTING
      ↓
CONNECTED
      ↓
READY
```

If connection is lost:

```text
READY
  ↓
CONNECTION_LOST
  ↓
RECONNECTING
  ↓
CONNECTED
  ↓
READY
```

The application shall not continuously reconnect at maximum frequency.

It should use bounded retry intervals / exponential backoff.

---

# 12. Connection Health

The system shall use heartbeat messages.

```text
Mobile → PING
Edge   → PONG
```

The application should measure:

```text
Round-trip latency
Connection state
Last successful response
```

Example UI:

```text
EDGE AI

● CONNECTED

Latency
18 ms

Signal
Good
```

The exact signal-strength representation should only be shown if the platform provides reliable information.

---

# 13. Voice Processing Pipeline

The voice pipeline shall be:

```text
Microphone
    ↓
Audio Capture
    ↓
Voice Activity Detection
    ↓
Local STT
    ↓
Language Identification
    ↓
Translation / Normalization
    ↓
Intent Extraction
    ↓
Canonical Command
    ↓
Transport
```

The initial implementation should use push-to-talk.

---

# 14. Speech-to-Text Requirements

The STT engine shall:

- Run locally on the mobile device.
- Support multilingual speech.
- Return text.
- Return language information where supported.
- Avoid sending raw audio to a cloud service.
- Support configurable model sizes.
- Provide an abstraction layer for replacing the STT implementation.

Candidate technologies:

- Whisper-compatible local inference.
- whisper.cpp.
- ONNX Runtime.
- TensorFlow Lite.
- Core ML on iOS.
- Native optimized runtimes.

The final model shall be selected based on:

- Accuracy
- Device performance
- Model size
- Memory consumption
- Latency
- Supported languages

---

# 15. Initial Language Support

The first release should target:

1. English
2. Telugu
3. Hindi
4. Tamil
5. Kannada
6. Malayalam

Additional languages may be added through model updates.

The language system should not require the Edge AI computer to understand these languages.

---

# 16. Translation and Normalization

The mobile application shall provide language normalization between the user's spoken language and the internal command representation.

The application should **not send long translated English sentences to the Edge AI**.

Preferred:

```text
Telugu
   ↓
STT
   ↓
Intent
   ↓
Canonical Command
```

rather than:

```text
Telugu
   ↓
STT
   ↓
English Sentence
   ↓
Edge AI
```

An internal English semantic representation may be used by the intent model, but the network protocol shall use the canonical command schema.

---

# 17. Intent Engine

The Intent Engine converts natural language into a constrained command.

Example:

```text
"10 మీటర్లు ముందుకు వెళ్ళు"
```

becomes:

```json
{
  "command": "MOVE",
  "parameters": {
    "direction": "FORWARD",
    "distance_m": 10
  }
}
```

The Intent Engine shall not generate raw MAVLink packets.

Possible implementations:

- Rule-based parser
- Lightweight local language model
- Hybrid rule + ML parser
- Small local LLM
- Liquid AI model

The implementation shall be replaceable.

---

# 18. Canonical Command Schema

All mobile-to-edge commands shall use a versioned protocol.

Example:

```json
{
  "version": 1,
  "message_id": "cmd-001",
  "type": "COMMAND",
  "timestamp": 1791123456789,
  "payload": {
    "command": "MOVE",
    "parameters": {
      "direction": "FORWARD",
      "distance_m": 10
    },
    "confidence": 0.96
  }
}
```

---

# 19. Message Types

The protocol shall initially support:

### Mobile → Edge

```text
PING
COMMAND
STATUS_REQUEST
CANCEL
```

### Edge → Mobile

```text
PONG
ACK
COMMAND_STATUS
STATUS
ERROR
```

---

# 20. Message Envelope

Every message shall use:

```json
{
  "version": 1,
  "message_id": "unique-id",
  "type": "COMMAND",
  "timestamp": 1791123456789,
  "payload": {}
}
```

## Fields

| Field | Description |
|---|---|
| `version` | Protocol version |
| `message_id` | Unique message identifier |
| `type` | Message type |
| `timestamp` | Message creation timestamp |
| `payload` | Message-specific data |

---

# 21. Command Message

Example:

```json
{
  "version": 1,
  "message_id": "cmd-001",
  "type": "COMMAND",
  "timestamp": 1791123456789,
  "payload": {
    "command": "MOVE",
    "parameters": {
      "direction": "FORWARD",
      "distance_m": 10
    },
    "confidence": 0.96
  }
}
```

---

# 22. Acknowledgement

The Edge AI shall immediately acknowledge receipt.

```json
{
  "version": 1,
  "message_id": "ack-001",
  "type": "ACK",
  "timestamp": 1791123456790,
  "payload": {
    "request_id": "cmd-001",
    "status": "RECEIVED"
  }
}
```

`RECEIVED` means only that the Edge computer received the message.

It does not mean that the requested action was executed.

---

# 23. Command Status

The Edge AI may return:

```text
RECEIVED
PROCESSING
ACCEPTED
REJECTED
EXECUTING
COMPLETED
FAILED
CANCELLED
```

Example:

```json
{
  "version": 1,
  "message_id": "status-001",
  "type": "COMMAND_STATUS",
  "timestamp": 1791123456800,
  "payload": {
    "request_id": "cmd-001",
    "status": "ACCEPTED"
  }
}
```

The exact execution semantics are owned by the Edge AI system.

---

# 24. Duplicate Command Protection

The communication protocol shall prevent accidental duplicate execution.

Example failure:

```text
Mobile
  ↓
MOVE 10m
  ↓
Edge receives
  ↓
ACK lost
  ↓
Mobile retries
```

The Edge AI must recognize:

```text
message_id = cmd-001
```

as an already processed request.

The Edge should return the existing command state rather than treating the retry as a new command.

This requirement is mandatory before flight integration.

---

# 25. Command Queue

The mobile application shall maintain a lightweight outgoing command queue.

Each command should track:

```text
message_id
created_at
state
retry_count
last_attempt
```

The application should not blindly retransmit commands.

Commands that require stronger execution semantics should be controlled by the Edge AI protocol.

---

# 26. Flutter Architecture

Recommended project structure:

```text
lib/
│
├── core/
│   ├── constants/
│   ├── errors/
│   ├── logging/
│   └── network/
│
├── features/
│   │
│   ├── voice/
│   │   ├── data/
│   │   ├── domain/
│   │   └── presentation/
│   │
│   ├── language/
│   │   ├── data/
│   │   └── domain/
│   │
│   ├── intent/
│   │   ├── data/
│   │   └── domain/
│   │
│   ├── edge_connection/
│   │   ├── data/
│   │   ├── domain/
│   │   └── presentation/
│   │
│   └── command_history/
│
├── shared/
│   ├── models/
│   └── widgets/
│
└── main.dart
```

---

# 27. Transport Abstraction

The Flutter application shall isolate the communication implementation.

Conceptual interface:

```dart
abstract class EdgeTransport {
  Future<void> connect();

  Future<void> disconnect();

  Future<void> send(EdgeMessage message);

  Stream<EdgeMessage> get messages;

  Stream<ConnectionState> get connectionState;
}
```

The initial implementation shall be:

```text
WebSocketEdgeTransport
```

Future implementations could be:

```text
UdpEdgeTransport
QuicEdgeTransport
```

without changing the rest of the application.

---

# 28. Voice Engine Abstraction

```dart
abstract class SpeechEngine {
  Future<void> initialize();

  Future<Transcript> transcribe(AudioData audio);

  Future<void> dispose();
}
```

This allows STT models to be replaced without changing the command or communication layers.

---

# 29. Intent Engine Abstraction

```dart
abstract class IntentEngine {
  Future<DroneIntent> parse(
    String transcript,
    String language,
  );
}
```

The application shall keep the intent engine independent from the transport layer.

---

# 30. Main User Interface

The MVP should have a minimal voice-first interface.

```text
┌────────────────────────────────┐
│          AERONEX ONE             │
│                                 │
│       EDGE AI CONNECTED         │
│          18 ms                  │
│                                 │
│             🎙                  │
│                                 │
│          TAP TO TALK            │
│                                 │
│  "10 meters forward"            │
│                                 │
│  MOVE • FORWARD • 10m           │
│                                 │
│        COMMAND SENT             │
│                                 │
│  Connection       ● Connected   │
└────────────────────────────────┘
```

The interface shall expose:

- Connection status
- Edge device name
- Latency
- Microphone control
- Recognized text
- Detected language
- Parsed command
- Command status
- Recent command history

---

# 31. Connection Screen

The application shall provide a connection screen for:

- Wi-Fi status
- Edge discovery
- Available Edge devices
- Manual IP fallback
- Connection state
- Latency
- Reconnect

Example:

```text
AERONEX EDGE

● Found

Device:
AERONEX-EDGE-01

IP:
192.168.4.1

WebSocket:
8765

[ CONNECT ]
```

---

# 32. Offline Requirements

Normal operation shall not require:

- Internet
- Cloud STT
- Cloud translation
- Cloud LLM
- Remote API
- External server

Required local components:

```text
Phone
 ├── STT model
 ├── Intent/translation model
 └── Communication client

Drone
 └── Edge AI computer
     └── Communication server
```

---

# 33. Privacy

Voice data should remain on the mobile device.

The system shall not upload raw microphone recordings to external services during normal operation.

The application may store:

- Transcript
- Detected language
- Parsed command
- Command status

locally for diagnostics.

Raw audio storage should be disabled by default.

---

# 34. Security Requirements

The communication system shall:

- Use a protected Wi-Fi network.
- Authenticate the Edge endpoint where practical.
- Validate protocol version.
- Validate message structure.
- Reject malformed messages.
- Reject unsupported message types.
- Use unique message IDs.
- Prevent duplicate command execution.
- Avoid arbitrary code execution.
- Support encrypted transport where appropriate.

For the MVP, the local Wi-Fi network should use a strong password.

---

# 35. Performance Requirements

Target values:

| Metric | Target |
|---|---:|
| Microphone activation | < 300 ms |
| Local STT | < 2–3 seconds |
| Intent processing | < 500 ms |
| Command serialization | < 50 ms |
| Mobile → Edge network latency | < 100 ms target |
| PING/PONG round trip | < 100 ms target |
| UI response | < 100 ms |
| Reconnection start | < 2 seconds |
| Normal operation | Offline |

These are engineering targets and must be benchmarked on actual hardware.

---

# 36. Reliability Requirements

The application shall:

- Detect connection loss.
- Automatically attempt reconnection.
- Avoid duplicate commands.
- Track command IDs.
- Handle Edge restart.
- Handle phone Wi-Fi reconnection.
- Handle temporary packet/network loss.
- Provide visible connection state.

The application shall never display `CONNECTED` merely because Wi-Fi is available.

The Edge WebSocket session must be established and heartbeat-confirmed.

---

# 37. Edge Computer Requirements

The Edge computer shall:

1. Provide Wi-Fi connectivity.
2. Host the local communication server.
3. Advertise its service for discovery.
4. Accept authenticated client connections.
5. Parse protocol messages.
6. Return acknowledgements.
7. Return command status.
8. Detect duplicate message IDs.
9. Maintain Edge connection state.
10. Forward received commands internally to the Edge AI subsystem.

The Edge implementation may initially use Python.

Recommended MVP stack:

```text
Python
+
FastAPI / Starlette
+
WebSocket
+
mDNS / Zeroconf
```

---

# 38. Edge Communication Server

Initial architecture:

```text
edge/
│
├── app/
│   ├── server.py
│   ├── websocket.py
│   ├── protocol.py
│   ├── discovery.py
│   ├── commands.py
│   ├── state.py
│   └── config.py
│
├── tests/
│
├── requirements.txt
└── README.md
```

The communication server should remain separate from the Edge AI models.

```text
WebSocket Server
       ↓
Command Router
       ↓
Edge AI
```

---

# 39. Telemetry / Edge Status

The current mobile application does not need to implement full flight telemetry.

It should initially receive Edge status:

```json
{
  "edge": "READY",
  "version": "0.1.0",
  "uptime_ms": 123456,
  "ai_status": "READY"
}
```

Future versions may expose:

- Drone telemetry
- Battery
- GPS
- Altitude
- Flight mode
- Vision status
- AI workload
- Temperature
- Edge CPU/GPU utilization

---

# 40. Error Handling

## STT Failure

```text
Could not understand speech.
```

## Unsupported Language

```text
This language is not currently supported.
```

## Intent Failure

```text
Could not determine the requested command.
```

## Edge Not Found

```text
No AeroNex Edge computer found.
```

## Connection Failure

```text
Unable to connect to AeroNex Edge.
```

## Connection Lost

```text
Connection to Edge AI lost.
Reconnecting...
```

## Edge Rejection

```text
Command rejected by Edge AI.
```

---

# 41. Testing Strategy

## 41.1 Unit Tests

Test:

- Protocol serialization
- Protocol deserialization
- Message validation
- Command generation
- Intent parsing
- Language handling
- Connection state
- Retry logic
- Duplicate detection

## 41.2 STT Tests

For each supported language, create a command dataset.

Initial target:

```text
100 voice commands per language
```

Measure:

- Word Error Rate
- Intent Accuracy
- Parameter Accuracy
- Language Detection Accuracy
- Latency

## 41.3 Communication Tests

Test:

- Connect
- Disconnect
- Reconnect
- Edge restart
- Phone Wi-Fi reconnect
- Packet loss
- Duplicate messages
- Delayed ACK
- Invalid messages

---

# 42. Range Testing

The actual system shall be tested at:

```text
5 m
10 m
15 m
20 m
25 m
30 m
```

Testing shall be performed:

1. On the ground.
2. With final Edge computer.
3. With final Wi-Fi hardware.
4. With final antenna placement.
5. With the drone powered normally.
6. With the drone moving where practical.

Measure:

- Connection stability
- Round-trip latency
- Packet loss
- Reconnection behavior
- Command delivery success

The 30 m / ~100 ft target should be treated as an engineering benchmark rather than an assumed guaranteed range.

---

# 43. Development Phases

## Phase 1 — Communication Prototype

Build:

```text
Flutter
    ↓
Wi-Fi
    ↓
WebSocket
    ↓
Python Edge Server
```

Implement:

- Connect
- Disconnect
- Ping
- Pong
- Command
- ACK
- Status

No STT yet.

---

## Phase 2 — Connection Discovery

Implement:

```text
mDNS / Zeroconf
```

Flow:

```text
Phone
 ↓
Discover AERONEX-EDGE
 ↓
Connect
 ↓
Heartbeat
```

---

## Phase 3 — Local STT

Implement:

```text
Microphone
 ↓
Local STT
 ↓
Transcript
```

Initial languages:

- English
- Telugu
- Hindi

---

## Phase 4 — Translation / Intent

Implement:

```text
Transcript
 ↓
Language identification
 ↓
Translation / normalization
 ↓
Intent
 ↓
Canonical command
```

---

## Phase 5 — Integrated Voice Communication

Complete:

```text
Voice
 ↓
STT
 ↓
Intent
 ↓
Canonical Command
 ↓
Wi-Fi
 ↓
Edge AI
 ↓
ACK
 ↓
Mobile UI
```

---

# 44. MVP Acceptance Criteria

The MVP shall be considered successful when:

### Connectivity

- The phone discovers the Edge computer.
- The phone connects over local Wi-Fi.
- Connection survives normal operation.
- Reconnection works after temporary disconnection.

### STT

- English speech is recognized locally.
- Telugu speech is recognized locally.
- Hindi speech is recognized locally.

### Command Processing

A spoken command such as:

> Move forward 10 meters.

produces:

```json
{
  "command": "MOVE",
  "parameters": {
    "direction": "FORWARD",
    "distance_m": 10
  }
}
```

### Communication

The structured command reaches the Edge computer.

### Reliability

The Edge returns an ACK containing the request ID.

### Latency

The mobile-to-edge network round trip should meet the defined latency target under normal local-network conditions.

### Offline

The complete workflow works without Internet access.

---

# 45. Example End-to-End Workflow

User speaks:

> "డ్రోన్‌ను 10 మీటర్లు ముందుకు పంపు"

### Mobile

```text
Microphone
   ↓
Local STT
   ↓
"డ్రోన్‌ను 10 మీటర్లు ముందుకు పంపు"
   ↓
Intent Engine
   ↓
MOVE / FORWARD / 10m
```

### Canonical Message

```json
{
  "version": 1,
  "message_id": "cmd-001",
  "type": "COMMAND",
  "payload": {
    "command": "MOVE",
    "parameters": {
      "direction": "FORWARD",
      "distance_m": 10
    }
  }
}
```

### Network

```text
Flutter
   │
   │ WebSocket over local Wi-Fi
   ▼
Drone Edge Computer
```

### Edge

```text
Message received
       ↓
ACK
       ↓
Command Router
       ↓
Edge AI
```

### Mobile

```text
EDGE: RECEIVED
```

No MAVLink is involved in this application phase.

---

# 46. Final Architecture

```text
                         USER
                           │
                           │
                    Voice Command
                           │
                           ▼
              ┌────────────────────────┐
              │     FLUTTER MOBILE     │
              │                        │
              │  Microphone            │
              │      ↓                 │
              │  Local Multilingual    │
              │  STT                   │
              │      ↓                 │
              │  Language Processing   │
              │      ↓                 │
              │  Intent Engine         │
              │      ↓                 │
              │  Canonical Command     │
              │      ↓                 │
              │  WebSocket Client      │
              └───────────┬────────────┘
                          │
                          │
                    LOCAL WI-FI
                    2.4 GHz target
                          │
                          ▼
              ┌────────────────────────┐
              │     DRONE EDGE AI      │
              │                        │
              │  Wi-Fi Access Point    │
              │      ↓                 │
              │  mDNS Discovery        │
              │      ↓                 │
              │  WebSocket Server      │
              │      ↓                 │
              │  Protocol Parser       │
              │      ↓                 │
              │  Command Router        │
              │      ↓                 │
              │  Edge AI               │
              └────────────────────────┘
```

---

# 47. Architectural Rules

The following rules are mandatory for the initial implementation:

1. **The mobile application shall not depend on Internet connectivity.**
2. **Raw voice shall be processed locally.**
3. **The mobile application shall send structured commands, not raw audio.**
4. **The Edge AI shall receive a language-independent command representation.**
5. **The mobile application shall not generate MAVLink packets in this phase.**
6. **The Edge communication server shall be independent of the Edge AI model implementation.**
7. **Every command shall have a unique message ID.**
8. **The Edge shall suppress duplicate commands.**
9. **The communication layer shall provide ACK and status messages.**
10. **The connection shall use heartbeat monitoring.**
11. **The application shall automatically reconnect after temporary connection loss.**
12. **The transport layer shall be abstracted so WebSocket can be replaced later.**
13. **The initial wireless target shall be approximately 50–100 feet / 15–30 meters.**
14. **The actual RF range shall be established through hardware testing rather than assumed.**
15. **Flight-controller and MAVLink integration shall be implemented separately from this mobile communication project.**

---

# 48. Immediate Development Deliverable

The first implementation milestone is intentionally small:

```text
                  MILESTONE 1

Flutter Mobile
      │
      │ Wi-Fi
      ▼
WebSocket
      │
      ▼
Python Edge Server

Features:
✓ Discover / connect
✓ PING
✓ PONG
✓ COMMAND
✓ ACK
✓ STATUS
✓ Reconnect
✓ Message IDs
✓ Duplicate protection
✓ Latency measurement
```

After this communication layer is stable:

```text
Local STT
    ↓
Multilingual processing
    ↓
Intent Engine
    ↓
Canonical Command
    ↓
Existing communication layer
```

This keeps the project modular and prevents the voice/AI implementation from becoming coupled to the wireless protocol.
