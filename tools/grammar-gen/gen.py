#!/usr/bin/env python3
"""Canonical grammar generator (architecture v2 spec 4.8; plan task T9).

Reads grammar/intents.v1.yaml and grammar/lexicon/<lang>.yaml and writes:

  app/android/app/src/main/kotlin/com/aeronex/app/grammar/GrammarTables.kt
  app/android/app/src/main/assets/grammar/vosk_<lang>.json       Vosk command grammar (phrase list)
  app/android/app/src/main/assets/grammar/stop_<lang>.json       Vosk stop-only grammar
  app/android/app/src/main/assets/grammar/ctc_lexicon_<lang>.json  CTC template-matching lexicon
  app/android/app/src/main/assets/grammar/grammar_manifest.json
  app/android/app/src/test/resources/grammar/fixtures_<lang>.json  CI fixtures

Usage (from anywhere; paths default to this repository):
  python tools/grammar-gen/gen.py                 regenerate (fails if a Vosk word is missing)
  python tools/grammar-gen/gen.py --check         drift gate: exit 1 if any output differs
  python tools/grammar-gen/gen.py --suggest-fillers te --count 300
                                                  frequent model words for vosk.fillers

The Vosk vocabulary check reads each model's graph/Gr.fst from --vosk-models
(default ../tooling/vosk next to this repository) or a word list given with
--vosk-words LANG=FILE. A missing model is skipped with a note unless
--require-vosk is given. Output is deterministic: sorted JSON keys, LF line
ends, no timestamps. Needs PyYAML (6.0.2) only.
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import re
import struct
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
GRAMMAR_DIR = ROOT / "grammar"
DEFAULT_VOSK_MODELS = ROOT.parent / "tooling" / "vosk"
KOTLIN_DIR = "app/android/app/src/main/kotlin/com/aeronex/app/grammar"
ASSETS_DIR = "app/android/app/src/main/assets/grammar"
FIXTURES_DIR = "app/android/app/src/test/resources/grammar"
# Files gen.py owns in each output directory; anything else there is left alone.
GENERATED = {
    KOTLIN_DIR: re.compile(r"GrammarTables\.kt"),
    ASSETS_DIR: re.compile(r"(?:vosk|stop|ctc_lexicon)_[a-z]+\.json|grammar_manifest\.json"),
    FIXTURES_DIR: re.compile(r"fixtures_[a-z]+\.json"),
}
UNK = "[unk]"


class GrammarError(Exception):
    """The grammar sources are invalid."""


# --- YAML -----------------------------------------------------------------------------------------

class _Loader(yaml.SafeLoader):
    """YAML 1.2 booleans: only true/false. Words such as no, yes, on and off stay strings."""


_Loader.yaml_implicit_resolvers = {
    key: [(tag, rx) for tag, rx in resolvers if tag != "tag:yaml.org,2002:bool"]
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
_Loader.add_implicit_resolver("tag:yaml.org,2002:bool", re.compile(r"^(?:true|false)$"), list("tf"))


def load_yaml(path):
    with open(path, encoding="utf-8") as f:
        return yaml.load(f, Loader=_Loader)  # noqa: S506 - SafeLoader subclass


# --- grammar model --------------------------------------------------------------------------------

@dataclass(frozen=True)
class Entry:
    """One table entry. `fragment` entries (suffixes, apostrophes) are never standalone words."""
    word: str
    provisional: bool
    path: str
    fragment: bool = False


@dataclass(frozen=True)
class Numeral:
    phrase: str
    value: float
    # RULE (v0.2.0 rule tables), EXTRA (native addition), LOAN, FRACTION (0.4.0: a decimal or half, read as its
    # value), REJECT (recognised, then refused: a scale word)
    kind: str
    provisional: bool


@dataclass(frozen=True)
class ContextFiller:
    word: str
    after: frozenset
    before: frozenset


@dataclass(frozen=True)
class Loanword:
    loan: str
    latin: str
    word_class: str
    native: str | None
    provisional: bool


WORDS, FRAGMENTS, INT_MAP, NUM_MAP, STR_MAP = "words", "fragments", "int_map", "num_map", "str_map"
SCHEMA = {
    "rules": {
        "move_verbs": WORDS, "directions": "directions", "units": WORDS, "unit_abbreviations": WORDS,
        "commands": "commands", "negators": WORDS, "negator_suffixes": FRAGMENTS, "minus_words": WORDS,
        "other_units": WORDS, "scale_words": WORDS, "ones": INT_MAP, "tens": INT_MAP, "numbers": INT_MAP,
        "tens_values": "int_list", "units19": INT_MAP,
    },
    "plan": {
        "connectives": WORDS, "first_words": WORDS, "courtesy": WORDS, "ordinals": WORDS, "motion_verbs": WORDS,
        "fractions": WORDS, "other_units": WORDS, "measure_words": WORDS, "counted_words": WORDS,
        "sequence_words": WORDS, "indic_filler": WORDS, "context_fillers": "context", "straight_words": WORDS,
        "more_words": WORDS, "hedge_words": WORDS, "correction_words": WORDS, "correction_phrases": "groups",
        "negation_words": WORDS, "altitude_words": WORDS, "filler": WORDS, "takeoff_words": WORDS,
        "simple_words": WORDS, "land_words": WORDS, "status_words": WORDS, "before_direction": WORDS,
        "before_direction_skip": WORDS, "right_here_followers": WORDS, "slash_units": WORDS, "wake": "groups",
        "light_verb": "light_verb",
    },
    "transcript": {
        "negators": WORDS, "fused_negators": FRAGMENTS, "negation_words": WORDS, "negation_apostrophes": FRAGMENTS,
        "numbers": INT_MAP, "number_preceders": WORDS, "spelling": STR_MAP, "hold_aliases": WORDS,
        "bare_takeoff": WORDS, "mishearings": STR_MAP, "foreign_scripts": "ranges",
    },
    "stop_lane": WORDS,
    "grammar_negators": WORDS,
    "numerals": {"extra": NUM_MAP, "fractions": NUM_MAP, "decimal_point": WORDS, "and_half": WORDS,
                 "loans": {"ones": NUM_MAP, "tens": NUM_MAP, "hundred": NUM_MAP, "fractions": NUM_MAP}},
    "natural": {"direction_verbs": "direction_groups", "comparatives": "direction_groups"},
    "loanwords": "loanwords",
    "templates": "templates",
    "vosk": {"exclude": WORDS, "filler_exclude": WORDS, "near_stop": WORDS, "fillers": WORDS, "loan_numerals": "bool",
             "negators": WORDS},
}
# Whole-word negations of a lexicon, in this table order (the CTC lexicon and the Vosk negation pairs read them).
NEGATION_TABLES = ("rules.negators", "transcript.negators", "transcript.negation_words", "plan.negation_words",
                   "grammar_negators")
# Template positions that carry an argument; every other position (a verb-like class or a literal word) is a
# command position, beside which a spoken negation may stand (README "Negation in the grammar").
ARGUMENT_CLASSES = ("{num}", "{dir}", "{side}", "{comparative}", "{unit}")
TOP_KEYS = {"language", "name", "script", "provisional"} | set(SCHEMA)
# Template classes and the v0.2.0 command each command class is read from.
COMMAND_CLASSES = {"takeoff": "TAKEOFF", "land": "LAND", "hold": "HOVER", "stop": "STOP"}
CLASSES = ("verb", "dir", "side", "dirverb", "comparative", "unit", "num", "takeoff", "land", "hold", "stop", "status")
SLOTS = ("num", "dir")
# 0.4.0 classes the rule parser reads (README "Natural phrasing"): `{side}` (the left and right forms of `{dir}`),
# `{dirverb}` and `{comparative}`. The CTC template matcher reads directions only from `{dir}`, so a CTC
# language's templates may not use them.
RULE_ONLY_CLASSES = ("{side}", "{dirverb}", "{comparative}")
# Tables that never reach a recognizer: parser-only spellings and the authoring lists.
NOT_RECOGNIZED = ("transcript.mishearings", "vosk.")


class Lexicon:
    """One language's tables, validated."""

    def __init__(self, data: dict, grammar: "Grammar", source: Path):
        self.source = source
        if not isinstance(data, dict):
            raise GrammarError(f"{source}: not a mapping")
        unknown = set(data) - TOP_KEYS
        if unknown:
            raise GrammarError(f"{source}: unknown keys {sorted(unknown)}")
        self.code = data.get("language")
        self.name = data.get("name") or self.code
        self.script = data.get("script") or {}
        for key in ("name", "first", "last", "digit_zero"):
            if key not in self.script:
                raise GrammarError(f"{source}: script.{key} is missing")
        self.provisional = bool(data.get("provisional", False))
        self.grammar = grammar
        self.tables: dict = {}  # path -> list[Entry]
        self.values: dict = {}  # path -> parsed value (maps, structures)
        self._pending_refs: list = []
        self._walk(data, SCHEMA, "", self.provisional)
        for path, ref, prov in self._pending_refs:
            self._resolve(path, ref)
        self._derive()

    # parsing ---------------------------------------------------------------------------------------

    def _err(self, path, message):
        return GrammarError(f"{self.source.name}: {path}: {message}")

    def _walk(self, data, schema, prefix, prov):
        for key, value in data.items():
            if prefix == "" and key in ("language", "name", "script", "provisional"):
                continue
            path = f"{prefix}{key}"
            if key == "provisional" and prefix:
                continue
            if key not in schema:
                raise self._err(path, f"unknown key {key!r}")
            kind = schema[key]
            if isinstance(kind, dict):
                if not isinstance(value, dict):
                    raise self._err(path, "expected a mapping")
                self._walk(value, kind, path + ".", self._prov(value, prov))
            else:
                self._parse(path, kind, value, prov)

    @staticmethod
    def _prov(value, default):
        if isinstance(value, dict) and "provisional" in value:
            return bool(value["provisional"])
        return default

    def _word(self, path, word):
        if not isinstance(word, str) or not word or word != word.strip() or "  " in word:
            raise self._err(path, f"bad word {word!r}")
        if unicodedata.normalize("NFC", word) != word:
            raise self._err(path, f"{word!r} is not Unicode NFC")
        return word

    def _words(self, path, value, prov, fragment=False):
        if isinstance(value, dict) and "same_as" in value:
            self._pending_refs.append((path, value["same_as"], prov))
            self.tables[path] = []
            return []
        if isinstance(value, dict):
            unknown = set(value) - {"provisional", "words", "note"}
            if unknown:
                raise self._err(path, f"unknown keys {sorted(unknown)}")
            prov = self._prov(value, prov)
            value = value.get("words") or []
        if not isinstance(value, list):
            raise self._err(path, "expected a list of words")
        entries, seen = [], set()
        for item in value:
            item_prov = prov
            if isinstance(item, dict):
                item_prov = bool(item.get("provisional", prov))
                item = item.get("w")
            word = self._word(path, item)
            if word in seen:
                raise self._err(path, f"duplicate {word!r}")
            seen.add(word)
            entries.append(Entry(word, item_prov, path, fragment))
        self.tables[path] = entries
        return entries

    def _map(self, path, value, prov, value_type):
        if isinstance(value, dict) and "values" in value:
            prov = self._prov(value, prov)
            value = value["values"]
        if not isinstance(value, dict):
            raise self._err(path, "expected a mapping")
        out = {}
        for key, v in value.items():
            word = self._word(path, key)
            if not isinstance(v, value_type) or isinstance(v, bool):
                raise self._err(path, f"{word!r}: expected {value_type}, got {v!r}")
            out[word] = v
        self.tables[path] = [Entry(w, prov, path) for w in out]
        self.values[path] = out
        return out

    def _parse(self, path, kind, value, prov):
        if kind == WORDS:
            self._words(path, value, prov)
        elif kind == FRAGMENTS:
            self._words(path, value, prov, fragment=True)
        elif kind == INT_MAP:
            self._map(path, value, prov, int)
        elif kind == NUM_MAP:
            self._map(path, value, self._prov(value, prov), (int, float))
        elif kind == STR_MAP:
            self._map(path, value, prov, str)
        elif kind == "bool":
            if not isinstance(value, bool):
                raise self._err(path, "expected true or false")
            self.values[path] = value
        elif kind == "int_list":
            if not isinstance(value, list) or not all(isinstance(v, int) for v in value):
                raise self._err(path, "expected a list of integers")
            self.values[path] = list(value)
        elif kind == "ranges":
            if not all(isinstance(r, list) and len(r) == 2 and all(isinstance(x, int) for x in r) for r in value):
                raise self._err(path, "expected [[first, last], ...]")
            self.values[path] = [tuple(r) for r in value]
        elif kind == "directions":
            if list(value) != self.grammar.directions:
                raise self._err(path, f"keys must be {self.grammar.directions} in that order")
            self.values[path] = {d: [e.word for e in self._words(f"{path}.{d}", forms, prov)]
                                 for d, forms in value.items()}
        elif kind == "direction_groups":
            unknown = [d for d in value if d not in self.grammar.directions]
            if unknown:
                raise self._err(path, f"{unknown} are not directions ({self.grammar.directions})")
            self.values[path] = {d: [e.word for e in self._words(f"{path}.{d}", forms, prov)]
                                 for d, forms in value.items()}
        elif kind == "commands":
            commands = [c.get("command") for c in value]
            if sorted(commands) != sorted(self.grammar.rule_commands):
                raise self._err(path, f"commands must be {sorted(self.grammar.rule_commands)}")
            self.values[path] = [(c["command"], [e.word for e in self._words(f"{path}.{c['command']}", c["phrases"], prov)])
                                 for c in value]
        elif kind == "groups":
            self.values[path] = {g: [e.word for e in self._words(f"{path}.{g}", ws, prov)] for g, ws in value.items()}
        elif kind == "light_verb":
            if set(value) != {"stems", "auxiliaries", "replacement"}:
                raise self._err(path, "needs stems, auxiliaries and replacement")
            self.values[path] = {
                "stems": [e.word for e in self._words(path + ".stems", value["stems"], prov)],
                "auxiliaries": [e.word for e in self._words(path + ".auxiliaries", value["auxiliaries"], prov)],
                "replacement": self._word(path, value["replacement"]),
            }
            self.tables[path + ".replacement"] = [Entry(value["replacement"], prov, path)]
        elif kind == "context":
            fillers = []
            for i, item in enumerate(value):
                if set(item) - {"word", "after", "before"}:
                    raise self._err(path, f"unknown keys in {item}")
                sub = f"{path}.{i}"
                self.tables[sub] = [Entry(self._word(path, item["word"]), prov, path)]
                fillers.append((item["word"], f"{sub}.after", f"{sub}.before"))
                self._words(f"{sub}.after", item.get("after", []), prov)
                self._words(f"{sub}.before", item.get("before", []), prov)
            self.values[path] = fillers
        elif kind == "loanwords":
            prov = self._prov(value, prov)
            loans = []
            for item in value.get("words", []):
                if set(item) != {"loan", "latin", "class", "native"}:
                    raise self._err(path, f"a loanword needs loan, latin, class and native: {item}")
                if item["class"] not in CLASSES or item["class"] == "num":
                    raise self._err(path, f"{item['loan']!r}: unknown class {item['class']!r}")
                loans.append(Loanword(self._word(path, item["loan"]), item["latin"], item["class"], item["native"], prov))
            if len({x.loan for x in loans}) != len(loans):
                raise self._err(path, "duplicate loanword")
            self.values[path] = loans
            self.tables[path] = [Entry(x.loan, x.provisional, path) for x in loans]
        elif kind == "templates":
            parsed = []
            for intent, templates in value.items():
                spec = self.grammar.intents.get(intent)
                if spec is None or spec.get("enabled", True) is False:
                    raise self._err(path, f"{intent!r} is not an enabled intent")
                for template in templates:
                    parsed.append((intent, template))
            self.values[path] = parsed
        else:  # pragma: no cover - schema typo
            raise AssertionError(kind)

    def _resolve(self, path, ref):
        if ref.startswith("rules.directions."):
            direction = ref.rsplit(".", 1)[1]
            source = self.tables.get(f"rules.directions.{direction}")
        else:
            source = self.tables.get(ref)
        if source is None:
            raise self._err(path, f"same_as {ref!r} names no table")
        self.tables[path] = [Entry(e.word, e.provisional, path, e.fragment) for e in source]

    # derived data ----------------------------------------------------------------------------------

    def table(self, path) -> list:
        """The words of one table (paths like 'rules.move_verbs', 'vosk.fillers'), in order."""
        return [e.word for e in self.tables.get(path, [])]

    def value(self, path, default=None):
        return self.values.get(path, default)

    def entries(self) -> list:
        return [e for entries in self.tables.values() for e in entries]

    def is_provisional(self, word) -> bool:
        return any(e.provisional for e in self.entries() if e.word == word)

    def commands(self) -> list:
        return self.value("rules.commands", [])

    def directions(self) -> dict:
        return self.value("rules.directions", {})

    def loanwords(self) -> list:
        return self.value("loanwords", [])

    def word_tokens(self) -> set:
        """Every token of every recognizable entry (no fragments, no authoring lists)."""
        return {t for e in self.entries() if not e.fragment and not e.path.startswith(NOT_RECOGNIZED)
                for t in e.word.split()}

    def _derive(self):
        rule_forms = {w for forms in self.directions().values() for w in forms}
        rule_forms |= {p for _, phrases in self.commands() for p in phrases}
        rule_forms |= set(self.table("rules.move_verbs")) | set(self.table("rules.units"))
        for loan in self.loanwords():
            if loan.loan in rule_forms:
                raise self._err("loanwords", f"{loan.loan!r} is already a rule-table form; drop the loan entry")
            if loan.native is not None:
                if loan.native not in self._class_rule_forms(loan.word_class):
                    raise self._err("loanwords", f"{loan.loan!r}: native {loan.native!r} is not a {loan.word_class} form")
            elif loan.word_class != "status":
                raise self._err("loanwords", f"{loan.loan!r}: only status loans may have native: null")
        self._check_natural()
        self.numerals = _numerals(self)
        self.classes = self._classes()
        self.templates = self._templates()

    def _check_natural(self):
        """README "Natural phrasing": a direction verb or comparative names one direction, is no move verb or
        command phrase, and is no form of another direction ("ahead" may be a forward direction verb)."""
        verbs = set(self.table("rules.move_verbs")) | {p for _, phrases in self.commands() for p in phrases}
        for table in ("natural.direction_verbs", "natural.comparatives"):
            seen = {}
            for direction, phrases in self.value(table, {}).items():
                for phrase in phrases:
                    if phrase in verbs:
                        raise self._err(f"{table}.{direction}", f"{phrase!r} is a move verb or command phrase")
                    if seen.setdefault(phrase, direction) != direction:
                        raise self._err(table, f"{phrase!r} is in two directions ({seen[phrase]}, {direction})")
                    for other, forms in self.directions().items():
                        if other != direction and phrase in forms:
                            raise self._err(f"{table}.{direction}", f"{phrase!r} is a {other} direction form")

    def _class_rule_forms(self, name) -> list:
        if name == "verb":
            return self.table("rules.move_verbs")
        if name == "dir":
            return [w for forms in self.directions().values() for w in forms]
        if name == "side":
            return [w for d in ("left", "right") for w in self.directions().get(d, [])]
        if name == "dirverb":
            return [w for forms in self.value("natural.direction_verbs", {}).values() for w in forms]
        if name == "comparative":
            return [w for forms in self.value("natural.comparatives", {}).values() for w in forms]
        if name == "unit":
            return self.table("rules.units")
        if name == "status":
            return self.table("plan.status_words")
        if name in COMMAND_CLASSES:
            return [p for c, phrases in self.commands() if c == COMMAND_CLASSES[name] for p in phrases]
        raise KeyError(name)

    def _classes(self) -> dict:
        classes = {}
        for name in CLASSES:
            if name == "num":
                forms = [n.phrase for n in self.numerals]
            else:
                forms = self._class_rule_forms(name) + [x.loan for x in self.loanwords() if x.word_class == name]
            classes[name] = list(dict.fromkeys(forms))
        return classes

    def direction_of(self, form) -> str:
        for direction, forms in self.directions().items():
            if form in forms:
                return direction
        for loan in self.loanwords():
            if loan.loan == form and loan.word_class == "dir":
                return self.direction_of(loan.native)
        for table in ("natural.direction_verbs", "natural.comparatives"):
            for direction, forms in self.value(table, {}).items():
                if form in forms:
                    return direction
        raise KeyError(form)

    def _templates(self) -> list:
        """(intent, tokens) with optional tokens expanded; tokens are words or '{class}'."""
        known = self.word_tokens()
        out = []
        for intent, template in self.value("templates", []):
            tokens = template.split()
            for tok in tokens:
                inner = tok[1:-1] if tok.startswith("[") and tok.endswith("]") else tok
                if inner.startswith("{"):
                    name = inner[1:-1]
                    if name not in self.classes:
                        raise self._err("templates", f"{template!r}: unknown class {name!r}")
                    if not self.classes[name]:
                        raise self._err("templates", f"{template!r}: class {name!r} has no forms")
                elif inner not in known:
                    raise self._err("templates", f'{template!r}: "{inner}" is not a word of this lexicon')
            optional = [i for i, t in enumerate(tokens) if t.startswith("[")]
            for keep in itertools.product([True, False], repeat=len(optional)):
                kept = dict(zip(optional, keep))
                expanded = [t.strip("[]") for i, t in enumerate(tokens) if kept.get(i, True)]
                if (intent, expanded) not in out:
                    out.append((intent, expanded))
        return out

    def position_forms(self, token) -> list:
        return self.classes[token[1:-1]] if token.startswith("{") else [token]


def _numerals(lex: Lexicon) -> list:
    """Numeral phrases 0-100, halves, spoken decimals and loans, in a fixed order (see README "Numerals")."""
    reject = set(lex.table("rules.scale_words")) | set(lex.table("plan.fractions"))
    prov = {e.word: e.provisional for e in lex.entries()}
    out, values = [], {}

    def add(phrase, value, kind, provisional):
        if kind in ("RULE", "EXTRA") and any(w in reject for w in phrase.split()):
            kind = "REJECT"
        if phrase in values:
            if values[phrase] != value:
                raise lex._err("numerals", f"{phrase!r} is both {values[phrase]} and {value}")
            return
        values[phrase] = value
        out.append(Numeral(phrase, value, kind, provisional))

    ones, tens, numbers = (lex.value(f"rules.{k}", {}) for k in ("ones", "tens", "numbers"))
    base = {**ones, **tens, **numbers}
    for word, value in base.items():
        add(word, value, "RULE", prov[word])
    tens_words = dict(tens) if tens else {w: v for w, v in numbers.items() if v in lex.value("rules.tens_values", [])}
    unit_words = {w: v for w, v in (ones or lex.value("rules.units19", {})).items() if 1 <= v <= 9}
    for (t, tv), (u, uv) in itertools.product(tens_words.items(), unit_words.items()):
        add(f"{t} {u}", tv + uv, "RULE", prov[t] or prov[u])
    for phrase, value in lex.value("numerals.extra", {}).items():
        add(phrase, value, "EXTRA", prov[phrase])
    for phrase, value in lex.value("numerals.fractions", {}).items():
        add(phrase, value, "FRACTION", prov[phrase])
    _spoken_decimals(lex, out, add, prov)
    loan_ones = lex.value("numerals.loans.ones", {})
    loan_tens = lex.value("numerals.loans.tens", {})
    for phrase, value in {**loan_ones, **loan_tens, **lex.value("numerals.loans.hundred", {})}.items():
        add(phrase, value, "LOAN", prov[phrase])
    for (t, tv), (u, uv) in itertools.product(loan_tens.items(), loan_ones.items()):
        if 1 <= uv <= 9:
            add(f"{t} {u}", tv + uv, "LOAN", True)
    for phrase, value in lex.value("numerals.loans.fractions", {}).items():
        add(phrase, value, "FRACTION", prov[phrase])
    return out


def _spoken_decimals(lex, numerals, add, prov):
    """0.4.0 (README "Spoken numbers"): "<n> point <d>", "point <d>" and "<n> and a half" for every n below
    intents.v1.yaml spoken_numbers.below and every digit d 1-9, from numerals.decimal_point and numerals.and_half.
    The Kotlin reader (SpokenNumbers) composes the same forms without the bound."""
    below = lex.grammar.spoken_below
    whole = [n for n in numerals if n.kind in ("RULE", "EXTRA") and n.value == int(n.value)]
    integers = [n for n in whole if n.value < below]
    digits = [n for n in whole if 1 <= n.value <= 9 and " " not in n.phrase]
    for point in lex.table("numerals.decimal_point"):
        for n, d in itertools.product(integers, digits):
            add(f"{n.phrase} {point} {d.phrase}", round(n.value + d.value / 10, 1), "FRACTION",
                prov[point] or n.provisional or d.provisional)
        for d in digits:
            add(f"{point} {d.phrase}", round(d.value / 10, 1), "FRACTION", prov[point] or d.provisional)
    for half in lex.table("numerals.and_half"):
        for n in integers:
            if 1 <= n.value and n.value + 0.5 < below:
                add(f"{n.phrase} {half}", n.value + 0.5, "FRACTION", prov[half] or n.provisional)


def numerals(lex: Lexicon) -> list:
    return lex.numerals


class Grammar:
    def __init__(self, grammar_dir: Path):
        grammar_dir = Path(grammar_dir)
        self.dir = grammar_dir
        intents_path = grammar_dir / "intents.v1.yaml"
        if not intents_path.exists():
            raise GrammarError(f"{intents_path} not found")
        data = load_yaml(intents_path)
        self.data = data
        self.version = data["version"]
        self.languages = list(data["languages"])
        self.rule_languages = list(data["rule_languages"])
        self.script_tie_order = list(data.get("script_tie_order", []))
        self.rule_commands = dict(data["rule_commands"])
        self.directions = list(data["directions"])
        self.numeral_range = (data["numerals"]["min"], data["numerals"]["max"])
        self.halves = list(data["halves"])
        self.spoken_below = data.get("spoken_numbers", {}).get("below", 10)
        self.filler_range = (data["fillers"]["min"], data["fillers"]["max"])
        self.vosk = dict(data["engines"]["vosk"])
        self.vosk_languages = [lang for lang in self.languages if lang in self.vosk]
        self.ctc_languages = [lang for lang in self.languages if lang in data["engines"]["ctc"]]
        self.kws = data["kws"]
        self.intents = data["intents"]
        files = sorted(p.stem for p in (grammar_dir / "lexicon").glob("*.yaml"))
        if files != sorted(self.languages):
            raise GrammarError(f"lexicon files {files} do not match languages {self.languages}")
        self.lexicons = {}
        for lang in self.languages:
            path = grammar_dir / "lexicon" / f"{lang}.yaml"
            lex = Lexicon(load_yaml(path), self, path)
            if lex.code != lang:
                raise GrammarError(f"{path.name}: language is {lex.code!r}, expected {lang!r}")
            self.lexicons[lang] = lex
        self._validate()

    def _validate(self):
        low, high = self.numeral_range
        for lang, lex in self.lexicons.items():
            native = {n.value for n in lex.numerals if n.kind != "LOAN"}
            missing = [v for v in range(low, high + 1) if v not in native]
            if missing:
                raise GrammarError(f"{lang}: no numeral for {missing} (spec 7.4 needs {low}-{high})")
            halves = {n.value for n in lex.numerals if n.kind == "FRACTION"}
            if not set(self.halves) <= halves:
                raise GrammarError(f"{lang}: missing halves {sorted(set(self.halves) - halves)}")
            if lang in self.ctc_languages:
                for intent, tokens in lex.templates:
                    used = [t for t in tokens if t in RULE_ONLY_CLASSES]
                    if used:
                        raise GrammarError(f"{lang}: template {intent} {' '.join(tokens)!r} uses {used[0]}, which the "
                                           f"CTC template matcher cannot read (README \"Natural phrasing\")")
            stops = {p for c, phrases in lex.commands() if c == "STOP" for p in phrases}
            stops |= {x.loan for x in lex.loanwords() if x.word_class == "stop"}
            for phrase in lex.table("stop_lane"):
                if phrase not in stops:
                    raise GrammarError(f"{lang}: stop_lane {phrase!r} is not a STOP phrase")
            if not lex.table("stop_lane"):
                raise GrammarError(f"{lang}: stop_lane is empty")
            for d, forms in lex.directions().items():
                for other, other_forms in lex.directions().items():
                    if other != d and set(forms) & set(other_forms):
                        raise GrammarError(f"{lang}: {sorted(set(forms) & set(other_forms))} in two directions")
            for phrase in lex.table("vosk.negators"):
                if phrase not in negation_phrases(lex):
                    raise GrammarError(f"{lang}: vosk.negators {phrase!r} is not a negation of this lexicon "
                                       f"({', '.join(NEGATION_TABLES)})")
                if not _vosk_usable(lex, phrase):
                    raise GrammarError(f"{lang}: vosk.negators {phrase!r} is excluded from the Vosk grammar")
        for model, lines in self.kws.get("models", {}).items():
            for keyword in self.kws["keywords"]:
                if keyword not in lines:
                    raise GrammarError(f"kws model {model} has no token line for {keyword!r}")

    def source_files(self) -> list:
        return [self.dir / "intents.v1.yaml"] + [self.dir / "lexicon" / f"{lang}.yaml" for lang in self.languages]

    def source_sha256(self) -> str:
        h = hashlib.sha256()
        for path in self.source_files():
            h.update(path.relative_to(self.dir).as_posix().encode() + b"\0")
            h.update(path.read_bytes().replace(b"\r\n", b"\n") + b"\0")
        return h.hexdigest()


def load_grammar(grammar_dir=GRAMMAR_DIR) -> Grammar:
    return Grammar(Path(grammar_dir))


# --- Vosk phrase lists ----------------------------------------------------------------------------

def vosk_eligible(lex: Lexicon, phrase: str) -> bool:
    """Whether a Vosk model of this script can emit every word (en: a-z and '; others: the script block)."""
    if lex.script["name"] == "latin":
        return all(re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w) for w in phrase.split())
    first, last = lex.script["first"], lex.script["last"]
    return all(first <= ord(c) <= last for c in phrase.replace(" ", ""))


def _vosk_excluded(lex) -> set:
    """Words no Vosk grammar of this language may hold: vosk.exclude, and the loan numerals when off."""
    excluded = set(lex.table("vosk.exclude"))
    if lex.value("vosk.loan_numerals", True) is False:
        excluded |= {w for n in lex.numerals if n.kind == "LOAN" for w in n.phrase.split()}
    return excluded


def _vosk_usable(lex, phrase, excluded=None) -> bool:
    excluded = _vosk_excluded(lex) if excluded is None else excluded
    return vosk_eligible(lex, phrase) and not any(w in excluded for w in phrase.split())


def _check_vosk_numerals(lex):
    """Spec 4.8: the grammar holds every number of the range, so none is forced onto another.

    The native numerals must cover the whole range, and loan numerals are all in or all out: a partial loan
    set lets an unknown loan ("thirteen") decode as a known one ("three").
    """
    excluded = _vosk_excluded(lex)
    low, high = lex.grammar.numeral_range
    usable = {n.value for n in lex.numerals if n.kind != "LOAN" and _vosk_usable(lex, n.phrase, excluded)}
    missing = [v for v in range(low, high + 1) if v not in usable]
    if missing:
        raise GrammarError(f"{lex.code}: the Vosk grammar has no numeral for {missing}; every value "
                           f"{low}-{high} needs a native form the model can emit")
    loans = [n for n in lex.numerals if n.kind == "LOAN"]
    dropped = [n.phrase for n in loans if not _vosk_usable(lex, n.phrase, excluded)]
    if dropped and len(dropped) != len(loans):
        raise GrammarError(f"{lex.code}: loan numerals {dropped} are not in the Vosk grammar but other loan "
                           f"numerals are; set vosk.loan_numerals: false (all or none)")


def _check_vosk_coverage(lex):
    """Every direction and every template class keeps a form the model can emit (README "Running the generator").

    `vosk.exclude` may drop a form only while another form of its slot remains; otherwise a spoken "forward" would
    be forced onto another word.
    """
    excluded = _vosk_excluded(lex)
    for direction in lex.grammar.directions:
        if not any(lex.direction_of(f) == direction and _vosk_usable(lex, f, excluded) for f in lex.classes["dir"]):
            raise GrammarError(f"{lex.code}: the Vosk grammar has no form for direction {direction!r}; keep at least "
                               f"one of {lex.directions()[direction]} out of vosk.exclude")
    used = {t[1:-1] for _intent, tokens in lex.templates for t in tokens if t.startswith("{")}
    for name in CLASSES:
        if name in used and name not in SLOTS and not any(_vosk_usable(lex, f, excluded) for f in lex.classes[name]):
            raise GrammarError(f"{lex.code}: the Vosk grammar has no form for class {{{name}}}; keep at least one of "
                               f"{lex.classes[name]} out of vosk.exclude")


def _pairs(words: list) -> set:
    return {f"{a} {b}" for a, b in zip(words, words[1:])}


def _vosk_lexicon_phrases(lex) -> set:
    """Every recognizable lexicon word as a single-word phrase (multi-word entries as their word pairs)."""
    out = set()
    for e in lex.entries():
        if e.fragment or e.path.startswith(NOT_RECOGNIZED) or not _vosk_usable(lex, e.word):
            continue
        words = e.word.split()
        out |= _pairs(words) if len(words) > 1 else {e.word}
    return out


def vosk_template_phrases(lex) -> set:
    out = set()
    for intent, tokens in lex.templates:
        positions = []
        for tok in tokens:
            forms = [f for f in lex.position_forms(tok) if _vosk_usable(lex, f)]
            if not forms:
                raise GrammarError(f"{lex.code}: template {intent} {' '.join(tokens)!r}: no Vosk form for {tok}")
            positions.append(forms)
        for forms in positions:
            for form in forms:
                words = form.split()
                out |= _pairs(words) if len(words) > 1 else ({form} if len(positions) == 1 else set())
        for left, right in zip(positions, positions[1:]):
            out |= {f"{a.split()[-1]} {b.split()[0]}" for a in left for b in right}
    return out


def negation_phrases(lex) -> list:
    """Every whole-word negation of the lexicon (NEGATION_TABLES), in table order."""
    return list(dict.fromkeys(w for path in NEGATION_TABLES for w in lex.table(path)))


def vosk_negators(lex) -> list:
    """The negations a Vosk grammar places beside command words: vosk.negators, else every usable negation."""
    return lex.table("vosk.negators") or [n for n in negation_phrases(lex) if _vosk_usable(lex, n)]


def vosk_negation_phrases(lex) -> set:
    """Spec 4.8: every negator right before and right after each command position of every template.

    Single-word phrases alone let the bigram LM reach "don't take off" only through backoff, and W1 saw Vosk drop
    the negation. With these pairs a negated command costs the grammar no more than the plain one, so the audio
    decides and the parser refuses it (README "Negation in the grammar").
    """
    out = set()
    for _intent, tokens in lex.templates:
        positions = [[f for f in lex.position_forms(t) if _vosk_usable(lex, f)] for t in tokens]
        for i, tok in enumerate(tokens):
            if tok in ARGUMENT_CLASSES:
                continue
            for negator in vosk_negators(lex):
                first, last = negator.split()[0], negator.split()[-1]
                out |= {f"{last} {f.split()[0]}" for f in positions[i]}  # negator, command
                out |= {f"{f.split()[-1]} {first}" for f in positions[i]}  # command, negator
                if i:
                    out |= {f"{f.split()[-1]} {first}" for f in positions[i - 1]}  # word before, negator
    return out


def vosk_connective_phrases(lex) -> set:
    """Plans of several commands (v0.2.0): each template's last word before each connective's first word, and each
    connective's last word before each template's first word (README "Plans")."""
    connectives = [c for c in lex.table("plan.connectives") if _vosk_usable(lex, c)]
    lasts, firsts = set(), set()
    for _intent, tokens in lex.templates:
        lasts |= {f.split()[-1] for f in lex.position_forms(tokens[-1]) if _vosk_usable(lex, f)}
        firsts |= {f.split()[0] for f in lex.position_forms(tokens[0]) if _vosk_usable(lex, f)}
    return ({f"{w} {c.split()[0]}" for w in lasts for c in connectives}
            | {f"{c.split()[-1]} {w}" for c in connectives for w in firsts})


def _stem(word) -> str:
    """A prefix that also covers the word's inflections (stop: stops, stopped; रुको: रुके; వెళ్ళు: వెళ్ళకు)."""
    stripped = word.rstrip("".join(c for c in word if unicodedata.category(c).startswith("M")))
    stem = stripped if stripped != word else (word[:-1] if len(word) >= 4 else word)
    return stem or word


def _stems(phrases) -> list:
    return [_stem(t) for t in sorted({t for p in phrases for t in p.split()})]


def near_stop(lex, word) -> bool:
    """An acoustic neighbour of a stop-lane word: listed in vosk.near_stop, or an inflection of a stop-lane word.

    Such a word is never a filler and never a stop-grammar decoy, so it cannot absorb a spoken STOP (spec 4.12: the
    spotter is lenient; README "Stop lane and keyword spotting").
    """
    lane = {t for p in lex.table("stop_lane") for t in p.split()}
    if word in lane:
        return False
    return word in lex.table("vosk.near_stop") or any(word.startswith(s) for s in _stems(lane))


def _fillers(lex) -> list:
    fillers = lex.table("vosk.fillers")
    low, high = lex.grammar.filler_range
    if not low <= len(fillers) <= high:
        raise GrammarError(f"{lex.code}: vosk.fillers has {len(fillers)} words, needs {low}-{high} (spec 4.6)")
    clash = set(fillers) & lex.word_tokens()
    if clash:
        raise GrammarError(f"{lex.code}: fillers are lexicon words: {sorted(clash)}")
    for word in fillers:
        if " " in word or not vosk_eligible(lex, word):
            raise GrammarError(f"{lex.code}: filler {word!r} is not a single word of the model's script")
    close = [w for w in fillers if near_stop(lex, w)]
    if close:
        raise GrammarError(f"{lex.code}: fillers {close} sound like a stop-lane word (vosk.near_stop); a filler "
                           f"must never absorb a spoken STOP")
    return fillers


def vosk_phrase_parts(lex) -> dict:
    """The parts of a Vosk command grammar by name (vosk_eval.py compares grammars without one part)."""
    _check_vosk_numerals(lex)
    _check_vosk_coverage(lex)
    return {
        "templates": vosk_template_phrases(lex),
        "negation": vosk_negation_phrases(lex),
        "connectives": vosk_connective_phrases(lex),
        "lexicon": _vosk_lexicon_phrases(lex),
        "fillers": set(_fillers(lex)) | {UNK},
    }


def vosk_phrases(lex, without=()) -> list:
    return sorted(set().union(*(v for k, v in vosk_phrase_parts(lex).items() if k not in without)))


def stop_phrases(lex) -> list:
    """The stop-only grammar: the stop lane, every lexicon word and filler as a decoy except near-stop words, [unk]."""
    lane = {p for p in lex.table("stop_lane") if _vosk_usable(lex, p)}
    if not lane:
        raise GrammarError(f"{lex.code}: no stop_lane phrase is usable by Vosk")
    decoys = {p for p in _vosk_lexicon_phrases(lex) | set(_fillers(lex))
              if not any(near_stop(lex, w) for w in p.split())}
    return sorted(lane | decoys | {UNK})


def _word_origins(lex) -> dict:
    """word -> (label, table) for vocabulary error messages; command words win."""
    origins = {}
    for name, forms in lex.classes.items():
        for form in forms:
            for w in form.split():
                origins.setdefault(w, ("command word", f"class {{{name}}}"))
    for w in lex.table("stop_lane"):
        for t in w.split():
            origins.setdefault(t, ("command word", "stop_lane"))
    for intent, tokens in lex.templates:
        for t in tokens:
            if not t.startswith("{"):
                origins.setdefault(t, ("command word", f"templates.{intent}"))
    for e in lex.entries():
        for w in e.word.split():
            label = "filler" if e.path == "vosk.fillers" else "lexicon word"
            origins.setdefault(w, (label, e.path))
    return origins


def vocabulary_errors(grammar: Grammar, vocab: dict, sources: dict | None = None) -> list:
    """Messages for every Vosk grammar word missing from its model vocabulary (vocab: lang -> set)."""
    errors = []
    for lang, words in vocab.items():
        lex = grammar.lexicons[lang]
        model = grammar.vosk[lang]["model"]
        where = (sources or {}).get(lang, model)
        used = {w for p in vosk_phrases(lex) + stop_phrases(lex) if p != UNK for w in p.split()}
        origins = _word_origins(lex)
        for word in sorted(used - words):
            label, table = origins.get(word, ("word", "?"))
            errors.append(
                f'vosk {lang} ({model}): {label} "{word}" ({table}) is not in the model vocabulary {where}; '
                f"Vosk would drop it from the grammar. Fix grammar/lexicon/{lang}.yaml: correct the spelling, "
                f"or list it under vosk.exclude if another form of that slot covers it.")
    return errors


# --- CTC lexicon ----------------------------------------------------------------------------------

def ctc_templates(lex) -> list:
    """Templates whose tokens are single words, `{num}`/`{dir}` slots or single-word `{class}`es.

    A class form of several words becomes its own template with the words spelled out.
    """
    out = []
    for intent, tokens in lex.templates:
        options = []
        for tok in tokens:
            if tok in ("{num}", "{dir}"):
                options.append([[tok]])
            elif tok.startswith("{"):
                forms = lex.classes[tok[1:-1]]
                alts = [[tok]] if any(" " not in f for f in forms) else []
                alts += [f.split() for f in forms if " " in f]
                options.append(alts)
            else:
                options.append([[tok]])
        for combo in itertools.product(*options):
            flat = [t for part in combo for t in part]
            if {"intent": intent, "tokens": flat} not in out:
                out.append({"intent": intent, "tokens": flat})
    return out


def ctc_lexicon(lex) -> dict:
    templates = ctc_templates(lex)
    used = {t[1:-1] for tpl in templates for t in tpl["tokens"] if t.startswith("{")} - set(SLOTS)
    negation = negation_phrases(lex)
    suffixes = list(dict.fromkeys(lex.table("rules.negator_suffixes") + lex.table("transcript.fused_negators")))
    return {
        "connectives": lex.table("plan.connectives"),
        "fixed": {name: [f for f in lex.classes[name] if " " not in f] for name in sorted(used)},
        "language": lex.code,
        "negation": {"suffixes": suffixes, "words": negation},
        "provisional": lex.provisional,
        "provisional_words": sorted({e.word for e in lex.entries() if e.provisional and not e.path.startswith("vosk.")}),
        "reject_numbers": {n.phrase: n.value for n in lex.numerals if n.kind == "REJECT"},
        "slots": {
            "dir": {f: lex.direction_of(f) for f in lex.classes["dir"]},
            "num": {n.phrase: n.value for n in lex.numerals if n.kind != "REJECT"},
        },
        "stop_words": lex.table("stop_lane"),
        "templates": templates,
        "version": lex.grammar.version,
    }


# --- fixtures -------------------------------------------------------------------------------------

def _numeral_for(lex, value) -> str:
    for kind in ("RULE", "EXTRA"):
        for n in lex.numerals:
            if n.value == value and n.kind == kind:
                return n.phrase
    raise GrammarError(f"{lex.code}: no numeral for {value}")


def _instances(lex, intent, tokens, use_loans=False):
    """Fixture cases of one template as (case, form per position): each form of the intent's own class, first
    forms elsewhere."""
    loans = {x.loan for x in lex.loanwords()}
    numeral = _numeral_for(lex, 3 if intent == "takeoff" else 2)
    results = []
    own = {"takeoff": "{takeoff}", "land": "{land}", "hold": "{hold}", "stop": "{stop}", "status": "{status}",
           "move": "{dir}"}[intent]
    if own not in tokens and intent == "move":  # 0.4.0: "to the {side}", "{dirverb}", "{comparative}"
        own = next(t for t in ("{side}", "{dirverb}", "{comparative}") if t in tokens)

    def forms(tok):
        if tok == "{num}":
            return [numeral]
        options = [f for f in lex.position_forms(tok) if (f in loans) == use_loans or not tok.startswith("{")]
        if tok == own or not options:
            return options
        return options[:1]

    position_options = []
    for tok in tokens:
        options = forms(tok)
        if use_loans and tok.startswith("{") and tok != "{num}":
            options = options or [f for f in lex.position_forms(tok) if f not in loans][:1]
        position_options.append(options)
    if any(not o for o in position_options):
        return []
    for combo in itertools.product(*position_options):
        if use_loans and not any(w in loans for w in combo):
            continue
        text = " ".join(combo)
        if intent == "move":
            direction = lex.direction_of(combo[tokens.index(own)])
            expect = {"tool": "move_relative", "arguments": {"direction": direction, "distance_m": 2}}
        elif intent == "takeoff":
            expect = {"tool": "takeoff", "arguments": {"altitude_m": 3}}
        else:
            expect = {"tool": lex.grammar.intents[intent]["tool"], "arguments": {}}
        results.append(({"intent": intent, "text": text, **expect}, combo))
    return results


def _negated(lex, firsts) -> list:
    """Negated commands the parser must refuse (README "Negation in the grammar").

    Latin script: each of the first three negators before the first command position ("do not land"). Indic
    scripts: the first two negators after the last command position ("ల్యాండ్ వద్దు"), the first one before it
    ("लैंड मत करो"), and the last command word replaced by the first two of its negative forms in
    grammar_negators ("ముందుకు వెళ్ళకు").
    """
    negators = vosk_negators(lex) if lex.code in lex.grammar.vosk else negation_phrases(lex)
    out = []
    for intent in ("takeoff", "move", "land", "hold"):
        if intent not in firsts:
            continue
        tokens, combo = firsts[intent]
        commands = [i for i, t in enumerate(tokens) if t not in ARGUMENT_CLASSES]

        def insert(at, negator):
            return " ".join([*combo[:at], negator, *combo[at:]])

        if lex.script["name"] == "latin":
            texts = [insert(commands[0], n) for n in negators[:3]]
        else:
            last = commands[-1]
            texts = [insert(last + 1, n) for n in negators[:2]] + [insert(last, negators[0])]
            fused = [n for n in lex.table("grammar_negators") if n.startswith(_stem(combo[last]))]
            texts += [" ".join([*combo[:last], n, *combo[last + 1:]]) for n in fused[:2]]
        for text in texts:
            if {"text": text} not in out:
                out.append({"text": text})
    return out


def fixtures(lex) -> dict:
    positive, loans, firsts = [], [], {}
    for intent, tokens in lex.templates:
        for case, combo in _instances(lex, intent, tokens):
            if case not in positive:
                positive.append(case)
                firsts.setdefault(intent, (tokens, combo))
        if lex.loanwords():
            for case, _combo in _instances(lex, intent, tokens, use_loans=True):
                if case not in loans:
                    loans.append(case)
    negated = _negated(lex, firsts)
    hold = next(c for c in positive if c["intent"] == "hold")
    stop = next(c for c in positive if c["intent"] == "stop")
    move = next(c for c in positive if c["intent"] == "move")
    land = next(c for c in positive if c["intent"] == "land")
    plans = [{"text": f"{move['text']} {c} {land['text']}",
              "steps": [{"tool": s["tool"], "arguments": s["arguments"]} for s in (move, land)]}
             for c in lex.table("plan.connectives")]
    words = move["text"].split()
    unk = [
        {"text": f"{UNK} {hold['text']}", "expect": "hold"},
        {"text": f"{stop['text']} {UNK}", "expect": "hold"},
        {"text": f"{UNK} {move['text']}", "expect": "reject"},
        {"text": " ".join(words[:1] + [UNK] + words[1:]), "expect": "reject"},
    ]
    out = {
        "language": lex.code,
        "loans": loans,
        "negated": negated,
        "numbers": [{"kind": n.kind, "phrase": n.phrase, "value": n.value} for n in lex.numerals],
        "plans": plans,
        "positive": positive,
        "unk": unk,
    }
    if lex.table("vosk.fillers"):
        f = lex.table("vosk.fillers")
        out["out_of_grammar"] = [{"text": " ".join(f[i:i + 3])} for i in (0, 3, 6, 9)]
    return out


# --- Kotlin tables --------------------------------------------------------------------------------

def _union(grammar, langs, getter, ordered=False):
    items = [w for lang in langs for w in getter(grammar.lexicons[lang])]
    return list(dict.fromkeys(items)) if ordered else set(items)


def kotlin_tables(grammar: Grammar) -> dict:
    """Every table GrammarTables.kt holds, by Kotlin name, as Python values (see README "Kotlin")."""
    t = {}
    allv, rule = grammar.languages, grammar.rule_languages
    lex = grammar.lexicons
    for code, lx in lex.items():
        p = f"Lexicon.{code}."
        t[p + "moveVerbs"] = lx.table("rules.move_verbs")
        t[p + "directions"] = lx.directions()
        t[p + "units"] = lx.table("rules.units")
        t[p + "unitAbbreviations"] = lx.table("rules.unit_abbreviations")
        t[p + "commands"] = [(c, list(ph)) for c, ph in lx.commands()]
        t[p + "negators"] = lx.table("rules.negators")
        t[p + "negatorSuffixes"] = lx.table("rules.negator_suffixes")
        t[p + "minusWords"] = lx.table("rules.minus_words")
        t[p + "otherUnits"] = lx.table("rules.other_units")
        t[p + "scaleWords"] = lx.table("rules.scale_words")
        t[p + "ones"] = dict(lx.value("rules.ones", {}))
        t[p + "tens"] = dict(lx.value("rules.tens", {}))
        t[p + "numbers"] = dict(lx.value("rules.numbers", {}))
        t[p + "tensValues"] = list(lx.value("rules.tens_values", []))
        t[p + "units19"] = dict(lx.value("rules.units19", {}))
        t[p + "grammarNegators"] = lx.table("grammar_negators")
        t[p + "stopLane"] = lx.table("stop_lane")
        t[p + "kwsKeywords"] = list(grammar.kws["keywords"])
        t[p + "numerals"] = lx.numerals
        t[p + "decimalPoints"] = lx.table("numerals.decimal_point")
        t[p + "andHalf"] = lx.table("numerals.and_half")
        t[p + "directionVerbs"] = lx.value("natural.direction_verbs", {})
        t[p + "comparatives"] = lx.value("natural.comparatives", {})
        t[p + "loanwords"] = lx.loanwords()
        t[p + "templates"] = [(i, list(tokens)) for i, tokens in lx.templates]
        t[p + "classes"] = {k: list(v) for k, v in lx.classes.items()}
        t[p + "provisionalWords"] = sorted({e.word for e in lx.entries() if e.provisional
                                            and not e.path.startswith("vosk.")})

    def plan(name, langs=allv, ordered=False):
        return _union(grammar, langs, lambda lx: lx.table(f"plan.{name}"), ordered)

    def rules(name, langs=rule):
        return _union(grammar, langs, lambda lx: lx.table(f"rules.{name}"))

    def words_of(phrases):
        return {w for p in phrases for w in p.split(" ")}

    t["Plan.SCALE_WORDS"] = rules("scale_words") | plan("fractions", rule)
    t["Plan.NUMERAL_WORDS"] = {c: set(lex[c].value("rules.ones", {})) | set(lex[c].value("rules.tens", {}))
                               | set(lex[c].value("rules.numbers", {})) for c in rule}
    t["Plan.NUMBER_WORDS"] = set().union(*t["Plan.NUMERAL_WORDS"].values())
    t["Plan.CONNECTIVES"] = plan("connectives", ordered=True)
    t["Plan.FIRST_WORDS"] = plan("first_words", ordered=True)
    for key in ("courtesy", "ordinals", "motion_verbs", "measure_words", "counted_words", "sequence_words",
                "indic_filler", "straight_words", "more_words", "hedge_words", "correction_words", "negation_words",
                "altitude_words", "filler", "status_words", "before_direction", "before_direction_skip",
                "right_here_followers"):
        t["Plan." + key.upper()] = plan(key)
    t["Plan.SLASH_UNITS"] = plan("slash_units", ordered=True)
    t["Plan.MINUS_WORDS"] = rules("minus_words")
    t["Plan.OTHER_UNITS"] = rules("other_units") | plan("other_units")
    t["Plan.CONTEXT_FILLERS"] = [
        ContextFiller(word, frozenset(lex[c].table(after)), frozenset(lex[c].table(before)))
        for c in allv for word, after, before in lex[c].value("plan.context_fillers", [])]
    light = [lex[c].value("plan.light_verb") for c in allv if lex[c].value("plan.light_verb")]
    t["Plan.LIGHT_VERB_STEMS"] = [w for lv in light for w in lv["stems"]]
    t["Plan.LIGHT_VERB_AUXILIARIES"] = [w for lv in light for w in lv["auxiliaries"]]
    t["Plan.LIGHT_VERB_REPLACEMENT"] = light[0]["replacement"] if light else ""
    corrections = [lex[c].value("plan.correction_phrases", {}) for c in allv]
    t["Plan.CORRECTION_PHRASES_ANYWHERE"] = [w for g in corrections for w in g.get("anywhere", [])]
    t["Plan.CORRECTION_PHRASES_LEADING"] = [w for g in corrections for w in g.get("leading", [])]
    wake = [lex[c].value("plan.wake", {}) for c in allv]
    t["Plan.WAKE_GREETINGS"] = [w for g in wake for w in g.get("greetings", [])]
    t["Plan.WAKE_NAMES"] = [w for g in wake for w in g.get("names", [])]
    takeoff, simple = set(), set()
    for c in rule:
        for command, phrases in lex[c].commands():
            (takeoff if command == "TAKEOFF" else simple).update(words_of(phrases))
    t["Plan.TAKEOFF_WORDS"] = plan("takeoff_words", rule) | takeoff
    t["Plan.SIMPLE_WORDS"] = plan("simple_words", rule) | simple
    t["Plan.LAND_WORDS"] = t["Plan.SIMPLE_WORDS"] | plan("land_words", rule)
    t["Plan.UNITS"] = rules("units") | rules("unit_abbreviations")
    t["Plan.DIRECTION_WORDS"] = {c: {f for forms in lex[c].directions().values() for f in forms} for c in rule}
    t["Plan.FORWARD_WORDS"] = {f for c in rule for f in lex[c].directions()["forward"]}
    latin = [c for c in rule if lex[c].script["name"] == "latin"]
    indic = [c for c in rule if lex[c].script["name"] != "latin"]
    t["Plan.ENGLISH_MOVE_WORDS"] = {w for c in latin for w in lex[c].table("rules.move_verbs")} | {
        w for c in latin for w in t["Plan.DIRECTION_WORDS"][c] | t["Plan.NUMERAL_WORDS"][c]}
    t["Plan.INDIC_MOVE_WORDS"] = words_of(
        [w for c in indic for w in lex[c].table("rules.move_verbs") + list(lex[c].value("rules.numbers", {}))]
        + [f for c in indic for f in t["Plan.DIRECTION_WORDS"][c]])
    t["Plan.SCRIPT_TIE_ORDER"] = list(grammar.script_tie_order)
    t["Transcript.NEGATORS"] = _union(grammar, allv, lambda lx: lx.table("transcript.negators"))
    t["Transcript.FUSED_NEGATORS"] = _union(grammar, allv, lambda lx: lx.table("transcript.fused_negators"), True)
    t["Transcript.NEGATION_WORDS"] = _union(grammar, allv, lambda lx: lx.table("transcript.negation_words"), True)
    t["Transcript.NEGATION_APOSTROPHES"] = _union(
        grammar, allv, lambda lx: lx.table("transcript.negation_apostrophes"), True)
    t["Transcript.NUMBERS"] = {w: v for c in allv for w, v in lex[c].value("transcript.numbers", {}).items()}
    t["Transcript.NUMBER_PRECEDERS"] = _union(grammar, allv, lambda lx: lx.table("transcript.number_preceders"), True)
    t["Transcript.SPELLING"] = {w: v for c in allv for w, v in lex[c].value("transcript.spelling", {}).items()}
    t["Transcript.HOLD_ALIASES"] = _union(grammar, allv, lambda lx: lx.table("transcript.hold_aliases"), True)
    t["Transcript.BARE_TAKEOFF"] = _union(grammar, allv, lambda lx: lx.table("transcript.bare_takeoff"), True)
    t["Transcript.MISHEARINGS"] = {w: v for c in allv for w, v in lex[c].value("transcript.mishearings", {}).items()}
    t["Transcript.FOREIGN_SCRIPTS"] = {c: lex[c].value("transcript.foreign_scripts") for c in allv
                                       if lex[c].value("transcript.foreign_scripts")}
    return t


def kotlin_string(s: str) -> str:
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif o > 0xFFFF:
            raise GrammarError(f"{s!r}: characters outside the BMP are not supported")
        elif o < 0x20 or unicodedata.category(ch) in ("Cc", "Cf", "Zl", "Zp", "Co", "Cs"):
            out.append(f"\\u{o:04X}")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def _num(v) -> str:
    return repr(float(v))


def _call(name, parts, indent, width=120):
    one = f"{name}({', '.join(parts)})"
    if len(one) + indent <= width:
        return one
    pad = " " * (indent + 4)
    lines, line = [], ""
    for part in parts:
        piece = part + ","
        if line and len(pad) + len(line) + 1 + len(piece) > width:
            lines.append(pad + line)
            line = piece
        else:
            line = f"{line} {piece}" if line else piece
    if line:
        lines.append(pad + line)
    return f"{name}(\n" + "\n".join(lines) + "\n" + " " * indent + ")"


def _strs(items):
    return [kotlin_string(x) for x in items]


def _k_list(items, indent):
    return _call("listOf", _strs(items), indent) if items else "emptyList()"


def _k_set(items, indent):
    return _call("setOf", _strs(sorted(items) if isinstance(items, (set, frozenset)) else items), indent) \
        if items else "emptySet()"


def _k_map(pairs, indent):
    return _call("mapOf", [f"{k} to {v}" for k, v in pairs], indent) if pairs else "emptyMap()"


def _render_lexicon(grammar, code, t) -> str:
    lx = grammar.lexicons[code]
    p = f"Lexicon.{code}."
    ind = 8

    def lst(name):
        return _k_list(t[p + name], ind)

    def int_map(name):
        return _k_map([(kotlin_string(k), str(v)) for k, v in t[p + name].items()], ind)

    fields = [
        ("code", kotlin_string(code)),
        ("name", kotlin_string(lx.name)),
        ("provisional", "true" if lx.provisional else "false"),
        ("ruleVocabulary", "true" if code in grammar.rule_languages else "false"),
        ("scriptFirst", f"0x{lx.script['first']:04X}"),
        ("scriptLast", f"0x{lx.script['last']:04X}"),
        ("digitZero", f"0x{lx.script['digit_zero']:04X}"),
        ("moveVerbs", lst("moveVerbs")),
        ("directions", _k_map([(kotlin_string(d), _k_list(forms, ind + 4)) for d, forms in t[p + "directions"].items()],
                              ind)),
        ("units", lst("units")),
        ("unitAbbreviations", lst("unitAbbreviations")),
        ("commands", _call("listOf", [f"CommandPhrases({kotlin_string(c)}, {_k_list(ph, ind + 4)})"
                                      for c, ph in t[p + "commands"]], ind)),
        ("negators", lst("negators")),
        ("negatorSuffixes", lst("negatorSuffixes")),
        ("minusWords", lst("minusWords")),
        ("otherUnits", lst("otherUnits")),
        ("scaleWords", lst("scaleWords")),
        ("ones", int_map("ones")),
        ("tens", int_map("tens")),
        ("numbers", int_map("numbers")),
        ("tensValues", _call("setOf", [str(v) for v in t[p + "tensValues"]], ind) if t[p + "tensValues"]
         else "emptySet()"),
        ("units19", int_map("units19")),
        ("grammarNegators", lst("grammarNegators")),
        ("stopLane", lst("stopLane")),
        ("kwsKeywords", lst("kwsKeywords")),
        ("numerals", _call("listOf", [f"Numeral({kotlin_string(n.phrase)}, {_num(n.value)}, NumeralKind.{n.kind})"
                                      for n in t[p + "numerals"]], ind)),
        ("decimalPoints", lst("decimalPoints")),
        ("andHalf", lst("andHalf")),
        ("directionVerbs", _k_map([(kotlin_string(d), _k_list(forms, ind + 4))
                                   for d, forms in t[p + "directionVerbs"].items()], ind)),
        ("comparatives", _k_map([(kotlin_string(d), _k_list(forms, ind + 4))
                                 for d, forms in t[p + "comparatives"].items()], ind)),
        ("loanwords", _call("listOf", [
            f"Loanword({kotlin_string(x.loan)}, {kotlin_string(x.latin)}, {kotlin_string(x.word_class)}, "
            f"{kotlin_string(x.native) if x.native is not None else 'null'})" for x in t[p + "loanwords"]], ind)
         if t[p + "loanwords"] else "emptyList()"),
        ("templates", _call("listOf", [f"Template({kotlin_string(i)}, {_k_list(tokens, ind + 4)})"
                                       for i, tokens in t[p + "templates"]], ind)),
        ("classes", _k_map([(kotlin_string(k), _k_list(v, ind + 4)) for k, v in t[p + "classes"].items()], ind)),
        ("provisionalWords", _k_set(t[p + "provisionalWords"], ind)),
    ]
    body = ",\n".join(f"        {name} = {value}" for name, value in fields)
    return f"private object Lexicon{code.capitalize()} {{\n    val value = Lexicon(\n{body},\n    )\n}}\n"


def render_kotlin(grammar: Grammar) -> str:
    t = kotlin_tables(grammar)
    ind = 8

    def sset(name):
        return _k_set(t[name], ind)

    def slist(name):
        return _k_list(t[name], ind)

    def lang_sets(name):
        return _k_map([(kotlin_string(c), _k_set(v, ind + 4)) for c, v in t[name].items()], ind)

    def str_map(name):
        return _k_map([(kotlin_string(k), kotlin_string(v)) for k, v in t[name].items()], ind)

    plan = [
        ("SCALE_WORDS", "Set<String>", sset, "Scale, decimal and fraction words: refused unless the 0.4.0 "
         "spoken-number reader reads them first (`_scaleWords`)."),
        ("NUMERAL_WORDS", "Map<String, Set<String>>", lang_sets, "Numeral words per rule language (`_numeralWords`)."),
        ("NUMBER_WORDS", "Set<String>", sset, "All rule languages' numeral words (`_numberWords`)."),
        ("CONNECTIVES", "List<String>", slist, "Clause connectives, in match order (`CommandSplitter._connectives`)."),
        ("FIRST_WORDS", "List<String>", slist, "Clause-initial \"first\" (`CommandSplitter._firstWord`)."),
        ("COURTESY", "Set<String>", sset, "Courtesy-only clauses are dropped (`_courtesy`)."),
        ("ORDINALS", "Set<String>", sset, "Dropped only before another clause (`_ordinals`)."),
        ("MOTION_VERBS", "Set<String>", sset, "A lone motion-verb clause joins the next one (`_motionVerbs`)."),
        ("WAKE_GREETINGS", "List<String>", slist, "\"hey\" of the wake phrase \"hey drone\" (`_wakePhrase`)."),
        ("WAKE_NAMES", "List<String>", slist, "\"drone\" of the wake phrase; dropped when it opens a clause."),
        ("SLASH_UNITS", "List<String>", slist, "No clause split at \"/\" before these (`_separator`)."),
        ("MINUS_WORDS", "Set<String>", sset, "Spoken minus signs, rule languages (`StepParser._minusWords`)."),
        ("OTHER_UNITS", "Set<String>", sset, "Non-metre units (`StepParser._otherUnits`)."),
        ("MEASURE_WORDS", "Set<String>", sset, "Durations, angles, rates (`_measureWords`)."),
        ("COUNTED_WORDS", "Set<String>", sset, "Paces after a number (`_countedWords`)."),
        ("SEQUENCE_WORDS", "Set<String>", sset, "Counts, conditions, order (`_sequenceWords`)."),
        ("INDIC_FILLER", "Set<String>", sset, "Hindi/Telugu words a step may keep (`_indicFiller`)."),
        ("CONTEXT_FILLERS", "List<ContextFiller>", None, "Fillers only beside given words (`_isIndicFiller`)."),
        ("STRAIGHT_WORDS", "Set<String>", sset, "\"Straight\" beside a forward direction (`_straightWords`)."),
        ("FORWARD_WORDS", "Set<String>", sset, "Forward forms of the rule languages (`_forwardWords`)."),
        ("MORE_WORDS", "Set<String>", sset, "\"More\" beside a bare distance (`_moreWords`)."),
        ("HEDGE_WORDS", "Set<String>", sset, "\"Or\"/approximations beside a bare distance (`_hedgeWords`)."),
        ("LIGHT_VERB_STEMS", "List<String>", slist, "\"कर\"/\"करवा\" of the light verb (`_lightVerb`)."),
        ("LIGHT_VERB_AUXILIARIES", "List<String>", slist, "The auxiliary after a light-verb stem (`_lightVerb`)."),
        ("LIGHT_VERB_REPLACEMENT", "String", lambda n: kotlin_string(t[n]), "What a light verb is read as."),
        ("CORRECTION_WORDS", "Set<String>", sset, "Spoken self-corrections (`_correctionWords`)."),
        ("CORRECTION_PHRASES_ANYWHERE", "List<String>", slist, "\"i mean(t)\" anywhere (`_correctionPhrase`)."),
        ("CORRECTION_PHRASES_LEADING", "List<String>", slist, "\"make it/that\" opening a clause."),
        ("NEGATION_WORDS", "Set<String>", sset, "Plan-level negation (`StepParser._negationWords`)."),
        ("ALTITUDE_WORDS", "Set<String>", sset, "Words naming an altitude (`_altitudeWords`)."),
        ("FILLER", "Set<String>", sset, "Latin words any step may carry (`_filler`)."),
        ("TAKEOFF_WORDS", "Set<String>", sset, "Words a takeoff may carry (`_takeoffWords`)."),
        ("SIMPLE_WORDS", "Set<String>", sset, "Words hold may carry (`_simpleWords`)."),
        ("LAND_WORDS", "Set<String>", sset, "Words land may carry (`_landWords`)."),
        ("UNITS", "Set<String>", sset, "Metre words of the rule languages and \"m\" (`_units`)."),
        ("ENGLISH_MOVE_WORDS", "Set<String>", sset, "English words a move reads (`_englishMoveWords`)."),
        ("INDIC_MOVE_WORDS", "Set<String>", sset, "Hindi/Telugu words a move reads (`_indicMoveWords`)."),
        ("DIRECTION_WORDS", "Map<String, Set<String>>", lang_sets, "Direction forms per rule language."),
        ("STATUS_WORDS", "Set<String>", sset, "A clause with one of these asks for status (`_asksStatus`)."),
        ("BEFORE_DIRECTION", "Set<String>", sset, "Prepositions before a direction (`_beforeDirection`)."),
        ("BEFORE_DIRECTION_SKIP", "Set<String>", sset, "Skipped between such a preposition and the direction."),
        ("RIGHT_HERE_FOLLOWERS", "Set<String>", sset, "\"right\" before these is \"right here\" (`_rejectUnknownWords`)."),
        ("SCRIPT_TIE_ORDER", "List<String>", slist, "Script vote tie-break order (`_scriptLanguage`)."),
    ]
    transcript = [
        ("NEGATORS", "Set<String>", sset, "Whole-word negators (`CommandTranscript._negators`)."),
        ("FUSED_NEGATORS", "List<String>", slist, "Negation matched anywhere, fused onto a verb."),
        ("NEGATION_WORDS", "List<String>", slist, "English negation words (whole words)."),
        ("NEGATION_APOSTROPHES", "List<String>", slist, "Apostrophes of \"<word>n't\"."),
        ("NUMBERS", "Map<String, Int>", lambda n: _k_map([(kotlin_string(k), str(v)) for k, v in t[n].items()], ind),
         "\"one\"..\"ten\" become digits before \"meter(s)\"."),
        ("NUMBER_PRECEDERS", "List<String>", slist, "...only after the start or one of these words."),
        ("SPELLING", "Map<String, String>", str_map, "Whole-word respellings."),
        ("HOLD_ALIASES", "List<String>", slist, "A transcript of exactly one of these becomes \"hold\"."),
        ("BARE_TAKEOFF", "List<String>", slist, "A transcript of exactly one of these needs a height."),
        ("MISHEARINGS", "Map<String, String>", str_map, "Known mishearings and the suggested correction."),
        ("FOREIGN_SCRIPTS", "Map<String, List<IntRange>>", lambda n: _k_map(
            [(kotlin_string(c), _call("listOf", [f"0x{a:04X}..0x{b:04X}" for a, b in r], ind + 4))
             for c, r in t[n].items()], ind), "Scripts that send a transcript of that language back for review."),
    ]

    def block(items, prefix):
        out = []
        for name, kt, render, doc in items:
            if name == "CONTEXT_FILLERS":
                value = _call("listOf", [
                    f"ContextFiller({kotlin_string(c.word)}, {_k_set(c.after, ind + 4)}, {_k_set(c.before, ind + 4)})"
                    for c in t[prefix + name]], ind) if t[prefix + name] else "emptyList()"
            else:
                value = render(prefix + name)
            out.append(f"        /** {doc} */\n        val {name}: {kt} = {value}\n")
        return "\n".join(out)

    langs = grammar.languages
    header = f"""// GENERATED by tools/grammar-gen/gen.py from grammar/intents.v1.yaml and grammar/lexicon/*.yaml.
// Do not edit: change the YAML, then run `python tools/grammar-gen/gen.py`. `--check` is the drift gate.
// grammar/README.md documents every table. Source SHA-256: {grammar.source_sha256()}
@file:Suppress("SpellCheckingInspection", "MaxLineLength")

package com.aeronex.app.grammar

/** A v0.2.0 non-move command (STOP, TAKEOFF, LAND, HOVER) and its spoken phrases, in match order. */
data class CommandPhrases(val command: String, val phrases: List<String>)

/** A word that is a filler only after one of [after] or before one of [before] (hi "से" in "धीरे से"). */
data class ContextFiller(val word: String, val after: Set<String>, val before: Set<String>)

/**
 * How the grammar treats a numeral. RULE: in the v0.2.0 rule tables (the golden-parity parser reads it).
 * EXTRA: a native form added in v2 (zero). LOAN: an English number word in this script. FRACTION (0.4.0): a
 * spoken decimal or half, read as its value ("two point five", "two and a half", डेढ़, అర). REJECT: recognised,
 * then refused ("say a plain number"): phrases with a scale word ("one hundred").
 */
enum class NumeralKind {{ RULE, EXTRA, LOAN, FRACTION, REJECT }}

/** A spoken number of the recognizer grammars (spec 7.4). Numbers are slots: matched exactly, never fuzzily. */
data class Numeral(val phrase: String, val value: Double, val kind: NumeralKind)

/** An English command word in this script ([latin]), its template class and the native form it stands for. */
data class Loanword(val loan: String, val latin: String, val wordClass: String, val native: String?)

/** One word order of an intent. Tokens are words or `{{class}}` references (`{{num}}` and `{{dir}}` are slots). */
data class Template(val intent: String, val tokens: List<String>)

/**
 * One language's tables. The rule tables (moveVerbs ... units19) are the v0.2.0 tables verbatim, in source
 * order; for ta, kn and ml they are the provisional edge/app/intent.py reference ([ruleVocabulary] false).
 */
class Lexicon(
    val code: String,
    val name: String,
    /** The whole lexicon awaits native-speaker review (ta, kn, ml). */
    val provisional: Boolean,
    /** The v0.2.0 parser read this vocabulary; the golden vectors depend on exactly these tables. */
    val ruleVocabulary: Boolean,
    val scriptFirst: Int,
    val scriptLast: Int,
    val digitZero: Int,
    val moveVerbs: List<String>,
    /** Canonical direction (forward, backward, left, right, up, down) -> spoken forms, in match order. */
    val directions: Map<String, List<String>>,
    val units: List<String>,
    val unitAbbreviations: List<String>,
    val commands: List<CommandPhrases>,
    val negators: List<String>,
    val negatorSuffixes: List<String>,
    val minusWords: List<String>,
    val otherUnits: List<String>,
    val scaleWords: List<String>,
    /** en: 0-19 (`enOnes`). */
    val ones: Map<String, Int>,
    /** en: the tens (`enTens`). */
    val tens: Map<String, Int>,
    /** Single-word numerals (`teNumbers`, `hiNumbers`, ...). */
    val numbers: Map<String, Int>,
    /** Values of [numbers] that combine with a following [units19] word. */
    val tensValues: Set<Int>,
    val units19: Map<String, Int>,
    /**
     * Negations the recognizer grammars hold beyond [negators] (en "won't", "do not"; te negative imperatives such
     * as "వెళ్ళకు"). The parser already refuses each one: it holds a [negators] word, ends with a
     * [negatorSuffixes] entry, or is a word no rule table reads (no rule reading: rejected, as every v0.2.0
     * `ai_required` clause). Never add these to the transcript negators: that would change golden error messages
     * (grammar/README.md "Negation in the grammar").
     */
    val grammarNegators: List<String>,
    /** Voice STOP lane triggers (spec 7.5, 4.12). Never hold or hover words. */
    val stopLane: List<String>,
    /** Keyword-spotter keywords: "stop" only (W1 decision, grammar/README.md). */
    val kwsKeywords: List<String>,
    val numerals: List<Numeral>,
    /** 0.4.0 decimal-point words ("point": "two point five" = 2.5; grammar/README.md "Spoken numbers"). */
    val decimalPoints: List<String>,
    /** 0.4.0 "and a half" forms ("two and a half" = 2.5). */
    val andHalf: List<String>,
    /**
     * 0.4.0 verbs that carry a direction, by canonical direction ("climb" = up): read as a move in that direction in
     * a clause with a number and no move verb (grammar/README.md "Natural phrasing").
     */
    val directionVerbs: Map<String, List<String>>,
    /** 0.4.0 comparatives read as their direction after a move verb ("go higher" = up). */
    val comparatives: Map<String, List<String>>,
    val loanwords: List<Loanword>,
    val templates: List<Template>,
    /** Template word classes with their forms (loanwords included); num and dir are slots. */
    val classes: Map<String, List<String>>,
    /** Words with at least one entry awaiting native-speaker review. */
    val provisionalWords: Set<String>,
)

/** The canonical grammar v{grammar.version} tables. */
object GrammarTables {{
    const val VERSION: Int = {grammar.version}
    const val SOURCE_SHA256: String = "{grammar.source_sha256()}"

    val LANGUAGES: List<String> = {_k_list(langs, 4)}
    /** The v0.2.0 parser's vocabularies: the Kotlin parser reads exactly these for golden parity. */
    val RULE_LANGUAGES: List<String> = {_k_list(grammar.rule_languages, 4)}
    val VOSK_LANGUAGES: List<String> = {_k_list(grammar.vosk_languages, 4)}
    val CTC_LANGUAGES: List<String> = {_k_list(grammar.ctc_languages, 4)}
    val DIRECTIONS: List<String> = {_k_list(grammar.directions, 4)}
    /** v0.2.0 rule command id -> intent. */
    val RULE_COMMANDS: Map<String, String> = {_k_map([(kotlin_string(k), kotlin_string(v))
                                                       for k, v in grammar.rule_commands.items()], 4)}
    val KWS_KEYWORDS: List<String> = {_k_list(grammar.kws["keywords"], 4)}

    val lexicons: Map<String, Lexicon> = {_k_map([(kotlin_string(c), f"Lexicon{c.capitalize()}.value")
                                                   for c in langs], 4)}

    fun lexicon(code: String): Lexicon = lexicons[code] ?: throw IllegalArgumentException("No lexicon for $code")

    /** plan_interpreter.dart tables (CommandSplitter, StepParser), joined over the languages in order. */
    object Plan {{
{block(plan, "Plan.")}    }}

    /** command_transcript.dart tables (CommandTranscript.inspect). */
    object Transcript {{
{block(transcript, "Transcript.")}    }}
}}
"""
    lexicons = "\n".join(_render_lexicon(grammar, c, t) for c in langs)
    return header + "\n" + lexicons


# --- outputs --------------------------------------------------------------------------------------

def _json(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=1) + "\n").encode("utf-8")


def _phrase_list(phrases) -> bytes:
    return ("[\n" + ",\n".join(json.dumps(p, ensure_ascii=False) for p in phrases) + "\n]\n").encode("utf-8")


def build_outputs(grammar: Grammar) -> dict:
    """Repository-relative path -> bytes of every generated file."""
    out = {}
    languages = {}
    for lang in grammar.languages:
        lex = grammar.lexicons[lang]
        info = {
            "ctc": None,
            "kws_keywords": list(grammar.kws["keywords"]),
            "name": lex.name,
            "provisional": lex.provisional,
            "rule_vocabulary": lang in grammar.rule_languages,
            "stop_lane": lex.table("stop_lane"),
            "vosk": None,
        }
        if lang in grammar.vosk_languages:
            vosk, stop = vosk_phrases(lex), stop_phrases(lex)
            out[f"{ASSETS_DIR}/vosk_{lang}.json"] = _phrase_list(vosk)
            out[f"{ASSETS_DIR}/stop_{lang}.json"] = _phrase_list(stop)
            info["vosk"] = {
                "fillers": len(lex.table("vosk.fillers")),
                "grammar": f"grammar/vosk_{lang}.json",
                "grammar_sha256": hashlib.sha256(out[f"{ASSETS_DIR}/vosk_{lang}.json"]).hexdigest(),
                "model": grammar.vosk[lang]["model"],
                "phrases": len(vosk),
                "stop_grammar": f"grammar/stop_{lang}.json",
                "stop_grammar_sha256": hashlib.sha256(out[f"{ASSETS_DIR}/stop_{lang}.json"]).hexdigest(),
                "stop_phrases": len(stop),
            }
        if lang in grammar.ctc_languages:
            out[f"{ASSETS_DIR}/ctc_lexicon_{lang}.json"] = _json(ctc_lexicon(lex))
            info["ctc"] = {
                "lexicon": f"grammar/ctc_lexicon_{lang}.json",
                "lexicon_sha256": hashlib.sha256(out[f"{ASSETS_DIR}/ctc_lexicon_{lang}.json"]).hexdigest(),
            }
        out[f"{FIXTURES_DIR}/fixtures_{lang}.json"] = _json(fixtures(lex))
        languages[lang] = info
    lines = {model: [f"{tokens[k]} @{k}" for k in grammar.kws["keywords"]]
             for model, tokens in grammar.kws.get("models", {}).items()}
    out[f"{ASSETS_DIR}/grammar_manifest.json"] = _json({
        "generator": "tools/grammar-gen/gen.py",
        "kws": {"keywords": list(grammar.kws["keywords"]), "lines": lines},
        "languages": languages,
        "source_sha256": grammar.source_sha256(),
        "version": grammar.version,
    })
    out[f"{KOTLIN_DIR}/GrammarTables.kt"] = render_kotlin(grammar).encode("utf-8")
    return dict(sorted(out.items()))


def _stale(root: Path, outputs: dict) -> list:
    stale = []
    for directory, pattern in GENERATED.items():
        d = root / directory
        if d.is_dir():
            for f in sorted(d.iterdir()):
                rel = f"{directory}/{f.name}"
                if f.is_file() and pattern.fullmatch(f.name) and rel not in outputs:
                    stale.append(rel)
    return stale


def drift(root: Path, outputs: dict) -> list:
    problems = []
    for rel, data in outputs.items():
        f = root / rel
        if not f.exists():
            problems.append(f"missing: {rel}")
        elif f.read_bytes() != data:
            problems.append(f"out of date: {rel}")
    problems += [f"stale: {rel}" for rel in _stale(root, outputs)]
    return problems


def write_outputs(root: Path, outputs: dict) -> tuple:
    written = 0
    for rel, data in outputs.items():
        f = root / rel
        if f.exists() and f.read_bytes() == data:
            continue
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(data)
        written += 1
    removed = _stale(root, outputs)
    for rel in removed:
        (root / rel).unlink()
    return written, removed


# --- Vosk model readers ---------------------------------------------------------------------------

def _fst_string(f) -> bytes:
    n = struct.unpack("<i", f.read(4))[0]
    return f.read(n)


def _fst_symbols(f) -> dict:
    f.read(4)  # magic
    _fst_string(f)  # name
    _available, size = struct.unpack("<qq", f.read(16))
    table = {}
    for _ in range(size):
        word = _fst_string(f).decode("utf-8", "replace")
        table[struct.unpack("<q", f.read(8))[0]] = word
    return table


def _fst_header(f):
    f.read(4)  # magic
    fst_type = _fst_string(f).decode()
    _fst_string(f)  # arc type
    _version, flags = struct.unpack("<ii", f.read(8))
    f.read(32)  # properties, start, states, arcs
    isyms = _fst_symbols(f) if flags & 1 else None
    osyms = _fst_symbols(f) if flags & 2 else None
    return fst_type, isyms, osyms


def read_fst_symbols(path) -> list:
    """The word list of an OpenFst binary with embedded symbols (a Vosk model's graph/Gr.fst)."""
    with open(path, "rb") as f:
        _type, isyms, osyms = _fst_header(f)
    table = osyms or isyms
    if table is None:
        raise GrammarError(f"{path} has no embedded symbol table")
    return [table[k] for k in sorted(table)]


def read_unigram_costs(path) -> dict:
    """word -> unigram cost (-ln p) from an OpenFst NGramFst (Vosk small models' graph/Gr.fst)."""
    with open(path, "rb") as f:
        fst_type, isyms, osyms = _fst_header(f)
        if fst_type != "ngram":
            raise GrammarError(f"{path}: FST type {fst_type!r}, expected 'ngram'")
        words = osyms or isyms
        ns, nf, nfin = struct.unpack("<QQQ", f.read(24))
        w64 = lambda bits: (bits + 63) // 64  # noqa: E731
        f.read(8 * w64(2 * ns + 1))  # context bitmap
        future = f.read(8 * w64(nf + ns + 1))  # little-endian 64-bit words = bit i at byte i // 8, bit i % 8
        f.read(8 * w64(ns))  # final bitmap
        f.read(4 * (ns + 1))  # context words
        future_words = struct.unpack(f"<{nf}i", f.read(4 * nf))
        f.read(4 * (ns + 1) + 4 * nfin)  # backoffs, final probabilities
        future_costs = struct.unpack(f"<{nf}f", f.read(4 * nf))

    def bit(i):
        return future[i >> 3] >> (i & 7) & 1

    # LOUDS: the root (unigram) state's futures are the 1-bits after the first 0-bit.
    if bit(0):
        raise GrammarError(f"{path}: unexpected NGramFst layout")
    count = 0
    while bit(count + 1):
        count += 1
    return {words[future_words[i]]: future_costs[i] for i in range(count)}


def read_vocabulary(path) -> set:
    path = Path(path)
    if path.is_dir():
        for candidate in ("graph/Gr.fst", "graph/words.txt", "words.txt"):
            if (path / candidate).exists():
                return read_vocabulary(path / candidate)
        raise GrammarError(f"{path}: no graph/Gr.fst or words.txt")
    if path.suffix == ".fst":
        return set(read_fst_symbols(path))
    with open(path, encoding="utf-8") as f:
        return {line.split()[0] for line in f if line.strip()}


def suggest_fillers(lex, costs: dict, count: int) -> list:
    """The `count` most frequent model words that may serve as fillers for this lexicon (README "Fillers")."""
    taken = lex.word_tokens()
    excluded = set(lex.table("vosk.filler_exclude"))
    stems = _stems(lex.table("stop_lane") + lex.classes["hold"])
    out = []
    for word, _cost in sorted(costs.items(), key=lambda kv: (kv[1], kv[0])):
        if word[:1] in "<![#" or word in taken or word in excluded or near_stop(lex, word):
            continue
        if unicodedata.normalize("NFC", word) != word:  # Vosk emits grammar strings as written; ours are NFC
            continue
        if not vosk_eligible(lex, word) or any(word.startswith(s) for s in stems):
            continue
        out.append(word)
        if len(out) == count:
            break
    return out


# --- command line ---------------------------------------------------------------------------------

def _vosk_vocabularies(grammar, args) -> tuple:
    """(lang -> vocabulary, lang -> source, notes, errors)."""
    overrides = {}
    for item in args.vosk_words or []:
        lang, _, path = item.partition("=")
        overrides[lang] = Path(path)
    vocab, sources, notes, errors = {}, {}, [], []
    if args.no_vosk:
        return vocab, sources, ["vosk vocabulary check: disabled (--no-vosk)"], errors
    for lang in grammar.vosk_languages:
        source = overrides.get(lang) or Path(args.vosk_models) / grammar.vosk[lang]["model"]
        if not source.exists():
            message = f"vosk vocabulary check {lang}: skipped, {source} not found"
            (errors if args.require_vosk else notes).append(message)
            continue
        vocab[lang] = read_vocabulary(source)
        sources[lang] = str(source)
    return vocab, sources, notes, errors


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="fail if any generated file is missing or differs")
    ap.add_argument("--root", default=str(ROOT), help="output root (default: this repository)")
    ap.add_argument("--grammar", default=str(GRAMMAR_DIR), help="grammar directory")
    ap.add_argument("--vosk-models", default=str(DEFAULT_VOSK_MODELS), help="directory of Vosk model folders")
    ap.add_argument("--vosk-words", action="append", metavar="LANG=FILE",
                    help="word list, Gr.fst or model folder for one language (overrides --vosk-models)")
    ap.add_argument("--require-vosk", action="store_true", help="fail when a Vosk model is not found")
    ap.add_argument("--no-vosk", action="store_true", help="skip the Vosk vocabulary check")
    ap.add_argument("--suggest-fillers", metavar="LANG", help="print frequent model words for vosk.fillers")
    ap.add_argument("--count", type=int, default=300, help="number of fillers to suggest")
    args = ap.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):  # words and messages are in native scripts; Windows pipes are cp1252
        encoding = (getattr(stream, "encoding", None) or "").lower().replace("-", "")
        if encoding != "utf8" and hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    try:
        grammar = load_grammar(args.grammar)
        if args.suggest_fillers:
            lang = args.suggest_fillers
            vocab_args = {k.split("=")[0]: k.split("=", 1)[1] for k in args.vosk_words or []}
            source = Path(vocab_args.get(lang) or Path(args.vosk_models) / grammar.vosk[lang]["model"])
            fst = source if source.suffix == ".fst" else source / "graph" / "Gr.fst"
            words = suggest_fillers(grammar.lexicons[lang], read_unigram_costs(fst), args.count)
            print(f"# {len(words)} fillers for {lang} from {fst} (most frequent first)")
            print("  fillers: [" + ", ".join(json.dumps(w, ensure_ascii=False) if not re.fullmatch(r"[\w']+", w)
                                            or w in ("no", "yes", "on", "off", "y", "n", "true", "false", "null")
                                            else w for w in words) + "]")
            return 0
        outputs = build_outputs(grammar)
        vocab, sources, notes, errors = _vosk_vocabularies(grammar, args)
        errors += vocabulary_errors(grammar, vocab, sources)
    except GrammarError as e:
        print(f"grammar error: {e}", file=sys.stderr)
        return 1

    for line in notes:
        print(line)
    for lang in vocab:
        if not any(f"vosk {lang} " in e for e in errors):
            lex = grammar.lexicons[lang]
            used = {w for p in vosk_phrases(lex) + stop_phrases(lex) if p != UNK for w in p.split()}
            print(f"vosk vocabulary check {lang}: ok ({len(used)} words found in {sources[lang]})")
            stale = sorted(set(lex.table("vosk.exclude")) & vocab[lang])
            if stale:
                print(f"note: vosk {lang}: vosk.exclude words now in the model vocabulary: {stale}")
    for e in errors:
        print(e, file=sys.stderr)

    root = Path(args.root)
    if args.check:
        problems = drift(root, outputs)
        for p in problems:
            print(p)
        if problems or errors:
            print("grammar outputs are NOT current: run python tools/grammar-gen/gen.py" if problems
                  else "grammar check failed", file=sys.stderr)
            return 1
        print(f"grammar outputs are current ({len(outputs)} files)")
        return 0
    if errors:
        print("nothing written", file=sys.stderr)
        return 1
    written, removed = write_outputs(root, outputs)
    print(f"wrote {written} file(s), {len(outputs) - written} unchanged, removed {len(removed)} stale")
    return 0


if __name__ == "__main__":
    sys.exit(main())
