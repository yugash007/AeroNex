"""Host bench for the sherpa-onnx engines: IndicConformer NeMo CTC and keyword spotting.

Each engine runs in its own child process so load time and peak RSS are per
engine. Runs in the WSL venv (pip install sherpa-onnx==1.13.8 numpy sentencepiece):

    python -I tools/bench/sherpa_bench.py all --models <tooling>/models/sherpa \
        --clips <tooling>/models/sherpa/clips-espeak --out results.json

A desktop x86-64 CPU and eSpeak NG audio are NOT representative of a low-end
phone or of real speakers: use the numbers for relative comparison only.
"""
import argparse
import json
import platform
from pathlib import Path
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchlib  # noqa: E402
import phrases  # noqa: E402

CTC_LANGS = ("ta", "kn", "ml", "te")
KWS_MODELS = {
    "zh-en-3M-chunk-8": {
        "dir": "kws/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20",
        "encoder": "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx",
        "decoder": "decoder-epoch-13-avg-2-chunk-8-left-64.onnx",
        "joiner": "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx",
        "units": "phone", "lexicon": "en.phone",
        "selftest_wavs": ("test_wavs/en_0.wav", "test_wavs/en_1.wav")},
    "gigaspeech-3.3M-chunk-16": {
        "dir": "kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01",
        "encoder": "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "decoder": "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
        "joiner": "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "units": "bpe", "bpe": "bpe.model",
        "selftest_wavs": ("test_wavs/0.wav", "test_wavs/1.wav")},
}
# Real (read-speech) recordings shipped with both KWS models contain these
# phrases: a harness check that our keyword files and streaming loop work.
SELFTEST_KEYWORDS = ("light up", "lovely child")
CHUNK_S = 0.1  # audio pushed to the spotter per step, like a 100 ms capture read
TAIL_S = 0.6   # silence after each clip so the streaming model can flush


def _score(ref: str, hyp: str) -> dict:
    we, wn = benchlib.word_errors(ref, hyp)
    ce, cn = benchlib.char_errors(ref, hyp)
    return {"exact": benchlib.normalize_text(ref) == benchlib.normalize_text(hyp),
            "word_errors": we, "ref_words": wn, "char_errors": ce, "ref_chars": cn}


def bench_ctc(model: Path, tokens: Path, clip_dir: Path, lang: str, threads: int, repeat: int) -> dict:
    import sherpa_onnx

    mem_before = benchlib.memory_mib()
    t0 = time.perf_counter()
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=str(model), tokens=str(tokens), num_threads=threads)
    load_s = time.perf_counter() - t0
    mem_loaded = benchlib.memory_mib()
    rows = []
    for clip in (c for c in benchlib.read_index(clip_dir) if c["lang"] == lang):
        audio, rate = benchlib.read_wav(clip_dir / clip["file"])
        times, text = [], None
        for _ in range(repeat):
            t = time.perf_counter()
            stream = rec.create_stream()
            stream.accept_waveform(rate, audio)
            rec.decode_stream(stream)
            times.append(time.perf_counter() - t)
            text = stream.result.text if text is None else text
        seconds = len(audio) / rate
        decode_s = sorted(times)[len(times) // 2]
        rows.append({"file": clip["file"], "id": clip["id"], "voice": clip["voice"], "ref": clip["text"],
                     "hyp": text, "audio_s": round(seconds, 3), "first_decode_s": round(times[0], 4),
                     "decode_s": round(decode_s, 4), "rtf": round(decode_s / seconds, 4), **_score(clip["text"], text)})
    mem_end = benchlib.memory_mib()
    commands = [r for r in rows if r["id"] != "neg_takeoff"]
    return {
        "engine": "sherpa-onnx-nemo-ctc", "lang": lang, "model": str(model), "threads": threads,
        "model_bytes": model.stat().st_size, "load_s": round(load_s, 3),
        "memory_mib": {"before_load": mem_before, "after_load": mem_loaded, "end": mem_end},
        "rtf": benchlib.summarize(r["rtf"] for r in rows[1:]),
        "first_clip_first_decode_s": rows[0]["first_decode_s"] if rows else None,
        "exact": f"{sum(r['exact'] for r in commands)}/{len(commands)}",
        "wer": round(sum(r["word_errors"] for r in commands) / max(1, sum(r["ref_words"] for r in commands)), 3),
        "cer": round(sum(r["char_errors"] for r in commands) / max(1, sum(r["ref_chars"] for r in commands)), 3),
        "clips": rows,
    }


def keyword_lines(cfg: dict, model_dir: Path, words) -> list:
    """Keyword-file lines ("tok tok @label") for English words or phrases."""
    if cfg["units"] == "phone":
        lexicon = {}
        with open(model_dir / cfg["lexicon"], encoding="utf-8") as f:
            for line in f:
                word, *phones = line.split()
                lexicon.setdefault(word, phones)
        return [" ".join(p for word in w.upper().split() for p in lexicon[word]) + f" @{w.replace(' ', '_')}"
                for w in words]
    import sentencepiece

    sp = sentencepiece.SentencePieceProcessor(model_file=str(model_dir / cfg["bpe"]))
    return [" ".join(sp.encode(w.upper(), out_type=str)) + f" @{w.replace(' ', '_')}" for w in words]


def _spotter(cfg: dict, model_dir: Path, lines: list, threads: int, threshold: float, score: float):
    import sherpa_onnx

    with tempfile.TemporaryDirectory() as tmp:
        kw_file = Path(tmp) / "keywords.txt"
        kw_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return sherpa_onnx.KeywordSpotter(
            tokens=str(model_dir / "tokens.txt"), encoder=str(model_dir / cfg["encoder"]),
            decoder=str(model_dir / cfg["decoder"]), joiner=str(model_dir / cfg["joiner"]),
            keywords_file=str(kw_file), num_threads=threads, keywords_threshold=threshold,
            keywords_score=score)


def _spot(spotter, audio, rate: int):
    """Stream [audio] (plus a silent tail) in CHUNK_S steps; returns (hits, busy seconds, audio seconds)."""
    import numpy as np

    audio = np.concatenate([audio, np.zeros(int(TAIL_S * rate), dtype=np.float32)])
    step = int(CHUNK_S * rate)
    stream, hits, fed, busy = spotter.create_stream(), [], 0, 0.0
    for i in range(0, len(audio), step):
        chunk = audio[i:i + step]
        t = time.perf_counter()
        stream.accept_waveform(rate, chunk)
        while spotter.is_ready(stream):
            spotter.decode_stream(stream)
            found = spotter.get_result(stream)
            if found:
                hits.append({"keyword": found, "at_s": round((fed + len(chunk)) / rate, 2)})
                spotter.reset_stream(stream)
        busy += time.perf_counter() - t
        fed += len(chunk)
    return hits, busy, len(audio) / rate


def bench_kws(name: str, models: Path, clip_dir: Path, threads: int, threshold: float, score: float) -> dict:
    import sherpa_onnx  # noqa: F401  (library load is not part of the model load time)

    cfg = KWS_MODELS[name]
    model_dir = models / cfg["dir"]
    lines = keyword_lines(cfg, model_dir, phrases.KWS_KEYWORDS)
    mem_before = benchlib.memory_mib()
    t0 = time.perf_counter()
    spotter = _spotter(cfg, model_dir, lines, threads, threshold, score)
    load_s = time.perf_counter() - t0
    mem_loaded = benchlib.memory_mib()
    rows, busy, total_audio = [], 0.0, 0.0
    for clip in benchlib.read_index(clip_dir):
        audio, rate = benchlib.read_wav(clip_dir / clip["file"])
        hits, spent, seconds = _spot(spotter, audio, rate)
        busy += spent
        total_audio += seconds
        speech_end = clip["seconds"] - 0.2  # gen_clips pads 0.2 s of silence
        target = clip["id"] if clip["lang"] == "en" and clip["id"] in phrases.KWS_KEYWORDS else None
        rows.append({"file": clip["file"], "lang": clip["lang"], "id": clip["id"], "text": clip["text"],
                     "target": target, "hits": hits,
                     "latency_after_speech_end_s": round(hits[0]["at_s"] - speech_end, 2) if hits else None})
    positives = [r for r in rows if r["target"]]
    negatives = [r for r in rows if not r["target"]]
    mem_end = benchlib.memory_mib()
    self_lines = keyword_lines(cfg, model_dir, SELFTEST_KEYWORDS)
    self_spotter = _spotter(cfg, model_dir, self_lines, threads, threshold, score)
    selftest = []
    for wav in cfg["selftest_wavs"]:
        audio, rate = benchlib.read_wav(model_dir / wav)
        selftest.append({"file": wav, "hits": _spot(self_spotter, audio, rate)[0]})
    return {
        "engine": "sherpa-onnx-kws", "model": name, "threads": threads, "keywords": lines,
        "keywords_threshold": threshold, "keywords_score": score, "load_s": round(load_s, 3),
        "memory_mib": {"before_load": mem_before, "after_load": mem_loaded, "end": mem_end},
        "selftest": {"keywords": self_lines, "results": selftest},
        "rtf": round(busy / total_audio, 4),
        "recall": f"{sum(any(h['keyword'] == r['target'] for h in r['hits']) for r in positives)}/{len(positives)}",
        "false_accepts": f"{sum(bool(r['hits']) for r in negatives)}/{len(negatives)}",
        "clips": rows,
    }


def _child(args: list) -> dict:
    out = subprocess.run([sys.executable, "-I", str(Path(__file__).resolve()), *args],
                         check=True, capture_output=True, text=True)
    return json.loads(out.stdout)


def run_all(models: Path, clip_dir: Path, threads: list, repeat: int, kws_thresholds: list) -> dict:
    import sherpa_onnx

    result = {"host": {"platform": platform.platform(), "python": platform.python_version(),
                       "machine": platform.machine(), "sherpa_onnx": sherpa_onnx.__version__,
                       "cpu": _cpu_name()},
              "clips": str(clip_dir), "ctc": [], "kws": []}
    for lang in CTC_LANGS:
        for n in threads:
            print(f"ctc {lang} threads={n}", file=sys.stderr)
            result["ctc"].append(_child([
                "ctc", "--model", str(models / "indicconformer" / lang / "model.int8.onnx"),
                "--tokens", str(models / "indicconformer" / "tokens.txt"), "--clips", str(clip_dir),
                "--lang", lang, "--threads", str(n), "--repeat", str(repeat)]))
    for name in KWS_MODELS:
        for threshold in kws_thresholds:
            print(f"kws {name} threshold={threshold}", file=sys.stderr)
            result["kws"].append(_child(["kws", "--name", name, "--models", str(models), "--clips", str(clip_dir),
                                         "--threshold", str(threshold)]))
    return result


def _cpu_name() -> str:
    try:
        with open("/proc/cpuinfo", encoding="utf-8") as f:
            for line in f:
                if line.startswith("model name"):
                    return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="mode", required=True)
    c = sub.add_parser("ctc")
    c.add_argument("--model", required=True, type=Path)
    c.add_argument("--tokens", required=True, type=Path)
    c.add_argument("--clips", required=True, type=Path)
    c.add_argument("--lang", required=True)
    c.add_argument("--threads", type=int, default=2)
    c.add_argument("--repeat", type=int, default=3)
    k = sub.add_parser("kws")
    k.add_argument("--name", required=True, choices=sorted(KWS_MODELS))
    k.add_argument("--models", required=True, type=Path)
    k.add_argument("--clips", required=True, type=Path)
    k.add_argument("--threads", type=int, default=1)
    k.add_argument("--threshold", type=float, default=0.25)
    k.add_argument("--score", type=float, default=1.0)
    a = sub.add_parser("all")
    a.add_argument("--models", required=True, type=Path)
    a.add_argument("--clips", required=True, type=Path)
    a.add_argument("--threads", default="1,2")
    a.add_argument("--repeat", type=int, default=3)
    a.add_argument("--kws-thresholds", default="0.15,0.25,0.35")
    a.add_argument("--out", required=True, type=Path)
    args = ap.parse_args(argv)
    if args.mode == "ctc":
        result = bench_ctc(args.model, args.tokens, args.clips, args.lang, args.threads, args.repeat)
    elif args.mode == "kws":
        result = bench_kws(args.name, args.models, args.clips, args.threads, args.threshold, args.score)
    else:
        result = run_all(args.models, args.clips, [int(t) for t in args.threads.split(",")], args.repeat,
                         [float(t) for t in args.kws_thresholds.split(",")])
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        return 0
    json.dump(result, sys.stdout, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
