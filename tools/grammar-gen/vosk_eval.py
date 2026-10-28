#!/usr/bin/env python3
"""Host A/B checks of the generated Vosk grammars (grammar/README.md).

    python -I tools/grammar-gen/vosk_eval.py negation [--langs en hi te]   negation pairs: is "don't X" kept?
    python -I tools/grammar-gen/vosk_eval.py stop [--langs en hi]          stop grammar: spotter recall, false STOPs
    python -I tools/grammar-gen/vosk_eval.py plans [--langs en hi]         connective pairs: 2-step plans decoded

Each mode decodes eSpeak NG clips with a generated grammar and with a variant that lacks (or adds) one part, using
the desktop Vosk package. TTS on a desktop CPU is NOT representative of speakers on a phone: read the counts as an
A/B comparison only. Needs numpy, eSpeak NG, the desktop Vosk package in ../tooling/vosk/python and the models in
../tooling/vosk (tools/grammar-gen/requirements.txt).
"""
import argparse
import json
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "bench"))
import gen  # noqa: E402

VOICES = {"en": ["en-us", "en", "en+f3", "en+m3", "en-029", "en+f2"], "hi": ["hi", "hi+f3", "hi+m3", "hi+f2"],
          "te": ["te", "te+f3"]}
SPEEDS = (130, 160, 190)
# negation: phrases beyond the W1 clip set (tooling/models/sherpa/clips-espeak), two (voice, wpm) each.
NEGATION_VOICES = {"en": (("en-us", 160), ("en+f3", 140)), "hi": (("hi", 160), ("hi+f3", 140)),
                   "te": (("te", 160), ("te+f3", 140))}
NEGATION = {
    "en": [("neg", "don't take off"), ("neg", "do not land"), ("neg", "never move forward two meters"),
           ("neg", "don't hover"), ("pos", "take off to three meters"), ("pos", "move left two meters"),
           ("pos", "hover"), ("pos", "land")],
    "hi": [("neg", "आगे मत जाओ"), ("neg", "लैंड मत करो"), ("neg", "तीन मीटर आगे मत जाओ"), ("neg", "होवर मत करो"),
           ("pos", "तीन मीटर आगे जाओ"), ("pos", "लैंड करो"), ("pos", "होवर करो")],
    "te": [("neg", "ల్యాండ్ వద్దు"), ("neg", "హోవర్ వద్దు"), ("neg", "మూడు మీటర్లు టేకాఫ్ వద్దు"),
           ("neg", "టేకాఫ్ వద్దు"),
           ("neg", "రెండు మీటర్లు ముందుకు వెళ్ళకు"), ("pos", "మూడు మీటర్లు టేకాఫ్"), ("pos", "ల్యాండ్"),
           ("pos", "రెండు మీటర్లు ఎడమకు వెళ్ళు")],
}
# stop: True = a STOP the spotter must catch; False = speech it should let pass (a false STOP is a benign hover).
STOP = {
    "en": {"stop": True, "stop the drone": True, "stop stop": True, "hold": False, "go two steps": False,
           "start the engine": False, "land": False, "move forward three meters": False, "i think it is good": False,
           "step back": False, "keep still": False},
    "hi": {"रुको": True, "रुक जाओ": True, "स्टॉप": True, "ड्रोन रुको": True, "ऊपर उठो": False,
           "दो मीटर आगे जाओ": False, "लैंड करो": False, "होवर करो": False, "यह बहुत अच्छा है": False,
           "इसका रूप अच्छा है": False, "सौ रुपये दो": False},
    "te": {"ఆపు": True, "ఆగు": True, "స్టాప్": True, "ఆరు మీటర్లు": False, "పైకి వెళ్ళు": False, "ల్యాండ్": False},
}


class Bench:
    def __init__(self, args):
        tooling = Path(args.tooling)
        sys.path.insert(0, str(tooling / "vosk" / "python"))
        import benchlib  # noqa: PLC0415 - tools/bench, needs numpy
        import numpy as np  # noqa: PLC0415
        import vosk  # noqa: PLC0415 - desktop package from tooling/vosk/python
        vosk.SetLogLevel(-1)
        self.benchlib, self.np, self.vosk = benchlib, np, vosk
        self.tooling, self.espeak = tooling, args.espeak
        self.grammar = gen.load_grammar(gen.GRAMMAR_DIR)
        self._models = {}

    def model(self, lang):
        if lang not in self._models:
            self._models[lang] = self.vosk.Model(str(self.tooling / "vosk" / self.grammar.vosk[lang]["model"]))
        return self._models[lang]

    def synth(self, voice, speed, text) -> bytes:
        np, benchlib = self.np, self.benchlib
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp) / "t.txt", Path(tmp) / "o.wav"
            src.write_text(text, encoding="utf-8")
            subprocess.run([self.espeak, "-v", voice, "-s", str(speed), "-b", "1", "-f", str(src), "-w", str(out)],
                           check=True, capture_output=True)
            samples, rate = benchlib.read_wav(out)
        samples = benchlib.peak_normalize(benchlib.resample(samples, rate, 16000), 0.7)
        pad = np.zeros(3200, dtype=np.float32)  # 0.2 s, like the W1 clips
        return (np.clip(np.concatenate([pad, samples, pad]), -1, 1) * 32767).astype("<i2").tobytes()

    def decode(self, lang, pcm, phrases) -> list:
        """The final n-best: [(text, score)]."""
        rec = self.vosk.KaldiRecognizer(self.model(lang), 16000, json.dumps(phrases, ensure_ascii=False))
        rec.SetMaxAlternatives(3)
        for i in range(0, len(pcm), 6400):  # 200 ms chunks
            rec.AcceptWaveform(pcm[i:i + 6400])
        return [(a["text"], round(a["confidence"], 1)) for a in json.loads(rec.FinalResult()).get("alternatives", [])]

    def spot(self, lang, pcm, phrases, tokens) -> tuple:
        """Spec 4.12 spotter: a stop token in the partial on 2 consecutive 100 ms steps, or in the final result."""
        rec = self.vosk.KaldiRecognizer(self.model(lang), 16000, json.dumps(phrases, ensure_ascii=False))
        run, fired = 0, False
        for i in range(0, len(pcm), 3200):
            rec.AcceptWaveform(pcm[i:i + 3200])
            partial = json.loads(rec.PartialResult()).get("partial", "").split()
            run = run + 1 if set(partial) & tokens else 0
            fired = fired or run >= 2
        final = json.loads(rec.FinalResult()).get("text", "")
        return fired or bool(set(final.split()) & tokens), final


def _count(totals, key, ok):
    c = totals.setdefault(key, [0, 0])
    c[0], c[1] = c[0] + bool(ok), c[1] + 1


def negation(bench, langs):
    """Negation pairs (README "Negation in the grammar"): does the 1-best keep a spoken negation?"""
    clip_dir = bench.tooling / "models" / "sherpa" / "clips-espeak"
    index = json.loads((clip_dir / "index.json").read_text(encoding="utf-8"))["clips"]
    totals = {}
    for lang in langs:
        lex = bench.grammar.lexicons[lang]
        variants = {"with pairs": gen.vosk_phrases(lex), "without pairs": gen.vosk_phrases(lex, without=("negation",))}
        negators = {w for n in gen.negation_phrases(lex) for w in n.split()}
        clips = []
        for c in index:
            if c["lang"] == lang:
                with wave.open(str(clip_dir / c["file"])) as w:
                    clips.append(("neg" if c["intent"] == "negated" else "pos", c["text"], c["voice"],
                                  w.readframes(w.getnframes())))
        for kind, text in NEGATION[lang]:
            clips += [(kind, text, v, bench.synth(v, s, text)) for v, s in NEGATION_VOICES[lang]]
        for kind, text, voice, pcm in clips:
            rows = {name: bench.decode(lang, pcm, phrases) for name, phrases in variants.items()}
            for name, alts in rows.items():
                top = alts[0][0] if alts else ""
                negated = bool(set(top.split()) & negators)
                _count(totals, (lang, kind, name), negated if kind == "neg" else bool(top) and not negated)
            print(f"{lang} {kind} [{voice}] {text}\n  " + "\n  ".join(f"{n}: {a}" for n, a in rows.items()))
    print("\nlang kind  grammar         1-best ok   (neg: negation kept; pos: non-empty, no negation)")
    for (lang, kind, name), (ok, n) in sorted(totals.items()):
        print(f"{lang:4} {kind:4}  {name:14}  {ok}/{n}")


def stop(bench, langs):
    """Stop grammar (README "Stop lane and keyword spotting"): spotter hits with and without the near-stop decoys."""
    totals = {}
    for lang in langs:
        lex = bench.grammar.lexicons[lang]
        generated = gen.stop_phrases(lex)
        near = [w for w in lex.table("vosk.near_stop") if gen._vosk_usable(lex, w)]
        variants = {"stop grammar": generated, "+ near-stop decoys": sorted(set(generated) | set(near)),
                    "command grammar": gen.vosk_phrases(lex)}
        tokens = {t for p in lex.table("stop_lane") for t in p.split()} - {
            t for p in lex.table("stop_lane") for t in p.split()[1:]}  # first words: रुक of "रुक जाओ"
        for text, is_stop in STOP[lang].items():
            pcms = [bench.synth(v, s, text) for v in VOICES[lang] for s in SPEEDS]
            line = []
            for name, phrases in variants.items():
                hits = [bench.spot(lang, pcm, phrases, tokens) for pcm in pcms]
                fired = sum(h for h, _ in hits)
                for hit, _final in hits:
                    _count(totals, (lang, "STOP" if is_stop else "other", name), hit)
                misses = sorted({f for h, f in hits if h != is_stop})[:3]
                line.append(f"{name}: {fired}/{len(pcms)} {misses}")
            print(f"{lang} {'STOP ' if is_stop else 'other'} {text!r}\n  " + "\n  ".join(line))
    print("\nlang kind   grammar              fired   (STOP: should fire; other: a false STOP)")
    for (lang, kind, name), (ok, n) in sorted(totals.items()):
        print(f"{lang:4} {kind:5}  {name:19}  {ok}/{n}")


def plans(bench, langs):
    """Connective pairs (README "Plans"): does the 1-best of a 2-step plan keep a connective before the second
    command ("2 steps"), and the exact text? Single commands check that the pairs cost nothing."""
    totals = {}
    for lang in langs:
        lex = bench.grammar.lexicons[lang]
        variants = {"with pairs": gen.vosk_phrases(lex),
                    "without pairs": gen.vosk_phrases(lex, without=("connectives",))}
        fx = gen.fixtures(lex)
        land = next(c["text"] for c in fx["positive"] if c["intent"] == "land")
        connectives = lex.table("plan.connectives")
        cases = [("plan", c["text"]) for c in fx["plans"]]
        singles = []
        for c in fx["positive"]:
            if c["intent"] not in {i for i, _ in singles}:
                singles.append((c["intent"], c["text"]))
        cases += [("single", t) for _, t in singles]

        def two_steps(top):
            return any(f" {c} {land}" in f" {top}" and top.endswith(land) and not top.startswith(f"{c} ")
                       for c in connectives)

        for kind, text in cases:
            pcms = [bench.synth(v, s, text) for v in VOICES[lang][:3] for s in SPEEDS[1:]]
            line = []
            for name, phrases in variants.items():
                tops = [(bench.decode(lang, pcm, phrases) or [("", 0)])[0][0] for pcm in pcms]
                for top in tops:
                    _count(totals, (lang, kind, "exact", name), top == text)
                    if kind == "plan":
                        _count(totals, (lang, kind, "2 steps", name), two_steps(top))
                kept = sum(map(two_steps, tops)) if kind == "plan" else sum(t == text for t in tops)
                line.append(f"{name}: {kept}/{len(tops)} {sorted(set(tops) - {text})[:3]}")
            print(f"{lang} {kind} {text!r}\n  " + "\n  ".join(line))
    print("\nlang kind    metric   grammar         ok   (2 steps: a connective, then the second command, ends it)")
    for (lang, kind, metric, name), (ok, n) in sorted(totals.items()):
        print(f"{lang:4} {kind:6}  {metric:7}  {name:14}  {ok}/{n}")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("mode", choices=["negation", "stop", "plans"])
    ap.add_argument("--tooling", default=str(ROOT.parent / "tooling"))
    ap.add_argument("--espeak", default=r"C:\Program Files\eSpeak NG\espeak-ng.exe")
    ap.add_argument("--langs", nargs="+", default=["en", "hi", "te"])
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")
    {"negation": negation, "stop": stop, "plans": plans}[args.mode](Bench(args), args.langs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
