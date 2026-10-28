"""Drive the debug-only BenchActivity over adb (plan T14) and collect its reports.

For each engine it: force-stops the app, starts BenchActivity in a fresh process (so memory numbers never mix), waits
for getExternalFilesDir(null)/bench/<timestamp>.json, pulls it, and force-stops again. Before that it lets the app
create its folders, pushes the model packs to the import folder the app verifies them from (T11:
files/import/<pack id>/<file>; Vosk ZIPs as files/import/<profile id>/<zip>, a bench-only layout) and a subset of the
eSpeak clips to files/bench/clips/. Same-size files already on the device are not pushed again.

Every number is labelled with where it was measured. On the emulator it is an x86_64 guest on a desktop CPU and is
NOT representative of the R0-R5 reference phones; the spec's Tier gates stay pending real phones.

Run from the worktree root (needs a debug APK on the device: it has BenchActivity, release APKs do not):
    python tools/bench/run_bench.py --install app/build/app/outputs/flutter-apk/app-debug.apk \
        --vosk en,hi,te --ctc ta --kws kws-gigaspeech-3.3M-chunk-16 --clips 7 \
        --out docs/testing/2026-10-14-bench-emulator.json
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
TOOLING = ROOT.parent / "tooling"
PACKAGE = "com.aeronex.app"
ACTIVITY = f"{PACKAGE}/.bench.BenchActivity"
FILES = f"/sdcard/Android/data/{PACKAGE}/files"
IMPORT = f"{FILES}/import"
BENCH = f"{FILES}/bench"
CLIPS = f"{BENCH}/clips"
SCHEMA = "aeronex-bench-runs/1"
# Mirrors VoskModels.kt: language -> (profile id, archive in tooling/vosk/).
VOSK = {
    "en": ("vosk-en-in", "vosk-model-small-en-in-0.4.zip"),
    "hi": ("vosk-hi", "vosk-model-small-hi-0.22.zip"),
    "te": ("vosk-te", "vosk-model-small-te-0.42.zip"),
}
EMULATOR_NOTE = ("x86_64 guest on a desktop CPU: not representative of the R0-R5 reference phones; "
                 "the spec Tier gates (6.2, 6.3) stay pending real phones")


@dataclass(frozen=True)
class Unit:
    kind: str  # vosk | ctc | kws
    lang: str
    id: str
    pack: str | None


def units(vosk, ctc, kws, manifest) -> list[Unit]:
    """The engine runs in order: Vosk languages, CTC languages, KWS packs (each on its own language's clips)."""
    packs = {p["id"]: p for p in manifest["packs"]}
    result = []
    for lang in vosk:
        if lang not in VOSK:
            raise ValueError(f"No Vosk model for {lang}")
        result.append(Unit("vosk", lang, f"vosk-{lang}", None))
    for lang in ctc:
        pack = f"indicconformer-{lang}-ctc-int8"
        if pack not in packs:
            raise ValueError(f"No IndicConformer CTC pack for {lang}")
        result.append(Unit("ctc", lang, f"ctc-{lang}", pack))
    for pack in kws:
        if packs.get(pack, {}).get("engine") != "sherpa-onnx-kws":
            raise ValueError(f"Unknown KWS pack {pack}")
        result.append(Unit("kws", packs[pack]["language"], pack, pack))
    return result


def unit_files(unit: Unit, tooling: Path, manifest) -> list[tuple[Path, str]]:
    """(local file, device path) for one unit's model files."""
    if unit.kind == "vosk":
        profile, archive = VOSK[unit.lang]
        return [(tooling / "vosk" / archive, f"{IMPORT}/{profile}/{archive}")]
    pack = next(p for p in manifest["packs"] if p["id"] == unit.pack)
    return [(tooling / rel, f"{IMPORT}/{unit.pack}/{PurePosixPath(rel).name}") for rel in pack["files"]]


def push_plan(units_, tooling: Path, manifest):
    """Every file to push, and the local files that are missing (their unit is left out of the pushes)."""
    pushes, missing = [], []
    for unit in units_:
        files = unit_files(unit, tooling, manifest)
        absent = [local for local, _ in files if not local.is_file()]
        missing += absent
        if not absent:
            pushes += files
    return pushes, missing


def clip_plan(rows, langs, count, clips_root: Path):
    """The first `count` clips of each language in index order, and their pushes."""
    subset = []
    for lang in langs:
        subset += [r for r in rows if r["lang"] == lang][:count]
    pushes = []
    for row in subset:
        parts = PurePosixPath(row["file"]).parts
        if not parts or row["file"].startswith("/") or any(p in ("", ".", "..") for p in parts) or "\\" in row["file"]:
            raise ValueError(f"Clip path {row['file']} leaves the clip folder")
        pushes.append((clips_root.joinpath(*parts), f"{CLIPS}/{row['file']}"))
    return subset, pushes


def am_start(unit: Unit, clips: int, threads: int | None) -> list[str]:
    args = ["shell", "am", "start", "-n", ACTIVITY, "--es", "engines", unit.kind, "--es", "langs", unit.lang,
            "--ei", "clips", str(clips)]
    if threads is not None:
        args += ["--ei", "threads", str(threads)]
    return args


def prepare_start() -> list[str]:
    """Lets the app create files/import and files/bench/clips itself before the shell pushes into them."""
    return ["shell", "am", "start", "-W", "-n", ACTIVITY, "--ez", "prepare", "true"]


def new_results(before: set, listing: str) -> list[str]:
    names = [line.strip() for line in listing.splitlines()]
    return sorted(n for n in names if n.endswith(".json") and n not in before)


def failed(unit: Unit, reason: str) -> dict:
    return {"schema": "aeronex-bench/1", "unit": unit.id, "measuredOn": None, "engines": [], "error": f"{unit.id}: {reason}"}


def run_units(adb, units_, clips, threads, raw_dir: Path, timeout_s=1800, poll_s=2.0, grace_s=15.0) -> list[dict]:
    """One fresh app process per unit; returns each pulled report (or a failure record)."""
    reports = []
    for unit in units_:
        adb.run("shell", "am", "force-stop", PACKAGE)
        before = set(new_results(set(), adb.run("shell", "ls", "-1", BENCH, check=False)))
        print(f"== {unit.id}: starting BenchActivity", flush=True)
        adb.run(*am_start(unit, clips, threads))
        started = time.monotonic()
        name, reason = None, f"timed out after {timeout_s} s"
        while time.monotonic() - started < timeout_s:
            fresh = new_results(before, adb.run("shell", "ls", "-1", BENCH, check=False))
            if fresh:
                name = fresh[0]
                break
            if time.monotonic() - started >= grace_s and not adb.run("shell", "pidof", PACKAGE, check=False).strip():
                reason = "the bench process ended without a report (crash or OOM kill: check logcat -s AeroNexBench)"
                break
            time.sleep(poll_s)
        if name is None:
            print(f"   {unit.id}: {reason}", flush=True)
            reports.append(failed(unit, reason))
        else:
            local = raw_dir / name
            adb.run("pull", f"{BENCH}/{name}", str(local))
            report = json.loads(local.read_text(encoding="utf-8"))
            report["unit"] = unit.id
            reports.append(report)
            print(f"   {unit.id}: {name} ({time.monotonic() - started:.0f} s)", flush=True)
        adb.run("shell", "am", "force-stop", PACKAGE)
    return reports


def merge(reports: list[dict], request: dict) -> dict:
    labels = {r["measuredOn"] for r in reports if r.get("measuredOn")}
    on = labels.pop() if len(labels) == 1 else ("MIXED" if labels else None)
    return {
        "schema": SCHEMA,
        "measuredOn": on,
        "representativeOfR0R5": False if on in ("EMULATOR", "MIXED") else None,
        "note": next((r["note"] for r in reports if r.get("note")), None),
        "generatedBy": "tools/bench/run_bench.py",
        "request": request,
        "runs": reports,
    }


def _pair(summary, key, fmt="{:.1f}"):
    value = summary.get(key)
    return "-" if not value else f"{fmt.format(value['p50Ms'])}/{fmt.format(value['p95Ms'])}"


def _mib(kb):
    return "-" if kb is None else f"{kb / 1024:.1f}"


def table(merged: dict) -> str:
    on = merged.get("measuredOn")
    head = f"AeroNex bench, measured on {on}"
    if on in ("EMULATOR", "MIXED"):
        head += f" ({EMULATOR_NOTE})"
    rows = [["unit", "on", "thr", "load ms", "up->final p50/p95", "up->parsed p50/p95", "RTF p50",
             "exact/false/rej/fstop", "spot hit/stop false/other", "load PD+swap MiB", "peak PD+swap MiB",
             "peak file MiB", "error"]]
    for report in merged["runs"]:
        if not report.get("engines"):
            rows.append([report.get("unit", "?"), str(report.get("measuredOn")), "", "", "", "", "", "", "", "", "", "",
                         str(report.get("error"))])
        for engine in report.get("engines", []):
            s = engine.get("summary", {})
            verdicts = s.get("verdicts")
            spot = s.get("spotter")
            rtf = s.get("rtf")
            rows.append([
                engine["id"], str(engine.get("measuredOn")), str(engine.get("threads") or "-"),
                "-" if engine.get("loadMs") is None else f"{engine['loadMs']:.0f}",
                _pair(s, "pttUpToFinalMs"), _pair(s, "pttUpToParsedMs"),
                "-" if not rtf else f"{rtf['p50']:.3f}",
                "-" if not verdicts else "/".join(str(verdicts[k]) for k in ("EXACT", "FALSE_ACCEPT", "REJECTED", "FALSE_STOP")),
                "-" if not spot else f"{spot['hits']}/{spot['stopClips']} {spot['falseFires']}/{spot['otherClips']}",
                _mib(s.get("loadPrivateDirtyPlusSwapKb")), _mib(s.get("peakPrivateDirtyPlusSwapKb")),
                _mib(s.get("peakFileBackedKb")), str(engine.get("error") or report.get("error") or ""),
            ])
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    lines = [head] + ["  ".join(cell.ljust(widths[i]) for i, cell in enumerate(r)).rstrip() for r in rows]
    return "\n".join(lines)


class Adb:
    def __init__(self, path: str, serial: str | None = None):
        self.base = [path] + (["-s", serial] if serial else [])

    def run(self, *args, check=True) -> str:
        done = subprocess.run(self.base + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace")
        if check and done.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {done.stderr.strip() or done.stdout.strip()}")
        return done.stdout

    def remote_size(self, path: str) -> int | None:
        out = self.run("shell", "stat", "-c", "%s", path, check=False).strip()
        return int(out) if out.isdigit() else None

    def push_if_changed(self, local: Path, remote: str) -> bool:
        if self.remote_size(remote) == local.stat().st_size:
            return False
        self.run("shell", "mkdir", "-p", str(PurePosixPath(remote).parent))
        self.run("push", str(local), remote)
        return True


def default_adb() -> str:
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")):
        if root:
            for name in ("adb.exe", "adb"):
                candidate = Path(root) / "platform-tools" / name
                if candidate.is_file():
                    return str(candidate)
    return "adb"


def _list(text: str) -> list[str]:
    return [item.strip() for item in text.split(",") if item.strip()]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", default=default_adb())
    parser.add_argument("--serial")
    parser.add_argument("--install", type=Path, help="debug APK to install first (adb install -r)")
    parser.add_argument("--tooling", type=Path, default=TOOLING)
    parser.add_argument("--vosk", default="en,hi,te", help="Vosk languages (comma list, may be empty)")
    parser.add_argument("--ctc", default="ta", help="IndicConformer CTC languages")
    parser.add_argument("--kws", default="kws-gigaspeech-3.3M-chunk-16", help="KWS pack ids")
    parser.add_argument("--clips", type=int, default=7, help="clips per language (index order: voice v0 first)")
    parser.add_argument("--threads", type=int, help="CTC threads (default: the app's EnginePolicy value)")
    parser.add_argument("--no-push", action="store_true", help="the packs and clips are already on the device")
    parser.add_argument("--raw-dir", type=Path, help="where the per-engine JSON files are pulled (default: a temp dir)")
    parser.add_argument("--out", type=Path, help="merged JSON of every run")
    parser.add_argument("--timeout", type=int, default=1800, help="seconds per engine run")
    args = parser.parse_args(argv)

    manifest = json.loads((args.tooling / "models/sherpa/manifest.json").read_text(encoding="utf-8"))
    todo = units(_list(args.vosk), _list(args.ctc), _list(args.kws), manifest)
    adb = Adb(args.adb, args.serial)
    adb.run("get-state")
    if args.install:
        print(f"installing {args.install}", flush=True)
        adb.run("install", "-r", str(args.install))
    raw_dir = args.raw_dir or Path(tempfile.mkdtemp(prefix="aeronex-bench-"))
    raw_dir.mkdir(parents=True, exist_ok=True)

    if not args.no_push:
        adb.run("shell", "am", "force-stop", PACKAGE)
        adb.run(*prepare_start())
        time.sleep(2)
        pushes, missing = push_plan(todo, args.tooling, manifest)
        for path in missing:
            print(f"missing on this PC (its engine is skipped): {path}")
        todo = [u for u in todo if all(local.is_file() for local, _ in unit_files(u, args.tooling, manifest))]
        for local, remote in pushes:
            print(f"push {local.name} -> {remote}" if adb.push_if_changed(local, remote) else f"same size on device: {remote}",
                  flush=True)
        clips_root = args.tooling / "models/sherpa/clips-espeak"
        rows = json.loads((clips_root / "index.json").read_text(encoding="utf-8"))["clips"]
        subset, clip_pushes = clip_plan(rows, sorted({u.lang for u in todo}), args.clips, clips_root)
        index = raw_dir / "index.json"
        index.write_text(json.dumps({"clips": subset}, ensure_ascii=False, indent=1), encoding="utf-8")
        adb.run("shell", "mkdir", "-p", CLIPS)
        adb.run("push", str(index), f"{CLIPS}/index.json")
        for local, remote in clip_pushes:
            adb.push_if_changed(local, remote)
        print(f"pushed the clip index with {len(subset)} clips", flush=True)

    reports = run_units(adb, todo, args.clips, args.threads, raw_dir, timeout_s=args.timeout)
    merged = merge(reports, {"vosk": _list(args.vosk), "ctc": _list(args.ctc), "kws": _list(args.kws),
                             "clips": args.clips, "threads": args.threads, "rawDir": str(raw_dir)})
    if args.out:
        args.out.write_text(json.dumps(merged, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"wrote {args.out}")
    print(table(merged))
    return 0 if all(not r.get("error") and not any(e.get("error") for e in r.get("engines", [])) for r in reports) else 1


if __name__ == "__main__":
    sys.exit(main())
