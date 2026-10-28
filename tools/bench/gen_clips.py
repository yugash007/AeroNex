"""Generate 16 kHz mono command clips with eSpeak NG for every bench language.

eSpeak NG is formant synthesis: the clips exercise the pipeline and the script
of each model's output, they are NOT a proxy for real speakers (spec 10.2).

    python -I tools/bench/gen_clips.py --espeak "C:/Program Files/eSpeak NG/espeak-ng.exe" \
        --out ../tooling/models/sherpa/clips-espeak
"""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchlib  # noqa: E402
import phrases  # noqa: E402

RATE = 16000
PAD_S = 0.2  # silence before and after, like a push-to-talk window
SPEED = {0: 160, 1: 140}  # words per minute for the first and second voice


def synthesize(espeak: str, voice: str, text: str, speed: int, tmp: Path) -> np.ndarray:
    src, out = tmp / "text.txt", tmp / "out.wav"
    src.write_text(text, encoding="utf-8")
    subprocess.run([espeak, "-v", voice, "-s", str(speed), "-b", "1", "-f", str(src), "-w", str(out)],
                   check=True, capture_output=True)
    samples, rate = benchlib.read_wav(out)
    samples = benchlib.peak_normalize(benchlib.resample(samples, rate, RATE), 0.7)
    pad = np.zeros(int(PAD_S * RATE), dtype=np.float32)
    return np.concatenate([pad, samples, pad])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--espeak", required=True)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--langs", default=",".join(phrases.PHRASES))
    args = ap.parse_args(argv)
    version = subprocess.run([args.espeak, "--version"], check=True, capture_output=True,
                             text=True).stdout.strip()
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        for lang in args.langs.split(","):
            for v, voice in enumerate(phrases.VOICES[lang]):
                for p in phrases.PHRASES[lang]:
                    audio = synthesize(args.espeak, voice, p["text"], SPEED[v], Path(tmp))
                    rel = f"{lang}/{p['id']}_v{v}.wav"
                    benchlib.write_wav(args.out / rel, audio, RATE)
                    rows.append({"file": rel, "lang": lang, "id": p["id"], "intent": p["intent"],
                                 "text": p["text"], "voice": voice, "wpm": SPEED[v],
                                 "seconds": round(len(audio) / RATE, 3)})
    benchlib.write_index(args.out, rows)
    (args.out / "GENERATOR.txt").write_text(
        f"{version}\nvoices: {phrases.VOICES}\npad {PAD_S}s, peak 0.7, {RATE} Hz mono PCM16\n",
        encoding="utf-8")
    print(f"wrote {len(rows)} clips to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
