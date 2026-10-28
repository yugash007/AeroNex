"""ONNX Runtime session options vs memory for one IndicConformer CTC model (spec 4.6, R2, R24).

sherpa-onnx builds its own Ort::SessionOptions and exposes no config keys, so
this drives ONNX Runtime directly (pip onnxruntime, not the sherpa build) on
the same model: default, session.disable_prepacking=1, and the weights moved to
an external-data file (which ORT can memory-map). Each variant runs in its own
child process. Input is a fixed pseudo-random 80-dim feature matrix (3 s).

    python -I tools/bench/ort_options_bench.py --model <...>/te/model.int8.onnx --work <scratch dir> --out ort.json
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchlib  # noqa: E402

FRAMES = 300  # 3 s at a 10 ms hop
VARIANTS = {
    "default": ("single", {}),
    "disable_prepacking": ("single", {"session.disable_prepacking": "1"}),
    "external_data": ("external", {}),
    "external_data+disable_prepacking": ("external", {"session.disable_prepacking": "1"}),
}


def external_copy(model: Path, work: Path) -> Path:
    """The model with every initializer moved to <name>.data (written once)."""
    import onnx

    out = work / "external" / model.name
    if not out.exists():
        out.parent.mkdir(parents=True, exist_ok=True)
        m = onnx.load(str(model))
        onnx.save_model(m, str(out), save_as_external_data=True, all_tensors_to_one_file=True,
                        location=model.name + ".data", size_threshold=1024)
    return out


def run_variant(model: Path, configs: dict, threads: int, runs: int) -> dict:
    import numpy as np
    import onnxruntime as ort

    mem_before = benchlib.memory_mib()
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    for k, v in configs.items():
        so.add_session_config_entry(k, v)
    t0 = time.perf_counter()
    sess = ort.InferenceSession(str(model), so, providers=["CPUExecutionProvider"])
    load_s = time.perf_counter() - t0
    mem_loaded = benchlib.memory_mib()
    feats = np.random.default_rng(0).standard_normal((1, 80, FRAMES)).astype(np.float32)
    length = np.array([FRAMES], dtype=np.int64)
    times = []
    for _ in range(runs):
        t = time.perf_counter()
        sess.run(None, {"audio_signal": feats, "length": length})
        times.append(time.perf_counter() - t)
    return {"ort": ort.__version__, "threads": threads, "load_s": round(load_s, 3),
            "first_run_s": round(times[0], 4), "run_s": benchlib.summarize(times[1:]),
            "rtf_3s": round(sorted(times[1:])[len(times[1:]) // 2] / (FRAMES / 100), 4),
            "memory_mib": {"before_load": mem_before, "after_load": mem_loaded, "end": benchlib.memory_mib()}}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--model", required=True, type=Path)
    ap.add_argument("--work", type=Path, help="where the external-data copy is written")
    ap.add_argument("--variant", choices=sorted(VARIANTS))
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--runs", type=int, default=6)
    ap.add_argument("--out", type=Path)
    args = ap.parse_args(argv)
    if args.variant:
        _kind, configs = VARIANTS[args.variant]
        json.dump(run_variant(args.model, configs, args.threads, args.runs), sys.stdout)
        return 0
    external = external_copy(args.model, args.work)
    result = {"model": str(args.model), "frames": FRAMES, "variants": {}}
    for name, (kind, _configs) in VARIANTS.items():
        path = args.model if kind == "single" else external
        out = subprocess.run([sys.executable, "-I", str(Path(__file__).resolve()), "--model", str(path),
                              "--variant", name, "--threads", str(args.threads), "--runs", str(args.runs)],
                             check=True, capture_output=True, text=True)
        result["variants"][name] = json.loads(out.stdout)
        print(name, json.dumps({k: v for k, v in result["variants"][name].items() if k != "memory_mib"}))
    args.out.write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
