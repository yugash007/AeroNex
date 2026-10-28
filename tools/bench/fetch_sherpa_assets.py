"""Fetch, verify and catalogue the Phase 0 sherpa-onnx assets (operator-approved list).

Downloads into the shared tooling directory (outside the repo), checks every
file against a pinned sha256, extracts the KWS archives with path checks, and
writes models/sherpa/manifest.json (url, bytes, sha256, licence per file).
Existing files with the right hash are not downloaded again.

    python -I tools/bench/fetch_sherpa_assets.py --tooling ../tooling
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import sys
import tarfile
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchlib  # noqa: E402

HF_REPO = "parismitaglobalsolutions/indicconformer-sherpa-onnx"
HF_REV = "9721eb71eea141fae0982cfcdb9dd2e3d4953c4a"
HF = f"https://huggingface.co/{HF_REPO}/resolve/{HF_REV}"
GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
INDIC_LICENCE = ("MIT (AI4Bharat IndicConformer weights, ai4bharat/indicconformer_stt_<lang>_hybrid_ctc_rnnt_large); "
                 f"the {HF_REPO} card declares Apache-2.0 for the repackaging")
KWS_LICENCE = {
    "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20":
        ("UNCONFIRMED: no licence file in the archive and training data unstated; sherpa-onnx itself is "
         "Apache-2.0. Confirm with k2-fsa before shipping"),
    "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01":
        "Apache-2.0 (model card README.md inside the archive; trained on GigaSpeech XL with icefall)",
}

# path is relative to the tooling directory; sha256 is the publisher's digest
# (GitHub release digest, kws-models checksum.txt, Hugging Face LFS oid) except
# for tokens.txt, a plain git file with no published hash, pinned by revision.
ASSETS = [
    {"path": "sherpa-onnx/sherpa-onnx-1.13.8.aar",
     "url": f"{GH}/v1.13.8/sherpa-onnx-1.13.8.aar",
     "sha256": "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96",
     "licence": "Apache-2.0 (sherpa-onnx); bundles ONNX Runtime (MIT)",
     "source": "GitHub release v1.13.8, digest published by GitHub"},
    {"path": "models/sherpa/indicconformer/tokens.txt",
     "url": f"{HF}/tokens.txt",
     "sha256": "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2",
     "licence": INDIC_LICENCE,
     "source": f"{HF_REPO}@{HF_REV} (no published hash: pinned by revision, hash recorded at first download)"},
] + [
    {"path": f"models/sherpa/indicconformer/{lang}/model.int8.onnx",
     "url": f"{HF}/{lang}/model.int8.onnx",
     "sha256": sha,
     "licence": INDIC_LICENCE,
     "source": f"{HF_REPO}@{HF_REV}, Hugging Face LFS sha256"}
    for lang, sha in (
        ("ta", "abb7b59d706b8d27ba3fb5e5e3db7671c9e1a09bf7bc6122de507c60030e65fb"),
        ("kn", "b226ce7e4ea35b0dd66991964bd00e011b6b14b0fcdf4f7d1cccd777781c94dc"),
        ("ml", "dcbdfa9f773db910508b40b703cb76c5974e8d4c6f123ea81265b40853c3f0c2"),
        ("te", "710f176e33080f1be84b4f38bb5f33be3f522dcf4f191bf4d5cc89f7a85924f3"),
    )
] + [
    {"path": f"models/sherpa/kws/{name}.tar.bz2",
     "url": f"{GH}/kws-models/{name}.tar.bz2",
     "sha256": sha,
     "licence": KWS_LICENCE[name],
     "source": "sherpa-onnx kws-models release, checksum.txt",
     "extract": True}
    for name, sha in (
        ("sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20",
         "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6"),
        ("sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01",
         "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"),
    )
]

INDIC_PACK = {"engine": "sherpa-onnx-nemo-ctc", "streaming": False, "tier": None, "min_ram_mb": None,
              "grammar_version": None, "note": "tier/min_ram_mb pending phone measurements (Phase 0)"}
PACKS = [
    {"id": f"indicconformer-{lang}-ctc-int8", "language": lang, **INDIC_PACK,
     "files": [f"models/sherpa/indicconformer/{lang}/model.int8.onnx", "models/sherpa/indicconformer/tokens.txt"]}
    for lang in ("ta", "kn", "ml", "te")
] + [
    {"id": "kws-zh-en-3M-chunk-8", "language": "en", "engine": "sherpa-onnx-kws", "streaming": True,
     "tier": None, "min_ram_mb": None, "grammar_version": None,
     "files": [f"models/sherpa/kws/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/{f}" for f in (
         "encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx", "decoder-epoch-13-avg-2-chunk-8-left-64.onnx",
         "joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx", "tokens.txt", "en.phone")]},
    {"id": "kws-gigaspeech-3.3M-chunk-16", "language": "en", "engine": "sherpa-onnx-kws", "streaming": True,
     "tier": None, "min_ram_mb": None, "grammar_version": None,
     "files": [f"models/sherpa/kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/{f}" for f in (
         "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
         "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "tokens.txt", "bpe.model")]},
]


def download(url: str, dest: Path, sha256: str) -> None:
    """Downloads to a .part file and moves it into place only once its sha256 matches; a bad download is deleted."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "aeronex-bench/1"})
    try:
        with urllib.request.urlopen(request, timeout=120) as src:
            if not src.url.startswith("https://"):
                raise ValueError(f"refusing non-HTTPS redirect for {url}")
            with open(part, "wb") as out:
                shutil.copyfileobj(src, out, 1 << 20)
        benchlib.verify_sha256(part, sha256)
    except BaseException:
        part.unlink(missing_ok=True)
        raise
    part.replace(dest)


def extract(archive: Path) -> list:
    """Extract next to the archive; returns the extracted file paths."""
    with tarfile.open(archive, "r:bz2") as tar:
        members = benchlib.safe_members(tar)
        tar.extractall(archive.parent, members=members, filter="data")
    return [archive.parent / m.name for m in members if m.isfile()]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--tooling", required=True, type=Path, help=".worktrees/tooling directory")
    ap.add_argument("--offline", action="store_true", help="verify existing files only")
    args = ap.parse_args(argv)
    tooling = args.tooling.resolve()
    files, extracted = [], []
    for asset in ASSETS:
        dest = tooling / asset["path"]
        if not dest.exists():
            if args.offline:
                raise SystemExit(f"missing {dest} (offline)")
            print(f"downloading {asset['url']}")
            download(asset["url"], dest, asset["sha256"])
        benchlib.verify_sha256(dest, asset["sha256"])
        files.append(benchlib.file_record(dest, tooling, asset["url"], asset["licence"], source=asset["source"]))
        if asset.get("extract"):
            for f in extract(dest):
                extracted.append(benchlib.file_record(
                    f, tooling, asset["url"], asset["licence"], extracted_from=asset["path"]))
        print(f"ok {asset['path']} {dest.stat().st_size} B")
    manifest = {
        "schema": "aeronex-phase0-assets/1",
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "paths_relative_to": ".worktrees/tooling",
        "downloads": files,
        "extracted": extracted,
        "packs": PACKS,
    }
    known = {r["path"] for r in files + extracted}
    for pack in PACKS:
        missing = [f for f in pack["files"] if f not in known]
        if missing:
            raise SystemExit(f"pack {pack['id']} names missing files: {missing}")
    out = tooling / "models" / "sherpa" / "manifest.json"
    out.write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {out}: {len(files)} downloads, {len(extracted)} extracted files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
