"""Read literal tables out of Dart source text (test-only helper of gen.py).

The canonical grammar was extracted from the v0.2.0 Dart parser
(rule_intent_engine.dart, plan_interpreter.dart, command_transcript.dart).
test_gen.py uses this module to prove the YAML still equals those tables while
the Dart files exist; gen.py itself never reads Dart.

Only what the tables use is supported: string literals ('..', "..", r'..',
adjacent literals concatenated, escapes \\n \\t \\' \\" \\\\ \\$ \\xHH \\uHHHH
\\u{H..}), integers, lists, sets and maps of those, `SimpleEntry('X', [..])`
calls, and spreads (`...name`), which are reported, not resolved.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "v": "\v",
            "'": "'", '"': '"', "\\": "\\", "$": "$"}


@dataclass(frozen=True)
class Spread:
    """`...name` (or `...name.keys`, `...name['KEY']!`) inside a collection literal."""
    source: str


@dataclass(frozen=True)
class Ident:
    """A bare identifier (true, false, a constant name)."""
    name: str


@dataclass(frozen=True)
class Call:
    """`Name(arg, ...)`, e.g. SimpleEntry('STOP', ['stop'])."""
    name: str
    args: tuple


class _Reader:
    def __init__(self, text: str, pos: int = 0):
        self.text, self.pos = text, pos

    def skip(self) -> None:
        text = self.text
        while self.pos < len(text):
            c = text[self.pos]
            if c.isspace():
                self.pos += 1
            elif text.startswith("//", self.pos):
                end = text.find("\n", self.pos)
                self.pos = len(text) if end < 0 else end + 1
            elif text.startswith("/*", self.pos):
                self.pos = text.index("*/", self.pos) + 2
            else:
                return

    def peek(self) -> str:
        self.skip()
        return self.text[self.pos] if self.pos < len(self.text) else ""

    def expect(self, s: str) -> None:
        self.skip()
        if not self.text.startswith(s, self.pos):
            raise ValueError(f"expected {s!r} at {self.pos}: {self.text[self.pos:self.pos + 40]!r}")
        self.pos += len(s)

    def string_part(self) -> str:
        text = self.text
        raw = text[self.pos] == "r"
        if raw:
            self.pos += 1
        quote = text[self.pos]
        if text.startswith(quote * 3, self.pos):
            raise ValueError("triple-quoted strings are not supported")
        self.pos += 1
        out = []
        while True:
            c = text[self.pos]
            if c == quote:
                self.pos += 1
                return "".join(out)
            if c == "\\" and not raw:
                e = text[self.pos + 1]
                if e == "u" and text[self.pos + 2] == "{":
                    end = text.index("}", self.pos)
                    out.append(chr(int(text[self.pos + 3:end], 16)))
                    self.pos = end + 1
                elif e == "u":
                    out.append(chr(int(text[self.pos + 2:self.pos + 6], 16)))
                    self.pos += 6
                elif e == "x":
                    out.append(chr(int(text[self.pos + 2:self.pos + 4], 16)))
                    self.pos += 4
                else:
                    out.append(_ESCAPES.get(e, e))
                    self.pos += 2
                continue
            if c == "$" and not raw:
                raise ValueError(f"string interpolation at {self.pos} is not supported")
            out.append(c)
            self.pos += 1

    def at_string(self) -> bool:
        c = self.peek()
        return c in "'\"" or (c == "r" and self.text[self.pos + 1:self.pos + 2] in ("'", '"'))

    def value(self):
        c = self.peek()
        if self.at_string():
            parts = []
            while self.at_string():  # adjacent literals concatenate
                parts.append(self.string_part())
            return "".join(parts)
        if c == "[":
            return self.collection("[", "]")
        if c == "{":
            return self.collection("{", "}")
        if c.isdigit() or c == "-":
            m = re.compile(r"-?\d+(?:\.\d+)?").match(self.text, self.pos)
            self.pos = m.end()
            return float(m.group(0)) if "." in m.group(0) else int(m.group(0))
        if self.text.startswith("...", self.pos):
            self.pos += 3
            m = re.compile(r"[\w.]+(?:\[[^\]]*\])?!?").match(self.text, self.pos)
            self.pos = m.end()
            return Spread(m.group(0))
        if self.text.startswith("const ", self.pos):
            self.pos += len("const ")
            return self.value()
        m = re.compile(r"[A-Za-z_]\w*").match(self.text, self.pos)
        if m:
            self.pos = m.end()
            if self.peek() == "(":
                self.expect("(")
                args = []
                while self.peek() != ")":
                    arg = self.value()
                    if self.peek() == ":":  # named argument: keep its value only
                        self.expect(":")
                        arg = self.value()
                    args.append(arg)
                    if self.peek() == ",":
                        self.expect(",")
                self.expect(")")
                return Call(m.group(0), tuple(args))
            return Ident(m.group(0))
        raise ValueError(f"unsupported Dart at {self.pos}: {self.text[self.pos:self.pos + 40]!r}")

    def collection(self, open_: str, close: str):
        self.expect(open_)
        items, pairs = [], []
        while self.peek() != close:
            item = self.value()
            if self.peek() == ":":
                self.expect(":")
                pairs.append((item, self.value()))
            else:
                items.append(item)
            if self.peek() == ",":
                self.expect(",")
        self.expect(close)
        if pairs and items:
            raise ValueError("mixed map and set entries")
        if pairs:
            return dict(pairs)
        return items if open_ == "[" else set_like(items)


class set_like(list):  # noqa: N801 - a Dart set literal, order kept
    """A Dart set literal's elements, in source order."""


def declaration(source: str, name: str):
    """The literal value of the declaration `name = <literal>;` in `source`."""
    m = re.search(r"(?:^|[\s(])" + re.escape(name) + r"\s*=\s*", source)
    if not m:
        raise KeyError(name)
    return _Reader(source, m.end()).value()


def literal_strings(source: str, name: str) -> list:
    """Every string literal of the initializer of `name`, up to its `;`, in order.

    Works for any initializer (comprehensions, spreads, calls), because it only
    tokenises strings, comments and brackets.
    """
    m = re.search(r"(?:^|[\s(])" + re.escape(name) + r"\s*=\s*", source)
    if not m:
        raise KeyError(name)
    r = _Reader(source, m.end())
    out, depth = [], 0
    while True:
        r.skip()
        c = source[r.pos]
        if r.at_string():
            parts = []
            while r.at_string():
                parts.append(r.string_part())
            out.append("".join(parts))
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == ";" and depth == 0:
            return out
        r.pos += 1


def flat_strings(value) -> list:
    """Every string in `value` (nested lists/sets/maps/calls), in source order."""
    out = []
    if isinstance(value, str):
        out.append(value)
    elif isinstance(value, dict):
        for k, v in value.items():
            out += flat_strings(k) + flat_strings(v)
    elif isinstance(value, Call):
        for a in value.args:
            out += flat_strings(a)
    elif isinstance(value, (list, tuple)):
        for v in value:
            out += flat_strings(v)
    return out


def regex_source(source: str, name: str) -> str:
    """The pattern string of `name = RegExp(r'...' ...)`."""
    value = declaration(source, name)
    if not isinstance(value, Call) or value.name != "RegExp":
        raise ValueError(f"{name} is not a RegExp")
    return value.args[0]
