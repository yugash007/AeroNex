# Host evaluation of the on-device AI step (v0.4.0)

Builds the exact Android AI code for Linux x86_64 (WSL) and runs it on a natural-command corpus:

- `app/android/app/src/main/cpp/lfm_planner.h`: the LFM2.5 planner prompt, GBNF grammar and greedy decoding;
- `cactus_jni.cpp` + `cactus_runtime.h` + `needle_tools.h`: one Cactus Needle tool call per planner step;
- `needle_result.h`: the native mapping of a Needle answer to at most one call.

`evaluate.py` then scores the outputs the way the session uses them (spec 2026-10-15: rules first, A2 refusals
final, rules per step, Needle otherwise, A4 grounding, FlightPlan rules). Nothing is sent anywhere and no tool is
ever executed.

## Inputs (all local)

| What | Where |
|---|---|
| llama.cpp source, commit `fc9ce6b9` | `../tooling/llama.cpp` |
| Needle runtime `linux-x86_64/libneedle.a` (sha256 `8556785e…b9d3`) | `../tooling/cactus/` |
| libc++ 22 runtime for that archive | `../tooling/cactus/libc++1-22.deb`, `libc++abi1-22.deb` |
| Models | `../tooling/models/LFM2.5-VL-450M-Q4_K_M.gguf`, `../tooling/cactus/needle3.cact` |

## Build and run (WSL Ubuntu 24.04: g++ 13, cmake, ninja)

```sh
W=/mnt/c/Users/yugas/Downloads/Drone-app/.worktrees
mkdir -p ~/aeronex-ai-eval/libcxx22 ~/aeronex-ai-eval/build
dpkg-deb -x $W/tooling/cactus/libc++1-22.deb ~/aeronex-ai-eval/libcxx22
dpkg-deb -x $W/tooling/cactus/libc++abi1-22.deb ~/aeronex-ai-eval/libcxx22
cmake -G Ninja -S $W/aeronex-skeleton/tools/ai-eval -B ~/aeronex-ai-eval/build \
  -DAERONEX_LIBCXX_DIR=$HOME/aeronex-ai-eval/libcxx22/usr/lib/x86_64-linux-gnu
ninja -C ~/aeronex-ai-eval/build
~/aeronex-ai-eval/build/aeronex-needle-result-test        # needle_result.h mapping checks
cd ~/aeronex-ai-eval/build && NEEDLE_TELEMETRY=0 DO_NOT_TRACK=1 ./aeronex-ai-eval \
  --lfm $W/tooling/models/LFM2.5-VL-450M-Q4_K_M.gguf --needle $W/tooling/cactus/needle3.cact \
  --cactus-lib ./libaeronex_cactus.so --corpus $W/aeronex-skeleton/tools/ai-eval/corpus.json \
  --out results.jsonl --threads 4 --timeout-ms 4500
```

Copying the two model files into the WSL file system first makes loading faster (`/mnt/c` is slow to map).

## Score

```sh
python tools/ai-eval/evaluate.py --corpus tools/ai-eval/corpus.json --results results.jsonl \
  --out docs/testing/2026-10-15-ai-planner-eval.json --markdown table.md
python -m pytest tools/ai-eval -q
```

`corpus.json`: 82 English utterances (plain, odd word order, number words, fillers, multi-step, synonyms,
negations, out-of-range, unsafe, missing numbers) and 30 hi/te/ta/kn/ml ones with a word-by-word lexicon gloss
(`plannerInput`). `expected: []` means no plan may be accepted. Host timings are a desktop CPU, not a phone.
