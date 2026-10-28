"""Verify official Vosk ZIPs, extract checked manifests, and optionally smoke-test models.

Large archives and extracted weights belong outside the app source. Metadata contains
the complete extracted-file manifest used by Android, not a claim of speech accuracy.
"""
import argparse
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import sys
import urllib.request
import wave
import zipfile

CATALOG_URL = "https://alphacephei.com/vosk/models/model-list.json"
LICENSE_URL = "https://alphacephei.com/vosk/models"
MODELS = {
    "vosk-en-in": "vosk-model-small-en-in-0.4",
    "vosk-te": "vosk-model-small-te-0.42",
    "vosk-hi": "vosk-model-small-hi-0.22",
}
MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_EXTRACTED_BYTES = 256 * 1024 * 1024


class CatalogRows(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows, self.row, self.cell = [], None, None

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self.row = []
        if tag in ("td", "th") and self.row is not None:
            self.cell = []

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self.cell is not None:
            self.row.append(" ".join("".join(self.cell).split()))
            self.cell = None
        if tag == "tr" and self.row is not None:
            self.rows.append(self.row)
            self.row = None


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as source:
        if not source.url.startswith("https://"):
            raise ValueError("Model requests require HTTPS redirects")
        return source.read()


def hashes(path):
    md5, sha256 = hashlib.md5(), hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(65536), b""):
            md5.update(data)
            sha256.update(data)
    return md5.hexdigest(), sha256.hexdigest()


def download(entry, path):
    if not entry["url"].startswith("https://alphacephei.com/vosk/models/"):
        raise ValueError("Unexpected catalog download URL")
    temporary = path.with_suffix(".zip.part")
    try:
        with urllib.request.urlopen(entry["url"], timeout=60) as source, temporary.open("wb") as target:
            if not source.url.startswith("https://"):
                raise ValueError("Download redirected away from HTTPS")
            count = 0
            for data in iter(lambda: source.read(65536), b""):
                count += len(data)
                if count > entry["size"] or count > MAX_ARCHIVE_BYTES:
                    raise ValueError("Archive is larger than the official catalog size")
                target.write(data)
        if temporary.stat().st_size != entry["size"] or hashes(temporary)[0] != entry["md5"]:
            raise ValueError("Archive does not match the official catalog MD5/size")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def manifest_and_extract(archive, name, directory):
    manifest, readmes, seen, total = {}, {}, set(), 0
    with tempfile.TemporaryDirectory(prefix="vosk-check-", dir=directory) as temporary:
        staging = Path(temporary) / name
        staging.mkdir()
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                entry_name = entry.filename
                parts = entry_name.rstrip("/").split("/")
                if (entry_name in seen or "\\" in entry_name or ":" in entry_name
                        or any(part in ("", ".", "..") for part in parts)
                        or parts[0] != name or PurePosixPath(entry_name).is_absolute()
                        or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                    raise ValueError(f"Unsafe or duplicate ZIP entry: {entry_name}")
                seen.add(entry_name)
                if entry.is_dir():
                    (Path(temporary) / entry_name).mkdir(parents=True, exist_ok=True)
                    continue
                if len(parts) < 2:
                    raise ValueError("Files must be within the model root")
                total += entry.file_size
                if total > MAX_EXTRACTED_BYTES:
                    raise ValueError("Extracted archive exceeds the bounded model limit")
                relative = "/".join(parts[1:])
                output = staging / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                digest, size = hashlib.sha256(), 0
                with source.open(entry) as item, output.open("wb") as target:
                    for data in iter(lambda: item.read(65536), b""):
                        size += len(data)
                        if size > entry.file_size:
                            raise ValueError("Entry expanded beyond its declared size")
                        digest.update(data)
                        target.write(data)
                if size != entry.file_size:
                    raise ValueError("Incomplete ZIP entry")
                manifest[relative] = {"size": size, "sha256": digest.hexdigest()}
                if Path(relative).name.lower().startswith(("readme", "license", "copying")):
                    readmes[relative] = output.read_text(encoding="utf-8", errors="replace")
        installed = directory / name
        # Keep a verified existing extraction so another host evaluation may use it.
        expected_directories = {str(PurePosixPath(relative).parent) for relative in manifest
                                if str(PurePosixPath(relative).parent) != "."}
        expected_directories |= {str(parent) for relative in manifest
                                 for parent in PurePosixPath(relative).parents if str(parent) != "."}
        existing_valid = installed.is_dir() and not installed.is_symlink() and all(
            (installed / relative).is_file()
            and not (installed / relative).is_symlink()
            and (installed / relative).stat().st_size == item["size"]
            and hashes(installed / relative)[1] == item["sha256"]
            for relative, item in manifest.items()) and all(
            not path.is_symlink() and
            (path.is_dir() and path.relative_to(installed).as_posix() in expected_directories
             or path.is_file() and path.relative_to(installed).as_posix() in manifest)
            for path in installed.rglob("*"))
        if not existing_valid:
            if installed.exists():
                shutil.rmtree(installed)
            os.replace(staging, installed)
    return dict(sorted(manifest.items())), readmes, total


def smoke(model_path, wav_path):
    import vosk
    vosk.SetLogLevel(-1)
    model = vosk.Model(str(model_path))
    recognizer = vosk.KaldiRecognizer(model, 16000)
    segments, partials = [], []
    if wav_path:
        with wave.open(str(wav_path), "rb") as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
                raise ValueError("Host sample must be mono PCM16 at 16 kHz")
            chunks = []
            while data := audio.readframes(4000):
                chunks.append(data)
        sample_kind = "Supplied local WAV; no phone microphone accuracy claim"
    else:
        chunks = [b"\0" * 8000] * 4
        sample_kind = "1 second digital silence; initialization/finalization only"
    for chunk in chunks:
        if recognizer.AcceptWaveform(chunk):
            segments.append(json.loads(recognizer.Result()))
        else:
            partials.append(json.loads(recognizer.PartialResult()))
    final = json.loads(recognizer.FinalResult())
    del recognizer, model
    return {"sample_kind": sample_kind, "chunks": len(chunks),
            "segments": segments, "partials": partials, "final": final}


def write_kotlin(report, path):
    lines = ["package com.aeronex.app", "",
             "/** Official archives verified against catalog MD5; SHA-256 manifests generated",
             " * by tools/check_vosk_models.py. See docs/testing/2026-10-09-vosk-models.json. */",
             "object VoskModels {", "    val profiles: Map<String, LocalModelProfile> = mapOf("]
    for index, (profile_id, item) in enumerate(report["models"].items()):
        lines += [f'        "{profile_id}" to LocalModelProfile(',
                  f'            "{item["name"]}.zip", {item["archive_size"]}L,',
                  f'            "{item["archive_sha256"]}",',
                  f'            "{item["url"]}",', "            archiveFiles = mapOf("]
        for file_index, (relative, meta) in enumerate(item["archive_files"].items()):
            comma = "," if file_index < len(item["archive_files"]) - 1 else ""
            lines.append(f'                "{relative}" to LocalModelFile({meta["size"]}L, "{meta["sha256"]}"){comma}')
        comma = "," if index < len(report["models"]) - 1 else ""
        lines += ["            )", f"        ){comma}"]
    lines += ["    )", "", "    fun profileForLanguage(language: String): String? = when (language.lowercase(java.util.Locale.ROOT)) {",
              '        "en", "en-in", "en-us" -> "vosk-en-in"',
              '        "te", "te-in" -> "vosk-te"', '        "hi", "hi-in" -> "vosk-hi"',
              "        else -> null", "    }", "}", ""]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--download", action="store_true", help="Fetch missing official ZIPs")
    parser.add_argument("--kotlin", type=Path, help="Generate Android profiles from checked files")
    parser.add_argument("--smoke", action="store_true", help="Requires the Python vosk package")
    parser.add_argument("--python-package", type=Path, help="Optional isolated vosk Python package directory")
    parser.add_argument("--wav", type=Path, help="Optional mono PCM16 16 kHz host sample")
    args = parser.parse_args()
    if args.python_package:
        sys.path.insert(0, str(args.python_package.resolve()))
    args.directory.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(fetch(CATALOG_URL))
    licenses = CatalogRows()
    licenses.feed(fetch(LICENSE_URL).decode("utf-8"))
    report = {"checked_at_utc": datetime.now(timezone.utc).isoformat(),
              "catalog_url": CATALOG_URL, "license_url": LICENSE_URL, "models": {}}
    for profile_id, name in MODELS.items():
        entry = next(row for row in catalog if row["name"] == name)
        archive = args.directory / f"{name}.zip"
        if not archive.exists() and args.download:
            download(entry, archive)
        md5, sha256 = hashes(archive)
        if archive.stat().st_size != entry["size"] or md5 != entry["md5"]:
            raise ValueError(f"Official catalog MD5/size mismatch: {name}")
        manifest, readmes, total = manifest_and_extract(archive, name, args.directory)
        license_row = next(row for row in licenses.rows if name in row)
        if "Apache 2.0" not in license_row:
            raise ValueError(f"Unexpected official catalog license: {license_row}")
        item = {"name": name, "url": entry["url"], "catalog_entry": entry,
                "catalog_md5_matches": True, "archive_size": archive.stat().st_size,
                "archive_sha256": sha256, "extracted_size": total,
                "license": "Apache 2.0", "official_catalog_row": license_row,
                "license_files": readmes, "graph_files": [relative for relative in manifest if relative.endswith(".fst")],
                "archive_files": manifest}
        if args.smoke:
            item["host_smoke"] = smoke(args.directory / name, args.wav)
        report["models"][profile_id] = item
        print(f"Verified {name}: {len(manifest)} files, {total} extracted bytes, SHA256 {sha256}", flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.kotlin:
        write_kotlin(report, args.kotlin)


if __name__ == "__main__":
    main()
