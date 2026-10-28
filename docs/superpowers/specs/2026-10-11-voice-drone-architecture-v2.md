# AeroNex v2: final architecture for offline, low-latency, multilingual voice drone control

Date: 2026-10-11. Status: **proposed for team ratification (revision 2)**. Owner: lead architect.

Supersedes the architecture sections of [mobile-local control](2026-10-08-mobile-local-control-design.md) (speech stack, LLM interpretation, Dart MAVLink path, ARM64-only target, "STOP = hold, needs confirmation"). Keeps, unchanged, the plan semantics in [multi-step plans](2026-10-11-multi-step-plans-design.md) (1–5 steps, one confirmation, 15 s expiry, no retries or replay of plan steps). Based on the winning "Floor-First" design, with the judges' grafts from the "Hybrid" design and their corrections applied. Section 4.0 lists every disagreement and how it was resolved.

How to read the numbers. Every phone latency and memory figure in this document is a **target** or an **estimate** unless it is marked *measured*, and it is labelled as such. No published measurement exists for Vosk grammar mode, sherpa-onnx streaming Zipformer or IndicConformer CTC on a Cortex-A53, armv7 or Android Go phone. Phase 0 exists to measure them. "Host-measured" means a desktop experiment (AMD Ryzen 7 6800H, synthetic TTS audio), not a phone. Memory terms: **Private Dirty** and **SwapPss Dirty** are the `dumpsys meminfo` columns for memory lmkd cannot reclaim without killing the process. **File-backed resident** means mapped model-file pages, which the kernel can drop and re-read.

**Revision 2 changes (review round, 2026-10-11).**
- **Tier L memory.** The budget is redefined by metric (hard cap on Private Dirty + SwapPss Dirty, soft cap on file-backed resident pages). The ONNX Runtime (ORT) prepacking options are stated, the Vosk mmap mitigation is removed, and the per-engine numbers are re-derived (4.6, 6.2, 6.3).
- **STOP lane.** STOP no longer overrides a mode the autopilot or pilot chose (4.12, S4). The BRAKE fallback is LAND or ALT_HOLD, not LOITER.
- **Spoken STOP.** Targets are re-based on what the sherpa KWS page states, and the Vosk spotter is defined on partial results. The voice-STOP gap now covers every non-streaming engine, and early finalisation is limited to streaming engines.
- **Languages.** G3 is restated honestly. A new **Phase 2.5** small command model closes the Tier L language gap, with a go/no-go threshold for the Cortex-A53/armv7 slowdown. Tier M English adds Vosk en-in grammar as a candidate.
- **Latency budget.** Adds move, takeoff and post-release-tail rows, and re-balances the Tier L totals.
- **Parser.** Defines an `[unk]` policy, a CTC margin metric, exact-match slots and Vosk n-best.
- **Citations corrected.** 28/28 versus 22/28 comes from the scratchpad host experiment; the repo JSON holds a different 7/8 test.
- **Tiering and devices.** The Go flag alone no longer demotes a phone to Tier L, and reference phone R3 is replaced.
- **Inputs and crew.** Headset PTT becomes press-to-start/press-to-end, and the screen-off claim is removed. A crew model is defined.
- **Link.** Per-transport telemetry profiles come with a signed downlink budget, and the signing scope and SETUP_SIGNING transport rule are clarified.
- **Migration.** Phase 1 is split into 1a (link, executor, STOP) and 1b (speech), and host and x86_64 codec builds are planned.
- **Envelope.** Adds a horizontal home-distance check and a fence-radius audit.
- **Minor fixes:**
  - the one-runtime rule is scoped per tier, and the VAD choice agrees per tier and phase;
  - mis-citations are fixed;
  - Android platform caveats are added;
  - the CRC is filled in the pre-encoded STOP frame;
  - radio-band legality becomes a Phase 2 prerequisite;
  - the floor is aligned with minSdk 26.

---

## The decision in one page

1. **One native engine, a thin UI.** A Kotlin foreground service, `FlightSessionService`, owns the microphone, the speech engine, the parser, the safety supervisor, the executor and the MAVLink link, each on its own thread. Flutter becomes a thin client that renders snapshots and sends intents. It contains no control logic. Whether to rewrite the UI in Compose is decided later, after profiling.
2. **Command recognition, not dictation.** Recognition is constrained to a grammar generated from **one canonical command grammar** covering six languages and code-mixing. Every transcript goes through a strict whole-utterance template parser in Kotlin. The **LLM is removed** from the flight path, along with whisper.cpp, llama.cpp, Cactus and the Android recognizer.
3. **Push-to-talk ends the utterance.** You hold to talk on the on-screen bar or Volume-Up, then release. Release plus a short **adaptive tail** is the end of speech. The tail stops as soon as the VAD hears silence, at most 250 ms, so the last word is never clipped and there is no long silence wait. The model is loaded once per session, not once per command.
4. **Low-end first, honest about languages.** The app ships `armeabi-v7a` and `arm64-v8a`. A Tier L phone (2–3 GB, or 32-bit) keeps **exactly one ML runtime** and one language model resident.
   - **Phase 1b engines:** Vosk small grammar models for en-IN, hi and te, and IndicConformer-120M CTC through sherpa-onnx for ta, kn and ml (and as the te/hi accuracy path on mid-range phones).
   - **Consequence on 2 GB phones:** voice for ta, kn and ml, and possibly te, is **not expected before Phase 2.5**. Phase 2.5 is a small closed-vocabulary multilingual command model built to close that gap.
   - **Phase 3** replaces everything with one multilingual streaming Zipformer2 on a single runtime.
5. **A STOP lane that bypasses speech recognition, the parser and confirmation.**
   - **Triggers:** an always-visible STOP button, Volume-Down, the notification action, a voice stop spotter, a stop token in a streaming partial, or Cancel during execution.
   - **When the app owns the current flight mode:** airborne, STOP sends BRAKE, falling back to LAND. Armed on the ground, it sends a non-forced disarm. No confirmation.
   - **When the autopilot or the pilot set the mode** (failsafe LAND/RTL, RC switch): voice triggers are off, and a physical STOP says "use RC" unless the operator deliberately holds STOP for 1 s.
6. **A real vehicle link, a real crew.** UDP, USB-OTG serial (SiK or ELRS) and TCP share one transport interface. MAVLink 2 signing covers every link that reaches a flight-controller (FC) telemetry port. Mode changes are confirmed by COMMAND_ACK. Moves use body-frame offsets that the FC resolves. Real hardware stays locked until a parameter audit passes (GCS failsafe, fence radius and altitude, RC failsafe, sysid filter, signing, stream rates). It also needs an RC pilot with hands on the sticks (a two-person crew by default).
7. **Measure first, ship safety first.** Phase 0 ships the two dangerous fixes (STOP without confirmation, and Cancel sends BRAKE instead of disconnecting), and it benchmarks every engine on named low-end phones. Phase 1a then ships the native link, executor and STOP lane **before** any speech change.

---

## 1. Goals, non-goals, assumptions

### 1.1 Goals

| ID | Goal | Target (ratify in Phase 0) |
|---|---|---|
| G1 | Low command latency | Push-to-talk release to validated plan on screen, Tier L streaming path: **p50 ≤ 200 ms, p95 ≤ 450 ms**. The p95 is provisional; Phase 0 data sets the ratified value. Tier M CTC path: p50 ≤ 300 ms, p95 ≤ 600 ms. On Wi-Fi: confirm to COMMAND_ACK ≤ 150 ms p95; confirm to move-target echo ≤ 300 ms p95; confirm to motion onset ≤ 500 ms p95 (ratified from SITL). (Section 5) |
| G2 | Fast emergency stop | STOP button or Volume-Down to BRAKE ACK: **p95 ≤ 200 ms on Wi-Fi**. Spoken "stop" (push-to-talk not held) to BRAKE ACK: p95 ≤ 600 ms with the Vosk stop grammar or the sherpa zh-en chunk-8 KWS; ≤ 650 ms if only the gigaspeech chunk-16 KWS is usable. |
| G3 | Every phone, Android 8+ | STOP, link, tap pad and typed commands work on every supported phone. **Voice on Tier L:** en-IN and hi from Phase 1b, if the Vosk path fits the Tier L memory cap (measured in Phase 0). te only if the Phase 0 Telugu engine passes the corpus gates (10.2). ta, kn and ml are **expected to need the Phase 2.5 command model**, because the IndicConformer gate (6.3) is likely to fail on Cortex-A53/armv7 (R17). **Voice on Tier M/H:** all six languages from Phase 1b. |
| G4 | Offline-first | No network is needed for any flight function. Online paths are optional, opt-in and never in the safety path. |
| G5 | Operator in control | Voice can only select a closed allowlist intent. Every motion plan needs one confirmation. The RC pilot and the onboard failsafes outrank the phone, and the phone never overrides a failsafe without an explicit second action. |
| G6 | Incremental migration | Each phase (0, 1a, 1b, 2, 2.5, 3) ships on its own, reuses the v0.2.0 tests, and runs SITL CI. |

### 1.2 Non-goals

- Open-ended dictation, conversation, or an LLM anywhere in the voice-to-MAVLink path.
- Waypoint missions, loops, conditional plans, plans longer than 5 steps, swarms, or more than one vehicle.
- A wake word for flight commands. Push-to-talk is the activation (Section 4.4).
- Motor kill or flight termination from the phone. That belongs only on an RC spring switch.
- PX4 Offboard mode. The PX4 adapter, when it comes, uses Hold plus DO_REPOSITION.
- Bluetooth as a control link (view-only telemetry at most), cloud dependency, and iOS.

### 1.3 Assumptions

- **Autopilot:** ArduCopter over MAVLink 2, tested today against SITL plus Gazebo ([simulation report](../../testing/2026-10-10-simulation.md)). PX4 comes later through an adapter. Parameter names are checked against the firmware actually flown.
- **Crew model (real hardware).**
  - **Default: a two-person crew.** The **RC pilot** keeps both hands on the transmitter and is the highest authority (8.1). The **voice operator** holds the phone or wears a close-talk wired or USB-C headset, speaks, confirms on screen and watches the plan card.
  - **Single-operator mode** (optional, Phase 2, after user trials):
    - The phone sits on a transmitter mount.
    - STOP is an RC flight-mode switch position set to BRAKE (`FLTMODEn = 17`), or an RC switch assigned the Brake aux function ([aux functions](https://ardupilot.org/copter/docs/common-auxiliary-functions.html)). It is handled **onboard**, with no phone in the loop.
    - PTT is a second RC aux switch, read from `RC_CHANNELS` telemetry. It adds up to one telemetry interval of latency, so it is requested at 10 Hz. It is offered on Wi-Fi links only, because the ELRS and SiK downlink budget cannot carry it (4.15).
    - Confirm stays on the screen.
  - **Both modes:** motor kill stays on the RC side. SITL and simulator use need no crew.
- **Commands** are short (about 1–4 s).
- **Platform:** `minSdk 26` (Android 8.0), `targetSdk 36`, NDK r28 (16 KB-aligned 64-bit libraries, [Android page sizes](https://developer.android.com/guide/practices/page-sizes)). CPU inference only. NNAPI is deprecated ([NDK NNAPI](https://developer.android.com/ndk/guides/neuralnetworks)), and the LiteRT NPU path is arm64-only ([LiteRT NPU](https://developers.google.com/edge/litert/next/npu)).
- **Connectivity:** often none in the field. Models are provisioned before a flight.

### 1.4 Low-end tier definition (the floor)

The **floor device** has:

- 2 GB of RAM;
- Android 8.0 or later (`minSdk 26`), possibly Android Go edition;
- possibly a **32-bit-only userspace (`armeabi-v7a`)** on a 64-bit SoC;
- 4–8 Cortex-A53/A55 cores;
- no usable GPU or NPU acceleration.

The Android CDD strongly recommends 32-bit-only userspace for 2–4 GB phones ([Android 16 CDD](https://source.android.com/docs/compatibility/16/android-16-cdd)). A secondary report (GSMArena, 15 April 2025) says GMS phones with 2–3 GB must ship as Go from Android 15, and **4 GB phones must also ship as Go from Android 16** ([GSMArena](https://www.gsmarena.com/here_are_googles_new_minimum_ram_and_storage_requirements_for_android_phones-news-67387.php)). Two consequences:

- "Low-end" includes **new** phones, not only old ones.
- The Go flag by itself no longer means "2 GB phone", so tiering cannot rest on it (6.1).

lmkd kills sooner on low-RAM devices ([lmkd](https://source.android.com/docs/core/perf/lmkd)), so private dirty memory is the scarcest resource. Tiers are defined exactly in Section 6.

---

## 2. Why the current architecture falls short, and what is kept

### 2.1 Audit findings (v0.2.0, `feat/aeronex-skeleton`)

| # | Problem | Evidence | Consequence | v2 fix |
|---|---|---|---|---|
| 1 | No foreground service. Control lives in the Flutter UI. | [`AndroidManifest.xml`](../../../app/android/app/src/main/AndroidManifest.xml) declares no `<service>`. The GCS heartbeat is a Dart `Timer.periodic` ([`mavlink_transport.dart:185`](../../../app/lib/features/flight/data/mavlink_transport.dart)). | Android 14+ freezes a cached app about 10 s after it is cached ([cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)). Backgrounding silently stops the heartbeat, telemetry and control. | `FlightSessionService` (4.1) |
| 2 | Dart sits in the control path, and every datagram makes a JSON round trip. | The transport uses `MethodChannel('aeronex/mavlink')` ([`mavlink_transport.dart:109`](../../../app/lib/features/flight/data/mavlink_transport.dart)). The codec parses JSON (`aeronex::json::parse`, [`mavlink_codec.cpp:9`](../../../app/android/app/src/main/cpp/mavlink_codec.cpp)). Since Flutter 3.29, Dart runs on the Android main thread ([Flutter 3.29](https://flutter.dev/blog/whats-new-in-flutter-3-29)). | UI jank and GC delay MAVLink. Encode and decode cost scales with telemetry rate. | Native link with a buffer-level JNI API (4.13) |
| 3 | Mode changes are confirmed by polling for a heartbeat. | Polls every 200 ms ([`mavlink_transport.dart:276`](../../../app/lib/features/flight/data/mavlink_transport.dart)). HEARTBEAT arrives at 1 Hz. | Up to about 1 s of wait per mode change, and a takeoff needs several. | Confirm on COMMAND_ACK. ArduPilot ACKs DO_SET_MODE after `set_mode()` succeeds ([GCS_Common.cpp](https://github.com/ArduPilot/ardupilot/blob/master/libraries/GCS_MAVLink/GCS_Common.cpp)) (4.14) |
| 4 | **Unsafe Cancel and STOP.** | `_cancel()` calls `_flight.disconnect()` during execution ([`local_control_screen.dart:533-549`](../../../app/lib/features/flight/presentation/local_control_screen.dart)). `hold` (BRAKE 17) goes through the same 15 s confirmation as any other command ([`mavlink_transport.dart:329-333`](../../../app/lib/features/flight/data/mavlink_transport.dart)). | On a real vehicle FS_GCS_ENABLE defaults to 0 ([Copter Parameters.cpp](https://raw.githubusercontent.com/ArduPilot/ardupilot/master/ArduCopter/Parameters.cpp)), and GUID_TIMEOUT does not apply to position targets ([Guided mode](https://ardupilot.org/copter/docs/ac2_guidedmode.html)). The drone **finishes its move** while the operator thinks it has stopped. | STOP lane (4.12); Phase 0 hotfix |
| 5 | ARM64-only build. | `abiFilters += listOf("arm64-v8a")` ([`build.gradle.kts:26-31`](../../../app/android/app/build.gradle.kts)). Release APK 38,379,493 B (*measured*, built 2026-10-11). | Cannot install on 32-bit-userspace phones, which are part of the low-end target. | `armeabi-v7a` + `arm64-v8a` (4.19) |
| 6 | A heavy native stack: four speech engines plus an LLM. | Vosk, whisper.cpp, Cactus Whistle, the Android recognizer, and llama.cpp with LFM2.5-VL-450M Q4 (229,313,568 B, [device matrix](../../testing/mobile-device-matrix.md)). whisper tiny needs about 273 MB RAM and does not truly stream ([whisper.cpp](https://github.com/ggml-org/whisper.cpp)). | Too heavy for 2 GB phones. Whisper is slow and non-streaming. The LLM was semantically unreliable ([README](../../../README.md)). | Remove all of them (4.20) |
| 7 | The model is loaded before every command. | README: "Vosk/Whisper/LFM load per operation". Host-measured load times: 256 ms (en-in), 499 ms (hi), 718 ms (te) (scratchpad `vosk_load.py`). | Hundreds of ms on a desktop, likely much more on a Cortex-A53 (unmeasured), added to every command. | A model resident for the whole session (4.6) |
| 8 | Open-vocabulary ASR, then parsing. | Vosk te-0.42 WER is 87.9 on FLEURS ([Vosk models](https://alphacephei.com/vosk/models)). Host experiment (en-in model, 28 TTS clips; session scratchpad `vosk_bench.py`, to be committed under `tools/bench/`): 22/28 exact in open vocabulary versus 28/28 in grammar mode. The repo's own 8-utterance test ([grammar evaluation](../../testing/2026-10-09-vosk-grammar-evaluation.json)) got 7/8 hinted transcripts right. It returned `[unk] hold position` for a valid command, and decoded the out-of-grammar "What is the weather in Delhi" as `[unk] what is the [unk] fly`. | Misrecognitions reach the parser and the LLM fallback. | Grammar-constrained recognition, strict templates and an explicit `[unk]` policy (4.6–4.9) |
| 9 | Too many taps. | Speak → Finish → Confirm ([README](../../../README.md)). | Slow, and not usable with gloves or with eyes on the drone. | Hold → release → Confirm (4.4) |
| 10 | Mic loop jitter, and no pre-roll or post-roll. | `READ_NON_BLOCKING` + `Thread.sleep(10)` ([`LocalInferenceBridge.kt:381-383`](../../../app/android/app/src/main/kotlin/com/aeronex/app/LocalInferenceBridge.kt)). | Up to about 10 ms of jitter. A late press clips the first syllable, and an early release clips the last word. | Blocking reads, ring buffer, 500 ms pre-roll and an adaptive post-release tail (4.2) |
| 11 | Absolute move targets computed on the phone. | `telemetry.target(direction, distance)`, then an absolute LOCAL_NED target ([`mavlink_transport.dart:334-350`](../../../app/lib/features/flight/data/mavlink_transport.dart)). Mode is set to GUIDED again before every move. | Stale phone telemetry gives a wrong target, and every move costs an extra round trip. | BODY_OFFSET_NED resolved by the FC (4.14) |
| 12 | One transport, unbound. | `RawDatagramSocket.bind(anyIPv4)` ([`mavlink_transport.dart:153`](../../../app/lib/features/flight/data/mavlink_transport.dart)). No USB serial, no signing. | Android routes traffic around Wi-Fi networks without internet ([Wi-Fi device apps](https://android-developers.googleblog.com/2016/07/connecting-your-app-to-wi-fi-device.html)). No path to real hardware. | Transport interface, MAVLink socket binding, signing (4.13) |
| 13 | A monolithic screen. | `local_control_screen.dart` is 1,479 lines. | Hard to test, and safety logic is mixed into widgets. | Modules (4.17) |
| 14 | Legacy Python edge demo. | [`edge/app/server.py`](../../../edge/app/server.py) is not on the flight path. | Maintenance noise. | Archived. Its protocol code is reused by the optional assist node (4.21). |

### 2.2 What is kept

- **Plan semantics and their fixtures.**
  - 1–5 steps and connective splitting.
  - Takeoff only first, with an explicit 1–10 m height. Land only last. Status on its own.
  - Moves > 0 and ≤ 10 m. Projected altitude 1–10 m. No invented takeoff.
  - Rejection of negation and of non-metre units.
  - One-use confirmation bound to the plan, with a 15 s expiry and the existing invalidation rules.
  - The outcome states: COMPLETED, NOT SENT, REJECTED, UNKNOWN, SKIPPED. No retries or replay of steps.
  - Dry run by default, and a per-connection execution switch that is never saved.
  
  Source: [`rule_intent_engine.dart`](../../../app/lib/features/intent/data/rule_intent_engine.dart) (884 lines), [`plan_interpreter.dart`](../../../app/lib/features/flight/application/plan_interpreter.dart) (889 lines), and their tests.
- **The pinned mavgen C `c_library_v2` codec** and its CTest (`tools/check_mavlink_native.cpp`). Its API changes from JSON to buffers.
- **`VerifiedModelStore.kt` and `LocalModelInventory.kt`**: SHA-256 and size checks, safe publication, restart recovery, and import from a file.
- **The Vosk integration** (`VoskSpeechRecognizer.kt`, `BufferedSpeechDecoder.kt`), the JNA R8 keep rules, and their APK contract check.
- **`tools/check_sitl.py`**, the WSL ArduCopter SITL plus Gazebo setup, and `tools/check_android_package.py`, extended for multiple ABIs.
- Saved SITL settings, typed commands, HistoryStore, and the readable vehicle status line.

---

## 3. Architecture overview

### 3.1 End-to-end flow

```
+------------------------------------ Android phone (one process) -------------------------------------+
|  UI client: Flutter thin shell now (Compose only if the Section 4.17 gate fires)                      |
|   [ STOP ]   [ hold-to-talk ]   plan card + [ CONFIRM ] + expiry   status line   tap pad / typed      |
|        | intents: pttDown/pttUp, confirm(token), cancel, stop          ^ immutable snapshots 5-10 Hz  |
|--------+---------------------------------------------------------------+------------------------------|
|  FlightSessionService  (Kotlin foreground service, type microphone|connectedDevice)                   |
|                                                                                                       |
|  [mic thread] AudioRecord VOICE_RECOGNITION 16 kHz -> 20 ms frames -> ring buffer (>=1 s, 500 ms pre-roll)
|        silenced-capture detector -> "No voice" level  |                          |                    |
|              (PTT held + adaptive post-release tail)  v                          v (PTT not held,     |
|  [decode thread] command recognizer (ONE runtime on Tier L)        stop spotter   VAD-gated, pre-roll |
|     Vosk grammar | sherpa CTC+lexicon | (Ph2.5/3) streaming cmd      Vosk stop grammar (partials)     |
|        | partials (streaming engines only) -- stop-token check ---+  | sherpa KWS zh-en chunk-8       |
|        v final after release + tail                                 |              |                  |
|  normaliser (NFC, script cleanup, loanwords, number tables)         |              |                  |
|        v                                                            |              |                  |
|  strict template parser ([unk] policy, exact slots) -> PlanBuilder -> PlanValidator(VehicleState,     |
|        v                                                            |   home distance)                |
|  [supervisor actor, single thread]  SafetySupervisor  <-------------+--------------+                  |
|        | PendingPlan(token, 15 s)          | stop()   <--- STOP button / Volume-Down / notification /   |
|        v confirm(token)                    v               Cancel-while-executing                     |
|  Executor (ArduPilot adapter)        STOP lane: mode-owner check (APP | EXTERNAL)                     |
|        |                                   APP: BRAKE (fallback LAND) | DISARM(non-forced) on ground  |
|        |                                   EXTERNAL: "use RC" banner; BRAKE only on 1 s STOP hold     |
|        v                                   v  (jumps the queue, pre-encoded payload)                  |
|  [link TX thread] priority TX queue + 1 Hz GCS heartbeat (never throttled)                            |
|        v                                                                                              |
|  JNI MAVLink codec (pack + CRC + MAVLink 2 sign)   [link RX thread] decode -> VehicleState snapshot   |
|        v                                                      ^  (ACK, mode + mode owner, EKF, pos,   |
|  Transport: UDP (MAVLink socket bound to drone Wi-Fi Network)   |   echo, home, STATUSTEXT, landed)   |
|             | USB-OTG serial | TCP; per-transport telemetry profile                                   |
+--------+------------------------------------------------------+---------------------------------------+
         v                                                      |
   Wi-Fi bridge (ESP32 / ELRS Backpack) or USB telemetry radio (SiK / ELRS MAVLink)
         v                                                      |
   Flight controller: ArduCopter (PX4 adapter later) -----------+
   onboard: GCS failsafe, RC failsafe, battery/EKF failsafe, geofence, BRAKE/LAND, sysid filter, signing
         ^
   RC transmitter (pilot): highest authority, motor-kill spring switch, BRAKE mode switch

   Optional, Phase 4: [AeroNex Assist laptop on the same LAN] receives PTT audio, returns N-best TEXT only;
   the phone re-parses and validates; disagreement means reject; it never sends MAVLink.
```

### 3.2 Session start (once per session, never per command)

The operator taps **Start session** while the app is visible. Android requires a microphone foreground service to start while the app is in use ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types), [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes)). Then:

1. `EnginePolicy` picks the tier, the runtime(s) and one language pack (Section 6).
2. The model is loaded. Vosk loads onto the heap. sherpa-onnx memory-maps external-data weights with the ORT options in 4.6. The command and stop recognizers are pre-created.
3. The link opens. The 1 Hz GCS heartbeat starts, the transport's telemetry profile is requested (4.15), and HOME_POSITION is requested.
4. On a real-hardware profile, the parameter audit runs (Section 8.4).

### 3.3 Command path

1. Push-to-talk down feeds the pre-roll plus live frames to the command recognizer. On streaming engines, every partial is checked for a stop token.
2. On release, capture continues for the adaptive tail (4.2). The last frame is then drained and the final result is taken.
3. The result goes through the normaliser, the strict template parser (with the `[unk]` policy and exact-match slots) and the plan validator (including the home-distance check).
4. STOP, HOLD and status go straight to the supervisor without confirmation. Any other plan becomes a `PendingPlan` with a one-use token and a 15 s expiry.
5. The operator confirms on screen. The executor re-validates each step against a fresh snapshot, then sends it. RX telemetry closes each step.
6. Any of these aborts the plan: an external mode change, a stale link, a fence or failsafe STATUSTEXT, or a thermal or battery limit. Nothing is replayed.

### 3.4 Stop path

Any of the six triggers calls `supervisor.stop()`, which:

1. cancels the plan and invalidates any confirmation;
2. checks who owns the current flight mode (4.12);
3. if the app owns it, sends the state-dependent stop frame to the front of the TX queue, re-sent until an ACK arrives, for at most 3 s;
4. if the autopilot or the pilot owns it, sends nothing and shows "use RC", unless the operator deliberately holds STOP for 1 s.

Physical keys and the notification action reach the service without passing through Flutter. The voice spotter runs on its own thread. Section 4.12 has the details.

---

## 4. Component decisions per layer

### 4.0 Disagreements resolved

| Topic | Floor-First (winner) | Hybrid / judges / review | **Decision** |
|---|---|---|---|
| UI framework | Compose in Phase 2 | Both judges: defer, since a thin Flutter shell already removes Dart from control | **Keep Flutter as the thin client.** Revisit Compose only if the Tier L profiling gate in 4.17 fires. |
| Parser language | Kotlin | C++ "command core" | **Kotlin.** `java.text.Normalizer` gives NFC on API 26. The NDK has no public ICU before API 31, so C++ would have to bundle ICU. The assist node returns text and the phone parses it, so a shared C++ core is not needed. |
| Confirm input | Short tap on the PTT key | On-screen button or headset | **On-screen CONFIRM is the default and the only confirm in Phases 1a/1b.** Hardware-key confirm is an opt-in setting enabled only after user trials (R13). |
| STOP action | BRAKE, never disarm | Judge 2: state-dependent | **App-owned mode, airborne or unknown: BRAKE**, falling back to LAND. **App-owned mode, ON_GROUND and armed: non-forced DISARM.** Never a forced disarm or flight termination. This deliberately changes the 2026-10-08 rule "STOP = hold, never disarm" for the on-ground case only. |
| STOP while the autopilot or pilot owns the mode (review) | Always BRAKE | Review: a GCS BRAKE cancels a failsafe LAND/RTL | **No automatic override.** Voice and partial triggers are disabled. Physical STOP shows "use RC" and sends BRAKE only after an explicit 1 s hold of the on-screen STOP (4.12). |
| BRAKE fallback (review) | LOITER, then LAND | Review: Loiter needs the same position estimate | **LAND (9) by default, or ALT_HOLD (2)** if the GPS-denied SITL case shows it is safer. LOITER is dropped from the chain ([Loiter](https://ardupilot.org/copter/docs/loiter-mode.html)). |
| Move command | BODY_OFFSET_NED, no ACK | DO_REPOSITION through COMMAND_INT, with ACK | **BODY_OFFSET_NED, sent exactly once, never retried** (it is non-idempotent). Verified through the POSITION_TARGET echo, requested at 4–5 Hz while a move is pending. If the echo is missing, the step is UNKNOWN and the plan stops. DO_REPOSITION is compared in Phase 1a SITL. |
| Spoken STOP target | p50/p95 150/300 ms | Judges 300/500 ms; review: KWS floor is 320 ms for gigaspeech | **Vosk stop grammar on partials: 300/500 ms. sherpa zh-en chunk-8: 250/450 ms. gigaspeech chunk-16: 350/550 ms.** All are Phase 1b targets until Phase 0 measures them (Section 5). |
| Voice STOP gap (review) | Implicit | Was documented for ta/kn/ml only | **Generalised:** any language whose engine is non-streaming has no native-language voice STOP and no partial-token STOP (4.12). This weighs in the Phase 0 Telugu decision. |
| Runtimes per session | One per phone | Two on Tier L (Silero plus Vosk) | **Tier L: exactly one ML runtime, a hard rule** (libfvad is not an ML runtime). **Tier M/H: one by default.** A second runtime is allowed only for the Tier H cross-check, and only if it passes the Tier M/H memory cap in Phase 0. |
| Tier L memory metric (review) | ≤ 150 MB TOTAL PSS | Vendor figure: Vosk about 300 MB; review: PSS is the wrong metric | **Hard cap on Private Dirty + SwapPss Dirty, plus a soft cap on file-backed resident pages with a re-fault check, plus the no-lmkd-kill gate** (6.2). |
| Tier boundaries | 2–3 / 4–6 / 6–8+ GB | Inconsistent; review: the Go flag now covers 4 GB phones | **64-bit ABI support plus `totalMem` cut points of 3.3 GiB and 5.5 GiB, plus a first-session benchmark.** The Go flag selects a stricter memory profile; it does not set the tier (6.1). |
| Headset PTT (review) | Hold-to-talk through a MediaSession, also with the screen off | Review: unreliable on Android | **Press-to-start / press-to-end with a timeout**, validated on the field headsets in Phase 2, or dropped. No screen-off claim. |
| Tier L language gap (review) | Wait for Phase 3 | Review: plan a cheaper interim | **Phase 2.5:** a small closed-vocabulary multilingual streaming command model, ahead of the full Zipformer2 (4.7). |
| Tier M English (review) | Zipformer with hotwords | Review: hotwords bias, they do not constrain | **Add Vosk en-in grammar to the Phase 0 bake-off.** English engines are judged on command exact match and false accepts, not on Svarah WER. |
| Phase 1 scope (review) | One large phase | Review: too much in one ship | **1a** ships the link, codec, executor and STOP lane, with the Dart parser behind Pigeon. **1b** ships speech, grammar-gen and the Kotlin parser port. |
| Assist node and cloud | None | Core feature | **Phase 4, optional, recognition-only.** Cloud only through a key-proxy backend with per-session opt-in. |
| BRAKE re-send | Until ACK | Bounded | **Bounded to 3 s**, then a loud "STOP NOT CONFIRMED, use RC" alarm. Onboard FS_GCS covers link loss. |

### 4.1 Process model

**Decision.** One process with a Kotlin `FlightSessionService`: a foreground service of type `connectedDevice` in Phase 1a, then `microphone|connectedDevice` from Phase 1b, with the prerequisite permissions listed in the [FGS types doc](https://developer.android.com/develop/background-work/services/fgs/service-types). The service owns these threads:

- audio capture (`THREAD_PRIORITY_URGENT_AUDIO`)
- speech decode
- link RX
- link TX, which also sends the 1 Hz heartbeat
- a single-threaded supervisor/executor actor, which owns all plan, mode-ownership and vehicle-command state

The UI sends intents (`pttDown`, `pttUp`, `confirm(token)`, `cancel`, `stop`) through Pigeon. It receives immutable `StateFlow` snapshots through an EventChannel, throttled to 5 Hz on Tier L and 10 Hz on Tier M/H.

**Why.**
- It survives backgrounding, because a foreground service keeps the process from being treated as cached ([freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)).
- It keeps foreground priority against lmkd ([lmkd](https://source.android.com/docs/core/perf/lmkd)).
- It takes Dart off the voice-to-MAVLink path ([Flutter 3.29](https://flutter.dev/blog/whats-new-in-flutter-3-29)).
- It puts every safety invariant in one JVM-testable place.

**Notification caveats.**
- On API 33+, the FGS notification and its STOP action are shown only if the user grants `POST_NOTIFICATIONS` ([notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)). The service runs either way.
- Since Android 14, users can dismiss ongoing notifications ([Android 14 behaviour changes](https://developer.android.com/about/versions/14/behavior-changes-all)).
- So the notification STOP action is **best-effort** and never the only STOP. Onboarding requests the permission and explains why.

**Main-thread caveat.** Every Android input path runs on the main thread, and Flutter UI work shares it: touch events, `dispatchKeyEvent`, and `Service.onStartCommand` for the notification action. So:
- STOP handlers do no work beyond posting to the supervisor.
- Volume keys are intercepted in `MainActivity.dispatchKeyEvent` before Flutter sees them.
- Diagnostics records main-thread stalls, and latency S1 in Section 5 is measured under UI load.

The voice spotter is the only STOP path fully off the main thread. The RC transmitter is the real backstop.

**Alternatives.**
- Rejected: keeping control in Dart. It fails on freezing and on jank.
- Rejected: a separate `:remote` process for the service. It adds about one ART heap of memory on Tier L for no safety gain, because the onboard GCS failsafe already covers a process death.

### 4.2 Audio capture

**Decision.**
- `AudioRecord`, source `VOICE_RECOGNITION`, 16 kHz mono PCM16.
- A dedicated thread does **blocking** reads of 20 ms frames into a preallocated lock-free ring buffer holding at least 1 s, including **500 ms of pre-roll**.
- Capture runs for the whole session. No Oboe. No allocations in the loop.
- While the app plays its own prompt audio, capture is muted or the stop spotter ignores it, so the app does not hear itself.
- **Adaptive post-release tail.** On PTT release, capture continues until libfvad reports 60 ms of non-speech, or for at most 250 ms (configurable 150–250 ms).
  - Why: operators often release before the last syllable. Without the tail, the unit word ("…metres") is clipped, the strict template fails, and the operator has to repeat.
  - The tail ends early when the operator released after speaking, so its usual cost is about 60 ms (Section 5, stage 2b).
- **Input latency** (audio still in the HAL and `AudioRecord` buffers at release) is measured in Phase 0 with a loopback click test and budgeted as stage 2a.
- **Spotter pre-roll replay.** When the VAD opens a speech segment for the stop spotter, the last 300 ms of the ring buffer are fed to the spotter first, so the onset of "stop" is not lost.
- **Silenced-capture detection.** On Android 10+, another app can take the microphone; this app keeps running but receives silence ([sharing audio input](https://developer.android.com/media/platform/sharing-audio-input)). Two detectors:
  - an `AudioRecordingCallback` registered before `startRecording()`, checking `isClientSilenced()` (API 29+);
  - a digital-zeros detector (more than 500 ms of all-zero frames) on every API level, because the platform callback may not reach the silenced app.
  
  Either one switches to the **No voice** level (4.11) with a banner.

**Why.**
- The CDD requires 16 kHz mono PCM16 raw capture, with noise suppression and AGC off by default for `VOICE_RECOGNITION` ([CDD](https://source.android.com/docs/compatibility/16/android-16-cdd)).
- Blocking reads remove the current `sleep(10)` jitter.
- Pre-roll lets a press be backdated, so a late press does not clip the first syllable.
- Oboe adds little for 16 kHz speech input ([AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)).

**Headsets.** Recommend a wired or USB-C close-talk headset. The legacy Bluetooth SCO path is 8 kHz mono ([SCO audio](https://source.android.com/docs/core/audio/sco-audio-mgmt)). If SCO is used anyway, open it once per session, never per press.

### 4.3 VAD and endpointing

**Decision, per tier and phase.**

| | Phases 1b–2.5 | Phase 3 |
|---|---|---|
| Tier L | WebRTC VAD through [libfvad](https://github.com/dpirch/libfvad) (BSD-3, not an ML runtime) | [Silero VAD](https://github.com/snakers4/silero-vad) (MIT, about 2 MB) inside sherpa-onnx, by then the only runtime |
| Tier M/H, session runtime Vosk | libfvad | Silero |
| Tier M/H, session runtime sherpa-onnx | Silero inside sherpa-onnx (no extra runtime) | Silero |

- VAD gates the stop spotter, ends the adaptive post-release tail, and (Tier M/H, opt-in) drives hands-free capture.
- [TEN-VAD](https://github.com/TEN-framework/ten-vad) has the best measured low-end numbers: RTF 0.057 on a Galaxy J6+ and 0.049 on an Oppo A3s, and a 373 KB armv7 library. It is adopted **only** if legal clears its [licence's](https://github.com/TEN-framework/ten-vad/blob/main/LICENSE) Agora non-compete conditions.
- **Push-to-talk:** the endpoint is the release plus the adaptive tail.
- **Hands-free (Tier M/H, opt-in):** 300–500 ms of trailing silence, a policy value tuned in the field.
- **Early finalisation applies to streaming engines only** (Vosk grammar, Phase 2.5/3 models). It fires when the partial is already a complete template, unit word included, and has been stable for 2 chunks. Non-streaming engines (IndicConformer CTC, Whisper) produce no partials, so they always wait for the endpoint and then decode the whole segment.

**Why.** End-of-speech wait, not decode time, dominates hands-free latency. Shipped Vosk models wait 0.5 s on rule2: the local `vosk-model-small-hi-0.22/conf/model.conf` sets `rule2.min-trailing-silence=0.5`, and endpoint rules are read in [model.cc](https://github.com/alphacep/vosk-api/blob/master/src/model.cc). sherpa's documented examples wait 1.2–2.4 s ([endpointing](https://k2-fsa.github.io/sherpa/ncnn/endpoint.html)).

### 4.4 Activation: push-to-talk, no wake word

**Decision.**
- **Guaranteed PTT inputs:** the on-screen hold bar, and Volume-Up held while the flight screen is in the foreground. These are hold-to-talk.
- **Headset button** (optional, Phase 2, only after validation on the actual field headsets): **press-to-start / press-to-end**, not hold-to-talk. One press opens an utterance and the next press closes it, followed by the adaptive tail. A 6 s timeout closes it anyway.
  - Hold-to-talk is not offered on the headset, because many Bluetooth headsets send play/pause as an immediate down-and-up pair, so there is no hold to detect.
  - The system or OEM often claims a long press of `KEYCODE_HEADSETHOOK` for the voice assistant.
  - Media buttons go to the active or last-playing media session, which a non-playing app does not reliably hold.
  
  These points are platform knowledge, not web-verified ([media buttons](https://developer.android.com/media/legacy/media-buttons)). **No screen-off claim is made.** If the headsets fail validation, the feature is dropped.
- **Single-operator mode** (1.3): PTT comes from an RC aux switch through `RC_CHANNELS`, on Wi-Fi links only.
- **Volume-Down = STOP**, on any press, in the foreground.
- **Any STOP key press immediately ends PTT** (the utterance is discarded) and runs STOP. Holding both volume keys triggers the system accessibility shortcut ([Android accessibility](https://support.google.com/accessibility/android/answer/7650693)), which apps cannot intercept (platform knowledge). With this rule, pressing both keys still produces STOP. Onboarding tells operators never to hold both keys.
- **Confirm** is the large on-screen CONFIRM button. Short-tap confirm on the PTT key is an opt-in setting enabled only after user trials. If enabled, the tap must be under about 300 ms, and a hold always starts a new utterance and invalidates the pending plan.
- Hands-free mode is opt-in, on Tier M/H only.

**Why.** Release gives a near-zero endpoint, and rotor noise cannot start a capture. The usual flow drops from three taps (Speak, Finish, Confirm) to hold, release, confirm.

**Wake words rejected.**
- [openWakeWord](https://github.com/dscripka/openWakeWord) is English-only, and its models are non-commercial (CC BY-NC-SA).
- Porcupine and Rhino support only Hindi among Indian languages and need online AccessKey validation ([Picovoice FAQ](https://picovoice.ai/docs/faq/general/)). Their free tier ended 30 June 2026 ([HA forum](https://community.home-assistant.io/t/fyi-picovoice-confirmed-free-tier-accesskeys-will-stop-working-after-june-30-2026/1012744)).

### 4.5 Language handling

**Decision.**
- The operator selects a language once, and the choice is saved.
- There is **no automatic language ID in the audio hot path**. Code-mixing is handled by the grammar union (Section 7), not by language ID.
- Switching language reloads one pack. Tier H may keep two packs resident.
- The existing script-based `detect_language` stays, for typed text only.

**Why.** Language ID costs RAM and CPU on 2 GB phones and adds a failure mode. Each language grammar already contains that language's English command loanwords.

### 4.6 Speech runtime policy and Phase 1b recognizers

**Runtime decision.**
- **Target: a single runtime, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** v1.13.x (Apache-2.0, ONNX Runtime CPU, int8). It covers streaming transducers, NeMo CTC, keyword spotting (KWS), VAD and GTCRN, with prebuilt armv7 and arm64 libraries. Native library sizes: armv7 ORT 10 MB plus 2.1 MB JNI; arm64 15 MB plus 3.7 MB ([build docs](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html)).
- **Vosk until Phase 3.** Vosk 0.3.75 is tolerated as a second runtime in the APK **only** for grammar mode in en-IN, hi and te. Its AAR ships an armeabi-v7a `libvosk.so` of 8,985,628 B (*verified* in the AAR, [Maven](https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/)).
- **Runtimes loaded per session:** see the "Runtimes per session" row in 4.0. Tier L is exactly one. Tier M/H is one, except the Tier H cross-check.
- **Phase 3** moves to an operator-reduced ORT build ([ORT mobile](https://onnxruntime.ai/docs/tutorials/mobile)).

**Phase 1b recognizer matrix.** These are defaults; Phase 0 confirms them. "Streaming" decides whether the language gets native voice STOP and partial-token STOP (4.12).

| Language | Tier L | Tier M/H | Streaming on Tier L? |
|---|---|---|---|
| en-IN | Vosk `small-en-in-0.4` grammar (36 MB), if it fits the Tier L cap | **Phase 0 bake-off** on command exact match and false accepts from the corpus (10.2), not Svarah WER, among: Vosk en-in grammar; sherpa streaming Zipformer en-20M (42.8 MB int8 encoder, [HF](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/tree/main)) with hotwords (`modified_beam_search`); Indian-English Zipformer 66M (about 68 MB int8, Svarah WER 27.96%, [HF](https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en)) with hotwords. Hotwords bias decoding; they do not constrain it. | yes |
| hi | Vosk `small-hi-0.22` grammar (42 MB), if it fits the Tier L cap | Vosk-hi grammar or IndicConformer-hi CTC, whichever wins Phase 0 | yes |
| te | **Phase 0 decides** among Vosk `small-te-0.42` grammar (58 MB, streaming), IndicConformer-te CTC (non-streaming) and [whisper-telugu-tiny](https://huggingface.co/vasista22/whisper-telugu-tiny) int8 (99 MB, [packs](https://huggingface.co/ippocode/indic-asr-onnx), non-streaming). Scored on corpus accuracy **and** on the loss of native voice STOP that a non-streaming pick causes. | IndicConformer-te CTC, or Vosk-te grammar if it wins | only if Vosk-te is picked |
| ta, kn, ml | IndicConformer CTC **only if the Tier L gate passes** (6.3), which is unlikely (R17). Otherwise tap pad and typed commands until **Phase 2.5**, plus the assist node in Phase 4. whisper-kannada-tiny (99 MB) is a Phase 0 candidate for kn. | IndicConformer CTC | no (until Phase 2.5) |

**Vosk grammar mode (Tier L streaming path).**
- Load the model once per session and keep it resident.
- Pre-create two recognizers on it: a **command grammar** and a **stop-only grammar**. A grammar recognizer took about 1 ms to create in the host experiment.
- Add **200–2,000 frequent non-command words as single-word filler phrases, plus `[unk]`**.
- Write loanwords in native script.
- **N-best margin:** `Recognizer.setMaxAlternatives(3)` gives a real n-best list, so the margin rule in 4.8 applies to Vosk too (*verify that alternatives are produced in grammar mode*).
- **Partial results:** `setPartialWords(true)` and `getPartialResult()` drive the stop-token check and early finalisation.
- **Endpointer:** `setEndpointerDelays(...)` / `setEndpointerMode(...)` shorten the final-result endpointer for the stop grammar and for hands-free mode.

All of these methods are *verified* present in the vosk-android 0.3.75 Java API: `javap` on `org.vosk.Recognizer` in the local AAR lists `setMaxAlternatives`, `setPartialWords`, `getPartialResult`, `setEndpointerMode` and `setEndpointerDelays`.

**Evidence for grammar mode.**
- **Host experiment** (session scratchpad `vosk_bench.py` and `vosk_filler.py`, to be committed under `tools/bench/` in Phase 0; en-in model, 28 TTS clips of 14 phrases from 2 voices):
  - Grammar mode matched 28/28 clips, versus 22/28 in open vocabulary.
  - Grammar decode RTF was 0.012–0.019, versus 0.127–0.288. FinalResult after the last chunk took 6–11 ms.
  - Without fillers, "take a photo of the field" decoded as "take off" with confidence 1.0. A Vosk "grammar" is compiled into a **bigram LM** (`ngram_order=2`, discount 0.5, [recognizer.cc](https://github.com/alphacep/vosk-api/blob/master/src/recognizer.cc)).
  - With 214 fillers, all 4 out-of-grammar clips were rejected and all 28 command clips were still recognised.
- **The repo's own grammar test** ([grammar evaluation](../../testing/2026-10-09-vosk-grammar-evaluation.json), 8 utterances): 7/8 hinted transcripts were right. It returned `[unk] hold position` for a valid command, and `[unk] what is the [unk] fly` for an out-of-grammar weather question. Both cases drive the `[unk]` policy in 4.9.
- **Vocabulary:** the hi and te symbol tables contain native-script loanwords (టేకాఫ్, ల్యాండ్, ఆగు, ఫైవ్, टेकऑफ, होल्ड) and drop Latin words (scratchpad `fstsyms.py`).
- **Confidence:** grammar confidences are overconfident, so **the strict template check and the n-best margin, not confidence alone, are the gate.**
- **Memory:** stock vosk-android loads the model through `new Model(path)`. That is the only constructor in the 0.3.75 API, and Kaldi reads nnet3 and FSTs onto the heap. So **Vosk memory is all Private Dirty, and memory-mapping is not available** without a native Vosk fork using OpenFst's map read mode. That fork is not planned (6.2).

**IndicConformer-120M CTC (sherpa-onnx `from_nemo_ctc`).**
- **Packs.** Per-language int8 packs of about 131–190 MB ([AI4Bharat](https://github.com/AI4Bharat/IndicConformerASR), MIT; exports: [nmukthap](https://huggingface.co/nmukthap/vertexvoice-indic), [parismita](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)). Re-quantize to QUInt8, because the upstream int8 file hits a ConvInteger error.
- **Memory, honestly.**
  - A decode touches every weight, so during inference the weight pages are resident whether they are memory-mapped or not.
  - The community "about 30 MiB versus 202 MiB" figure was measured on an iPhone, at idle and under iOS accounting. It does not describe decode-time memory on Android.
  - ORT also **prepacks** weights by default, and "pre-packed data resides on the heap" ([ORT session config keys](https://github.com/microsoft/onnxruntime/blob/main/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h)). That is a Private Dirty copy of roughly the weight size.
  - Expect **about 200–350 MB resident in total** on any tier (estimate, consistent with the community 280–340 MB peak, [SwarTarang](https://github.com/ONKAR-100/SwarTarang-Android)).
- **ORT options for the CTC path** (key names verified in the header):
  - Weights: keep them in an ONNX external-data file, which ORT memory-maps when the file layout allows it (*verify on the armv7 build*). Alternatively, convert to `.ort` format and set `session.use_memory_mapped_ort_model=1` with `session.use_ort_model_bytes_for_initializers=1`.
  - Prepacking: set `session.disable_prepacking=1`, so the weights stay file-backed; Phase 0 measures the decode-speed cost. Alternatively, export with `session.save_external_prepacked_constant_initializers=1`, so the prepacked weights themselves live in an external file that is memory-mapped at load.
  - sherpa-onnx builds its own `Ort::SessionOptions`, so passing these keys needs a small patch to sherpa-onnx (Apache-2.0) or an upstream option (*to check*).
  - Even with all of the above, Private Dirty falls to activations plus the arena, but file-backed resident stays at about 130–190 MB during every decode, and it is re-faulted after memory pressure (6.2).
- **Decoding.** Decode once after release and the tail, then match against the command lexicon (4.8). sherpa hotwords cannot be used, because they need transducers and the transducer export is blocked ([discussion #3199](https://github.com/k2-fsa/sherpa-onnx/discussions/3199), [hotwords](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html)).
- **Accuracy.** Best open model on Voice of India, OI-WER hi 8.2, te 23.7, ta 19.9, kn 21.4, ml 25.9 ([Voice of India](https://voiceofindiablogs.ai.joshtalks.com/)). Whether the 120M or the 600M model was tested is not stated.
- **Speed and the low-end slowdown.**
  - Community RTF is 0.04–0.07, measured on Dimensity and Snapdragon 7-series phones with A76/A78-class big cores and the int8 dot-product instruction. At that speed a 3 s command decodes in about 120–210 ms (estimate).
  - **Cortex-A53 is ARMv8.0 and lacks dot-product.** The Cortex-A55 has it. ORT's 32-bit ARM int8 kernels are expected to be less optimised than its arm64 ones (platform knowledge, *estimate*). A several-fold slowdown on R1 is plausible.
  - **Go/no-go:** a measured decode RTF ≤ 0.15 (3 s command ≤ 450 ms p95) on R1 and R2, or the CTC path is no-go on Tier L (6.3).

**Whisper-tiny fine-tunes (te, kn candidates).**
- They are non-streaming.
- It is *unverified* whether sherpa-onnx's Whisper path pads short clips to the 30 s window. If it does, encoder cost on a Cortex-A53 would likely be seconds, and **Whisper is disqualified for Tier L**.
- ORT memory for the 99 MB tiny pack is *unknown* and is measured in Phase 0. whisper.cpp tiny needs about 273 MB ([whisper.cpp](https://github.com/ggml-org/whisper.cpp)), so no Tier L fit is assumed.

**Alternatives rejected for the core path.**
- whisper.cpp: non-streaming, about 273 MB RAM for tiny.
- Android `SpeechRecognizer` and ML Kit GenAI: API 31+, not on Go or Android 8–11, and no te/ta/kn/ml documented ([API 33 diff](https://developer.android.com/sdk/api_diff/33/changes/android.speech.SpeechRecognizer), [ML Kit](https://developers.google.com/ml-kit/genai/speech-recognition/android)).
- Picovoice: no Indian languages beyond Hindi, online AccessKey ([Rhino](https://picovoice.ai/docs/rhino/)).
- Moonshine and Distil-Whisper: English only ([Moonshine](https://github.com/moonshine-ai/moonshine)).
- Omnilingual CTC-300M: 365 MB ([HF](https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12)).
- Dolphin: Telugu WER 58.5 for base ([paper](https://arxiv.org/html/2503.20212v1)).
- SraVaani-0.5-live and Nemotron 3.5: about 430M and 600M params ([SraVaani](https://huggingface.co/ARTPARK-IISc/SraVaani-0.5-live), [Nemotron](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)).
- Sarvam Edge: commercial partnership only ([Sarvam Edge](https://www.sarvam.ai/blogs/sarvam-edge/)).

### 4.7 Phase 2.5 and Phase 3 recognizers

#### 4.7.1 Phase 2.5: small closed-vocabulary multilingual command model (closes the Tier L gap)

**Decision.** Before the full Phase 3 model, train **one small streaming command model** covering te, hi, ta, kn, ml and en-IN, aimed at Tier L.

- **Vocabulary.** Closed, generated from the canonical grammar (4.8): command verbs and directions in all six languages; loanwords in each script; number words 0–100 plus halves; units; connectives; negation; stop words; plus a garbage/filler class. That is a few hundred words per language (*estimate*), not open vocabulary.
- **Model.** A 3–20M-parameter streaming Zipformer transducer, or a streaming CTC model, trained with icefall. Int8, target ≤ 20 MB, exported to sherpa-onnx and decoded against the grammar (hotwords or a phrase list).
- **It streams**, so every language gets partial results. That gives native-language voice STOP and partial-token STOP on Tier L, which the CTC and Whisper paths cannot.
- **Data:**
  - synthetic commands from [Indic Parler-TTS](https://huggingface.co/ai4bharat/indic-parler-tts) (Apache-2.0, 21 languages, controllable noise and rate);
  - the native-speaker command corpus collected from Phase 0 onwards (10.2);
  - Kathbath and IndicVoices subsets if legal clears them;
  - rotor and wind noise augmentation.
- **Gate.** It ships per language as each language passes the 10.2 corpus gates, the Tier L memory cap and the Tier L streaming latency targets on R1 and R2.

**Why this is credible and cheaper than Phase 3.**
- Closed-vocabulary keyword models can be tiny: BC-ResNet-1 reaches 96.9% on Google Speech Commands v2 with 9.2k params ([BC-ResNet](https://arxiv.org/abs/2106.04140)), and MatchboxNet reaches 97.21% with about 93K ([MatchboxNet](https://arxiv.org/abs/2004.08531)). This model must also compose numbers and slots, so it is larger than those, but it is far below open-vocabulary size.
- A small multilingual model loses little against monolingual ones (multilingual TinyS2I, [Amazon](https://www.amazon.science/publications/multilingual-end-to-end-spoken-language-understanding-for-ultra-low-footprint-applications)).
- TTS data helps but does not replace real recordings. Synth4Kws: TTS-only EER 12.62% versus 8.19% for real plus TTS ([arXiv](https://arxiv.org/abs/2407.16840)). So the real corpus from Phase 0 is required, not optional.
- It uses the same toolchain as Phase 3, so the work carries over.

#### 4.7.2 Phase 3: one multilingual streaming open-ish model

**Decision.** Train **one multilingual streaming Zipformer2 transducer** with icefall covering te, hi, ta, kn, ml and en-IN, including code-mixed speech.
- Export int8 to sherpa-onnx with about a 320 ms chunk.
- Decode against the canonical grammar using hotwords or a phrase list.
- Ship two size variants by device targeting: small for Tier L and larger for Tier M/H. The target is 20–70M params, about 25–70 MB int8 (*estimate*). Phase 3 replaces Phase 2.5 only where it is measured better.
- Fine-tune a sherpa KWS model on stop words in all six languages ([KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html)).
- **Data:** IndicVoices ([paper](https://arxiv.org/pdf/2403.01926)) and Kathbath ([HF](https://huggingface.co/datasets/ai4bharat/kathbath)), both CC BY 4.0 and gated; Indic Parler-TTS synthetic commands; real native-speaker command recordings mixed with rotor and wind noise.
- **Alternative:** commission Alpha Cephei.
- Once the model passes the gates in Section 10, delete `:speech-vosk`.

**Why.**
- No public streaming model exists for hi, te, ta, kn or ml.
- Alpha Cephei's small streaming Bengali Zipformer2 (FLEURS WER 20.6, [HF](https://huggingface.co/alphacep/vosk-model-small-streaming-bn)) shows the recipe works.
- One multilingual model handles Hinglish and Tenglish without language ID.

**Work track.** Corpus collection starts in Phase 0. Phase 2.5 training starts once about the first 10 speakers per language are recorded. Phase 3 training runs in parallel with Phases 2–2.5.

### 4.8 Grammar and normalisation per language

**Decision.** One **canonical grammar** in `:grammar`: `grammar/intents.v1.yaml` holds the intent schema, and `grammar/lexicon/<lang>.yaml` holds each language's surface forms.

Intents:
- `status`
- `takeoff(h)`
- `move(dir, d)`, where dir is forward, back, left, right, up or down
- `hold` / `stop`
- `land`
- `confirm`, `cancel`
- `rtl`, only after safety review

Each lexicon defines **per-language word orders** per intent. Indic languages are mostly verb-final, so their templates are not translations of the English ones.

A build-time generator in `tools/grammar-gen` (a JVM tool that shares `:grammar` code) emits:

- (a) Vosk phrase lists plus fillers, covering **every adjacent word pair of every template**. The full cross-product is not needed, because the bigram LM generalises and the strict parser re-checks.
- (b) sherpa hotword and keyword files.
- (c) the CTC matching lexicon (a token trie).
- (d) Kotlin parser tables.
- (e) positive, negative (negation), out-of-grammar, `[unk]` and number fixtures for CI.
- (f) the Phase 2.5 training vocabulary and synthetic-prompt list.

**Rules that matter for safety.**
- **Numbers 0–100 plus halves are in the grammar, not just 1–10.** A bigram grammar that only knows 1–10 can force "fifteen" onto "five". With the full range, "fifteen metres" is recognised and then rejected by the validator ("at most 10 m").
- **Negation words are in the grammar** (en *don't, do not, not*; hi *मत, नहीं*; te *వద్దు*; and so on, provisional), so they are decoded and the parser rejects the plan. If they were missing, "don't take off" could decode as "take off". v0.2.0 already lost the negation in "Do not take off" in one rerun ([README](../../../README.md)).
- **Fillers** come from a public text frequency list intersected with each model's symbol table. Vosk drops words missing from a model's vocabulary with a warning, so the generator checks every lexicon word against the model's `Gr.fst` symbols and fails the build if a command word is missing.

**Runtime normaliser (Kotlin, pure JVM).**
- Unicode NFC through `java.text.Normalizer`, plus ZWJ/ZWNJ and nukta cleanup.
- A two-way map between native-script loanwords and Latin forms (డ్రోన్ ↔ drone).
- Per-language number tables (Section 7.4).
- Unit words, and connective splitting.

**Why lookup tables.** No maintained on-device inverse text normalisation covers te, ta, kn and ml. [indic-punct](https://github.com/Open-Speech-EkStep/indic-punct) is stale, and [NeMo ITN](https://docs.nvidia.com/nemo-framework/user-guide/25.09/nemotoolkit/nlp/text_normalization/wfst/wfst_text_normalization.html) covers only hi and mr. Lookup tables are the fastest, most dependable option for a 0–100 range. Generating every artifact from one source means the recognizer and the parser cannot drift apart.

**CTC lexicon matching (IndicConformer path).** sherpa-onnx's offline NeMo CTC decode is greedy and returns one hypothesis, with no n-best. So the margin is defined on templates, not on alternatives:

1. Normalise the hypothesis and split it into clauses and words.
2. **Slots match exactly.** Number, direction and negation tokens must match a lexicon entry exactly after normalisation. There is **no fuzzy substitution and no nearest-number snapping** in a slot: five/fifteen/fifty, ఐదు/ఆరు and similar near-misses are the most dangerous misrecognitions. An unknown token in a slot position rejects the clause.
3. **Fixed words may be fuzzy.** For each candidate template instance, compute the word-level edit distance over the fixed words only (verbs, units, connectives, particles). Call the best distance `d1`, and the best distance of any template with a different intent or slot assignment `d2`.
4. **Accept** only if `d1 ≤ 1` (and `d1 = 0` when the template has fewer than 3 fixed words) and `d2 − d1 ≥ 2`. Otherwise reject with "say again". The thresholds are proposals, tuned on the corpus in Phase 0.

If Phase 0 shows text matching underperforms, implement logit-level constrained CTC decoding. It needs CTC log-probs, either by extending the sherpa-onnx JNI or through HLG decoding if sherpa supports it for NeMo CTC (*unverified*).

**Vosk n-best margin.** With `setMaxAlternatives(3)`, accept only if:
- the top alternative parses to a valid plan, and
- every other alternative whose confidence is within Δ of the top one (Δ proposed 0.1, tuned on the corpus) parses to the **same** intents and slot values, or fails to parse at all.

Otherwise reject with "say again".

### 4.9 Speech to intent: strict template parser, no LLM

**Decision.** A pure-Kotlin `:command` module, ported from `rule_intent_engine.dart` and `plan_interpreter.dart`.

- **Before the port, the Dart tests are exported as golden JSON vectors** (input transcript, language, vehicle state, expected plan or rejection message). They run against the Kotlin port in CI. In Phase 1a the Dart parser itself still runs, behind Pigeon.
- The parser does connective splitting, 1–5 clauses and an exact template match per clause.
- Any clause that does not match rejects the whole plan, with an earcon and a per-language "say again" clip.
- Negation is rejected. There is **no AI fallback**.
- **`[unk]` policy:**
  - Any `[unk]` inside a motion clause, or between clauses of a multi-step plan, rejects the whole utterance.
  - A leading or trailing `[unk]` is tolerated **only** when the rest of the utterance is exactly a STOP, HOLD or status template.
  - Golden vectors: `[unk] hold position` → HOLD, accepted. `[unk] what is the [unk] fly` → rejected.
- A bare motion word ("fly", "go", "move") never forms a plan, because takeoff needs an explicit height and move needs a direction and a distance.

**Why.** LFM and Needle were semantically unreliable, which is why v0.2.0 already went rules-first ([README](../../../README.md)). Small Indic models still get about 1 word in 4 wrong in open vocabulary ([IndicVoices](https://arxiv.org/html/2403.01926)), so open text must never reach an LLM in the flight path. The repo's own grammar test produced exactly the `[unk]` patterns the policy covers (finding 8).

### 4.10 Plan validation and confirmation

**Decision.** Keep the v0.2.0 contract exactly (Section 2.2), with these additions:

- Each step is re-validated against a fresh `VehicleState` just before it is sent.
- **Horizontal envelope.**
  - The validator projects each step's horizontal position relative to home, using HOME_POSITION, requested at connect and re-requested on change.
  - It rejects a plan whose projected horizontal distance from home exceeds `max_home_distance_m` (proposed **30 m**, ratify in Phase 0).
  - On the real-hardware profile, moves are rejected while home is unknown.
  - This closes the gap where "forward 10" repeated across plans had no bound. The onboard fence is the backstop (8.4).
- **STOP, HOLD and status never need confirmation.** LAND and RTL still do.
- The plan card shows the steps, an expiry countdown, and a **source chip**: Phone, Typed, Tap, and in Phase 4 Assist or Agreed.
- The **tap command pad** and **typed commands** go through the same validator and confirmation, and they work without any speech pack.
- Every outcome is written to the trace and the `.tlog`.

### 4.11 Degradation ladder

The UI always shows which level is active:

| Level | Condition | Available |
|---|---|---|
| Voice OK | Pack loaded, RTF within policy | PTT voice, tap pad, typed, all STOP lanes |
| Voice degraded | Thermal throttling, RTF over policy, or repeated margin failures | Tap pad and typed promoted. Voice still allowed, with a warning chip. |
| No voice | No pack, recognizer failed, or **microphone silenced by another app** (4.2) | Tap pad, typed, physical STOP lanes |
| External mode | The autopilot (failsafe, fence) or the pilot set the flight mode (4.12) | Status only. Plans rejected. Voice STOP off. Physical STOP shows "use RC" (1 s hold to override). |
| Phone lost | Process killed or frozen, battery dead | Onboard GCS failsafe plus the RC pilot |

The STOP lanes and the link behave identically at every speech level. Thermal or memory pressure may degrade speech, never the link or STOP.

### 4.12 Emergency STOP lane

**Triggers.** All six call `supervisor.stop()`, which can be called from any thread:

1. The giant always-visible STOP button.
2. **Volume-Down**, intercepted in `dispatchKeyEvent` before Flutter. It also ends any active PTT (4.4).
3. The foreground-service notification action (best-effort, 4.1).
4. The **voice stop spotter**. It is armed while the vehicle is armed or airborne and the app owns the mode. It runs only while push-to-talk is not held, is VAD-gated, and gets the 300 ms pre-roll replay (4.2).
   - **Tier L Vosk path:** the stop-only grammar recognizer on the resident model. **Detection uses partial results:** it fires when a stop token appears in `getPartialResult()` on two consecutive 100 ms decode steps. The final result, with the endpointer tightened through `setEndpointerDelays`, is a backstop only. Waiting for the final result alone would add the model's 0.5 s rule2 trailing silence to every stop. `reset()` runs between VAD segments.
   - **sherpa path** (Tier M/H, and Tier L when the session runtime is sherpa): the [KWS](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html) **zh-en 3M chunk-8 model**, with English "stop" and "hold" written as phone sequences in its token set. The page states 160 ms latency for chunk-8 models. The **gigaspeech 3.3M** English model is the fallback if zh-en English recall is worse. It ships only a chunk-16 variant, so its floor is about 320 ms. Native stop words as phone sequences are experimental.
   - **Phase 2.5/3:** native stop words through the streaming command model's partials, and the multilingual KWS.
5. A **stop token in a streaming partial** while push-to-talk is held. This exists **only on streaming engines** (Vosk grammar, Phase 2.5/3 models). On non-streaming engines (IndicConformer CTC, Whisper), a stop spoken while PTT is held is recognised only after release, the tail and a full decode. That is the normal T0 → parse latency of that path, not a fast lane (S2e in Section 5).
6. **Cancel during execution.**

**Documented gap (generalised).** Any language whose engine on that phone is non-streaming has **no native-language voice STOP and no partial-token STOP**. Operators then have only the English KWS words ("stop", "hold"), spoken while PTT is not held, plus the physical lanes. This applies to:
- ta, kn and ml everywhere in Phase 1b;
- te on Tier L if Phase 0 picks IndicConformer-te or whisper-telugu-tiny;
- te and hi on Tier M/H when the CTC path is chosen.

Phase 2.5 closes the gap. The Phase 0 Telugu decision weighs it explicitly (4.6).

**Mode ownership.** The supervisor tracks `modeOwner`:
- **APP:** the current HEARTBEAT mode is the mode of the app's last ACCEPTED DO_SET_MODE (GUIDED for takeoff or move, BRAKE from STOP, LAND or RTL from a confirmed plan), and no mode change the app did not command has been seen since.
- **EXTERNAL:** anything else. That covers a mode change the app did not command (RC mode switch, battery, EKF or GCS failsafe, fence action) and the mode found at connect. A failsafe or fence STATUSTEXT within ±2 s of the change labels it "Autopilot failsafe" in the UI; otherwise it is labelled "Pilot mode". The behaviour is the same for both labels.

**Action.**

| Mode owner and vehicle state (EXTENDED_SYS_STATE, HEARTBEAT) | Voice spotter and partial token | Physical STOP (button, Volume-Down, notification) and Cancel | Fallback |
|---|---|---|---|
| Not connected | inactive | Nothing sent. Plan cancelled. Banner: "Not connected, use RC". | n/a |
| Disarmed | inactive | Nothing sent. Plan cancelled. Pending confirmation invalidated. | n/a |
| APP, armed, `ON_GROUND` (spool-up, takeoff not yet lifted) | triggers STOP | Queued steps are cancelled first, so NAV_TAKEOFF is never sent after STOP. Then **MAV_CMD_COMPONENT_ARM_DISARM, param1=0, non-forced**. | If refused: BRAKE, plus an alarm. ArduCopter is expected to refuse a non-forced GCS disarm when not landed; **verify in SITL**. |
| APP, `TAKEOFF`, `IN_AIR`, `LANDING` or `UNDEFINED` | triggers STOP | **MAV_CMD_DO_SET_MODE → BRAKE (17)** | If refused because there is no position estimate: **LAND (9)** by default, or **ALT_HOLD (2)** if the GPS-denied SITL case (open question 6) shows it is safer. LOITER is not in the chain, because Loiter needs the same GPS 3D lock or EKF-origin position source as Brake ([Loiter](https://ardupilot.org/copter/docs/loiter-mode.html)), and a second refused round trip would waste up to about 200 ms on ELRS. |
| **EXTERNAL**, any state, including failsafe LAND, RTL or SmartRTL and RC-selected modes | **disabled** (the executor has already aborted any plan) | Plan cancelled. **No frame sent.** Banner: "Autopilot failsafe active, use RC" or "Pilot mode MODE, use RC". The state-dependent action of the APP rows is sent **only after an explicit second action: holding the on-screen STOP for ≥ 1 s**. Volume-Down and the notification never override. Each override is logged. | as the APP rows |

**Rules.**
- STOP **never sends a forced disarm or flight termination.** Disarming in the air drops the copter uncontrolled. Motor kill stays on an RC aux spring switch ([aux functions](https://ardupilot.org/copter/docs/common-auxiliary-functions.html)).
- **Pre-encoded frame.** The stop frame jumps the TX queue. Its payloads (BRAKE, LAND fallback, non-forced disarm) are **pre-encoded** at session start. At send time the codec does four things: fills the sequence number; **recomputes the CRC**, which covers the sequence byte and the payload; fills the signing timestamp; and computes the signature. On re-sends the COMMAND_LONG `confirmation` field is incremented, which changes the payload and so also the CRC. Cost is under 1 ms (*estimate*).
- **Bounded re-send.** The frame is re-sent until an ACK arrives, **for at most 3 s**. After that the UI raises "STOP NOT CONFIRMED, take RC control". FS_GCS handles a link that stays down.
- STOP invalidates any pending confirmation, cancels the plan, and is logged.
- The spotter uses a **lenient** threshold. That is acceptable **only because** it is active only while the app owns the mode, where a false STOP means a benign hover. Motion commands use strict gates.

**Why.**
- BRAKE stops the copter as quickly as possible and then holds position ([Brake mode](https://ardupilot.org/copter/docs/brake-mode.html)).
- Emergency-stop practice says the stop overrides everything and needs a single action ([Kollmorgen](https://www.kollmorgen.com/en-us/developer-network/stop-and-emergency-stop-function)). On a drone, though, the onboard failsafes and the RC pilot rank above the phone (8.1).
- After a battery failsafe, "the user can re-take control of the vehicle by changing the flight mode", and the failsafe "will not trigger again" ([battery failsafe](https://ardupilot.org/copter/docs/failsafe-battery.html)). A GCS failsafe likewise stays in its mode until the pilot or a GCS changes it ([GCS failsafe](https://ardupilot.org/copter/docs/gcs-failsafe.html)). So a false voice STOP during a failsafe LAND would leave a low-battery copter hovering instead of landing. Hence the EXTERNAL row.
- The current app is unsafe here (finding 4 in Section 2.1).

### 4.13 Vehicle link, transport and MAVLink signing

**Transport interface.** One interface with these adapters:

- **UDP:** SITL, ESP32/ESP8266 Wi-Fi bridges, ELRS Backpack at `10.0.0.1:14550`.
- **USB-OTG serial** through [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android):
  - SiK radios with `MAVLINK=2`, `MAX_WINDOW=33`, `AIR_SPEED=64`, ECC off ([SiK](https://ardupilot.org/copter/docs/common-3dr-radio-advanced-configuration-and-technical-information.html)).
  - An ELRS TX module over USB at 460800 baud ([ELRS MAVLink](https://www.expresslrs.org/software/mavlink/)).
  - The FTDI latency timer set to **2 ms** instead of the 16 ms default ([FTDI](https://www.ftdichip.com/Support/Knowledgebase/settingacustomdefaultlaten.htm)). The D2XX `FT_SetLatencyTimer` range is 2–255 ms; *verify the value usb-serial-for-android accepts*.
  - A direct cable to the FC's own USB port (signing-key provisioning, bench).
- **TCP:** [mavlink-router](https://github.com/mavlink-router/mavlink-router) on port 5760.
- **Bluetooth:** telemetry viewing only, never control. Range is about 50 m ([Mission Planner BT](https://ardupilot.org/sub/docs/common-mission-planner-bluetooth-connectivity.html)). Latency is not characterised by any source used here; measure it before even viewing use.

**Wi-Fi details.**
- Call `requestNetwork(TRANSPORT_WIFI)` without `NET_CAPABILITY_INTERNET`, and bind **only the MAVLink socket** with `Network.bindSocket()`. Model downloads and all other traffic stay on the default network, so a Wi-Fi-only phone can still download packs on an ordinary Wi-Fi network. The Phase 4 assist-node socket is bound to its LAN `Network` explicitly.
- Hold a `WIFI_MODE_FULL_LOW_LATENCY` lock and **measure** whether it engages; AOSP lists conditions ([Wi-Fi low latency](https://source.android.com/docs/core/connect/wifi-low-latency)). Power save may add about 100–300 ms (claim from a slide deck).
- Add `ACCESS_LOCAL_NETWORK` before targeting SDK 37 ([local network permission](https://developer.android.com/privacy-and-security/local-network-permission)).

**Recommended field kit.** RC transmitter, plus a USB-OTG telemetry radio or ELRS in MAVLink mode, in a band that is legal in India (8.4). Use a USB radio whenever Wi-Fi also carries audio or other bulk data. USB host support is optional in the CDD, so it is checked per phone.

**Codec.** Keep the pinned mavgen C `c_library_v2` behind a **buffer-level JNI API**: direct ByteBuffers in and out, compact typed structs, no JSON, no nlohmann. Build targets:
- armv7 and arm64, for the shipping AAB;
- x86_64 Android, for a debug/CI-only variant used by the emulator smoke test and excluded from the shipping AAB;
- **Linux x86_64 host**, so the JVM `:link` and `:vehicle` tests and SITL CI run the same codec.

Kotlin link threads own the sockets and USB ports.

**MAVLink 2 signing** ([guide](https://mavlink.io/en/guide/message_signing.html), [ArduPilot](https://ardupilot.org/dev/docs/common-MAVLink2-signing.html)).
- **Scope.** Signing applies at the FC's port, not at the phone's transport. It is **required on every link that reaches an FC serial or telemetry port**: Wi-Fi bridges, TCP routers, **and USB-OTG telemetry radios (SiK, ELRS)**, whose traffic crosses the air into an FC TELEM port. The **only exception** is a direct cable from the phone to the FC's own USB port, which ArduPilot does not sign-protect.
- **Key handling:**
  - a 32-byte key wrapped by the Android Keystore;
  - a monotonic timestamp persisted across sessions;
  - a distinct `linkID` per transport;
  - an **UNSIGNED** banner whenever signing is off.
- **Key provisioning.** SETUP_SIGNING is sent **only over a direct phone-to-FC USB cable**. A USB transport counts as "direct FC" only when:
  - the device is a CDC-ACM device on a configured autopilot VID/PID allowlist;
  - the HEARTBEAT comes from the FC's own sysid;
  - no RADIO_STATUS frames are seen (SiK radios inject them).
  
  The link layer **refuses SETUP_SIGNING on UDP, TCP and radio transports** (unit test plus SITL test). Through a radio, SETUP_SIGNING would carry the key over the air in the clear.
- Signing costs 13 B per frame. ArduPilot also signs its outgoing frames on a signed channel, so the downlink budget includes it (4.15). Signing authenticates; it does not encrypt.

**Alternatives.**
- Fallback: [mavlink-kotlin](https://github.com/divyanshupundir/mavlink-kotlin) (Apache-2.0, supports signing), if the team wants no NDK code in the link.
- Rejected:
  - the MAVSDK-Java server: 40.4 MB AAR, a localhost gRPC hop, no signing support found ([Maven](https://repo1.maven.org/maven2/io/mavsdk/mavsdk-server/4.0.6/));
  - `dart_mavlink`: no signing;
  - dronefleet: unmaintained.

### 4.14 Executor

**Decision.** A Kotlin step state machine on the supervisor thread, with an ArduPilot adapter ([Guided commands](https://ardupilot.org/dev/docs/copter-commands-in-guided-mode.html)):

| Step | Messages | Confirmed by |
|---|---|---|
| Takeoff(h) | DO_SET_MODE GUIDED (4) → ARM → NAV_TAKEOFF(h): 3 round trips plus arming checks | ACK for each, then telemetry: altitude ≥ h − 0.4 m and landed state IN_AIR (the current rule) |
| Move(dir, d) | SET_POSITION_TARGET_LOCAL_NED, frame **BODY_OFFSET_NED (9)**, `type_mask 3576` (position only, yaw ignored). GUIDED is set only if the vehicle is not already in GUIDED. POSITION_TARGET_LOCAL_NED is requested at **5 Hz (Wi-Fi) or 4 Hz (SiK, ELRS) while the move is pending**, and set back to 0 afterwards. | No ACK. The POSITION_TARGET_LOCAL_NED echo within the link timeout, then position progress and the arrival tolerance (current rule, min(0.5 m, d/2)) |
| Land | DO_SET_MODE LAND (9) | ACK, then landed state ON_GROUND |
| Hold/Stop | STOP lane (4.12) | ACK |
| RTL | DO_SET_MODE RTL (6), after safety review | ACK, then telemetry |

**Retry rules.**
- COMMAND_LONG commands are re-sent per the [command protocol](https://mavlink.io/en/services/command.html): same parameters, `confirmation` incremented. Link-dependent timeouts (*estimate*): about 0.5 s on Wi-Fi, about 1–1.5 s on SiK or ELRS, tuned by measurement.
- **SET_POSITION_TARGET in BODY_OFFSET_NED is non-idempotent. It is never retried or duplicated.** If the echo is missing, the step is UNKNOWN and the plan stops.
- A REJECTED ACK that arrives after a retry, while telemetry shows the step in progress, is treated as UNKNOWN (conservative).
- Step completion always comes from telemetry.
- The plan aborts on any of: an external mode change (`modeOwner` becomes EXTERNAL), a stale link, or a fence or failsafe STATUSTEXT. Nothing is replayed after a reconnect.

**Alternative under evaluation.** MAV_CMD_DO_REPOSITION through COMMAND_INT. It is ACKed and, because the target is absolute, safe to retry. But it needs a phone-computed absolute target from possibly stale telemetry, which is finding 11 again ([GCS_MAVLink_Copter.cpp](https://github.com/ArduPilot/ardupilot/blob/master/ArduCopter/GCS_MAVLink_Copter.cpp)). Phase 1a SITL compares both. The default stays BODY_OFFSET_NED.

**PX4 adapter (Phase 3).** Hold plus DO_REPOSITION, never Offboard, because Offboard needs a setpoint stream above 2 Hz ([PX4 Offboard](https://docs.px4.io/main/en/flight_modes/offboard.html)).

### 4.15 Telemetry

**Decision.**
- Request each message with SET_MESSAGE_INTERVAL ([requesting data](https://ardupilot.org/dev/docs/mavlink-requesting-data.html)) and **re-request after every (re)connect**.
- Use a **per-transport rate profile**.
- **Zero the FC's `SRx_*` / `MAVn` stream groups** on the radio's serial port, because the app requests each message itself. The parameter audit checks this (8.4). If a third-party GCS or a Yaapu telemetry display shares the link, the ELRS guidance is 1 for most groups, with ADSB, PARAMS and RAW_CTRL at 0 ([ELRS MAVLink](https://www.expresslrs.org/software/mavlink/)). The budget below must then be re-measured.

| Message | Payload B (MAVLink 2, untruncated) | Wi-Fi/UDP, TCP, direct USB | SiK and ELRS |
|---|---:|---|---|
| HEARTBEAT | 9 | 1 Hz | 1 Hz |
| LOCAL_POSITION_NED | 28 | 5 Hz | 3 Hz (moves, altitude envelope, home distance) |
| GLOBAL_POSITION_INT | 28 | 5 Hz | 0.5 Hz (map, status) |
| ATTITUDE | 28 | 5 Hz | 1 Hz |
| EXTENDED_SYS_STATE | 2 | 2 Hz | 1 Hz |
| SYS_STATUS | 43 | 1 Hz | 0.5 Hz |
| EKF_STATUS_REPORT | 26 | 1 Hz | 0.5 Hz |
| POSITION_TARGET_LOCAL_NED | 51 | 5 Hz **only while a move is pending**, else 0 | 4 Hz **only while a move is pending**, else 0 |
| HOME_POSITION | 60 | on connect and on change | same |
| COMMAND_ACK, STATUSTEXT | 10, 54 | events | events |
| RC_CHANNELS (single-operator mode only) | 42 | 10 Hz | not offered |

**Downlink budget** (frame = payload + 12 B header and CRC + 13 B signature; untruncated sizes, so conservative; derived, not measured):

| Profile | Steady (no move) | During a move | Link capacity | Load |
|---|---:|---:|---:|---|
| Wi-Fi profile, signed | about 1,000 B/s | about 1,380 B/s | Wi-Fi: not a constraint | n/a |
| Wi-Fi profile, if sent over ELRS (what v2 rev 1 implied, with POSITION_TARGET at 2 Hz) | about 1,150 B/s | — | ELRS 333 Hz Full downlink about 1,470 B/s ([ELRS MAVLink](https://www.expresslrs.org/software/mavlink/)) | about 79%: ACKs queue behind telemetry. **Rejected.** |
| **SiK/ELRS profile, signed** | **about 360 B/s** | **about 660 B/s** | ELRS 333 Hz Full about 1,470 B/s | **about 25% steady, about 45% during a move**, leaving room for COMMAND_ACK (35 B) and STATUSTEXT (79 B) bursts |

- The RX thread updates an immutable `VehicleState`, and the UI reads it throttled.
- On ELRS, the uplink with RC is about 735 B/s. The connect-time SET_MESSAGE_INTERVAL burst (about 9 × 58 B) therefore takes about 0.7 s. It happens before arming, not in the command path.

**Why re-request.** ArduPilot 4.7 no longer saves stream-rate requests ([4.7 release](https://discuss.ardupilot.org/t/copter-4-7-0-released/144650)). Stream rates also drop during parameter or mission transfers ([requesting data](https://ardupilot.org/dev/docs/mavlink-requesting-data.html)). These rates are this design's proposal, not a vendor recommendation.

### 4.16 Safety envelope

The safety envelope is in Section 8: onboard failsafes, the parameter audit, RC authority, the crew model and India compliance hooks.

### 4.17 UI and app structure

**Decision.** Keep **Flutter as a thin client** through Phase 2. It has no control logic, talks through Pigeon intents and EventChannel snapshots, and keeps the existing widget tests. Split the 1,479-line screen into four screens:

- **Flight:** giant STOP at the top (hold 1 s for the EXTERNAL override), PTT bar, plan card with countdown and source chip, large CONFIRM, a one-line vehicle status including the mode owner, the degradation-level chip, the tap command pad, and typed input.
- **Link setup.**
- **Models.**
- **Diagnostics:** per-stage p50/p95, main-thread stalls, raw telemetry, and downlink load per transport.

Feedback uses haptics, earcons and short **prerecorded per-language clips** ("stopped", "say again", "confirm?", "use RC"), a few KB each. There is no TTS engine on Tier L.

Gradle modules: `:app` (Flutter host), `:session`, `:audio`, `:speech-api`, `:speech-vosk` (Phases 1b–2.5 only), `:speech-sherpa`, `:grammar`, `:command`, `:safety`, `:vehicle`, `:link` (with the JNI codec), `:models`, `:observability`.

**Compose gate.** Revisit a Compose rewrite only if Phase 1–2 profiling on Tier L reference phones shows either:
- Flutter's Private Dirty pushes the Tier L session over the hard cap (6.2) or into lmkd kills, or
- plan-card display (stage 5 in Section 5) or STOP-button handling (S1) misses its p95 target because of UI-thread work.

**Context for the gate (not memory evidence).** In one Pixel 4a sample, the native Compose app had a **download size** of 1.46 MB (Bundletool) and a **cold start** of 413 ms (Macrobenchmark), against Flutter's 6.83 MB and 634 ms ([comparison](https://github.com/jacobras/flutter-vs-native-vs-kmp)). Compose plus a Baseline Profile is cheap ([Compose metrics](https://developer.android.com/develop/ui/compose/migrate/compare-metrics)). On 32-bit phones, Impeller falls back to OpenGLES ([Impeller Android](https://flutter.googlesource.com/mirrors/engine/+/refs/heads/main/impeller/docs/android.md)). These figures are size and startup, not memory, so the memory part of the gate rests on the Phase 1–2 measurements alone.

### 4.18 Noise handling

**Decision.**
- No denoiser by default.
- Use the `VOICE_RECOGNITION` source and recommend a close-talk headset.
- Add rotor and wind noise augmentation in Phase 2.5 and Phase 3 training.
- [GTCRN](https://github.com/Xiaobin-Rong/gtcrn) (48.2K params, 33 MMACs/s, already in sherpa-onnx) is enabled only on Tier M/H, only if field A/B tests show a command-accuracy gain, and with observation adding.

**Why.** Single-channel enhancement artifacts often make ASR worse ([Iwamoto 2022](https://arxiv.org/abs/2201.06685)). The microphone is at the operator, not on the drone, so SNR should be far better than the onboard SNR below −15 dB reported in drone ego-noise studies ([DOAJ](https://doaj.org/article/1adf6ed0eec447aca98a5c0fea8f2b15)). That is not measured for this setup.

### 4.19 Model packaging, delivery and ABIs

**Decision.**
- Models are **ABI-agnostic data packs** with a manifest: `engine`, `language`, `tier`, `bytes`, `sha256`, `min_ram_mb`, `streaming`, `licence`, `grammar_version`.
- On Play, they ship as **on-demand asset packs** ([asset delivery](https://developer.android.com/guide/playcore/asset-delivery)), not AI packs. AI packs officially support only LiteRT/MediaPipe models ([on-device AI](https://developer.android.com/google/play/on-device-ai)). Size variants use RAM device-tier targeting (beta, [tier targeting](https://developer.android.com/google/play/device-tier-targeting)).
- Keep `VerifiedModelStore`'s SHA-256 download and import, including import from SD card, USB or a file share, for sideloaded and offline field kits. In Phase 4 the assist node can also act as an offline LAN mirror, verified against the same manifest.
- The AAB carries **armeabi-v7a and arm64-v8a** splits (from Phase 1b). Split-per-ABI APKs are built for field installs. x86_64 native libraries exist only in a debug/CI variant.
- Only the operator's language pack is installed by default.
- Models are re-hosted with attribution, which MIT and Apache-2.0 allow.

### 4.20 Removed

- whisper.cpp
- llama.cpp with LFM2.5-VL-450M
- Cactus Whistle and Needle
- the Android recognizer in the hot path
- the Dart MAVLink transport, the per-datagram MethodChannel, and the JSON codec API
- the legacy Python edge demo, archived to a branch; its protocol code is reused in 4.21

### 4.21 Optional extensions (Phase 4, grafted from Hybrid)

**AeroNex Assist (LAN, offline).**
- **What it is.** A headless laptop daemon rebuilt from `edge/app/server.py`. It keeps envelope v1, PING/PONG and `message_id` deduplication. It is **recognition-only**: it receives PTT audio frames tagged with an `utterance_id` (16 kHz × 16 bit = 256 kbit/s) and returns N-best **text** with scores, model hash and timing.
- **Candidate models.** [IndicConformer-600M multilingual](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual) int8 (0.62 GB, about 1.1 GB RSS per a community report) and [vasista22 Whisper-small](https://huggingface.co/vasista22/whisper-telugu-small) fine-tunes. Laptop latency is unmeasured.
- **Arbiter rules:**
  - The phone re-parses and validates every candidate in Kotlin, with the same `[unk]`, slot and margin rules.
  - If the local result is valid with a good margin, it is used immediately, and the assist result is logged in shadow mode.
  - If there is no valid local result (for example ta, kn or ml on Tier L before Phase 2.5), the arbiter waits for the assist result up to a **400 ms cap** (*estimate*).
  - **Two valid candidates that disagree on intent or on any number mean reject and "say again".**
  - CONFIRM is enabled only after arbitration closes. The source chip shows Assist or Agreed.
- **Limits.**
  - It **never sends MAVLink**, and STOP never waits for it.
  - Pairing uses a QR code with a pinned key.
  - A single-radio phone cannot join both a drone ESP32 access point and a separate laptop LAN. Assist is supported only when the laptop and the drone bridge share one router, or when MAVLink runs over a USB-OTG radio.

**Shadow evaluation (from Phase 0).** Alternative recognizers run on identical captured audio. Per-utterance records hold the audio hash, candidates and decision. They are used to choose engines and to grow the corpus, and they never touch the flight path.

**Cloud ASR** ([Sarvam Saaras v4](https://www.sarvam.ai/blogs/introducing-saaras-v4)). Deferred until a **key-proxy backend** exists, because an API key embedded in the APK can be extracted. It is opt-in per session (audio leaves the device), its socket is bound to the cellular network, and it follows the same arbiter rules. Bhashini (proof-of-concept terms only) and Chirp 3 (US/EU hosting, Indic languages in Preview) are not adopted ([Bhashini](https://dibd-bhashini.gitbook.io/bhashini-apis), [Chirp 3](https://docs.cloud.google.com/speech-to-text/docs/models/chirp-3)).

**Companion computer.** Optional mavlink-router or mavp2p for read-only fan-out and onboard tlogs, with sysid filtering so the phone stays the only commanding GCS.

---

## 5. Latency budget

T0 is push-to-talk release. Targets are for a **Tier L reference phone** (R1/R2 in Section 10) unless stated otherwise. All numbers are targets or estimates, not measurements.

**How measured (all rows).**
- Every utterance gets `SystemClock.elapsedRealtimeNanos` trace points: `ptt_down`, `first_partial`, `ptt_up`, `tail_end`, `audio_drained`, `asr_final`, `parsed`, `plan_validated`, `plan_shown`, `confirm`, `tx`, `ack`, `echo`, `mode_observed`, `in_air`, plus `stop_trigger`, `stop_tx` and `stop_ack`.
- UI stages use the Flutter frame timeline and Perfetto. Audio input latency uses a loopback click test.
- Wire times use MAVLink `TIMESYNC` to align the phone and FC clocks, with SITL logs or the real FC's dataflash log. Motion onset is the first dataflash sample where horizontal velocity toward the target is ≥ 0.2 m/s.
- Diagnostics shows rolling p50/p95, and each session exports JSONL.
- **p95 values do not add up.** On a loaded low-end phone, CPU contention correlates the stages, so their sum is a realistic worst case. The totals are set as separate targets, and the p95 totals are provisional until Phase 0.

**Voice command path.**

| # | Stage | p50 target | p95 target | Basis | Trace span |
|---|---|---:|---:|---|---|
| 1 | Release → service event (touch or key) | 10 | 30 | estimate | input `eventTime` → `ptt_up` |
| 2a | Audio input path (HAL + `AudioRecord` buffers) + last-frame drain | 20 | 40 | estimate; loopback-measured in Phase 0 | `ptt_up` → `audio_drained` (minus tail) |
| 2b | Adaptive post-release tail (ends at 60 ms of VAD non-speech, max 250 ms) | 60 | 250 | by design; the 250 ms case occurs only when the operator releases mid-word | `ptt_up` → `tail_end` |
| 3a | Streaming grammar final (Vosk grammar Phase 1b; Phase 2.5/3 models) | 60 | 150 | estimate. Host-measured FinalResult 6–11 ms, grammar RTF 0.012–0.019 | `audio_drained` → `asr_final` |
| 3b | CTC final, Tier M, 3 s command (IndicConformer) | 150 | 300 | estimate from community RTF 0.04–0.07 (A76/A78-class) → about 120–210 ms | same |
| 3c | CTC final, Tier L, 3 s command (if the gate passes) | unknown | **gate ≤ 450** | unmeasured; go/no-go RTF ≤ 0.15 on R1/R2 | same |
| 4 | Normalise + strict parse + plan validation | 2 | 10 | estimate (table lookups) | `asr_final` → `plan_validated` |
| 5 | Plan card visible (EventChannel + frame) | 33 | 100 | estimate (2–6 frames at 60 Hz) | `plan_validated` → `plan_shown` |
| **=** | **T0 → validated plan on screen, Tier L streaming** | **≤ 200** | **≤ 450** | target (G1). Stage p95 sum is 580; ratify the p95 from Phase 0 data | `ptt_up` → `plan_shown` |
| = | T0 → plan on screen, Tier M CTC | ≤ 300 | ≤ 600 | target. Stage p95 sum is 730 | same |
| = | T0 → plan on screen, Tier L CTC | — | ≤ 800 | gate for enabling a non-streaming engine on Tier L (6.3) | same |
| — | Human confirmation | n/a | n/a | not budgeted. Zero for STOP, HOLD and status. Measured as `plan_shown` → `confirm`. | |

**Confirm to vehicle.**

| # | Stage | p50 target | p95 target | Basis | Trace span |
|---|---|---:|---:|---|---|
| 6 | Confirm tap → executor dispatch | 10 | 30 | estimate (Flutter → Pigeon → supervisor) | `confirm` → `tx` |
| 7 | MAVLink pack + CRC + sign (SHA-256 over < 300 B) | < 1 | < 1 | estimate | inside `tx` |
| 8a | Uplink, Wi-Fi UDP | 5 | 50 | estimate. +100–300 ms if power save is active (slide-deck claim) | `tx` → FC receive (TIMESYNC) |
| 8b | Uplink, SiK 57600 baud, AIR_SPEED 64, MAX_WINDOW 33 | 45 | 80 | derived for a 78 B signed SET_POSITION_TARGET: phone→radio UART about 14 ms, air about 10 ms at 64 kbit/s, TDM wait 0–33 ms, radio→FC UART about 14 ms. How much the hops overlap is unknown. | same |
| 8c | Uplink, ELRS 333 Hz with RC | 100 | 150 | derived: 67–80 B at about 735 B/s ≈ 90–110 ms. COMMAND_LONG (58 B signed) about 80 ms | same |
| 9 | FC → COMMAND_ACK downlink | same as 8, + FTDI timer | same as 8, + FTDI timer | FTDI timer 16 ms by default, set to 2 ms. On ELRS, the 4.15 profile keeps queueing behind telemetry bounded | FC → `ack` |
| = | Confirm → ACK, Wi-Fi | ≤ 50 | ≤ 150 | target (G1) | `confirm` → `ack` |
| = | Confirm → ACK, SiK | ≤ 110 | ≤ 200 | target | same |
| = | Confirm → ACK, ELRS | ≤ 200 | ≤ 320 | target; requires the SiK/ELRS telemetry profile | same |

**Move (the most common motion step; no ACK exists).**

| # | Stage | p50 target | p95 target | Basis | Trace span |
|---|---|---:|---:|---|---|
| M1 | Confirm → move frame on the wire | 10 | 30 | stages 6 + 7 | `confirm` → `tx` |
| M2 | Move frame → POSITION_TARGET echo observed, Wi-Fi (echo at 5 Hz) | 110 | 300 | derived: uplink + wait for the next echo (0–200 ms) + downlink | `tx` → `echo` |
| M2 | Same, SiK (echo at 4 Hz) | 215 | 400 | derived: 8b + 0–250 ms + 8b | same |
| M2 | Same, ELRS (echo at 4 Hz) | 300 | 500 | derived: 8c + 0–250 ms + about 50 ms downlink for 76 B | same |
| M3 | **Confirm → motion onset, Wi-Fi** | **≤ 300** | **≤ 500** | target (G1). The FC position-controller response is unmeasured; ratified from Phase 1a SITL and the 10.6 bench | `confirm` → dataflash onset |

**Takeoff.**

| # | Stage | p50 target | p95 target | Basis |
|---|---|---:|---:|---|
| K1 | Confirm → NAV_TAKEOFF ACK, Wi-Fi (GUIDED, ARM, TAKEOFF: 3 round trips plus arming checks) | ≤ 300 | ≤ 800 | estimate: 3 × confirm→ACK plus unmeasured arming time |
| K1 | Same, ELRS | ≤ 900 | ≤ 1,500 | estimate |
| K2 | Confirm → landed state IN_AIR, Wi-Fi | report | ≤ 3,000 | provisional. Depends on spool-up and takeoff detection; EXTENDED_SYS_STATE at 1–2 Hz adds up to 1 s of observation delay. Ratified from SITL |

Mode changes confirmed by ACK remove today's 0–1000 ms wait for a heartbeat (protocol fact). A move while already in GUIDED costs one frame and no extra mode change.

**STOP lane.**

| # | Stage | p50 | p95 | Basis | Trace span |
|---|---|---:|---:|---|---|
| S1 | STOP button / Volume-Down / notification → stop frame on the wire | 20 | 50 | estimate, measured under UI load (4.1 caveat) | input `eventTime` → `stop_tx` |
| S2a | End of spoken stop word → detection, Vosk stop grammar on partials (2 stable 100 ms steps), Tier L Vosk path, Phase 1b | 300 | 500 | conservative estimate (judges' graft) | audio offset (injected clip) → `stop_trigger` |
| S2b | Same, sherpa KWS zh-en 3M **chunk-8**, sherpa paths from Phase 1b | 250 | 450 | published 160 ms for chunk-8 ([KWS models](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html)) + compute (estimate) | same |
| S2c | Same, sherpa KWS gigaspeech 3.3M (ships chunk-16 only), fallback | 350 | 550 | 320 ms is stated for chunk-16 (on the zh-en model; the page gives no gigaspeech figure, so the same chunk-16 floor is assumed) + compute | same |
| S2d | Stop spoken while PTT is held, streaming engine (stop token in a partial) | 300 | 500 | as S2a | same |
| S2e | Stop spoken while PTT is held, **non-streaming** engine | = T0 → `plan_validated` of that path | Tier M ≤ 500, Tier L CTC ≤ 700 | no fast lane exists (4.12). Measured from release, not from the end of the word | `ptt_up` → `stop_trigger` |
| S3 | Detection → stop frame on the wire | 5 | 15 | estimate (pre-encoded payload) | `stop_trigger` → `stop_tx` |
| = | **Spoken STOP → BRAKE ACK, Wi-Fi**: Vosk / zh-en chunk-8 / gigaspeech | ≤ 370 / ≤ 320 / ≤ 420 | **≤ 600 / ≤ 550 / ≤ 650** | target (G2) | |
| = | **STOP button → BRAKE ACK, Wi-Fi** | ≤ 60 | **≤ 200** | target (G2) | |
| = | STOP button → BRAKE ACK, ELRS | ≤ 180 | ≤ 300 | target (COMMAND_LONG about 80 ms up, ACK about 25–50 ms down) | |

**Other costs.**
- Hands-free mode (Tier M/H) adds 300–500 ms of trailing silence.
- Model load happens **once per session**. Host-measured 256–718 ms; unmeasured on a phone. Target ≤ 3 s at session start (proposed).
- **Baseline for comparison (v0.2.0):**
  - a model load per operation;
  - the Speak → Finish → Confirm taps;
  - mode confirmation by 200 ms polling against a 1 Hz heartbeat;
  - a Dart MethodChannel hop on every datagram;
  - STOP waiting behind a 15 s confirmation.
  
  Phase 0 measures the baseline on the same phones, so the gain is shown, not asserted.

---

## 6. Device tiers

### 6.1 Classification (runtime, in `EnginePolicy`)

```
abi64 = Build.SUPPORTED_64_BIT_ABIS is not empty
if not abi64                                     -> Tier L   (32-bit-only userspace)
else if MemoryInfo.totalMem < 3.3 GiB            -> Tier L
else if MemoryInfo.totalMem < 5.5 GiB            -> Tier M
else                                             -> Tier H
ActivityManager.isLowRamDevice() (Go flag) does NOT set the tier. It selects the strict memory
      profile: a lower Private Dirty cap within the tier, 5 Hz snapshots, no hands-free, eager
      onTrimMemory handling of non-flight caches.
then: first-session micro-benchmark on a bundled 3 s clip (release->final p95, RTF, Private Dirty +
      SwapPss Dirty); if it misses the tier's targets -> downgrade one tier; persist the result.
runtime: PowerManager thermal status (API 29+) / thermal headroom (API 30+, polled at most every 10 s,
      https://developer.android.com/games/optimize/adpf/thermal) or measured RTF drift (Android 8-9 and
      devices that always report NONE) -> degrade speech features (threads, partial rate, hands-free).
      The link threads and the STOP lane are never throttled.
```

**Why the Go flag is not a tier.** From Android 16, 4 GB GMS phones must also ship as Go (GSMArena, 1.4). Under a "Go means Tier L" rule, most new 4 GB arm64 phones would lose the Tier M accuracy path for no measured reason. The benchmark still downgrades any phone that cannot keep up.

The cut points 3.3 and 5.5 GiB are proposed values. Phones sold as "4 GB" report a `totalMem` below 4 GiB. The cut points are calibrated on the reference phones.

### 6.2 Feature and model sets per tier

| | **Tier L (floor)** | **Tier M (standard)** | **Tier H (plus)** |
|---|---|---|---|
| Typical phone | 2–3 GB, or any 32-bit-only userspace; Cortex-A53/A55 | 4–6 GB, arm64 (often Go from Android 16) | ≥ 6 GB, arm64 |
| ML runtimes resident | **Exactly one**: Vosk (en/hi/te grammar) **or** sherpa-onnx (CTC path, Phase 2.5/3 model) | **One**: sherpa-onnx, or Vosk if Vosk wins for that language | One, plus a second only for the cross-check |
| Command recognizer (Phase 1b) | Vosk grammar en-in 36 MB / hi 42 MB / te 58 MB, if each fits the cap. te: Phase 0 pick. ta/kn/ml: IndicConformer CTC only if the 6.3 gate passes (unlikely). | IndicConformer CTC te/ta/kn/ml (+hi if it wins). en: Phase 0 bake-off (4.6). | Same as M, plus a **second independent recognizer** cross-checking motion commands (streaming grammar + CTC). Disagreement means reject. |
| Command recognizer (Phase 2.5) | **Small multilingual streaming command model** for every language that passes the gates | Phase 1b engine, or the Phase 2.5 model where it is measured better | Same as M |
| Command recognizer (Phase 3) | Multilingual Zipformer2, small variant, where it beats Phase 2.5 | Larger variant | Larger variant + cross-check |
| Stop spotter | Vosk stop grammar on the same model (Vosk path). sherpa KWS zh-en chunk-8 on the sherpa path (English words only until Phase 2.5). | Vosk stop grammar (Vosk session) or sherpa KWS (sherpa session) + partial check on streaming engines | Same as M |
| VAD | libfvad (Phase 3: Silero) | libfvad with Vosk, Silero with sherpa | Same as M |
| Activation | PTT only | PTT; hands-free opt-in | PTT; hands-free opt-in |
| Denoiser | none | GTCRN only if A/B passes | GTCRN only if A/B passes |
| Language packs resident | 1 | 1 | 2 |
| UI snapshot rate | 5 Hz | 10 Hz | 10 Hz |
| Threads for ASR | 2–4, tuned by measured RTF | 4 | 4 |
| **Hard memory cap** (whole app, Private Dirty + SwapPss Dirty, `dumpsys meminfo` TOTAL row) | **≤ 200 MB on 2 GB phones** (proposal; Phase 0 ratifies it from the no-lmkd-kill tests; the number may move, the metric does not) | ≤ 400 MB (proposal) | ≤ 800 MB (proposal) |
| **Soft cap: file-backed resident** (mapped model files) | ≤ 200 MB, with a **re-fault check**: after a memory-pressure event, the next decode must stay within its p95 target | ≤ 300 MB | ≤ 600 MB |
| **Gate** | No lmkd kill in a 30-min foreground session **and** in a 5-min background test with a heavy foreground app (10.5) | Same | Same |
| **Storage budget** (APK split + packs, target) | ≤ 300 MB including install headroom | ≤ 450 MB | ≤ 800 MB |

**Tier L per-engine memory (re-derived; every row is an estimate until Phase 0).**

| Engine | Private Dirty (heap) | File-backed resident during decode | Tier L outlook |
|---|---|---|---|
| App baseline: Flutter shell, ART, service, audio, link, parser | **unknown.** Measured in Phase 0 on v0.2.0 with no model loaded and on the Phase 1a build. The earlier "≤ 50–60 MB" figure is withdrawn as unsupported. | small | sets how much of the cap is left for the model |
| Vosk small grammar (en-in, hi, te) | **100–300 MB.** Lower bound about the on-disk size of the loaded components (36–58 MB packs) plus decoder state; upper bound the vendor's "about 300 MB runtime" ([Vosk models](https://alphacephei.com/vosk/models)). All heap: stock vosk-android has no mmap option (4.6). | about 0 | fits only if measured model Private Dirty ≤ cap − app baseline. If not, en/hi/te on Tier L also wait for Phase 2.5. |
| IndicConformer CTC int8, ORT defaults | about the weight size (130–190 MB) in **prepacked heap copies**, plus activations | low after load (mapped pages go cold once prepacked) | **fails** the 200 MB cap |
| IndicConformer CTC with `disable_prepacking` or externally saved prepacked initializers | activations + arena: tens of MB (estimate) | **130–190 MB, touched on every decode** | about 200–350 MB resident in total, mostly reclaimable but **re-faulted after pressure**. Over the soft cap at the high end, and slow on A53 (R17). Likely no-go. |
| whisper-tiny int8 (ORT), te/kn | unknown | about 99 MB | unknown. Disqualified if it pads to 30 s (4.6). |
| English KWS (zh-en 3M / gigaspeech 3.3M) | small (estimate < 20 MB) | about 5–13 MB | fits |
| Phase 2.5 command model | target ≤ 30 MB | target ≤ 20 MB | designed to fit |

**Rules.**
- `onTrimMemory` releases only non-flight caches, never the STOP lane or the resident model during a flight.
- No allocations in the audio and link loops.
- The trimmed command-only Vosk graph is dropped as an idea. It would need Vosk's compile package, which Vosk does not publish for Telugu ([Vosk adaptation](https://alphacephei.com/vosk/adaptation)), and it does not change the heap-loading behaviour.

**Native library sizes per ABI** (uncompressed, from research). Phase 1b armv7: `libvosk` 9.0 MB, ORT 10 MB, sherpa JNI 2.1 MB, plus the codec and the Flutter engine. Phase 3 drops Vosk and uses an operator-reduced ORT build, whose size is measured then; no figure is asserted. APK size per split is measured in Phase 1b, not asserted here.

### 6.3 32-bit armv7 handling

- Build every native component for `armeabi-v7a`: the codec; Vosk, which ships it; sherpa-onnx/ORT, which ships it; libfvad; and Flutter. Removing whisper.cpp, llama.cpp and Cactus (Phase 1b) removes the reason for ARM64-only.
- A 32-bit-only userspace means Tier L regardless of RAM.
- **Tier L non-streaming gate.** This gate decides voice on Tier L for ta, kn and ml, and for te if a non-streaming engine is picked. On R1 and R2, all of these must hold:
  - the 130–190 MB pack loads, with external data memory-mapped, in a 32-bit process (*unverified* today);
  - decode RTF ≤ 0.15 (3 s command ≤ 450 ms p95), the **A53/armv7 go/no-go** (R17);
  - T0 → plan p95 ≤ 800 ms;
  - **Private Dirty + SwapPss Dirty within the hard cap, and file-backed resident within the soft cap with the re-fault check** (6.2);
  - no lmkd kill in the 30-minute and background tests (10.5).
  
  If the gate fails, those languages get the tap pad and typed commands on Tier L until **Phase 2.5**, or the assist node in Phase 4.
- 16 KB alignment applies to 64-bit libraries. The alignment of the prebuilt sherpa-onnx `.so` files is *unverified*, and `check_android_package.py` checks it.

---

## 7. Multilingual strategy

### 7.1 Languages and availability

Telugu (default), Hindi, Indian English, Tamil, Kannada and Malayalam. Hinglish and Tenglish code-mixing is common.

**Expected voice availability** (STOP buttons, tap pad and typed commands work everywhere, always):

| Language | Tier L, Phase 1b | Tier L, Phase 2.5 | Tier M/H, Phase 1b | Native voice STOP on Tier L |
|---|---|---|---|---|
| en-IN | Vosk grammar, if it fits the cap | command model | bake-off winner | yes (Vosk) / KWS |
| hi | Vosk grammar, if it fits the cap | command model | Vosk-hi or IndicConformer-hi | yes (Vosk) |
| te | Phase 0 pick, **only if it passes the corpus gates** | command model | IndicConformer-te or Vosk-te | only with Vosk-te or Phase 2.5 |
| ta, kn, ml | **unlikely** (6.3 gate) | command model | IndicConformer | from Phase 2.5 |

### 7.2 One canonical grammar, many surfaces

A single intent schema (4.8) is paired with a reviewed per-language lexicon. Each lexicon contains:

- native verbs and directions
- **English command loanwords in native script** (టేకాఫ్, ల్యాండ్, ఫార్వర్డ్, टेकऑफ, लैंड, होल्ड)
- Latin English forms, only for engines with a Latin lexicon (the en-IN models and the Phase 2.5/3 models)
- number words (7.4)
- unit words (metre and its loan forms)
- connectives: the existing en/hi/te lists; ta (பிறகு, மற்றும்), kn (ನಂತರ, ಮತ್ತು) and ml (ശേഷം, പിന്നെ) are provisional
- negation words
- stop words
- 200–2,000 fillers

The ta, kn and ml tables are **provisional until native speakers review them**.

### 7.3 Code-mixing

Code-mixing comes from the grammar union, not from language ID. Every language grammar includes the English command subset written in that script, so "డ్రోన్ ని 3 మీటర్లు ముందుకు" parses whenever its tokens are in the lexicon.

For IndicConformer, English words are expected to come out in native script, because each language's vocabulary is masked ([parismita packs](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)). This is *inferred, to be confirmed on recorded audio*. The lexicon carries those spellings.

Vosk hi and te drop Latin-script words ("Ignoring word missing in vocabulary: stop", host log), so their grammars use the native-script forms.

The Phase 2.5 and Phase 3 models are trained on code-mixed command prompts, with one shared token set across scripts.

### 7.4 Numerals

Per-language tables cover:
- 0–100, half, point five, one and a half and two and a half;
- English number words in native script;
- digits, in case a model emits them.

They are generated into both the recognizer artifacts and the parser. Numbers are **slots**: they must match exactly, with no fuzzy matching (4.8). Illustrative rows (the lexicon in `:grammar` is authoritative):

| Value | en | hi | te |
|---|---|---|---|
| 3 | three | तीन, थ्री | మూడు, త్రీ |
| 5 | five | पाँच, फाइव | ఐదు, ఫైవ్ |
| 10 | ten | दस, टेन | పది, టెన్ |
| 0.5 | half | आधा | అర |
| 1.5 | one and a half | डेढ़ | ఒకటిన్నర |
| 2.5 | two and a half | ढाई | రెండున్నర |

"and" inside a number does not split a clause (current behaviour, kept). Out-of-range numbers are recognised and then rejected with a message (4.8).

### 7.5 Stop and negation words

These are provisional and need native-speaker review:

- **en:** stop, hold
- **hi:** रुको, रुक जाओ, स्टॉप
- **te:** ఆగు, ఆపు, స్టాప్
- **ta:** நிறுத்து, நில், ஸ்டாப்
- **kn:** ನಿಲ್ಲಿಸು, ನಿಲ್ಲು, ಸ್ಟಾಪ್
- **ml:** നിർത്തൂ, നിൽക്കൂ, സ്റ്റോപ്പ്

On non-streaming engines these are recognised only after release (4.12). The native-language voice spotter for ta, kn and ml arrives with Phase 2.5. Negation words get the same per-language review.

### 7.6 Engine by language and tier

The Phase 1b engines are in Section 4.6. Phase 2.5 adds the small streaming command model for Tier L, and Phase 3 adds one multilingual Zipformer2 and a multilingual KWS. Decoding is constrained to the selected language's grammar plus the English subset.

### 7.7 Feedback

Prerecorded clips per language, earcons and haptics. No TTS on Tier L.

### 7.8 How to add a language (for example Marathi or Bengali)

1. Add `grammar/lexicon/<lang>.yaml` with verbs, directions, word orders, loanwords in that script, number table, units, connectives, negation, stop words and fillers. Get native-speaker review.
2. Run `tools/grammar-gen`. The build fails if any command word is missing from the target model's vocabulary.
3. Pick an engine. An IndicConformer-120M CTC pack exists for all 22 scheduled languages ([AI4Bharat](https://github.com/AI4Bharat/IndicConformerASR)). A Vosk grammar works where a lookahead small model exists. The Phase 2.5 model needs a fine-tune on synthetic plus real prompts for the new language.
4. Record the evaluation corpus (Section 10.2) and pass the per-language gates.
5. Record the prompt clips.
6. Publish a model pack with its manifest.

No code change outside the `:grammar` data and the pack manifest should be needed.

---

## 8. Safety model

### 8.1 Authority, highest first

1. **The RC pilot.** A transmitter is mandatory for real hardware, with motor E-stop on a spring-return aux switch and BRAKE on a mode-switch position. The mode switch and sticks override the phone. On PX4, stick movement in Hold switches to Position mode ([PX4 Hold](https://docs.px4.io/main/en/flight_modes_mc/hold.html)). **Crew:** a two-person crew by default. In single-operator mode, the RC switches are the STOP, with no phone in the loop (1.3).
2. **The onboard autopilot:** GCS, RC, battery and EKF failsafes, geofence, BRAKE, sysid filtering and signing. It keeps working if the phone dies. **The phone does not automatically override a mode the autopilot or pilot chose** (4.12).
3. **The phone's `SafetySupervisor`:** validation, confirmation, the home-distance envelope, the STOP lane and plan aborts.
4. **The voice pipeline, which is untrusted input.**

### 8.2 Invariants (each has a test)

- **S1.** Voice, typed and tap input can only produce a closed allowlist intent through an exact template match, with exact-match slots and the `[unk]` policy. There is no LLM, no generated code, and no path to arbitrary MAVLink.
- **S2.** Every motion plan is validated and needs **one confirmation**.
  - Validation covers the 1–10 m soft limits, the altitude envelope, the home-distance limit, step order, and ground/airborne state.
  - The confirmation is bound to the plan, works once and expires after 15 s. It is invalidated by new input, Cancel, backgrounding, disconnect, a readiness change or toggling execution.
  - Each step is re-validated before it is sent.
- **S3.** ACCEPTED means the command started, not that it finished. Step completion comes from telemetry. Any UNKNOWN or REJECTED step stops the plan. No plan step is retried blindly. Nothing is replayed after a reconnect. An external mode change aborts the plan at once.
- **S4.** **STOP needs no confirmation, ever, while the app owns the flight mode.** It uses the state-dependent action in 4.12, and never sends a forced disarm or flight termination. **While the autopilot or pilot owns the mode** (failsafe LAND/RTL/SmartRTL, fence action, RC switch):
  - voice and partial-token triggers are disabled;
  - physical STOP sends nothing without an explicit 1 s hold of the on-screen button.
  
  The re-send is bounded and followed by an alarm.
- **S5.** Cancel during execution equals STOP. It never disconnects.
- **S6.** Dry run is the default. The execution switch applies per connection and is never saved. The real-hardware profile is locked behind the audit (8.4).
- **S7.** The foreground service keeps the heartbeat, telemetry and STOP alive in the background. If the process is killed or frozen, that counts as link loss, and the onboard GCS failsafe takes over. The app never auto-resumes a plan.
- **S8.** Thermal or memory pressure may degrade speech, never the link or STOP.
- **S9.** BODY_OFFSET_NED moves are never retried or duplicated.
- **S10.** Recognizer disagreement (Tier H cross-check, Phase 4 assist, Vosk n-best) means reject, never "pick one".
- **S11.** Every safety-relevant event is timestamped into the `.tlog` and the JSONL trace for incident review. That includes every STOP, every override, and every mode-owner change. Audio is stored only with explicit evaluation consent.
- **S12.** SETUP_SIGNING is sent only over a direct phone-to-FC USB cable. The link layer refuses it on UDP, TCP and radio transports.
- **S13.** No plan may project the vehicle beyond `max_home_distance_m` from home. On the real-hardware profile, moves are rejected while home is unknown.

### 8.3 What the app may and may not do

| The app MAY | The app MAY NOT |
|---|---|
| Arm, take off to 1–10 m, move ≤ 10 m in the body frame within the home-distance limit, BRAKE, LAND, report status (RTL after review) | Send forced disarm, flight termination or motor test, or set parameters during flight |
| Disarm, non-forced, only when ON_GROUND, as part of STOP in an app-owned mode | Override an autopilot failsafe or pilot-selected mode without the explicit 1 s STOP hold |
| Read parameters for the audit | Fly without an RC transmitter and an RC pilot on real hardware |
| Provision a signing key over a direct phone-to-FC USB cable | Send SETUP_SIGNING over UDP, TCP or any radio, or broadcast it |
| Use Bluetooth for viewing telemetry | Write failsafe, fence or stream-rate parameters silently (the audit reports and blocks; the operator fixes them in a GCS) |
| | Use Bluetooth, the assist node or the cloud for control |

### 8.4 Real-hardware prerequisites (the real-hardware profile stays locked until all pass)

**Parameter audit.** A `PARAM_REQUEST_READ` audit must pass all of these:

- **GCS failsafe:** `FS_GCS_ENABLE ≠ 0` and `FS_GCS_TIMEOUT ≤ 5 s` ([GCS failsafe](https://ardupilot.org/copter/docs/gcs-failsafe.html)).
- **RC failsafe** enabled, and the **battery failsafe** configured.
- **Fence consistent with the app's envelope**, not just the legal ceiling ([geofencing](https://ardupilot.org/copter/docs/common-geofencing-landing-page.html)). The values below are proposals to ratify; 120 m is only the legal ceiling.
  - `FENCE_ENABLE = 1`;
  - `FENCE_TYPE` altitude + circle (+ polygon);
  - `FENCE_RADIUS` ≤ 50–100 m and ≥ `max_home_distance_m` + margin;
  - `FENCE_ALT_MAX` ≤ 15–30 m;
  - `FENCE_ACTION` RTL/LAND or BRAKE/LAND.
- **GCS sysid filtering.** On 4.7: `MAV_GCS_SYSID` equal to the app's sysid, and `MAV_OPTIONS` bit 0 ([GCS_MAVLink_Parameters.cpp](https://github.com/ArduPilot/ardupilot/blob/master/libraries/GCS_MAVLink/GCS_MAVLink_Parameters.cpp)). Older firmware uses `SYSID_MYGCS` / `SYSID_ENFORCE`. **Verify on the firmware actually flown.**
- **Stream rates:** the `SRx_*` / `MAVn` stream groups on the radio's serial port at 0, or at the documented ELRS values if a third-party display shares the link, so the downlink budget in 4.15 holds.
- **Signing:** MAVLink 2 signing on for every link that reaches an FC serial or telemetry port, USB-OTG radios included (4.13).

**Operational prerequisites:**
- RC transmitter bound and tested, BRAKE on a mode-switch position, and the crew model chosen (1.3).
- The STOP lane proven in SITL, including the GPS-denied case and the failsafe-ownership case, and on the bench over the real radio.
- The first flights tethered or caged.

**Radio-band legality (Phase 2 prerequisite):**
- Common SiK variants are 433 MHz or 915 MHz. India's delicensed sub-GHz band is around 865–867 MHz (*to verify with WPC*). ELRS 2.4 GHz use also needs confirming (*verify*).
- Phase 2 does not exit until the compliant SiK and ELRS band variants are named and procured.

**India compliance hooks:**
- altitude ceiling (green zone 120 m, [Drone Rules 2021](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2022/jan/doc202212810701.pdf));
- a cached zone map;
- storage for the UIN and the Remote Pilot Certificate;
- a `.tlog` archive;
- a pluggable NPNT / Remote ID module.

---

## 9. Migration plan from v0.2.0

The phases have no fixed durations; scope depends on the Phase 0 results. Every phase keeps the existing test suites green, adds its own tests, and runs SITL CI.

### Phase 0: hotfix and measurement (ships as 0.2.x)

**Hotfix, in the current Dart transport:**
- `_cancel()` during execution sends BRAKE (DO_SET_MODE 17) and keeps the link up.
- STOP/HOLD skip confirmation.
- Mode changes are accepted on COMMAND_ACK.
- On-screen STOP is always visible.

**Measurement:**
- Buy the reference phones (10.1).
- Build the benchmark harness: an Android instrumented app that runs each engine on injected clips and records:
  - load time, release → final, and RTF;
  - **Private Dirty + SwapPss Dirty, file-backed resident, and TOTAL PSS**;
  - re-fault latency after a pressure event;
  - RTF after 10 minutes.
- Measure:
  - Vosk grammar (en/hi/te), including whether `setMaxAlternatives` yields alternatives in grammar mode;
  - IndicConformer CTC (te/ta/kn/ml/hi) under ORT defaults, with `disable_prepacking`, and with externally saved prepacked initializers;
  - whisper-telugu-tiny and whisper-kannada-tiny, including **whether sherpa-onnx pads short clips to 30 s** and their memory;
  - the zh-en chunk-8 and gigaspeech chunk-16 KWS on English "stop" and "hold";
  - the app baseline memory with no model loaded;
  - audio input latency (loopback click);
  - the v0.2.0 baseline.
- Record the first native-speaker command corpus (10.2), including early-release takes.
- Commit the host scripts (`vosk_bench.py`, `vosk_filler.py`, `vosk_load.py`, `fstsyms.py`) under `tools/bench/`.
- Export the Dart parser and plan tests as golden JSON vectors, including the two `[unk]` cases.
- Start legal review of TEN-VAD, of the IndicVoices/Kathbath commercial terms, and of radio bands.

**Exit criteria:**
- A SITL test proves STOP mid-move gets a BRAKE ACK and Cancel equals STOP.
- The benchmark table is filled for every engine × language on R0–R4.
- Ratified from data:
  - the Phase 1b engine matrix (4.6), including the Telugu pick weighed against the voice-STOP gap;
  - the Tier L memory caps (6.2);
  - the A53/armv7 go/no-go (R17).
- The Phase 2.5 vocabulary and data plan are scoped.
- Golden vectors are exported.

### Phase 1a: native link, executor and STOP lane (safety and latency first)

**Scope:**
- **`FlightSessionService`** (FGS type `connectedDevice`): Kotlin link threads, the buffer-level JNI codec, the executor, the supervisor with mode ownership, and the transport telemetry profiles.
- **STOP lane:** the physical triggers (button, Volume-Down, notification with the `POST_NOTIFICATIONS` flow) and Cancel, with the state-dependent and mode-owner-aware action.
- **Validator:** a **Kotlin `PlanValidator`** ported first, including the home-distance check and gated on the validation golden vectors.
- **Speech stays as it is.** The existing Dart speech stack and Dart parser still produce the plan and call `submitPlan(plan)` through Pigeon. The service re-validates the plan and owns confirmation, execution and STOP.
- **UI:** Flutter reduced to a thin client for flight control, with the screen split into 4 screens.
- **Observability:** traces for link, move, takeoff and STOP.
- **Codec builds:** Linux x86_64 host build (for JVM and SITL CI) and x86_64 Android debug build (for the emulator).
- **SITL CI** (10.4), including the BODY_OFFSET_NED versus DO_REPOSITION comparison.
- The build stays ARM64-only, because whisper.cpp, llama.cpp and Cactus are still present.

**Exit criteria:**
- All SITL CI scenarios are green (10.4), including fault injection, failsafe ownership, GPS-denied fallback, home distance and the ELRS-shaped link.
- STOP-button → BRAKE ACK p95 ≤ 200 ms over Wi-Fi to SITL, measured under UI load on arm64 reference phones (R3, R4, and R2 if its userspace is 64-bit).
- Confirm → ACK, confirm → echo and confirm → motion-onset targets (Section 5) are measured and ratified from SITL.

### Phase 1b: speech in the service

**Scope:**
- **Service:** FGS type `microphone|connectedDevice`. Audio capture moves into the service: ring buffer, pre-roll, adaptive tail, silenced-capture detection.
- **Grammar and parser:** `:grammar` plus `tools/grammar-gen`. The **Kotlin parser port is gated on 100% of the golden vectors** and replaces the Dart parser behind Pigeon.
- **Recognizers:**
  - resident Vosk command and stop grammars with fillers, n-best margin and the partial-result spotter;
  - sherpa KWS and IndicConformer CTC on Tier M/H, and on Tier L only per the 6.3 gate;
  - the Tier M English bake-off winner.
- **PTT:** the bar and Volume-Up. STOP keys end PTT.
- **Build:** multi-ABI (armv7 + arm64), with whisper.cpp, llama.cpp and Cactus removed.

**Exit criteria:**
- 100% of golden vectors pass on the Kotlin port, including the `[unk]` vectors.
- The armv7 and arm64 splits install and run on R0–R4. `check_android_package.py` passes for both ABIs.
- On R1 and R2 (Tier L streaming path):
  - T0 → plan meets the Section 5 targets, with the p95 ratified;
  - the memory caps hold;
  - no lmkd kill in the 30-minute and background tests.
- Spoken STOP meets G2 for the engines in use.
- Per-language command accuracy is no worse than v0.2.0 on the corpus.

### Phase 2: real-hardware readiness and delivery

**Scope:**
- USB-OTG serial (SiK, ELRS) in **legal band variants**.
- Wi-Fi MAVLink-socket binding and the low-latency lock, measured.
- Signing, with direct-USB key provisioning and the SETUP_SIGNING transport refusal.
- The parameter-audit gate, including fence radius and altitude and stream rates.
- Play Asset Delivery packs, tier detection and the first-session benchmark.
- **Headset press-to-start/press-to-end validation** on the field headsets (or drop it).
- Optional single-operator mode and optional hardware-key confirm, both behind user trials.
- Real-link bench tests, including the ELRS and SiK telemetry profiles.
- Tethered field hardware-in-the-loop with an RC pilot.

**Exit criteria:**
- Gates 10.5 and 10.6 pass.
- A signing mismatch is rejected on a real FC, and the audit blocks a non-compliant FC.
- Radio bands are confirmed legal.
- A tethered field trial completes, with every UNKNOWN, every false STOP and every override reviewed.
- The Compose gate (4.17) is evaluated and its decision recorded.

### Phase 2.5: small multilingual command model (closes the Tier L language gap)

**Scope:**
- Train, export and ship the 4.7.1 model per language.
- Native-language voice STOP and partial-token STOP on Tier L.
- Tier M/H switch to it only where it is measured better.

**Exit criteria (per language):**
- The 10.2 gates are met on R1/R2: exact match ≥ 95%, false accepts ≤ 1%, stop recall ≥ 99%.
- Private Dirty is within the Tier L cap.
- T0 → plan is within the Tier L streaming targets.

### Phase 3: single runtime

**Scope:**
- The multilingual Zipformer2 (small and large variants) and the multilingual KWS in sherpa-onnx.
- Delete `:speech-vosk`.
- An operator-reduced ORT build.
- The PX4 adapter.
- The Tier H cross-check.

**Exit criteria:**
- On the corpus, per language, the new model meets the 10.2 gates and is no worse than the Phase 1b/2.5 engine it replaces.
- Tier L memory and latency are no worse than Phase 2.5.
- Multilingual stop-word recall ≥ 99%.

### Phase 4: optional extensions

The assist node and LAN model mirror (4.21). Cloud ASR, once a key proxy exists. The companion-computer fan-out. Compose, if the gate fired.

---

## 10. Validation and benchmarks plan

The thresholds are **proposed acceptance targets for the team to ratify**, not measured values.

### 10.1 Reference devices

These are candidates. **Verify the SoC, RAM, `getprop ro.product.cpu.abilist` and `ro.config.low_ram` on the actual unit bought.**

| ID | Role | Candidate | Why |
|---|---|---|---|
| R0 | Oldest OS, Cortex-A53 | Oppo A3s or Galaxy J6+ (Android 8.x) | The only phones with published low-end numbers (TEN-VAD RTF) |
| R1 | Floor, 32-bit Go, Cortex-A53 (no dot-product) | A 2 GB Helio G36 Android Go phone (e.g. Redmi A2) with 32-bit-only userspace | Tier L worst case; the A53/armv7 go/no-go |
| R2 | Floor, Go, Cortex-A55 | A 2 GB Unisoc SC9863A Android Go phone (e.g. Galaxy A03 Core, Nokia C12) | The most common Go SoC class |
| R3 | Low-mid, 4 GB, **non-Go** arm64 | A 4 GB Helio G85-class phone running full Android (e.g. Redmi 12C 4 GB); confirm `ro.config.low_ram` is false | Tier M lower edge. The Moto E13 is not used: it ships Android 13 Go edition ([Wikipedia](https://en.wikipedia.org/wiki/Moto_E13)) |
| R3b | 4 GB **Go** arm64 (extra data point) | Any 4 GB phone shipping Android 16 Go edition | Checks that the Go flag selects the strict profile without demoting the tier (6.1) |
| R4 | Mid, 4–6 GB | Snapdragon 680 (e.g. Redmi Note 12 4G, Galaxy A23) | Tier M typical |
| R5 | High (optional) | Any arm64 phone with ≥ 8 GB | Tier H cross-check |

### 10.2 Speech accuracy corpus, per language

- **Speakers:** 30–50 native speakers per language. Recording starts in Phase 0; the first 10 per language feed Phase 2.5 training, and the rest are held out.
- **Material:**
  - the full generated command list and code-mixed phrasings;
  - a negation set;
  - an out-of-grammar set (chatter, near-miss phrases, number near-misses such as five/fifteen/fifty);
  - **early-release takes** (PTT released before the last syllable);
  - stop words spoken during and outside PTT.
- **Conditions:** recorded on the reference phones, outdoors, with a drone hovering 5–20 m away and in wind, with and without a headset.
- **Metrics:**
  - **exact command match** on motion commands (proposed ≥ 95%);
  - **false accepts** on the out-of-grammar, negation and number-near-miss sets (proposed ≤ 1%; any wrong motion command or wrong number counts as a severe failure);
  - **stop-word recall** (proposed ≥ 99%);
  - **false STOPs per hour** of chatter and rotor noise (reported; no threshold until measured);
  - the early-release recovery rate (adaptive tail on versus off);
  - open-vocabulary WER per engine, for comparison only.
- **Decides:**
  - the Phase 1b matrix: Vosk-te vs IndicConformer-te vs whisper-telugu-tiny; Vosk-hi vs IndicConformer-hi; the English bake-off including Vosk en-in grammar;
  - whether GTCRN or a headset is required;
  - the CTC margin thresholds and the n-best Δ.

### 10.3 Host CI (every commit)

- Pure-JVM tests for `:grammar`, `:command`, `:safety`, `:vehicle` and `:link`, using the Linux x86_64 host build of the codec.
- Golden vectors from the Dart suite, including the `[unk]` vectors.
- Grammar round-trip tests:
  - every generated phrase maps to its intent;
  - every filler, out-of-grammar and negation fixture is rejected;
  - number tables per language, and **no fuzzy match ever changes a slot value**;
  - every command word is present in the target model's vocabulary.
- Link tests: the SETUP_SIGNING refusal on UDP, TCP and radio transports; the pre-encoded STOP frame's CRC and signature are valid after the sequence and confirmation fields change.
- Codec CTest plus JNI tests.
- Speech regression: replay the labelled corpus through desktop Vosk and sherpa with the production grammar. A regression in exact match or false accepts fails the build.

### 10.4 SITL CI (Linux runner, ArduCopter SITL, reusing `check_sitl.py`, driving the same Kotlin `:vehicle` and `:link` modules over UDP)

**Motion and STOP:**
- Takeoff, BODY_OFFSET_NED move, hold, land.
- STOP mid-move must reach a BRAKE ACK; Cancel must equal STOP.
- STOP while armed on the ground must disarm (non-forced).
- **A non-forced disarm while airborne must be refused**, with BRAKE used instead.
- **GPS denied** must refuse BRAKE and run the fallback (LAND or ALT_HOLD per the decision). LOITER is never sent.

**Mode ownership:**
- **Battery failsafe LAND (simulated battery drop) plus an injected false voice STOP must not change the mode.** The banner must be raised. A physical STOP without the hold sends nothing. The 1 s hold override sends BRAKE and is logged.
- An RC mode switch during a plan must abort the plan, set `modeOwner` to EXTERNAL and disable voice STOP.
- An external mode change must abort the plan.

**Link and envelope:**
- Mode-change ACK latency; confirm → echo; confirm → motion onset (from the SITL dataflash log).
- Link loss must fire FS_GCS, with the app reporting UNKNOWN.
- Fence breach.
- Repeated "forward 10" must be rejected at the home-distance limit.
- A signing key mismatch must be rejected. SETUP_SIGNING over UDP must be refused.
- No replay after a reconnect.
- A BODY_OFFSET_NED frame must never be duplicated under loss.
- **ELRS-shaped link:** a UDP proxy rate-limited to 1,470 B/s down and 735 B/s up, with the SiK/ELRS telemetry profile. Confirm → ACK p95 ≤ 320 ms, with no ACK starvation.

**Fault injection:**
- 10–30% packet loss and added delay through a UDP proxy or netem;
- killing the recognizer mid-utterance;
- Wi-Fi contention between audio and MAVLink;
- process death of the service (the onboard failsafe takes over).

**Emulator:** an x86_64 emulator smoke test with injected audio, using the debug-only x86_64 native libraries.

### 10.5 Phone-to-SITL hardware-in-the-loop

A reference phone connects to SITL over Wi-Fi, with and without the low-latency lock. Measure with the on-device trace:

- confirm → ACK, confirm → echo, confirm → motion onset;
- STOP button → BRAKE ACK (p95 ≤ 200 ms), measured **while the UI is under load**;
- spoken STOP → BRAKE ACK, per spotter engine (G2);
- T0 → plan, per engine;
- **lmkd tests:** a 30-minute foreground session; then **a 5-minute background test**, with the session running while the operator opens the camera app and a maps app. The service must survive. If it is killed, the onboard failsafe must take over and the app must report it on return.
- silenced-capture detection: start another recording app mid-session; the app must switch to No voice.

### 10.6 Real-link bench (before any field flight)

- SiK, in a legal band variant, with the FTDI timer at 16 ms versus 2 ms and MAX_WINDOW at 131 versus 33.
- ELRS MAVLink at 333 Hz, with the SiK/ELRS telemetry profile. Verify the downlink load in Diagnostics.
- Measure command → ACK, command → echo and command → motion onset (from the FC's dataflash log).
- Run the parameter audit against a real FC and confirm it blocks a non-compliant configuration (fence radius, stream rates, signing).
- Validate headset press-to-start/press-to-end on the actual field headsets.

### 10.7 Field trials (only after 10.1–10.6 pass)

Tethered or caged first, with an RC pilot holding override and the chosen crew model. Log `.tlog` and JSONL traces. Review every UNKNOWN step, every rejected command, every false STOP and every override.

### 10.8 Release checks

- Multi-ABI APK and AAB: `armeabi-v7a` and `arm64-v8a` present, no x86_64 in the shipping AAB, 16 KB alignment for 64-bit, JNI and JNA symbol contracts.
- Install on R0–R4.
- Run the latency and memory regression (Private Dirty + SwapPss Dirty, file-backed resident) against the previous release.

---

## 11. Risks and open questions

### 11.1 Risks

| ID | Risk | Mitigation |
|---|---|---|
| R1 | **No phone measurements exist** for Vosk grammar, sherpa Zipformer or IndicConformer CTC on Cortex-A53, armv7 or Go. Every phone figure here is a target. | The Phase 0 benchmark gates Phase 1b. Tiering uses the first-session benchmark. |
| R2 | Tier L memory: Vosk is all heap (100–300 MB Private Dirty, estimate), and IndicConformer needs about 200–350 MB resident with any ORT option. Either may break the Tier L cap. | Measure by the right metric (6.2). ORT prepacking options. No Vosk mmap claim. If Vosk does not fit, en/hi/te on Tier L move to Phase 2.5. The hard gate is "no lmkd kill". |
| R3 | Grammar-mode accuracy with real Telugu and Hindi speakers outdoors has never been measured (only English TTS on a desktop). Vosk-te is weak (FLEURS 87.9, mostly deletions). | Phase 0 three-way Telugu benchmark. Corpus gates. Phase 2.5 model. |
| R4 | A Vosk grammar is a bigram LM and can force out-of-grammar speech onto a command, including **dropped negation** (seen in v0.2.0) and `[unk]`-padded fragments (seen in the repo test). | Fillers, the full number range, negation words in the grammar, the `[unk]` policy, the n-best margin, strict templates, confirmation for all motion, and the Tier H cross-check. |
| R5 | IndicConformer on Android exists only as community CTC exports: re-quantization is needed, there are no hotwords and no n-best, 32-bit loading is unverified, loanword spelling is unknown, and it is unclear whether the Voice of India result is 120M or 600M. | Self-hosted verified packs. Phase 0 gate. Lexicon carries native-script loanwords. Template-edit-distance margin with exact slots. Logit-level matching as a fallback. |
| R6 | The Phase 3 custom model needs GPU budget, gated data with commercial terms to check, and real recordings with rotor noise. Timeline and accuracy unknown. | Phase 2.5 delivers the Tier L gap first at far smaller scope. Corpus collection starts in Phase 0. Legal review. Alpha Cephei as an alternative. |
| R7 | **Voice-STOP gap:** every non-streaming engine (IndicConformer, Whisper) has no native-language voice STOP and no partial-token STOP. No Indic KWS model exists. Native stop-word recall and false accepts under rotor noise are unknown. The spotter's battery and CPU cost on Tier L is unmeasured. | Physical STOP lanes are primary. English KWS words. Voice spotter active only in app-owned modes. VAD gating. The Telugu pick weighs the gap. Phase 2.5 closes it. Multilingual KWS in Phase 3. |
| R8 | BRAKE needs a position estimate, and so does LOITER. The fallback for GPS-denied builds is LAND or ALT_HOLD, still to be decided. | The GPS-denied SITL scenario decides it before Phase 1a exit. |
| R9 | Every Android input path, the STOP button included, shares the main thread with Flutter. | Minimal handlers, key interception before Flutter, main-thread stall telemetry, S1 measured under load, the Compose gate, and the RC backstop. |
| R10 | The Wi-Fi low-latency lock may not engage on a drone access point without internet (100–300 ms power-save delay, slide-deck claim). USB host support is optional per the CDD. | Measure. Recommend a USB-OTG radio. Per-phone USB check in onboarding. |
| R11 | OEM battery managers may kill long sessions despite the foreground service. Volume-key PTT works only in the foreground. | Onboarding for the battery-optimisation exemption. The background lmkd test (10.5). Onboard GCS failsafe. |
| R12 | Licences: TEN-VAD's Agora non-compete; IndicVoices and Kathbath commercial terms. 16 KB alignment of prebuilt sherpa `.so` files is unverified. | libfvad / Silero instead. Legal review in Phase 0. Package check. |
| R13 | Hardware-key short-tap confirm could fire by accident. | Off by default. Enabled only after user trials. The visible plan card remains the safeguard. |
| R14 | ArduPilot parameter names (MAV_GCS_SYSID, MAV_OPTIONS, MAVn stream groups) were read from master source; the firmware actually flown may differ. The 2026 NPNT and draft Drone Bill status is unconfirmed. | The audit reads names per firmware version. Regulatory review before field use. |
| R15 | Phases 1b–2.5 still ship two speech runtimes in the APK, which grows the download. | Only one is loaded per session on Tier L. Vosk is deleted in Phase 3. |
| R16 | The assist node (Phase 4) adds pairing, encryption and topology complexity, and Wi-Fi contention with MAVLink. | Deferred. Recognition-only. USB radio for MAVLink. Disagreement means reject. |
| R17 | **Cortex-A53/armv7 slowdown.** The community IndicConformer RTF (0.04–0.07) comes from A76/A78-class cores with dot-product. The A53 lacks it, and ORT's 32-bit int8 kernels are weaker (estimate). A several-fold slowdown would push a 3 s command past 450 ms. | **Go/no-go:** decode RTF ≤ 0.15 on R1/R2, or no CTC path on Tier L. Phase 2.5 model as the planned path. |
| R18 | **A false STOP during an autopilot failsafe** would cancel a failsafe LAND or RTL. | Mode-ownership tracking. Voice off in EXTERNAL modes. Physical override only with a 1 s hold. SITL case (10.4). |
| R19 | **Headset PTT** is unreliable on Android (assistant long-press, down-up pairs, media-session routing). | Press-to-start/press-to-end with a timeout, validated in Phase 2 or dropped. The on-screen bar and Volume-Up are guaranteed. |
| R20 | **Crew:** a single operator cannot keep hands on the sticks and also operate the phone. | Two-person crew by default. Single-operator mode only with RC-switch STOP and RC-aux PTT after trials. |
| R21 | **ELRS downlink saturation** would delay ACKs behind telemetry. | Per-transport profiles (about 25–45% load), SRx zeroed and audited, the ELRS-shaped SITL link test, and Diagnostics showing the load. |
| R22 | **Radio-band legality** in India for SiK (433/915 MHz) versus the delicensed 865–867 MHz band (to verify); ELRS 2.4 GHz to confirm. | Phase 2 prerequisite. Procure compliant band variants. |
| R23 | **Android platform caveats:** both volume keys trigger the accessibility shortcut; FGS notifications are dismissible from Android 14 and need `POST_NOTIFICATIONS` on 13+; another app can silence our capture on Android 10+. | STOP keys end PTT. The notification lane is best-effort. Silenced-capture detection maps to No voice. Onboarding. |
| R24 | sherpa-onnx may not expose ORT session config keys (prepacking, mmap). | A small Apache-2.0 patch or upstream option, checked in Phase 0. |

### 11.2 Open questions (owner: Phase 0 unless noted)

1. Real RTF, release → final latency, Private Dirty + SwapPss Dirty and file-backed resident of each engine on R0–R4, and the app baseline with no model.
2. Does a 32-bit ORT process load 130–190 MB IndicConformer packs, with external data memory-mapped, reliably on a 2 GB phone? What does `disable_prepacking` cost in decode speed?
3. How does IndicConformer spell English loanwords and numbers per language: native script, digits or Latin?
4. Can the IndicConformer transducer head be exported to sherpa-onnx, which would enable hotwords? Can CTC log-probs be exposed for constrained decoding?
5. Does ArduCopter refuse a non-forced GCS disarm when airborne, on the firmware we fly? (SITL)
6. What is the best BRAKE fallback when there is no position estimate: LAND or ALT_HOLD? (SITL, Phase 1a)
7. BODY_OFFSET_NED versus DO_REPOSITION: echo reliability and latency over lossy links. (SITL, Phase 1a)
8. Does the Wi-Fi low-latency lock engage on ESP32 and ELRS Backpack access points? (Phase 2)
9. Stop-word recall and false-STOP rate under rotor noise, per language and per spotter engine, and the battery cost of the spotter.
10. Does a close-talk headset or GTCRN measurably improve command accuracy in the field?
11. Commercial terms for IndicVoices and Kathbath, and the Phase 2.5 and Phase 3 training budgets, or an Alpha Cephei quote.
12. Is Flutter's memory and main-thread cost on Tier L acceptable? (The Compose gate, Phase 2)
13. Does sherpa-onnx's Whisper path pad short clips to the 30 s window, and what is ORT Whisper-tiny's memory?
14. Does `setMaxAlternatives` produce useful alternatives in Vosk grammar mode? What `setEndpointerDelays` values suit the stop grammar?
15. Does the zh-en chunk-8 KWS recognise English "stop"/"hold" from Indian speakers as well as gigaspeech chunk-16 does?
16. Which SiK and ELRS band variants are legal in India? (WPC; Phase 2)
17. Do the field headsets deliver distinct presses for press-to-start/press-to-end, or are they claimed by the system assistant? (Phase 2)

---

## 12. Sources

**Repository evidence**
- [README.md](../../../README.md), [multi-step plans design](2026-10-11-multi-step-plans-design.md), [mobile-local control design](2026-10-08-mobile-local-control-design.md), [Vosk design](2026-10-09-vosk-design.md), [device matrix](../../testing/mobile-device-matrix.md), [simulation report](../../testing/2026-10-10-simulation.md), [Vosk grammar evaluation](../../testing/2026-10-09-vosk-grammar-evaluation.json) (8 utterances, 7/8 hinted; the `[unk]` cases), [Vosk integration](../../testing/2026-10-09-vosk-integration.md)
- [`local_control_screen.dart`](../../../app/lib/features/flight/presentation/local_control_screen.dart), [`mavlink_transport.dart`](../../../app/lib/features/flight/data/mavlink_transport.dart), [`rule_intent_engine.dart`](../../../app/lib/features/intent/data/rule_intent_engine.dart), [`plan_interpreter.dart`](../../../app/lib/features/flight/application/plan_interpreter.dart), [`mavlink_codec.cpp`](../../../app/android/app/src/main/cpp/mavlink_codec.cpp), [`LocalInferenceBridge.kt`](../../../app/android/app/src/main/kotlin/com/aeronex/app/LocalInferenceBridge.kt), [`VerifiedModelStore.kt`](../../../app/android/app/src/main/kotlin/com/aeronex/app/VerifiedModelStore.kt), [`build.gradle.kts`](../../../app/android/app/build.gradle.kts), [`AndroidManifest.xml`](../../../app/android/app/src/main/AndroidManifest.xml), [`edge/app/server.py`](../../../edge/app/server.py)
- Host experiments (session scratchpad, to be committed under `tools/bench/` in Phase 0): `vosk_bench.py` (28 clips, grammar 28/28 vs open vocabulary 22/28, RTF, FinalResult), `vosk_filler.py` (out-of-grammar rejection with 214 fillers), `vosk_load.py` (model load), `fstsyms.py` (Gr.fst vocabulary probes)
- vosk-android 0.3.75 Java API, *verified* with `javap` on `org.vosk.Recognizer` and `org.vosk.Model` in the local AAR (`setMaxAlternatives`, `setPartialWords`, `getPartialResult`, `setEndpointerMode`, `setEndpointerDelays`; `Model(String)` is the only loading constructor)

**Android platform**
- [Cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer), [lmkd](https://source.android.com/docs/core/perf/lmkd), [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types), [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes), [Android 14 behaviour changes (dismissible notifications)](https://developer.android.com/about/versions/14/behavior-changes-all), [notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission), [sharing audio input](https://developer.android.com/media/platform/sharing-audio-input), [Android 16 CDD](https://source.android.com/docs/compatibility/16/android-16-cdd), [AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio), [SCO audio](https://source.android.com/docs/core/audio/sco-audio-mgmt), [media buttons](https://developer.android.com/media/legacy/media-buttons), [accessibility shortcut](https://support.google.com/accessibility/android/answer/7650693), [memory management](https://developer.android.com/topic/performance/memory-management), [ADPF thermal](https://developer.android.com/games/optimize/adpf/thermal), [page sizes](https://developer.android.com/guide/practices/page-sizes), [NNAPI](https://developer.android.com/ndk/guides/neuralnetworks), [LiteRT NPU](https://developers.google.com/edge/litert/next/npu), [asset delivery](https://developer.android.com/guide/playcore/asset-delivery), [on-device AI delivery](https://developer.android.com/google/play/on-device-ai), [device tier targeting](https://developer.android.com/google/play/device-tier-targeting), [Wi-Fi device apps](https://android-developers.googleblog.com/2016/07/connecting-your-app-to-wi-fi-device.html), [Wi-Fi low latency](https://source.android.com/docs/core/connect/wifi-low-latency), [local network permission](https://developer.android.com/privacy-and-security/local-network-permission), [SpeechRecognizer API 33](https://developer.android.com/sdk/api_diff/33/changes/android.speech.SpeechRecognizer), [ML Kit GenAI speech](https://developers.google.com/ml-kit/genai/speech-recognition/android), [GSMArena RAM requirements, 15 Apr 2025 (secondary)](https://www.gsmarena.com/here_are_googles_new_minimum_ram_and_storage_requirements_for_android_phones-news-67387.php), [Moto E13 (Wikipedia)](https://en.wikipedia.org/wiki/Moto_E13)

**UI**
- [Flutter 3.29](https://flutter.dev/blog/whats-new-in-flutter-3-29), [Flutter supported platforms](https://docs.flutter.dev/reference/supported-platforms), [Impeller on Android](https://flutter.googlesource.com/mirrors/engine/+/refs/heads/main/impeller/docs/android.md), [Flutter vs native vs KMP (download size, startup)](https://github.com/jacobras/flutter-vs-native-vs-kmp), [Compose metrics](https://developer.android.com/develop/ui/compose/migrate/compare-metrics)

**Speech runtimes, models, VAD**
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [sherpa-onnx Android build](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html), [hotwords](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html), [KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html), [KWS models (latency stated for zh-en 3M chunk-8/16; gigaspeech ships chunk-16 only)](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html), [endpointing](https://k2-fsa.github.io/sherpa/ncnn/endpoint.html), [ORT mobile](https://onnxruntime.ai/docs/tutorials/mobile), [ORT session options config keys](https://github.com/microsoft/onnxruntime/blob/main/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h), [sherpa discussion #3199](https://github.com/k2-fsa/sherpa-onnx/discussions/3199)
- [Vosk models](https://alphacephei.com/vosk/models), [Vosk adaptation](https://alphacephei.com/vosk/adaptation), [vosk recognizer.cc](https://github.com/alphacep/vosk-api/blob/master/src/recognizer.cc), [vosk_api.h](https://github.com/alphacep/vosk-api/blob/master/src/vosk_api.h), [vosk model.cc](https://github.com/alphacep/vosk-api/blob/master/src/model.cc), [vosk-android 0.3.75](https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/)
- [IndicConformerASR](https://github.com/AI4Bharat/IndicConformerASR), [nmukthap packs](https://huggingface.co/nmukthap/vertexvoice-indic), [parismita packs](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx), [OpenVoiceOS te export](https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-te-onnx), [SwarTarang-Android](https://github.com/ONKAR-100/SwarTarang-Android), [IndicConformer-600M](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual), [Voice of India](https://voiceofindiablogs.ai.joshtalks.com/), [IndicVoices paper](https://arxiv.org/pdf/2403.01926), [Kathbath](https://huggingface.co/datasets/ai4bharat/kathbath)
- [Zipformer en-20M](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/tree/main), [Indian-English Zipformer](https://huggingface.co/Akshatkasera007/STT-streaming-zipformer-indian-en), [Vosk streaming Bengali](https://huggingface.co/alphacep/vosk-model-small-streaming-bn), [whisper-telugu-tiny](https://huggingface.co/vasista22/whisper-telugu-tiny), [whisper-telugu-small](https://huggingface.co/vasista22/whisper-telugu-small), [ippocode packs](https://huggingface.co/ippocode/indic-asr-onnx), [whisper.cpp](https://github.com/ggml-org/whisper.cpp), [Moonshine](https://github.com/moonshine-ai/moonshine), [Omnilingual 300M](https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12), [Dolphin paper](https://arxiv.org/html/2503.20212v1), [SraVaani-0.5-live](https://huggingface.co/ARTPARK-IISc/SraVaani-0.5-live), [Nemotron 3.5 streaming](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b), [Sarvam Edge](https://www.sarvam.ai/blogs/sarvam-edge/), [Saaras v4](https://www.sarvam.ai/blogs/introducing-saaras-v4), [Bhashini APIs](https://dibd-bhashini.gitbook.io/bhashini-apis), [Chirp 3](https://docs.cloud.google.com/speech-to-text/docs/models/chirp-3)
- [Silero VAD](https://github.com/snakers4/silero-vad), [TEN-VAD](https://github.com/TEN-framework/ten-vad), [TEN-VAD licence](https://github.com/TEN-framework/ten-vad/blob/main/LICENSE), [libfvad](https://github.com/dpirch/libfvad), [GTCRN](https://github.com/Xiaobin-Rong/gtcrn), [enhancement artifacts and ASR](https://arxiv.org/abs/2201.06685), [drone ego-noise](https://doaj.org/article/1adf6ed0eec447aca98a5c0fea8f2b15)
- [openWakeWord](https://github.com/dscripka/openWakeWord), [Picovoice FAQ](https://picovoice.ai/docs/faq/general/), [Picovoice Rhino](https://picovoice.ai/docs/rhino/), [Picovoice free tier end](https://community.home-assistant.io/t/fyi-picovoice-confirmed-free-tier-accesskeys-will-stop-working-after-june-30-2026/1012744)
- [Synth4Kws](https://arxiv.org/abs/2407.16840), [Indic Parler-TTS](https://huggingface.co/ai4bharat/indic-parler-tts), [multilingual TinyS2I](https://www.amazon.science/publications/multilingual-end-to-end-spoken-language-understanding-for-ultra-low-footprint-applications), [BC-ResNet](https://arxiv.org/abs/2106.04140), [MatchboxNet](https://arxiv.org/abs/2004.08531), [indic-punct](https://github.com/Open-Speech-EkStep/indic-punct), [NeMo text normalisation](https://docs.nvidia.com/nemo-framework/user-guide/25.09/nemotoolkit/nlp/text_normalization/wfst/wfst_text_normalization.html)

**MAVLink, ArduPilot, PX4, links**
- [MAVLink signing](https://mavlink.io/en/guide/message_signing.html), [ArduPilot signing](https://ardupilot.org/dev/docs/common-MAVLink2-signing.html), [MAVLink command protocol](https://mavlink.io/en/services/command.html), [MAVLink languages](https://mavlink.io/en/#supported_languages), [mavlink-kotlin](https://github.com/divyanshupundir/mavlink-kotlin), [MAVSDK server 4.0.6](https://repo1.maven.org/maven2/io/mavsdk/mavsdk-server/4.0.6/)
- [Copter Parameters.cpp](https://raw.githubusercontent.com/ArduPilot/ardupilot/master/ArduCopter/Parameters.cpp), [GCS_Common.cpp](https://github.com/ArduPilot/ardupilot/blob/master/libraries/GCS_MAVLink/GCS_Common.cpp), [GCS_MAVLink_Copter.cpp](https://github.com/ArduPilot/ardupilot/blob/master/ArduCopter/GCS_MAVLink_Copter.cpp), [GCS_MAVLink_Parameters.cpp](https://github.com/ArduPilot/ardupilot/blob/master/libraries/GCS_MAVLink/GCS_MAVLink_Parameters.cpp), [Guided mode](https://ardupilot.org/copter/docs/ac2_guidedmode.html), [Guided commands](https://ardupilot.org/dev/docs/copter-commands-in-guided-mode.html), [Brake mode](https://ardupilot.org/copter/docs/brake-mode.html), [Loiter mode](https://ardupilot.org/copter/docs/loiter-mode.html), [auxiliary functions](https://ardupilot.org/copter/docs/common-auxiliary-functions.html), [GCS failsafe](https://ardupilot.org/copter/docs/gcs-failsafe.html), [battery failsafe](https://ardupilot.org/copter/docs/failsafe-battery.html), [geofencing](https://ardupilot.org/copter/docs/common-geofencing-landing-page.html), [requesting data](https://ardupilot.org/dev/docs/mavlink-requesting-data.html), [Copter 4.7 release](https://discuss.ardupilot.org/t/copter-4-7-0-released/144650), [PX4 Offboard](https://docs.px4.io/main/en/flight_modes/offboard.html), [PX4 Hold](https://docs.px4.io/main/en/flight_modes_mc/hold.html)
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android), [FTDI latency timer](https://www.ftdichip.com/Support/Knowledgebase/settingacustomdefaultlaten.htm), [SiK configuration](https://ardupilot.org/copter/docs/common-3dr-radio-advanced-configuration-and-technical-information.html), [ELRS MAVLink (throughput per packet rate, SRx guidance)](https://www.expresslrs.org/software/mavlink/), [mavlink-router](https://github.com/mavlink-router/mavlink-router), [Mission Planner Bluetooth (range)](https://ardupilot.org/sub/docs/common-mission-planner-bluetooth-connectivity.html)

**Safety and regulation**
- [Kollmorgen: stop and emergency stop](https://www.kollmorgen.com/en-us/developer-network/stop-and-emergency-stop-function), [India Drone Rules 2021 (PIB)](https://static.pib.gov.in/WriteReadData/specificdocs/documents/2022/jan/doc202212810701.pdf)
