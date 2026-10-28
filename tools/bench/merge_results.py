"""Merge the Phase 0 bench outputs into one report JSON (docs/testing/...-phase0-engine-eval.json).

    python -I tools/bench/merge_results.py --manifest <tooling>/models/sherpa/manifest.json --aar aar.json \
        --sherpa sherpa.json --vosk vosk_linux.json [--vosk-windows vosk.json] --ort ort.json \
        --clips <tooling>/models/sherpa/clips-espeak --out docs/testing/2026-10-12-phase0-engine-eval.json
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys


def _load(path):
    return json.loads(Path(path).read_text(encoding="utf-8")) if path else None


def _vosk_diff(linux: dict, windows: dict) -> list:
    out = []
    for ml, mw in zip(linux["models"], windows["models"]):
        for cl, cw in zip(ml["clips"], mw["clips"]):
            for mode, rl in cl["modes"].items():
                tw = cw["modes"][mode]["text"]
                if rl["text"] != tw:
                    out.append({"lang": ml["lang"], "mode": mode, "ref": cl["ref"], "voice": cl["voice"],
                                "linux": rl["text"], "windows": tw})
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for name in ("manifest", "aar", "sherpa", "vosk", "ort", "clips", "out"):
        ap.add_argument(f"--{name}", required=True, type=Path)
    ap.add_argument("--vosk-windows", type=Path)
    args = ap.parse_args(argv)
    manifest, aar, sherpa = _load(args.manifest), _load(args.aar), _load(args.sherpa)
    vosk, vosk_win, ort = _load(args.vosk), _load(args.vosk_windows), _load(args.ort)
    # Transcripts do not depend on the thread count: keep clip rows of the 2-thread runs only.
    ctc = [r if r["threads"] == 2 else {k: v for k, v in r.items() if k != "clips"} for r in sherpa["ctc"]]
    kws = [{**r, "clips": [c for c in r["clips"] if c["hits"] or c["target"]]} for r in sherpa["kws"]]
    report = {
        "schema": "aeronex-phase0-engine-eval/1",
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "caveat": ("Host evaluation only: AMD Ryzen 7 6800H (WSL2 x86-64) and eSpeak NG / Windows SAPI "
                   "synthetic speech. Neither the CPU nor the audio represents a low-end phone or a real "
                   "speaker; accuracy figures say nothing about real users, timing and memory figures are "
                   "relative only. Phone numbers (R0-R5) remain pending."),
        "clips": {"generator": (args.clips / "GENERATOR.txt").read_text(encoding="utf-8").strip(),
                  "count": len(json.loads((args.clips / "index.json").read_text(encoding="utf-8"))["clips"])},
        "assets": {"manifest": "../tooling/models/sherpa/manifest.json (paths relative to .worktrees/tooling)",
                   "downloads": manifest["downloads"], "packs": manifest["packs"]},
        "aar": aar,
        "sherpa": {"host": sherpa["host"], "ctc": ctc, "kws": kws,
                   "note": "kws clip rows keep only clips with a hit or a target keyword"},
        "ort_session_options": ort,
        "vosk": vosk,
    }
    if vosk_win:
        report["vosk_windows"] = {"host": vosk_win["host"],
                                  "summary": {m["lang"]: m["summary"] for m in vosk_win["models"]},
                                  "transcripts_differing_from_linux": _vosk_diff(vosk, vosk_win)}
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {args.out} ({args.out.stat().st_size} B)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
