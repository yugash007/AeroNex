# Phase 0 engine assets and host evaluation (T3), 2026-10-12

Plan: [architecture v2, phases 0-1](../superpowers/plans/2026-10-12-architecture-v2-phases-0-1.md), task T3. Spec: [voice-drone architecture v2](../superpowers/specs/2026-10-11-voice-drone-architecture-v2.md), sections 4.6, 4.12, 4.19, 9 (Phase 0), 11.
Raw data: [2026-10-12-phase0-engine-eval.json](2026-10-12-phase0-engine-eval.json) (every transcript, timing and memory sample). Scripts: `tools/bench/`.

## Read this first

- **Host only.** Every number comes from an AMD Ryzen 7 6800H laptop (WSL2 Ubuntu 24.04, x86-64, AVX2), not a phone. A Cortex-A53 without dot-product, or armv7, will be several times slower (spec R17). Timings and memory are for **relative** comparison only. The phone harness is T14; R0-R5 numbers are still pending.
- **Synthetic speech only.** The Indic clips are eSpeak NG 1.52 formant synthesis. The English set adds the earlier Windows SAPI en-IN voices (Ravi, Heera). No native speaker was recorded. **The accuracy figures below say nothing about real users.** They show that each engine loads, decodes, and in what script and shape its output comes; the accuracy gate is the native-speaker corpus (spec 10.2).
- No real Indic speech was available on this machine. The IndicConformer models were therefore not validated on real speech here. The keyword-spotter harness *was* checked on real read speech (the recordings shipped with both KWS models).

## Verdict

| Question | Result on the host |
|---|---|
| Do the IndicConformer int8 packs load in sherpa-onnx 1.13.8? | **Yes, all four (ta, kn, ml, te).** No ConvInteger error: the chosen export quantizes MatMul only (154 `MatMulInteger`, Conv stays fp32), so **no re-quantization was needed**. |
| CTC speed | RTF p50 0.039-0.043 (2 threads), 0.069-0.072 (1 thread). A 3 s command decodes in about 0.13 s on this CPU. |
| CTC memory (sherpa, ORT defaults) | +260-270 MiB RSS at load, about +285 MiB after decoding (peak RSS 324-331 MiB including a 41 MiB Python baseline). Almost all anonymous (heap): RssFile grows by 11 MiB only. |
| CTC accuracy on eSpeak | 0/12 exact commands per language; CER 0.54-0.72. Output is always in the right script, numbers come out as native words, loanwords in native script, often split. eSpeak is not a usable accuracy proxy. |
| ORT options that cut private memory | Weights as external data + `session.disable_prepacking=1`: anonymous +57 MiB after a decode instead of +309 MiB, weights become file-backed (+166 MiB clean); RTF +7 %. **Not reachable through sherpa-onnx 1.13.8** (R24 confirmed). |
| KWS "stop"/"hold" (English TTS) | gigaspeech 3.3M chunk-16: recall 4/4, false accepts 1/80. zh-en 3M chunk-8: recall 2/4 at the default threshold 0.25, false accepts 7/80. Both find their own keywords in real read speech. "hold" fires on the loanword "hover" in Indic clips. |
| Vosk grammar vs open (Linux) | en-IN: 36/42 vs 27/42 (SAPI subset 28/28 vs 23/28, reproduces the spec's 28/28); hi: 10/14 vs 2/14; te: 1/14 vs 0/14. Grammar RTF 0.014-0.017 vs 0.08-0.38. |
| Vosk n-best in grammar mode (Q14) | **Yes**, `SetMaxAlternatives(3)` returns several alternatives in grammar mode (hi 11/14 clips, en 9/46). Their `confidence` is an utterance score of about 50-180, **not 0-1**: the spec's Δ = 0.1 margin is in the wrong units. |
| AAR | armeabi-v7a, arm64-v8a, x86, x86_64; **every .so is 16 KB aligned**; bundles ONNX Runtime 1.28.2; minSdk 21; Kotlin API with `OfflineRecognizer` + `OfflineNemoEncDecCtcModelConfig` and `KeywordSpotter`. |

## 1. Assets

All files are in `C:/Users/yugas/Downloads/Drone-app/.worktrees/tooling` (outside the repo). Catalogue: `tooling/models/sherpa/manifest.json` (url, bytes, sha256 and licence for each of the 8 downloads and the 39 files extracted from the two KWS archives, plus per-pack groupings for T11). `tools/bench/fetch_sherpa_assets.py` pins every URL and sha256 and re-verifies offline (`--offline`).

| File (relative to `tooling/`) | Bytes | sha256 (prefix) | Hash source | Licence |
|---|---:|---|---|---|
| `sherpa-onnx/sherpa-onnx-1.13.8.aar` | 50,129,134 | `633c24321e06` | GitHub release digest | Apache-2.0; bundles ONNX Runtime (MIT) |
| `models/sherpa/indicconformer/tokens.txt` | 67,605 | `ee6096763021` | none published; pinned by HF revision | MIT (AI4Bharat) |
| `models/sherpa/indicconformer/ta/model.int8.onnx` | 197,595,513 | `abb7b59d706b` | HF LFS oid | MIT (AI4Bharat) |
| `models/sherpa/indicconformer/kn/model.int8.onnx` | 197,595,728 | `b226ce7e4ea3` | HF LFS oid | MIT (AI4Bharat) |
| `models/sherpa/indicconformer/ml/model.int8.onnx` | 197,595,555 | `dcbdfa9f773d` | HF LFS oid | MIT (AI4Bharat) |
| `models/sherpa/indicconformer/te/model.int8.onnx` | 197,595,693 | `710f176e3308` | HF LFS oid | MIT (AI4Bharat) |
| `models/sherpa/kws/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2` | 32,885,699 | `68447f4fbc67` | release `checksum.txt` | **Unconfirmed**: no licence file, training data unstated |
| `models/sherpa/kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2` | 17,626,723 | `f170013b4716` | release `checksum.txt` | Apache-2.0 (model card in the archive) |

Every download matched its published hash.

**Which IndicConformer export, and why.**

| Hugging Face repo (revision) | Layout | sherpa-ready? | Licence | Decision |
|---|---|---|---|---|
| `parismitaglobalsolutions/indicconformer-sherpa-onnx` @ `9721eb71` | `<lang>/model.int8.onnx` (197.6 MB) + one shared `tokens.txt` (5,633 tokens) | **Yes**: sherpa metadata (`vocab_size`, `normalize_type=per_feature`, `subsampling_factor=4`, `model_type=EncDecCTCModelBPE`), language mask baked in, documented for `from_nemo_ctc` | weights MIT (AI4Bharat `indicconformer_stt_<lang>_hybrid_ctc_rnnt_large`, checked on HF); the repo card says Apache-2.0 for the repackaging | **Downloaded** for ta, kn, ml, te |
| `nmukthap/vertexvoice-indic` @ `10c4d4be` | `model.int8.opt.onnx` + `.onnx.data` (136.5 MB) + `vocab.txt` + mel filterbank JSON | No: own featurizer, `vocab.txt` instead of sherpa `tokens.txt`, no sherpa metadata | card: Apache-2.0 | Not downloaded. Smaller and already QUInt8 with external data; usable later only with metadata added and a tokens file written (not done) |
| `OpenVoiceOS/ai4bharat-indicconformer-te-onnx` @ `d952dec2` | onnx-asr format, te only, `model.int8.onnx` 137.7 MB, fp32 482 MB | No | MIT | Not downloaded. This is the upstream int8 file the spec says fails with ConvInteger |

The parismita export is about 60 MB larger than a fully quantized pack because the convolutions stay fp32. The spec's "re-quantize to QUInt8" step applied to the OpenVoiceOS file; it was **not needed** for this export, and no fp32 model was downloaded.

KWS archives were extracted next to themselves with path checks (`benchlib.safe_members`, `filter="data"`). The zh-en archive holds chunk-8 and chunk-16 variants (int8 encoder/joiner, fp32 decoder), `tokens.txt` (phones + partial pinyin) and the `en.phone` lexicon; gigaspeech holds chunk-16 only, with a 500-piece `bpe.model`.

## 2. sherpa-onnx 1.13.8 AAR

`tools/bench/aar_inspect.py` (full output, including `javap -public` of the key classes, in the JSON under `aar`).

| ABI | libonnxruntime.so | libsherpa-onnx-jni.so | libsherpa-onnx-c-api.so | libsherpa-onnx-cxx-api.so | LOAD p_align |
|---|---:|---:|---:|---:|---|
| armeabi-v7a | 15,359,592 | 3,428,780 | 3,202,700 | 282,008 | 0x4000 for all |
| arm64-v8a | 22,249,560 | 4,771,760 | 4,465,168 | 440,688 | 0x4000 for all |
| x86 | 26,491,148 | 5,358,928 | 5,051,192 | 392,768 | 0x4000 for all |
| x86_64 | 25,581,120 | 5,201,192 | 4,927,096 | 436,952 | 0x4000 for all |

- **16 KB pages (R12):** every LOAD segment of every library is aligned to 0x4000. Verified.
- **Needed libraries** (`readelf -d`): `libsherpa-onnx-jni.so` needs only `libonnxruntime.so` plus system libraries (`libandroid`, `liblog`, `libm`, `libdl`, `libc`). The C and C++ API libraries are not used by the Kotlin API and can be excluded from the APK (saves 3.5 MB armv7, 4.9 MB arm64). The Kotlin path then costs **18.8 MB (armv7) and 27.0 MB (arm64)** uncompressed, more than the spec's 4.6 figures (12.1 / 18.7 MB).
- **ONNX Runtime version:** 1.28.2 (`VERS_1.28.2` symbol version), the same as the sherpa-onnx 1.13.8 Python wheel used for the host bench.
- **Manifest:** package `com.k2fsa.sherpa.onnx`, `minSdkVersion 21`.
- **Native loading:** `System.loadLibrary("sherpa-onnx-jni")` runs in the companion initialiser, so these classes cannot be constructed in plain JVM unit tests.
- **Kotlin/Java API:** 103 public top-level classes in `com.k2fsa.sherpa.onnx` (ASR, KWS, VAD incl. `TenVadModelConfig`/`SileroVadModelConfig`, speech denoisers incl. GTCRN, TTS, punctuation, speaker ID, language ID). The ones later tasks need:
  - `OfflineRecognizer(assetManager: AssetManager?, config: OfflineRecognizerConfig)`: `createStream()`, `decode(stream)`, `getResult(stream): OfflineRecognizerResult` (`text`, `tokens`, `timestamps`), `release()`.
  - `OfflineRecognizerConfig(featConfig: FeatureConfig, modelConfig: OfflineModelConfig, hr, decodingMethod, maxActivePaths, hotwordsFile, hotwordsScore, ruleFsts, ruleFars, blankPenalty)`.
  - `OfflineModelConfig(..., nemo: OfflineNemoEncDecCtcModelConfig(model), ..., numThreads, debug, provider, modelType, tokens, modelingUnit, bpeVocab)`. **No ORT session-config field** (no `disable_prepacking`, no memory-mapped `.ort` option): spec R24 confirmed for 1.13.8.
  - `OfflineStream`: `acceptWaveform(FloatArray, sampleRate)`, `release()`.
  - `FeatureConfig(sampleRate, featureDim, dither)`.
  - `KeywordSpotter(assetManager, KeywordSpotterConfig)`: `createStream(keywords: String = "")` (keywords can be given per stream), `decode`, `isReady`, `getResult(stream): KeywordSpotterResult(keyword, tokens, timestamps)`, `reset(stream)`, `release()`.
  - `KeywordSpotterConfig(featConfig, modelConfig: OnlineModelConfig, maxActivePaths, keywordsFile, keywordsScore, keywordsThreshold, numTrailingBlanks)`; `OnlineModelConfig(transducer = OnlineTransducerModelConfig(encoder, decoder, joiner, qnnConfig), ..., tokens, numThreads, debug, provider, modelType, modelingUnit, bpeVocab)`; `OnlineStream`: `acceptWaveform`, `inputFinished`, `release`.
  - All config classes are Kotlin data classes with default arguments, so Kotlin callers can use named arguments.

## 3. Test clips

`tools/bench/gen_clips.py` writes 84 clips to `tooling/models/sherpa/clips-espeak/` (16 kHz mono PCM16, 0.2 s silence before and after, peak 0.7, FFT resampled from eSpeak's 22,050 Hz). Six languages × 7 phrases × 2 voices (`<lang>` at 160 wpm and `<lang>+f3` at 140 wpm). The phrases (`tools/bench/phrases.py`) are built from the rule-parser lexicons: `rule_intent_engine.dart` for en/hi/te, and the PROVISIONAL ta/kn/ml tables in `edge/app/intent.py`. A test runs every phrase through the reference parser and checks the intended command, and that the negated phrase is refused.

| id | en | hi | te | ta | kn | ml |
|---|---|---|---|---|---|---|
| takeoff_3 | take off to three metres | टेकऑफ तीन मीटर | మూడు మీటర్లు టేకాఫ్ | மூன்று மீட்டர் டேக்ஆஃப் | ಮೂರು ಮೀಟರ್ ಟೇಕಾಫ್ | മൂന്ന് മീറ്റർ ടേക്ക്ഓഫ് |
| forward_3 | move forward three metres | तीन मीटर आगे जाओ | మూడు మీటర్లు ముందుకు వెళ్ళు | மூன்று மீட்டர் முன்னால் செல் | ಮೂರು ಮೀಟರ್ ಮುಂದೆ ಹೋಗು | മൂന്ന് മീറ്റർ മുന്നോട്ട് പോകൂ |
| left_2 | go left two metres | दो मीटर बाएं जाओ | రెండు మీటర్లు ఎడమకు వెళ్ళు | இரண்டு மீட்டர் இடது செல் | ಎರಡು ಮೀಟರ್ ಎಡಕ್ಕೆ ಹೋಗು | രണ്ട് മീറ്റർ ഇടത്തേക്ക് പോകൂ |
| land | land | लैंड | ల్యాండ్ | தரையிறங்கு | ಲ್ಯಾಂಡ್ | ലാൻഡ് |
| stop | stop | रुको | ఆపు | நிறுத்து | ನಿಲ್ಲಿಸು | നിർത്തൂ |
| hold | hold | होवर | హోవర్ | ஹோவர் | ಹೋವರ್ | ഹോവർ |
| neg_takeoff | do not take off | टेकऑफ मत करो | టేకాఫ్ చేయకు | டேக்ஆஃப் வேண்டாம் | ಟೇಕಾಫ್ ಬೇಡ | ടേക്ക്ഓഫ് വേണ്ട |

## 4. IndicConformer CTC (sherpa-onnx 1.13.8 `from_nemo_ctc`, greedy)

`tools/bench/sherpa_bench.py all`. Each run is a fresh process. RTF is the median of 3 decodes per clip over clips 2-14. The first decode of a process includes warm-up. "Exact" counts the 12 command clips (the negated phrase is excluded). The Python baseline before load is RSS 41 MiB (anonymous 18, file 23).

| Lang | Threads | Load (s) | RSS after load (MiB) | anon / file | RSS at end | RTF p50 / p95 | First decode (s) | Exact | WER | CER |
|---|---:|---:|---:|---|---:|---|---:|---|---:|---:|
| ta | 1 | 1.55 | 310 | 277 / 34 | 331 | 0.070 / 0.074 | 0.168 | 0/12 | 1.04 | 0.65 |
| ta | 2 | 1.12 | 307 | 274 / 34 | 328 | 0.043 / 0.049 | 0.093 | 0/12 | 1.04 | 0.65 |
| kn | 1 | 1.44 | 309 | 276 / 34 | 330 | 0.069 / 0.073 | 0.151 | 0/12 | 0.93 | 0.55 |
| kn | 2 | 1.09 | 308 | 275 / 34 | 330 | 0.042 / 0.047 | 0.122 | 0/12 | 0.93 | 0.55 |
| ml | 1 | 1.34 | 303 | 270 / 34 | 326 | 0.072 / 0.078 | 0.161 | 0/12 | 0.89 | 0.72 |
| ml | 2 | 1.20 | 307 | 274 / 34 | 330 | 0.039 / 0.044 | 0.091 | 0/12 | 0.89 | 0.72 |
| te | 1 | 1.42 | 301 | 268 / 34 | 324 | 0.072 / 0.074 | 0.168 | 0/12 | 1.11 | 0.54 |
| te | 2 | 1.09 | 302 | 269 / 34 | 325 | 0.043 / 0.047 | 0.117 | 0/12 | 1.11 | 0.54 |

Transcripts do not depend on the thread count (identical across the 1- and 2-thread runs).

**What the outputs show (relevant to spec Q3, R5, 4.8):**
- Output is always in the language's own script. Numbers come out as **native-script words** (மூன்று, ಮೂರು, മൂന്ന്, మూడు), never as digits or Latin.
- English loanwords come out in native script and are often split or mangled: டேக்ஆஃப் → "பேக் ஆஃப்", ಟೇಕಾಫ್ → "ಕೇಕ್ ಆಪ್", ടേക്ക്ഓഫ് → "പേ കോപ്പി".
- **Word boundaries are unreliable** ("మీటర్లుటే"). A hypothesis can also **start with a dependent vowel sign** ("ೇಕಾಗಲೇನಾ", "ോന്ന", "ிறது"). The T10 normaliser must tolerate both.
- **Number near-misses appear**: te left_2 "రెండు మీటర్లు …" decoded with "నాలుగ" (four-like). te forward_3 decoded "మూడు … నాలు మూడు". This supports the spec rule that slots match exactly, with no fuzzy number snapping.
- The negation word survived in several clips ("வேண்டாம்", "വേണ്ട", "చేయదు/చేయలు" ≈ చేయకు).
- Empty output happens (kn/ml left_2, female voice).

<details><summary>All CTC transcripts (2 threads)</summary>

**ta**

| Phrase | Reference | Voice ta | Voice ta+f3 |
|---|---|---|---|
| takeoff_3 | மூன்று மீட்டர் டேக்ஆஃப் | மூன்று மீட் த்த்தயக்கா | மூன்று மீட்டா கேக்கா |
| forward_3 | மூன்று மீட்டர் முன்னால் செல் | மமி தர்முள் செயல் | மூங்க மீ |
| left_2 | இரண்டு மீட்டர் இடது செல் | இவன்ன்றுீ நற நது செய்வது | வ |
| land | தரையிறங்கு | தலாயி அங்கு | தலாயிரன்றுிரு |
| stop | நிறுத்து | நீ விஸ்டு | ிறது |
| hold | ஹோவர் | ஹோம | ஓ மேட |
| neg_takeoff | டேக்ஆஃப் வேண்டாம் | பேக் ஆஃப் வே நாம் | பேக் ஆஃப் வேண்டாம் |

**kn**

| Phrase | Reference | Voice kn | Voice kn+f3 |
|---|---|---|---|
| takeoff_3 | ಮೂರು ಮೀಟರ್ ಟೇಕಾಫ್ | ಮೂರು ಮೀಟರ್ ಪೇೇಕ | ಮೂಳು ಮೀಟರ್ ಕಗ |
| forward_3 | ಮೂರು ಮೀಟರ್ ಮುಂದೆ ಹೋಗು | ಮೂಳ ಮೀಟ ಮುಂದೆ ಹೌದು | ಳ ಮೀಟರ್ ಮು ಹದು |
| left_2 | ಎರಡು ಮೀಟರ್ ಎಡಕ್ಕೆ ಹೋಗು | ಮಿ ನ ನಕ್ಕೆ ಹದು | (empty) |
| land | ಲ್ಯಾಂಡ್ | ಅಯನ್ | ಸ್ಯನ್ |
| stop | ನಿಲ್ಲಿಸು | ನೀಲ್ಲಿಸು | ಎಲ್ಲಿಯ ಮಸೋ |
| hold | ಹೋವರ್ | ಹಓ ಮಾರ | ಹೋ ನ |
| neg_takeoff | ಟೇಕಾಫ್ ಬೇಡ | ೇಕಾಗಲೇನಾ | ಕೇಕ್ ಆಪ್ ಬೇನಾ |

**ml**

| Phrase | Reference | Voice ml | Voice ml+f3 |
|---|---|---|---|
| takeoff_3 | മൂന്ന് മീറ്റർ ടേക്ക്ഓഫ് | മൂന്ന് | മ മീ ഓ |
| forward_3 | മൂന്ന് മീറ്റർ മുന്നോട്ട് പോകൂ | മൂന്ന് മീട്ട മുന്നോട്ട് പോകു | മൂന്ന് മീ |
| left_2 | രണ്ട് മീറ്റർ ഇടത്തേക്ക് പോകൂ | മന്ല | (empty) |
| land | ലാൻഡ് | നാത് | ആ അഞ്ചപ |
| stop | നിർത്തൂ | തിപ്പൂ | നിപ്പോ |
| hold | ഹോവർ | ോന്ന | വ |
| neg_takeoff | ടേക്ക്ഓഫ് വേണ്ട | പേ കോപ്പി വേണ്ട | പേ |

**te**

| Phrase | Reference | Voice te | Voice te+f3 |
|---|---|---|---|
| takeoff_3 | మూడు మీటర్లు టేకాఫ్ | మూజు మీటర్లుటే కారు | మూ మీటే |
| forward_3 | మూడు మీటర్లు ముందుకు వెళ్ళు | మూజు మీ తలు ముందు రుమ్మెెల్ళ | మూడు మీ నాలు మూడు రుమెల్లు |
| left_2 | రెండు మీటర్లు ఎడమకు వెళ్ళు | రెడు మీ నాలుగనా నకుూ మేల్లు | ల్ మీ ననా రూమెల్లు |
| land | ల్యాండ్ | యాన్ | య |
| stop | ఆపు | ఆ | ఆప్పు |
| hold | హోవర్ | హఓ మరి | కోవా |
| neg_takeoff | టేకాఫ్ చేయకు | పగా చేయదు | పగా చేయలు |

</details>

## 5. ONNX Runtime session options vs memory (te model)

`tools/bench/ort_options_bench.py`: plain ONNX Runtime 1.31.0 from pip (sherpa-onnx cannot pass these options), 2 threads, 3 s of fixed pseudo-random 80-dim features, one process per variant. The external-data copy was written to the WSL ext4 filesystem (removed afterwards). Baseline: anonymous 23 MiB, file 27 MiB.

| Variant | Load (s) | Anonymous after load → after 6 runs (MiB) | File-backed after load → after runs | Peak RSS | RTF (3 s) |
|---|---:|---|---|---:|---:|
| default (single file) | 1.08 | 310 → 332 | 35 → 39 | 370 | 0.040 |
| `session.disable_prepacking=1` | 1.16 | 256 → 263 | 35 → 39 | 301 | 0.046 |
| weights as external data | 0.37 | 145 → 175 | 35 → 107 | 282 | 0.039 |
| external data + `disable_prepacking` | 0.22 | **51 → 80** | 35 → **201** | 282 | 0.043 |

With external data and no prepacking, the weights stay clean and file-backed (reclaimable under memory pressure and re-faulted, spec 6.2), and private memory is about 57 MiB above baseline after decoding. Resident memory during a decode is still about 280 MiB in total, as the spec predicted. Using this from the app needs a sherpa-onnx patch or an upstream option (R24); sherpa-onnx 1.13.8 exposes neither in Kotlin nor in Python. Whether 32-bit ORT maps the external file the same way is **not verified** (open question 2).

## 6. Keyword spotting: English "stop" / "hold"

`tools/bench/sherpa_bench.py kws`: 1 thread, 100 ms chunks, 0.6 s silent tail, keywords from the model's own lexicon (zh-en: `S T AA1 P @stop`, `HH OW1 L D @hold`; gigaspeech BPE: `▁ST O P @stop`, `▁HO L D @hold`), keywords_score 1.0. Positives are the 4 English stop/hold clips. Negatives are the other 80 clips in all six languages (Indic stop words are expected to be missed: the spotter is English-only).

| Model | Threshold | Recall | False accepts | RTF | Load (s) | RSS after load |
|---|---:|---|---|---:|---:|---|
| zh-en 3M chunk-8 | 0.15 | 3/4 | 11/80 | 0.024 | 0.66 | +38 MiB |
| zh-en 3M chunk-8 | 0.25 (default) | 2/4 | 7/80 | 0.023 | 0.66 | +38 MiB |
| zh-en 3M chunk-8 | 0.35 | 2/4 | 3/80 | 0.024 | 0.73 | +38 MiB |
| gigaspeech 3.3M chunk-16 | 0.15 | 4/4 | 2/80 | 0.017 | 0.74 | +39 MiB |
| gigaspeech 3.3M chunk-16 | 0.25 (default) | **4/4** | **1/80** | 0.016 | 0.79 | +39 MiB |
| gigaspeech 3.3M chunk-16 | 0.35 | 4/4 | 1/80 | 0.015 | 0.68 | +39 MiB |

- **Harness check on real speech:** both models detect "light up" (3.7 s) and "lovely child" (6.3 s / 6.6 s) in the read-speech recordings shipped with them, using keyword lines generated by our code.
- **Every false accept is "hold"**, mostly on the Indic loanword **"hover"** (ஹோவர், ಹೋವರ್, ഹോവർ, होवर). zh-en also fires on kn ಹೋಗು and ml പോകൂ inside move commands. While voice is active, "hover" in the ta/kn/ml/hi lexicons collides with the spotter's "hold". Semantically the two are close, but this must be decided in T9/T11.
- Detection came between 0.07 s before and 0.19 s after the estimated end of speech (gigaspeech 0.05-0.19 s). The resolution is 100 ms, and eSpeak's own trailing silence blurs the estimate.
- Spec Q15 (Indian speakers) **cannot be answered** from eSpeak en-us. On this audio, gigaspeech chunk-16 beat zh-en chunk-8.

## 7. Vosk grammar vs open vocabulary

`tools/bench/vosk_bench.py`. This merges the earlier scratchpad `vosk_bench.py`, `vosk_filler.py`, `vosk_load.py` and `vosk_conf.py`; `fstsyms.py` is committed separately. Vosk Python 0.3.45 (Android ships 0.3.75), models from `tooling/vosk`, one process per model. Primary run: WSL (Linux RSS split). Clips: eSpeak en/hi/te, plus for English the 32 earlier SAPI clips (Ravi, Heera: 28 commands and 4 out-of-grammar). Bench grammars come from `phrases.vosk_grammar` (numbers 1-10, every bench phrase, negation phrases, legacy English phrases; en 647, hi 152, te 274 phrases; every word is in the model's `Gr.fst` vocabulary). The en-in model has no "metres", so English references are scored with "meters".

| Model | Load (s) | Anonymous / file at load | RSS end (peak) | Mode | Exact (in-grammar) | Out-of-grammar accepted | RTF p50 / p95 | FinalResult p50 / p95 | Recognizer init p50 |
|---|---:|---|---|---|---|---|---|---|---|
| small-en-in-0.4 | 0.89 | +83 / +3 MiB | 174 (175) | open | 27/42 (SAPI 23/28, eSpeak 4/14) | 0/4 | 0.124 / 0.178 | 19.9 / 46.6 ms | 47 ms |
| | | | | grammar | 36/42 (SAPI **28/28**, eSpeak 8/14) | **1/4** | 0.014 / 0.017 | 8.4 / 12.0 ms | 1.1 ms |
| | | | | grammar + 210 fillers | 36/42 | 0/4 | 0.018 / 0.021 | 8.6 / 12.9 ms | 1.5 ms |
| small-hi-0.22 | 1.48 | +140 / +3 MiB | 280 | open | 2/14 | n/a | 0.380 / 0.515 | 31 / 63 ms | 40 ms |
| | | | | grammar | 10/14 | n/a | 0.017 / 0.019 | 5.8 / 8.3 ms | 0.6 ms |
| small-te-0.42 | 1.93 | +211 / +3 MiB | 318 | open | 0/14 | n/a | 0.081 / 0.101 | 7.1 / 15.0 ms | 100 ms |
| | | | | grammar | 1/14 | n/a | 0.015 / 0.017 | 5.1 / 7.8 ms | 0.8 ms |

- **Reproduces the spec 4.6 evidence:** SAPI grammar 28/28. Open vocabulary scored 23/28 here (the spec says 22/28; the scoring differs slightly). Without fillers "take a photo of the field" → **"take off"**; with fillers no out-of-grammar clip yields a command phrase ("take photo off field", "weather today").
- **Vosk memory is all heap:** RssFile grows by 3 MiB. This confirms spec 4.6/R2: Vosk cannot be memory-mapped.
- **n-best in grammar mode (Q14): yes.** But `confidence` in alternatives mode is an utterance score (~50-180), and the gaps that matter are 0.1-3 points:
  - hi `टेकऑफ मत करो` → top as spoken, 2nd **`टेकऑफ करो`** (negation dropped) at Δ ≈ 1.2-1.5.
  - hi+f3 `दो मीटर बाएं जाओ` → 3rd **`नौ मीटर बाएं जाओ`** (9 instead of 2) at Δ ≈ 2.1. hi 3rd `दो मीटर आगे जाओ` (other direction) at Δ ≈ 2.8.
  - te+f3 `టేకాఫ్ చేయకు` → top `""`, 2nd **`టేకాఫ్`** at Δ 0.28.

  **The spec's Δ = 0.1 is in the wrong units for Vosk alternatives.** T10 must calibrate Δ in score units (or normalise) on the corpus.
- **Dropped negation as the final result:** in the Windows run of the same Vosk version, te+f3 `టేకాఫ్ చేయకు` decoded as **`టేకాఫ్`** in grammar mode. Linux gave `""`. Having the negation words in the grammar does not guarantee they are decoded (spec R4). The confirmation step remains the backstop.
- **Linux and Windows builds differ** on borderline clips (8 transcripts; listed in the JSON under `vosk_windows`). Windows hi grammar scored 11/14 against 10/14 on Linux. CI must pin the platform.
- English failures on eSpeak are mostly "meter" vs "meters" and a dropped leading verb ("forward three meter"). The strict parser would accept many of these. Both "hold" clips returned nothing in grammar mode.
- Vosk-te on eSpeak Telugu recognises almost nothing in either mode (spec R3: Vosk-te is weak). The IndicConformer-te CTC output on the same clips is phonetically much closer (section 4). This leans toward the CTC model for te, **but eSpeak cannot decide the Telugu pick**.

## 8. Spec questions touched

| Spec item | Status after T3 |
|---|---|
| R5 / 4.6 "re-quantize to QUInt8" | Not needed for the chosen export (MatMul-only int8, no ConvInteger); all 4 packs load on x86-64. 32-bit loading is unverified. |
| R12 16 KB alignment of sherpa .so | Verified: all four ABIs, all libraries, p_align 0x4000. |
| R24 / 4.6 ORT session keys | Confirmed absent from the sherpa-onnx 1.13.8 Kotlin API and Python API. A patch is needed for prepacking/external data. Host effect measured (section 5). |
| Q2 (32-bit ORT, mmap, `disable_prepacking` cost) | Host x86-64 only: `disable_prepacking` costs about 15 % RTF on a single file and about 10 % with external data. 32-bit is untested. |
| Q3 (spelling of numbers and loanwords) | On TTS: native-script number words; native-script, often split loanwords; no digits or Latin seen. Needs real speech. |
| Q14 (Vosk alternatives in grammar mode) | Yes; units are utterance scores (see 7). `setEndpointerDelays` not tested. |
| Q15 (zh-en chunk-8 vs gigaspeech chunk-16) | On eSpeak en-us only: gigaspeech better (4/4, 1/80 vs 2/4, 7/80). Indian speakers untested. |
| 4.6 native library sizes | AAR 1.13.8: armv7 15.4 MB ORT + 3.4 MB JNI, arm64 22.2 + 4.8 MB (larger than the spec's figures). |

## 9. Reproduce

From the worktree root on Windows (Python 3.12, numpy), then in WSL (`/home/drone/sherpa-venv`: sherpa-onnx 1.13.8, numpy, soundfile, onnx, onnxruntime 1.31.0, sentencepiece 0.2.2, vosk 0.3.45; created for this task):

```
python -I tools/bench/fetch_sherpa_assets.py --tooling ../tooling            # add --offline to only verify
python -I tools/bench/gen_clips.py --espeak "C:/Program Files/eSpeak NG/espeak-ng.exe" --out ../tooling/models/sherpa/clips-espeak
python -I tools/bench/aar_inspect.py --aar ../tooling/sherpa-onnx/sherpa-onnx-1.13.8.aar --javap "C:/Program Files/Android/Android Studio/jbr/bin/javap.exe" --out aar.json
# WSL (W=/mnt/c/Users/yugas/Downloads/Drone-app/.worktrees, PY=/home/drone/sherpa-venv/bin/python):
$PY -I $W/aeronex-skeleton/tools/bench/sherpa_bench.py all --models $W/tooling/models/sherpa --clips $W/tooling/models/sherpa/clips-espeak --out sherpa.json
$PY -I $W/aeronex-skeleton/tools/bench/ort_options_bench.py --model $W/tooling/models/sherpa/indicconformer/te/model.int8.onnx --work /home/drone/ort-work --out ort.json
$PY -I $W/aeronex-skeleton/tools/bench/vosk_bench.py --models $W/tooling/vosk --clips $W/tooling/models/sherpa/clips-espeak --tsv-clips <dir with the SAPI index.tsv> --out vosk_linux.json
# Windows Vosk run (for the platform comparison):
python -I tools/bench/vosk_bench.py --vosk-python ../tooling/vosk/python --models ../tooling/vosk --clips ../tooling/models/sherpa/clips-espeak --tsv-clips <same dir> --out vosk.json
python -I tools/bench/merge_results.py --manifest ../tooling/models/sherpa/manifest.json --aar aar.json --sherpa sherpa.json --vosk vosk_linux.json --vosk-windows vosk.json --ort ort.json --clips ../tooling/models/sherpa/clips-espeak --out docs/testing/2026-10-12-phase0-engine-eval.json
python -m pytest tools/bench -q                                               # 27 tests, helpers only
```

The SAPI clips are not regenerated by these scripts. They live in the 2026-10-11 session scratchpad (`tts/`, `index.tsv`).

## 10. Not done or not verified

- No phone or emulator measurement (T14), and no armv7/arm64 run of any engine. No real Indic or Indian-English speech.
- No hi IndicConformer pack was downloaded (not in the approved list), so the "Vosk-hi vs IndicConformer-hi" comparison is open. Whisper-te/kn-tiny was not evaluated (not approved here).
- `setEndpointerDelays`/`setEndpointerMode`, re-fault latency after memory pressure, RTF after 10 minutes, and the app's no-model baseline were not measured.
- The zh-en KWS licence is unconfirmed (no licence file in the archive).
- The external-data/no-prepacking result uses pip ORT 1.31.0, not the AAR's 1.28.2, and x86-64 only.
