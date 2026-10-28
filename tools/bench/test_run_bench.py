"""Tests for run_bench.py (pure logic and the adb loop against a fake device; no emulator needed).

Run from the worktree root: python -m pytest tools/bench/test_run_bench.py -q
"""
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import run_bench  # noqa: E402

IMPORT = run_bench.IMPORT
CLIPS = run_bench.CLIPS


def _manifest():
    return {"packs": [
        {"id": "indicconformer-ta-ctc-int8", "language": "ta", "engine": "sherpa-onnx-nemo-ctc",
         "files": ["models/sherpa/indicconformer/ta/model.int8.onnx", "models/sherpa/indicconformer/tokens.txt"]},
        {"id": "kws-gigaspeech-3.3M-chunk-16", "language": "en", "engine": "sherpa-onnx-kws",
         "files": ["models/sherpa/kws/giga/encoder.onnx", "models/sherpa/kws/giga/tokens.txt"]},
    ]}


def _tooling(tmp_path, missing=()):
    for rel in ["vosk/vosk-model-small-en-in-0.4.zip", "vosk/vosk-model-small-hi-0.22.zip",
                "models/sherpa/indicconformer/ta/model.int8.onnx", "models/sherpa/indicconformer/tokens.txt",
                "models/sherpa/kws/giga/encoder.onnx", "models/sherpa/kws/giga/tokens.txt"]:
        if rel not in missing:
            f = tmp_path / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(b"x")
    return tmp_path


def test_units_are_vosk_then_ctc_then_kws():
    units = run_bench.units(["en", "hi"], ["ta"], ["kws-gigaspeech-3.3M-chunk-16"], _manifest())
    assert [(u.kind, u.lang, u.id) for u in units] == [
        ("vosk", "en", "vosk-en"), ("vosk", "hi", "vosk-hi"), ("ctc", "ta", "ctc-ta"),
        ("kws", "en", "kws-gigaspeech-3.3M-chunk-16")]


def test_units_refuse_unknown_languages_and_packs():
    with pytest.raises(ValueError, match="Vosk"):
        run_bench.units(["ta"], [], [], _manifest())
    with pytest.raises(ValueError, match="CTC"):
        run_bench.units([], ["kn"], [], _manifest())
    with pytest.raises(ValueError, match="KWS"):
        run_bench.units([], [], ["kws-nope"], _manifest())


def test_push_plan_uses_the_import_layout_the_app_reads(tmp_path):
    tooling = _tooling(tmp_path)
    units = run_bench.units(["en"], ["ta"], ["kws-gigaspeech-3.3M-chunk-16"], _manifest())
    pushes, missing = run_bench.push_plan(units, tooling, _manifest())
    assert missing == []
    assert [(local.relative_to(tooling).as_posix(), remote) for local, remote in pushes] == [
        ("vosk/vosk-model-small-en-in-0.4.zip", f"{IMPORT}/vosk-en-in/vosk-model-small-en-in-0.4.zip"),
        ("models/sherpa/indicconformer/ta/model.int8.onnx", f"{IMPORT}/indicconformer-ta-ctc-int8/model.int8.onnx"),
        ("models/sherpa/indicconformer/tokens.txt", f"{IMPORT}/indicconformer-ta-ctc-int8/tokens.txt"),
        ("models/sherpa/kws/giga/encoder.onnx", f"{IMPORT}/kws-gigaspeech-3.3M-chunk-16/encoder.onnx"),
        ("models/sherpa/kws/giga/tokens.txt", f"{IMPORT}/kws-gigaspeech-3.3M-chunk-16/tokens.txt"),
    ]


def test_push_plan_reports_missing_files_and_drops_their_unit(tmp_path):
    tooling = _tooling(tmp_path, missing={"models/sherpa/indicconformer/tokens.txt"})
    units = run_bench.units(["en"], ["ta"], [], _manifest())
    pushes, missing = run_bench.push_plan(units, tooling, _manifest())
    assert [m.relative_to(tooling).as_posix() for m in missing] == ["models/sherpa/indicconformer/tokens.txt"]
    assert all("indicconformer" not in remote for _, remote in pushes)


def test_clip_plan_takes_the_first_clips_per_language():
    rows = [{"file": f"{lang}/{i}.wav", "lang": lang} for lang in ("en", "hi", "ta") for i in range(3)]
    subset, pushes = run_bench.clip_plan(rows, ["en", "ta"], 2, Path("/clips"))
    assert [r["file"] for r in subset] == ["en/0.wav", "en/1.wav", "ta/0.wav", "ta/1.wav"]
    assert pushes[0] == (Path("/clips/en/0.wav"), f"{CLIPS}/en/0.wav")


def test_clip_plan_refuses_escaping_paths():
    with pytest.raises(ValueError):
        run_bench.clip_plan([{"file": "../x.wav", "lang": "en"}], ["en"], 1, Path("/clips"))


def test_am_start_passes_the_extras_the_activity_reads():
    unit = run_bench.Unit("ctc", "ta", "ctc-ta", "indicconformer-ta-ctc-int8")
    assert run_bench.am_start(unit, 7, None) == [
        "shell", "am", "start", "-n", run_bench.ACTIVITY,
        "--es", "engines", "ctc", "--es", "langs", "ta", "--ei", "clips", "7"]
    assert run_bench.am_start(unit, 3, 2)[-6:] == ["--ei", "clips", "3", "--ei", "threads", "2"]


def test_prepare_lets_the_app_create_its_own_folders_first():
    assert run_bench.prepare_start() == ["shell", "am", "start", "-W", "-n", run_bench.ACTIVITY, "--ez", "prepare", "true"]


def test_new_results_ignores_partial_and_old_files():
    before = {"20261014-100000-000.json"}
    listing = "20261014-100000-000.json\n20261014-110000-000.json.part\n20261014-110500-123.json\nclips\n"
    assert run_bench.new_results(before, listing) == ["20261014-110500-123.json"]


def _report(unit_id="vosk-en", on="EMULATOR", error=None):
    return {
        "schema": "aeronex-bench/1", "measuredOn": on, "representativeOfR0R5": False if on == "EMULATOR" else None,
        "note": "EMULATOR ... not representative ...", "environment": {"model": "sdk_gphone64_x86_64"},
        "error": None,
        "engines": [{
            "measuredOn": on, "id": unit_id, "engine": "VOSK_GRAMMAR", "threads": 1, "loadMs": 812.3, "error": error,
            "summary": {
                "clips": 7, "verdicts": {"EXACT": 5, "FALSE_ACCEPT": 0, "REJECTED": 2, "FALSE_STOP": 0},
                "pttUpToFinalMs": {"n": 6, "p50Ms": 101.5, "p95Ms": 140.0, "maxMs": 140.0},
                "pttUpToParsedMs": {"n": 6, "p50Ms": 103.0, "p95Ms": 142.0, "maxMs": 142.0},
                "rtf": {"n": 6, "p50": 0.031, "p95": 0.05, "max": 0.05},
                "spotter": {"stopClips": 1, "hits": 1, "otherClips": 6, "falseFires": 0},
                "loadPrivateDirtyPlusSwapKb": 102400, "peakPrivateDirtyPlusSwapKb": 204800,
                "peakFileBackedKb": 51200,
            },
        }],
    }


def test_merge_keeps_every_run_and_the_weakest_label():
    merged = run_bench.merge([_report("vosk-en"), _report("ctc-ta")], {"vosk": ["en"]})
    assert merged["schema"] == run_bench.SCHEMA
    assert merged["measuredOn"] == "EMULATOR"
    assert merged["representativeOfR0R5"] is False
    assert [r["engines"][0]["id"] for r in merged["runs"]] == ["vosk-en", "ctc-ta"]
    assert merged["request"] == {"vosk": ["en"]}
    mixed = run_bench.merge([_report(), _report(on="DEVICE")], {})
    assert mixed["measuredOn"] == "MIXED"
    assert mixed["representativeOfR0R5"] is False


def test_table_labels_every_row_with_where_it_was_measured():
    text = run_bench.table(run_bench.merge([_report("vosk-en"), _report("ctc-ta", error="OutOfMemoryError: x")], {}))
    lines = text.splitlines()
    assert "EMULATOR" in lines[0] and "not representative" in lines[0]
    row = next(line for line in lines if line.startswith("vosk-en"))
    assert "EMULATOR" in row and "812" in row and "101.5/140.0" in row and "0.031" in row
    assert "5/0/2/0" in row and "1/1 0/6" in row and "100.0" in row and "200.0" in row and "50.0" in row
    assert "OutOfMemoryError" in next(line for line in lines if line.startswith("ctc-ta"))


class FakeAdb:
    """A device whose BenchActivity writes one report per am start."""

    def __init__(self, tmp_path, die=False):
        self.tmp = tmp_path
        self.die = die
        self.files = {}
        self.calls = []
        self.started = 0

    def run(self, *args, check=True):
        self.calls.append(list(args))
        if args[:3] == ("shell", "am", "start"):
            self.started += 1
            if not self.die:
                self.files[f"2026101{self.started}-000000-000.json"] = json.dumps(_report(f"unit{self.started}"))
            return ""
        if args[:2] == ("shell", "ls"):
            return "\n".join(sorted(self.files)) + "\n"
        if args[:2] == ("shell", "pidof"):
            return "" if self.die else "1234\n"
        if args[0] == "pull":
            Path(args[2]).write_text(self.files[args[1].rsplit("/", 1)[1]], encoding="utf-8")
            return ""
        return ""


def test_each_unit_runs_in_a_fresh_process_and_its_json_is_pulled(tmp_path):
    adb = FakeAdb(tmp_path)
    units = run_bench.units(["en"], ["ta"], [], _manifest())
    reports = run_bench.run_units(adb, units, clips=7, threads=None, raw_dir=tmp_path, timeout_s=5, poll_s=0)
    assert [r["engines"][0]["id"] for r in reports] == ["unit1", "unit2"]
    starts = [c for c in adb.calls if c[:3] == ["shell", "am", "start"]]
    assert [c[c.index("engines") + 1] for c in starts] == ["vosk", "ctc"]
    stops = [i for i, c in enumerate(adb.calls) if c[:3] == ["shell", "am", "force-stop"]]
    first_start = adb.calls.index(starts[0])
    assert stops and stops[0] < first_start, "force-stop before each start"
    assert (tmp_path / "20261011-000000-000.json").is_file()


def test_a_process_that_dies_is_reported_not_waited_for(tmp_path):
    adb = FakeAdb(tmp_path, die=True)
    units = run_bench.units(["en"], [], [], _manifest())
    reports = run_bench.run_units(adb, units, clips=7, threads=None, raw_dir=tmp_path, timeout_s=5, poll_s=0,
                                  grace_s=0)
    assert reports[0]["error"].startswith("vosk-en: the bench process ended without a report")
    assert reports[0]["engines"] == []
