"""Read the word list of a Vosk small model from its graph/Gr.fst symbol table.

Vosk drops grammar words that are missing from the model vocabulary (only a
log warning), so every command word must be checked against this list.

    python -I tools/bench/fstsyms.py ../tooling/vosk/vosk-model-small-te-0.42/graph/Gr.fst words-te.txt
"""
import struct
import sys


def _string(f) -> bytes:
    n = struct.unpack("<i", f.read(4))[0]
    return f.read(n)


def _symbol_table(f) -> list:
    f.read(4)  # magic
    _string(f)  # name
    _available, size = struct.unpack("<qq", f.read(16))
    words = []
    for _ in range(size):
        words.append(_string(f).decode("utf-8", "replace"))
        f.read(8)  # key
    return words


def read_words(fst_path) -> list:
    """Output (or input) symbols of an OpenFst binary FST with embedded symbol tables."""
    with open(fst_path, "rb") as f:
        f.read(4)  # magic
        _string(f)  # fst type
        _string(f)  # arc type
        _version, flags = struct.unpack("<ii", f.read(8))
        f.read(32)  # properties, start, num states, num arcs
        isyms = _symbol_table(f) if flags & 1 else None
        osyms = _symbol_table(f) if flags & 2 else None
    words = osyms or isyms
    if words is None:
        raise ValueError(f"{fst_path} has no embedded symbol table")
    return words


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    words = read_words(argv[0])
    with open(argv[1], "w", encoding="utf-8") as out:
        out.write("\n".join(words) + "\n")
    print(f"{len(words)} words")
    return 0


if __name__ == "__main__":
    sys.exit(main())
