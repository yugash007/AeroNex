"""Tests for the Phase 0 bench helpers (pure logic only; no models needed).

Run from the worktree root: python -m pytest tools/bench -q
"""
import io
import importlib.util
import json
import math
from pathlib import Path
import struct
import sys
import tarfile

import numpy as np
import pytest

BENCH = Path(__file__).resolve().parent
ROOT = BENCH.parents[1]
sys.path.insert(0, str(BENCH))

import benchlib  # noqa: E402
import phrases  # noqa: E402


# --- hashing and manifest ---------------------------------------------------

def test_file_record_has_url_bytes_sha256_licence(tmp_path):
    f = tmp_path / "sub" / "model.onnx"
    f.parent.mkdir()
    f.write_bytes(b"abc")
    rec = benchlib.file_record(f, tmp_path, "https://example/m", "MIT", source="x")
    assert rec == {
        "path": "sub/model.onnx",
        "url": "https://example/m",
        "bytes": 3,
        "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "licence": "MIT",
        "source": "x",
    }


def test_verify_sha256_rejects_mismatch(tmp_path):
    f = tmp_path / "a.bin"
    f.write_bytes(b"abc")
    benchlib.verify_sha256(f, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    with pytest.raises(ValueError, match="sha256"):
        benchlib.verify_sha256(f, "0" * 64)


def _tar_with(member: tarfile.TarInfo, data: bytes = b"") -> tarfile.TarFile:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as t:
        member.size = len(data)
        t.addfile(member, io.BytesIO(data))
    buf.seek(0)
    return tarfile.open(fileobj=buf, mode="r")


@pytest.mark.parametrize("name", ["/etc/passwd", "../evil", "a/../../evil", "C:/x"])
def test_safe_members_rejects_escaping_paths(name):
    with _tar_with(tarfile.TarInfo(name), b"x") as t:
        with pytest.raises(ValueError):
            benchlib.safe_members(t)


def test_safe_members_rejects_links():
    info = tarfile.TarInfo("pack/link")
    info.type = tarfile.SYMTYPE
    info.linkname = "/etc/passwd"
    with _tar_with(info) as t:
        with pytest.raises(ValueError):
            benchlib.safe_members(t)


def test_safe_members_accepts_plain_files():
    with _tar_with(tarfile.TarInfo("pack/tokens.txt"), b"a 0\n") as t:
        assert [m.name for m in benchlib.safe_members(t)] == ["pack/tokens.txt"]


# --- audio ------------------------------------------------------------------

def test_resample_keeps_duration_and_tone():
    src = 22050
    t = np.arange(src) / src
    tone = (0.5 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
    out = benchlib.resample(tone, src, 16000)
    assert out.dtype == np.float32
    assert len(out) == 16000
    spectrum = np.abs(np.fft.rfft(out))
    peak_hz = np.argmax(spectrum) * 16000 / len(out)
    assert abs(peak_hz - 440) <= 2


def test_resample_identity_when_rates_match():
    x = np.linspace(-1, 1, 100, dtype=np.float32)
    assert np.array_equal(benchlib.resample(x, 16000, 16000), x)


def test_wav_roundtrip_is_16bit_mono(tmp_path):
    x = np.array([0.0, 0.5, -0.5, 1.0, -1.0], dtype=np.float32)
    p = tmp_path / "a.wav"
    benchlib.write_wav(p, x, 16000)
    y, rate = benchlib.read_wav(p)
    assert rate == 16000
    assert np.allclose(x, y, atol=1 / 32767)


def test_peak_normalize_avoids_clipping():
    x = np.array([0.0, 2.0, -4.0], dtype=np.float32)
    y = benchlib.peak_normalize(x, 0.9)
    assert math.isclose(float(np.max(np.abs(y))), 0.9, rel_tol=1e-6)


# --- text scoring -----------------------------------------------------------

def test_normalize_text_folds_case_punctuation_and_nfc():
    assert benchlib.normalize_text("  Take-off, to THREE metres! ") == "take off to three metres"
    # NFC: a decomposed Devanagari nukta letter equals its precomposed form.
    assert benchlib.normalize_text("\u0921\u093c") == benchlib.normalize_text("\u095c")
    # Combining marks (Indic vowel signs, virama) are kept.
    assert benchlib.normalize_text("மூன்று") == "மூன்று"


def test_normalize_text_drops_zero_width_joiners():
    assert benchlib.normalize_text("ಮೀಟರ್\u200cಗಳು") == "ಮೀಟರ್ಗಳು"


def test_word_and_char_errors():
    assert benchlib.word_errors("go left two metres", "go left two metres") == (0, 4)
    assert benchlib.word_errors("go left two metres", "go lift metres") == (2, 4)
    assert benchlib.word_errors("land", "") == (1, 1)
    assert benchlib.char_errors("stop", "stop") == (0, 4)
    assert benchlib.char_errors("stop", "shop") == (1, 4)


def test_summary_percentiles():
    s = benchlib.summarize([1.0, 2.0, 3.0, 4.0])
    assert s["n"] == 4 and s["max"] == 4.0
    assert math.isclose(s["mean"], 2.5)
    assert math.isclose(s["p50"], 2.5)
    assert 3.8 <= s["p95"] <= 4.0
    assert benchlib.summarize([]) == {"n": 0}


def test_memory_reports_positive_rss():
    m = benchlib.memory_mib()
    assert m["rss"] > 0 and m["peak_rss"] >= m["rss"] * 0.99


# --- ELF alignment (16 KB page check for .so files in the AAR) --------------

def _elf64_with_loads(aligns):
    phoff, phentsize = 64, 56
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = 2  # ELFCLASS64
    header[5] = 1  # little endian
    struct.pack_into("<Q", header, 0x20, phoff)
    struct.pack_into("<H", header, 0x36, phentsize)
    struct.pack_into("<H", header, 0x38, len(aligns) + 1)
    phdrs = bytearray()
    for a in aligns:
        phdrs += struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 0, 0, a)  # PT_LOAD
    phdrs += struct.pack("<IIQQQQQQ", 2, 6, 0, 0, 0, 0, 0, 8)  # PT_DYNAMIC
    return bytes(header) + bytes(phdrs)


def _elf32_with_loads(aligns):
    phoff, phentsize = 52, 32
    header = bytearray(52)
    header[0:4] = b"\x7fELF"
    header[4] = 1  # ELFCLASS32
    header[5] = 1
    struct.pack_into("<I", header, 0x1C, phoff)
    struct.pack_into("<H", header, 0x2A, phentsize)
    struct.pack_into("<H", header, 0x2C, len(aligns))
    phdrs = b"".join(struct.pack("<IIIIIIII", 1, 0, 0, 0, 0, 0, 5, a) for a in aligns)
    return bytes(header) + phdrs


def test_elf_load_alignments_64_and_32_bit():
    assert benchlib.elf_load_alignments(_elf64_with_loads([0x4000, 0x4000])) == [0x4000, 0x4000]
    assert benchlib.elf_load_alignments(_elf32_with_loads([0x1000])) == [0x1000]
    with pytest.raises(ValueError):
        benchlib.elf_load_alignments(b"not an elf")


# --- command phrases ----------------------------------------------------------

LANGS = ("en", "hi", "te", "ta", "kn", "ml")


def test_every_language_has_the_core_command_phrases():
    for lang in LANGS:
        ids = {p["id"] for p in phrases.PHRASES[lang]}
        assert set(phrases.CORE_IDS) <= ids, lang


def test_indic_phrases_are_written_in_their_own_script():
    for lang, (lo, hi) in phrases.SCRIPTS.items():
        for p in phrases.PHRASES[lang]:
            letters = [c for c in p["text"] if not c.isspace() and c not in "\u200c\u200d"]
            assert letters and all(lo <= ord(c) <= hi for c in letters), (lang, p["text"])


def test_phrases_parse_with_the_reference_rule_parser():
    """Every phrase is built from the lexicon: the rule parser reads it as intended."""
    if not (ROOT / "edge" / "app" / "intent.py").exists():
        pytest.skip("edge reference parser archived")
    spec = importlib.util.spec_from_file_location("ref_intent", ROOT / "edge" / "app" / "intent.py")
    intent = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(intent)
    for lang in LANGS:
        for p in phrases.PHRASES[lang]:
            if p["expect"] is None:
                with pytest.raises(intent.IntentError):
                    intent.parse_transcript(p["text"], lang)
                continue
            got = intent.parse_transcript(p["text"], lang)
            assert got["command"] == p["expect"]["command"], (lang, p["text"], got)
            for k, v in p["expect"].get("parameters", {}).items():
                assert got["parameters"][k] == v, (lang, p["text"], got)


VOSK_LANGS = ("en", "hi", "te")


def test_vosk_grammar_covers_every_bench_phrase():
    for lang in VOSK_LANGS:
        grammar = set(phrases.vosk_grammar(lang))
        for p in phrases.PHRASES[lang]:
            assert phrases.vosk_spelling(lang, p["text"]) in grammar, (lang, p["text"])
        assert "[unk]" in grammar


def test_vosk_grammar_has_number_range_and_negation():
    en = phrases.vosk_grammar("en")
    assert "move forward ten meters" in en and "move forward one meter" in en
    assert "do not take off" in en and "don't land" in en
    hi = phrases.vosk_grammar("hi")
    assert "दस मीटर आगे जाओ" in hi and "लैंड मत करो" in hi
    te = phrases.vosk_grammar("te")
    assert "పది మీటర్లు ముందుకు వెళ్ళు" in te and "ల్యాండ్ చేయకు" in te


def test_vosk_grammar_with_fillers_adds_only_non_command_words():
    plain, filled = phrases.vosk_grammar("en"), phrases.vosk_grammar("en", fillers=True)
    extra = set(filled) - set(plain)
    command_words = {w for p in plain for w in p.split()}
    assert len(extra) >= 150 and all(len(e.split()) == 1 for e in extra)
    assert not extra & command_words


def test_vosk_spelling_maps_british_metres_for_the_en_in_model():
    assert phrases.vosk_spelling("en", "move forward three metres") == "move forward three meters"
    assert phrases.vosk_spelling("te", "మూడు మీటర్లు టేకాఫ్") == "మూడు మీటర్లు టేకాఫ్"


def test_kws_keywords_are_english_stop_words():
    assert set(phrases.KWS_KEYWORDS) == {"stop", "hold"}


def test_clip_index_round_trip(tmp_path):
    rows = [{"file": "en/stop_m.wav", "lang": "en", "id": "stop", "text": "stop", "voice": "en", "seconds": 0.5}]
    benchlib.write_index(tmp_path, rows)
    assert json.loads((tmp_path / "index.json").read_text(encoding="utf-8"))["clips"] == rows
    assert benchlib.read_index(tmp_path) == rows
