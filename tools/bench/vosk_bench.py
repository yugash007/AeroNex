"""Vosk host bench: grammar vs open vocabulary, filler words and n-best, per model.

Merges the first host experiments (vosk_bench / vosk_filler / vosk_load /
vosk_conf in the 2026-10-11 session scratchpad) into one script. Runs with the
desktop Vosk Python package (0.3.45; Android ships 0.3.75):

    python -I tools/bench/vosk_bench.py --vosk-python ../tooling/vosk/python --models ../tooling/vosk \
        --clips ../tooling/models/sherpa/clips-espeak [--tsv-clips <dir with index.tsv>] --out vosk.json

TTS clips on a desktop CPU are NOT representative of Indian speakers on a
low-end phone: use the numbers for grammar-vs-open comparison only.
"""
import argparse
import json
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import time
import wave

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchlib  # noqa: E402
import fstsyms  # noqa: E402
import phrases  # noqa: E402

MODELS = {"en": "vosk-model-small-en-in-0.4", "hi": "vosk-model-small-hi-0.22", "te": "vosk-model-small-te-0.42"}
RATE = 16000
CHUNK = 3200  # 100 ms of 16 kHz samples per AcceptWaveform, like a capture read


def _decode(vosk, model, pcm: bytes, grammar=None, alternatives=0) -> dict:
    t0 = time.perf_counter()
    if grammar is None:
        rec = vosk.KaldiRecognizer(model, RATE)
    else:
        rec = vosk.KaldiRecognizer(model, RATE, json.dumps(grammar, ensure_ascii=False))
    init_s = time.perf_counter() - t0
    if alternatives:
        rec.SetMaxAlternatives(alternatives)
    parts, feed = [], 0.0
    for i in range(0, len(pcm), CHUNK * 2):
        t = time.perf_counter()
        if rec.AcceptWaveform(pcm[i:i + CHUNK * 2]):
            parts.append(json.loads(rec.Result()))
        feed += time.perf_counter() - t
    t = time.perf_counter()
    parts.append(json.loads(rec.FinalResult()))
    final_s = time.perf_counter() - t
    seconds = len(pcm) / 2 / RATE
    out = {"init_ms": round(init_s * 1000, 2), "rtf": round(feed / seconds, 4), "final_ms": round(final_s * 1000, 2)}
    if alternatives:
        alts = [p.get("alternatives", []) for p in parts]
        out["alternatives"] = [[{"text": a["text"], "confidence": round(a["confidence"], 2)} for a in seg]
                               for seg in alts if seg]
        out["n_alternatives"] = max((len(seg) for seg in alts), default=0)
    else:
        out["text"] = " ".join(p.get("text", "") for p in parts if p.get("text")).strip()
    return out


def _clips(clip_dir: Path, lang: str, tsv_dir: Path | None) -> list:
    rows = [{"file": str(clip_dir / c["file"]), "source": "espeak", "voice": c["voice"], "id": c["id"],
             "ref": phrases.vosk_spelling(lang, c["text"])}
            for c in benchlib.read_index(clip_dir) if c["lang"] == lang]
    if lang == "en" and tsv_dir:
        for line in (tsv_dir / "index.tsv").read_text(encoding="utf-8").strip().splitlines():
            name, text = line.split("\t")
            rows.append({"file": str(tsv_dir / name), "source": "sapi", "voice": name.split("_", 1)[1][:-4],
                         "id": None, "ref": text})
    return rows


def bench_model(vosk, models: Path, lang: str, clip_dir: Path, tsv_dir: Path | None) -> dict:
    model_dir = models / MODELS[lang]
    vocab = set(fstsyms.read_words(model_dir / "graph" / "Gr.fst"))
    grammar = phrases.vosk_grammar(lang)
    command_phrases = set(grammar) - {"[unk]"}
    modes = {"open": None, "grammar": grammar}
    if lang == "en":
        modes["grammar+fillers"] = phrases.vosk_grammar(lang, fillers=True)
    oov = sorted({w for p in command_phrases for w in p.split()} - vocab)
    mem_before = benchlib.memory_mib()
    t0 = time.perf_counter()
    model = vosk.Model(str(model_dir))
    load_s = time.perf_counter() - t0
    mem_loaded = benchlib.memory_mib()
    rows = []
    for clip in _clips(clip_dir, lang, tsv_dir):
        with wave.open(clip["file"], "rb") as w:
            if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (RATE, 1, 2):
                raise ValueError(f"{clip['file']}: need 16 kHz mono PCM16")
            pcm = w.readframes(w.getnframes())
        in_grammar = clip["ref"] in command_phrases
        row = {**clip, "file": Path(clip["file"]).name, "in_grammar": in_grammar, "modes": {}}
        for mode, g in modes.items():
            r = _decode(vosk, model, pcm, g)
            exact = benchlib.normalize_text(r["text"]) == benchlib.normalize_text(clip["ref"])
            r["exact"] = exact
            r["command_accepted"] = r["text"] in command_phrases
            row["modes"][mode] = r
        row["nbest"] = _decode(vosk, model, pcm, grammar, alternatives=3)
        rows.append(row)
    mem_end = benchlib.memory_mib()
    summary = {}
    for mode in modes:
        ing = [r for r in rows if r["in_grammar"]]
        oog = [r for r in rows if not r["in_grammar"]]
        summary[mode] = {
            "exact_in_grammar": f"{sum(r['modes'][mode]['exact'] for r in ing)}/{len(ing)}",
            "false_accepts_out_of_grammar": f"{sum(r['modes'][mode]['command_accepted'] for r in oog)}/{len(oog)}",
            "rtf": benchlib.summarize(r["modes"][mode]["rtf"] for r in rows),
            "final_ms": benchlib.summarize(r["modes"][mode]["final_ms"] for r in rows),
            "init_ms": benchlib.summarize(r["modes"][mode]["init_ms"] for r in rows),
        }
    summary["nbest_grammar"] = {
        "clips_with_more_than_one_alternative": f"{sum(r['nbest']['n_alternatives'] > 1 for r in rows)}/{len(rows)}"}
    return {"engine": "vosk", "lang": lang, "model": MODELS[lang], "load_s": round(load_s, 3),
            "memory_mib": {"before_load": mem_before, "after_load": mem_loaded, "end": mem_end},
            "grammar_phrases": len(grammar), "grammar_words_missing_from_model": oov,
            "summary": summary, "clips": rows}


def _run_children(args, langs: list) -> dict:
    """One child process per model, so a model's load memory is measured alone."""
    models, child = [], {}
    with tempfile.TemporaryDirectory() as tmp:
        for lang in langs:
            out = Path(tmp) / f"{lang}.json"
            cmd = [sys.executable, "-I", str(Path(__file__).resolve()),
                   "--models", str(args.models), "--clips", str(args.clips), "--langs", lang, "--out", str(out)]
            if args.vosk_python:
                cmd += ["--vosk-python", str(args.vosk_python)]
            if args.tsv_clips:
                cmd += ["--tsv-clips", str(args.tsv_clips)]
            subprocess.run(cmd, check=True, capture_output=True)
            child = json.loads(out.read_text(encoding="utf-8"))
            models += child["models"]
    return {**child, "models": models}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--vosk-python", type=Path, help="directory containing the vosk package (default: installed)")
    ap.add_argument("--models", required=True, type=Path, help="directory with the vosk-model-small-* folders")
    ap.add_argument("--clips", required=True, type=Path)
    ap.add_argument("--tsv-clips", type=Path)
    ap.add_argument("--langs", default="en,hi,te")
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args(argv)
    langs = args.langs.split(",")
    if len(langs) > 1:
        result = _run_children(args, langs)
    else:
        if args.vosk_python:
            sys.path.insert(0, str(args.vosk_python.resolve()))
        import vosk

        vosk.SetLogLevel(-1)
        result = {"host": {"platform": platform.platform(), "python": platform.python_version(),
                           "cpu": platform.processor(), "vosk": getattr(vosk, "__version__", "0.3.45")},
                  "models": [bench_model(vosk, args.models, langs[0], args.clips, args.tsv_clips)]}
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for m in result["models"]:
        print(m["lang"], m["model"], f"load {m['load_s']} s", json.dumps(
            {k: {kk: vv for kk, vv in v.items() if kk in ('exact_in_grammar', 'false_accepts_out_of_grammar',
                                                          'clips_with_more_than_one_alternative')}
             for k, v in m["summary"].items()}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
