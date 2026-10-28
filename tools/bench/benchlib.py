"""Shared helpers for the Phase 0 host benches (tools/bench).

Pure Python + numpy so the same code runs on Windows (Vosk, eSpeak NG) and in
the WSL venv (sherpa-onnx). Nothing here loads a model.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import struct
import sys
import tarfile
import unicodedata
import wave

import numpy as np

# --- hashing, manifest, archives ---------------------------------------------


def sha256_file(path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def verify_sha256(path, expected: str) -> None:
    actual = sha256_file(path)
    if actual != expected:
        raise ValueError(f"sha256 mismatch for {path}: expected {expected}, got {actual}")


def file_record(path, base, url: str, licence: str, **extra) -> dict:
    """Manifest row for one file: path relative to [base], url, bytes, sha256, licence."""
    path = Path(path)
    rel = PurePosixPath(*path.resolve().relative_to(Path(base).resolve()).parts)
    return {
        "path": str(rel),
        "url": url,
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        "licence": licence,
        **extra,
    }


def safe_members(tar: tarfile.TarFile) -> list:
    """Archive members that stay inside the target directory; raises on anything else."""
    members = []
    for m in tar.getmembers():
        name = m.name
        if (PurePosixPath(name).is_absolute() or PureWindowsPath(name).drive
                or name.startswith(("/", "\\")) or ".." in PurePosixPath(name).parts):
            raise ValueError(f"unsafe path in archive: {name!r}")
        if not (m.isfile() or m.isdir()):
            raise ValueError(f"unsupported member type in archive: {name!r}")
        members.append(m)
    return members


# --- audio ---------------------------------------------------------------------


def resample(samples: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    """Band-limited (FFT) resampling of a short mono clip."""
    samples = np.asarray(samples, dtype=np.float32)
    if src_rate == dst_rate:
        return samples
    n_out = int(round(len(samples) * dst_rate / src_rate))
    spectrum = np.fft.rfft(samples.astype(np.float64))
    keep = n_out // 2 + 1
    out_spec = np.zeros(keep, dtype=np.complex128)
    m = min(keep, len(spectrum))
    out_spec[:m] = spectrum[:m]
    out = np.fft.irfft(out_spec, n_out) * (n_out / len(samples))
    return out.astype(np.float32)


def peak_normalize(samples: np.ndarray, peak: float = 0.9) -> np.ndarray:
    top = float(np.max(np.abs(samples))) if len(samples) else 0.0
    return samples if top == 0 else (samples * (peak / top)).astype(np.float32)


def read_wav(path):
    """16-bit PCM wav -> (float32 mono samples in [-1, 1], sample rate)."""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError(f"{path}: only 16-bit PCM is supported")
        rate, channels = w.getframerate(), w.getnchannels()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return (data.astype(np.float32) / 32768.0), rate


def write_wav(path, samples: np.ndarray, rate: int) -> None:
    pcm = np.clip(np.round(np.asarray(samples) * 32767.0), -32768, 32767).astype("<i2")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def write_index(clip_dir, rows: list) -> None:
    Path(clip_dir, "index.json").write_text(
        json.dumps({"clips": rows}, ensure_ascii=False, indent=1), encoding="utf-8")


def read_index(clip_dir) -> list:
    return json.loads(Path(clip_dir, "index.json").read_text(encoding="utf-8"))["clips"]


# --- text scoring ----------------------------------------------------------------

_ZERO_WIDTH = dict.fromkeys(map(ord, "​‌‍⁠﻿"))


def normalize_text(text: str) -> str:
    """NFC, case-folded, punctuation/symbols to spaces, zero-width joiners dropped."""
    text = unicodedata.normalize("NFC", text).translate(_ZERO_WIDTH).casefold()
    chars = [" " if unicodedata.category(c)[0] in "PSZC" else c for c in text]
    return " ".join("".join(chars).split())


def _edits(ref: list, hyp: list) -> int:
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i]
        for j, h in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h)))
        prev = cur
    return prev[-1]


def word_errors(ref: str, hyp: str) -> tuple:
    r, h = normalize_text(ref).split(), normalize_text(hyp).split()
    return _edits(r, h), len(r)


def char_errors(ref: str, hyp: str) -> tuple:
    r = list(normalize_text(ref).replace(" ", ""))
    h = list(normalize_text(hyp).replace(" ", ""))
    return _edits(r, h), len(r)


def summarize(values) -> dict:
    values = [float(v) for v in values]
    if not values:
        return {"n": 0}
    a = np.asarray(values)
    return {
        "n": len(values),
        "mean": float(a.mean()),
        "p50": float(np.percentile(a, 50)),
        "p95": float(np.percentile(a, 95)),
        "max": float(a.max()),
    }


# --- process memory -------------------------------------------------------------


def memory_mib() -> dict:
    """Current and peak resident memory of this process, in MiB.

    Linux adds the anonymous/file-backed split (RssAnon/RssFile), which is the
    closest host analogue of Android Private Dirty vs file-backed resident.
    Windows reports the working set and private bytes (commit charge).
    """
    if sys.platform.startswith("linux"):
        fields = {}
        with open("/proc/self/status", encoding="ascii") as f:
            for line in f:
                key, _, rest = line.partition(":")
                if key in ("VmRSS", "VmHWM", "RssAnon", "RssFile", "RssShmem"):
                    fields[key] = int(rest.split()[0]) / 1024.0
        return {
            "rss": fields["VmRSS"],
            "peak_rss": fields["VmHWM"],
            "rss_anon": fields.get("RssAnon"),
            "rss_file": fields.get("RssFile"),
        }
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes as w

        class Counters(ctypes.Structure):
            _fields_ = [("cb", w.DWORD), ("PageFaultCount", w.DWORD),
                        ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                        ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

        c = Counters()
        c.cb = ctypes.sizeof(c)
        psapi, kernel32 = ctypes.WinDLL("psapi"), ctypes.WinDLL("kernel32")
        kernel32.GetCurrentProcess.restype = w.HANDLE
        psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, ctypes.POINTER(Counters), w.DWORD]
        if not psapi.GetProcessMemoryInfo(kernel32.GetCurrentProcess(), ctypes.byref(c), c.cb):
            raise OSError("GetProcessMemoryInfo failed")
        mib = float(1 << 20)
        return {
            "rss": c.WorkingSetSize / mib,
            "peak_rss": c.PeakWorkingSetSize / mib,
            "private": c.PagefileUsage / mib,
            "peak_private": c.PeakPagefileUsage / mib,
        }
    import resource

    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0
    return {"rss": peak, "peak_rss": peak}


# --- ELF ------------------------------------------------------------------------


def elf_load_alignments(data: bytes) -> list:
    """p_align of every PT_LOAD segment (16 KB pages need >= 0x4000 on 64-bit)."""
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    end = "<" if data[5] == 1 else ">"
    if is64:
        phoff = struct.unpack_from(end + "Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
    else:
        phoff = struct.unpack_from(end + "I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type = struct.unpack_from(end + "I", data, off)[0]
        if p_type != 1:
            continue
        if is64:
            aligns.append(struct.unpack_from(end + "Q", data, off + 48)[0])
        else:
            aligns.append(struct.unpack_from(end + "I", data, off + 28)[0])
    return aligns
