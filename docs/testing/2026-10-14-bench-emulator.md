# Phase 0 engine benchmark on the emulator (T14), 2026-10-14

Plan: [architecture v2, phases 0-1](../superpowers/plans/2026-10-12-architecture-v2-phases-0-1.md), task T14. Spec: [voice-drone architecture v2](../superpowers/specs/2026-10-11-voice-drone-architecture-v2.md), sections 5, 6.2, 6.3, 9 (Phase 0), 10.
Raw data: [2026-10-14-bench-emulator.json](2026-10-14-bench-emulator.json): five per-engine reports, with every clip, memory sample and probe. Harness: `app/android/app/src/debug/kotlin/com/aeronex/app/bench/` (debug builds only) and `tools/bench/run_bench.py`.

## Read this first

- **EMULATOR. Every number is from an emulator.** The guest is an x86_64 Android 15 image (`sdk_gphone64_x86_64`, AVD AeroNex_QA_API35: 4 cores, 2.5 GB RAM) running on the same AMD Ryzen 7 6800H desktop CPU as the T3 host evaluation. None of these numbers is representative of the R0-R5 reference phones (spec 10.1). Timing on a Cortex-A53, or on armv7, will be several times slower (R17). Memory should be close to arm64 for the same engine, but it was not measured on arm64 or armv7.
- **The spec Tier gates stay open.** These are the Tier L memory cap (6.2), the A53/armv7 CTC go/no-go RTF ≤ 0.15 (6.3, R17) and the T0 → plan latency targets (G1). They need real phones. The harness exports the same JSON on a phone, labelled `DEVICE` instead of `EMULATOR`.
- **Synthetic speech, very few clips.** Each engine ran on 7 eSpeak NG 1.52 clips: one voice (v0) of each phrase in its language. That is one run per engine, with no repetition sweep. The p95 values below are simply the worst of 6-7 samples. The accuracy results say nothing about real speakers (T3).
- **What one process contains.** Each engine ran alone, in a fresh app process (`am force-stop` before each start). That process holds no Flutter engine and no session service: only ART, the bench and the engine. The spec 6.2 cap applies to the whole app, so the app baseline with Flutter and the service (spec 6.2 table, first row) still has to be added. It is not measured here.

## How it was measured

`python tools/bench/run_bench.py --install app/build/app/outputs/flutter-apk/app-debug.apk --vosk en,hi,te --ctc ta --kws kws-gigaspeech-3.3M-chunk-16 --clips 7 --out docs/testing/2026-10-14-bench-emulator.json`. Exit 0, about 2 minutes on device. The emulator was started headless with `emulator -avd AeroNex_QA_API35 -no-window -no-audio -no-boot-anim -no-snapshot-save` and shut down afterwards. The debug APK is version 0.3.0+12.

1. **Packs.** The script pushes each pack into the folder the app imports from (T11), `files/import/<pack id>/<file>`. Vosk ZIPs use a bench-only layout, `files/import/<profile id>/<zip>`. `BenchActivity` then installs them through the app's own verifying stores, `ModelPackStore.import` and `VerifiedModelStore.install` (size and SHA-256 checked). It opens each engine exactly as the session does: `SpeechEngines.vosk/ctc/kws` from the verified files and the shipped grammar assets. The CTC thread count comes from `EnginePolicy`. On this 2.5 GB AVD the policy says Tier L, so CTC ran with 2 threads.
2. **Feeding.** `ClipPlayer` is an always-on microphone stand-in. It plays digital silence and the queued clip, one 20 ms frame per 20 ms, **at real time, never faster**. It goes into the real `AudioCapture` (with `EnergyVad`) and the 3 s `AudioRing`. On the bench (speech) thread, the command recognizer reads the ring through `PttUtterance`. That means a 500 ms pre-roll, press at the clip's first frame, release at its last frame, and the adaptive tail (60 ms of non-speech, 250 ms at most). `ptt_up` is the moment the clip's last frame is captured. The spotter reads the same ring through `SpotterGate`, armed for the whole clip (the in-flight case). Clips are at least 800 ms apart.
3. **Timings.** These are wall time on the bench thread:
   - `ptt_up → audio_drained` is the real-time tail;
   - `audio_drained → asr_final` is `finish()`, the final decode;
   - `asr_final → parsed` is `VoiceSession.interpret`, which runs the n-best margin, TranscriptInspector, PlanParser and CtcTemplateMatcher. This is the session's own parser path.

   RTF is the compute time inside `accept` + `finish`, divided by the audio duration (pre-roll + clip + tail). A partial stop token cancels the utterance, as the service does.
4. **Memory.** Memory is sampled before load, after load and after the last clip. Each sample records:
   - `Debug.MemoryInfo` total Private Dirty and `summary.total-swap` (SwapPss): the spec 6.2 hard-cap metric;
   - TOTAL PSS and the `summary.*` stats;
   - `/proc/self/smaps_rollup`. It was readable on API 35. The file-backed estimate is `Rss − Anonymous`.

## Results (EMULATOR, n = 7 clips per engine, one run)

**Latency and compute** (EMULATOR). ptt_up→final includes the 60 ms real-time tail.

| Engine (EMULATOR) | Threads | Load ms | ptt_up→final p50 / p95 ms | final decode p50 / p95 ms | ptt_up→parsed p50 / p95 ms | RTF p50 / p95 |
|---|---:|---:|---:|---:|---:|---:|
| vosk-en (en-in 0.4, grammar) | 1 | 645 | 76 / 85 | 15 / 23 | 79 / 191 | 0.034 / 0.043 |
| vosk-hi (hi 0.22, grammar) | 1 | 1061 | 75 / 82 | 14 / 20 | 86 / 156 | 0.044 / 0.046 |
| vosk-te (te 0.42, grammar) | 1 | 1139 | 72 / 73 | 12 / 13 | 73 / 132 | 0.032 / 0.036 |
| ctc-ta (IndicConformer int8) | 2 | 941 | 172 / 220 | 112 / 161 | 172 / 399 | 0.045 / 0.060 |
| kws-gigaspeech-3.3M-chunk-16 | 1 | 1544 | – | – | – | spotter RTF 0.024 / 0.028 |

- **Stage 1 and stage 2a are not in these numbers.** Stage 1 is the touch or key event to the service; stage 2a is the HAL and AudioRecord buffers. The emulator ran with `-no-audio`, and the clips enter after the HAL. A phone adds both (spec 5 estimates 10/30 ms and 20/40 ms).
- **The ptt_up→parsed p95 is the cold first parse in each process.** It took 64-106 ms on the Vosk paths and about 240 ms on the ta CTC path (first `CtcTemplateMatcher` for ta), against 0-7 ms afterwards. That cost is class initialisation, not parsing. The session pays it on the first voice command of a process unless the parser is warmed up when speech loads (recommendation below).
- **The CTC RTF matches the T3 host RTF** (0.039-0.043 with 2 threads on the same CPU). It says nothing about the A53/armv7 go/no-go (R17).

**Memory** (EMULATOR, MiB; one fresh process per engine, no Flutter, no session service)

| Engine (EMULATOR) | Process baseline PD+swap | Before load | After load | After decode | Load delta | TOTAL PSS after decode | File-backed after decode |
|---|---:|---:|---:|---:|---:|---:|---:|
| vosk-en | 49 | 57 | 154 | 143 | 96 | 147 | 101 |
| vosk-hi | 45 | 59 | 201 | 201 | 142 | 206 | 102 |
| vosk-te | 45 | 59 | 273 | 267 | 214 | 271 | 102 |
| ctc-ta | 45 | 58 | 278 | 293 | 220 | 309 | 113 |
| kws-gigaspeech | 46 | 53 | 89 | 87 | 36 | 102 | 113 |

PD+swap is Private Dirty + SwapPss, the spec 6.2 hard-cap metric. About 19-20 MiB of every column is SwapPss that the fresh process already has at start. Almost all of each load delta is native heap:

| Engine | Native heap after load (MiB) |
|---|---:|
| vosk-en | 102 |
| vosk-hi | 155 |
| vosk-te | 228 |
| ctc-ta | 234 |
| kws | 44 |

File-backed resident pages grow by only 11-12 MiB on a Vosk load, and by 20-29 MiB on a sherpa-onnx load. Those growths are about the size of each runtime's native libraries (`libvosk`; `libonnxruntime` + the sherpa JNI, about 30 MB on x86_64). The model data itself is not file-backed, which agrees with the spec:

- Vosk is all heap.
- sherpa-onnx 1.13.8 prepacks the IndicConformer weights onto the heap (R24).

**Accuracy** (eSpeak v0 clips, EMULATOR). EXACT means:

- for a command phrase: the expected single step;
- for "stop": a partial stop token, or the hold plan;
- for "do not take off": no plan at all.

| Engine | Exact | False accept | No plan ("say again") | Voice stop spotter on "stop" | Spotter false fires on the 6 other clips |
|---|---:|---:|---:|---|---:|
| vosk-en | 4/7 | 0 | 3 | **MISS** (Vosk stop grammar) | 0 |
| vosk-hi | 3/7 | 0 | 4 | MISS (eSpeak रुको is heard as उठो, known since T9) | 0 |
| vosk-te | 2/7 | 0 | 5 | **MISS** | 0 |
| ctc-ta | 1/7 | 0 | 6 | no spotter in this unit (the KWS runs on its own) | – |
| kws-gigaspeech (en clips) | – | – | – | **HIT**, 1.21 s after the clip start, 12 ms before the release (about 190 ms after the word; the clip ends in 200 ms of silence) | 0 |

- **No engine accepted a wrong command.**
- **"do not take off" got no plan for different reasons.** Only ta rejected it as a negation (வேண்டாம்). en heard "takeoff" and was refused for the missing altitude. hi heard "टेकऑफ़ आरोप" (not understood), and te heard nothing.
- **The en "stop" ended push-to-talk through the partial stop token,** 220 ms before the release.
- **te "stop" (ఆపు) came only in the final result,** which parses to the hold plan.
- **ta CTC transcripts are in the right script but wrong** (as in T3), so they are refused by the template matcher.

## Findings for later tasks

1. **The Vosk stop-grammar voice spotter missed the spoken "stop".** It missed in en and te on the device path, through `SpotterGate`. It is not a bench artefact, and the evidence and cause follow.
   - **Evidence.** A host replay of the same gate fed the stop grammar exactly as the device does (EnergyVad flags, reset at the onset, 300 ms replay, 600 ms hangover, 100 ms steps). It ran on Vosk 0.3.45; the scripts are in the T14 scratch folder, `diag/`. On en `stop_v0`, Vosk shows the `stop` partial only about 420-520 ms after the word ends. The second consecutive partial lands at frame 59. The gate stops feeding at frame 63, when the 600 ms hangover ends, so the margin is about 80 ms. The Vosk endpoint result, which `VoskStopSpotter` treats as its backstop, would come at about 1.6 s and is never reached. On the device (vosk-android 0.3.75) the clip was fed the same 1280 ms and the spotter never fired.
   - **Which lanes this affects.** The partial-token STOP while PTT is held is not affected (it fired on en). The KWS path is not affected (the hangover was sized for chunk-16, and gigaspeech hit). The Tier L Vosk voice STOP outside PTT is likely to miss a short isolated "stop".
   - **Suggested fix** (in `SpotterGate`/`VoskStopSpotter`, not T14 files):
     - when a segment's hangover ends, ask the spotter for its final result (`finalResult()` → `StopTokenRule.final`) before going idle;
     - or keep feeding a Vosk spotter until its endpoint.

     Then re-run `run_bench.py --vosk en,hi,te --ctc "" --kws ""`.
2. **vosk-android 0.3.75 still empties every partial with `setPartialWords(true)`.** It returned 0 non-empty partials against 9 (en) and 12 (hi) with the setting off. This confirms the T11 choice: keep `partialWords` off.
3. **The 0.3.75 `Recognizer` has `setEndpointerDelays` and `setEndpointerMode`** (found by reflection). T11 deviation 2 deferred the stop-grammar endpointer tuning because host Vosk 0.3.45 lacks these methods. It can now be wired, and that would also shorten the finding-1 backstop.
4. **Memory against the spec 6.2 Tier L hard cap** (≤ 200 MB for the whole app; indicative only, EMULATOR). Before adding any Flutter or service baseline, the engine processes alone already reach the levels below. If arm64 phones match these numbers:
   - hi, te and IndicConformer-ta are already over the cap without Flutter, so on Tier L they would need Phase 2.5, as spec 6.2 / R2 anticipated;
   - en is under it, but leaves less than 50 MiB for Flutter and the service.

   | Engine | PD+swap after load |
   |---|---|
   | vosk-te | 273 MiB |
   | vosk-hi | 201 MiB |
   | vosk-en | 154 MiB |
   | ctc-ta | 278 MiB (293 MiB after decode) |

   This decision must be made from R0-R5 data, not from this table.
5. **`EnginePolicy` on this AVD** (2.5 GB, x86_64 + arm64 ABIs) classifies the device as Tier L. It picks Vosk + the Vosk stop grammar for en/hi/te, and refuses ta: "Voice commands in this language need a phone with 4 GB of memory or more". The ta CTC numbers above were taken by opening the engine directly, which the session would not do on this device.
6. **Warm up the parser when speech loads.** One throwaway `PlanParser.parseText` per selected language would do it. This removes the 64-240 ms cold first parse from the first voice command (finding in the latency notes above).

## Not measured here

- Real phones (R0-R5), armv7 and the A53 go/no-go.
- The input path (AudioRecord `VOICE_RECOGNITION`, the HAL buffers, silenced-capture callbacks).
- The app baseline with Flutter and the service.
- The other Phase 0 measurements:
  - re-fault latency after memory pressure;
  - RTF after 10 minutes and under thermal throttling;
  - the no-lmkd-kill tests (10.5);
  - the zh-en KWS (licence unconfirmed, not pushed);
  - IndicConformer te/kn/ml (one pack, ta, was enough for this run);
  - the ORT prepacking variants (not reachable through sherpa-onnx 1.13.8, T3).

The harness runs the same way on a phone with a debug APK: `run_bench.py --serial <phone>`.

## Reproduce

From the worktree root, with an emulator or a phone attached and the debug APK built (`flutter build apk --debug`; release APKs have no `BenchActivity`):

```
python tools/bench/run_bench.py --install app/build/app/outputs/flutter-apk/app-debug.apk \
    --vosk en,hi,te --ctc ta --kws kws-gigaspeech-3.3M-chunk-16 --clips 7 --out <file>.json
```

- **Options.** `--clips 14` adds the second voice. `--threads N` overrides the CTC thread count. `--no-push` reuses what is on the device. `--serial` picks a device.
- **Starting it by hand.** `adb shell am start -n com.aeronex.app/.bench.BenchActivity --es engines vosk,ctc,kws --es langs en,ta --ei clips 7` writes `files/bench/<timestamp>.json`. Progress is in `adb logcat -s AeroNexBench`.
