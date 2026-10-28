"""List what a sherpa-onnx AAR ships: ABIs, native libraries (size, ELF LOAD
alignment for the 16 KB page rule), and the Kotlin/Java API classes.

    python -I tools/bench/aar_inspect.py --aar ../tooling/sherpa-onnx/sherpa-onnx-1.13.8.aar \
        --javap "C:/Program Files/Android/Android Studio/jbr/bin/javap.exe" --out aar.json
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchlib  # noqa: E402

API_PREFIX = "com/k2fsa/sherpa/onnx/"
# Classes later tasks call (T11 SherpaCtcEngine / SherpaKwsSpotter).
KEY_CLASSES = (
    "OfflineRecognizer", "OfflineRecognizerConfig", "OfflineModelConfig", "OfflineNemoEncDecCtcModelConfig",
    "OfflineStream", "OfflineRecognizerResult", "FeatureConfig", "KeywordSpotter", "KeywordSpotterConfig",
    "KeywordSpotterResult", "OnlineModelConfig", "OnlineTransducerModelConfig", "OnlineStream",
)


def inspect(aar: Path, javap: str | None) -> dict:
    out = {"aar": aar.name, "bytes": aar.stat().st_size, "sha256": benchlib.sha256_file(aar),
           "abis": {}, "classes": [], "javap": {}}
    with zipfile.ZipFile(aar) as z:
        for info in z.infolist():
            parts = PurePosixPath(info.filename).parts
            if len(parts) == 3 and parts[0] == "jni" and parts[2].endswith(".so"):
                data = z.read(info)
                aligns = benchlib.elf_load_alignments(data)
                out["abis"].setdefault(parts[1], []).append({
                    "lib": parts[2], "bytes": info.file_size, "load_align": [hex(a) for a in aligns],
                    "aligned_16k": all(a >= 0x4000 for a in aligns)})
        out["metadata"] = z.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode()
        out["manifest"] = z.read("AndroidManifest.xml").decode()
        classes_jar = z.read("classes.jar")
    with tempfile.TemporaryDirectory() as tmp:
        jar = Path(tmp) / "classes.jar"
        jar.write_bytes(classes_jar)
        with zipfile.ZipFile(jar) as cj:
            names = [n for n in cj.namelist() if n.endswith(".class")]
        out["classes"] = sorted(n[:-6].replace("/", ".") for n in names if n.startswith(API_PREFIX)
                                and "$" not in n)
        if javap:
            for cls in KEY_CLASSES:
                fq = API_PREFIX.replace("/", ".") + cls
                if fq not in out["classes"]:
                    out["javap"][cls] = None
                    continue
                res = subprocess.run([javap, "-public", "-cp", str(jar), fq], capture_output=True, text=True)
                out["javap"][cls] = [line for line in res.stdout.splitlines() if line.strip()]
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--aar", required=True, type=Path)
    ap.add_argument("--javap")
    ap.add_argument("--out", type=Path)
    args = ap.parse_args(argv)
    result = inspect(args.aar, args.javap)
    text = json.dumps(result, indent=1)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    for abi, libs in result["abis"].items():
        for lib in libs:
            print(f"{abi:12} {lib['lib']:28} {lib['bytes']:>10} align={','.join(lib['load_align'])}")
    print(f"{len(result['classes'])} API classes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
