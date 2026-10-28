"""Tests for the canonical grammar generator (tools/grammar-gen/gen.py).

Run from the repo root: python -m pytest tools/grammar-gen -q

The parity tests read the v0.2.0 Dart sources (and the edge/app/intent.py
reference for ta/kn/ml) and skip once those files are deleted.
"""
import importlib.util
import json
import re
import shutil
import struct
import sys
import unicodedata
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

import dart_source as dart  # noqa: E402
import gen  # noqa: E402

MINI = HERE / "testdata" / "mini"
MINI_WORDS = HERE / "testdata" / "mini_words_en.txt"
MINI_WORDS_MISSING = HERE / "testdata" / "mini_words_en_missing_forward.txt"

RULE_ENGINE = ROOT / "app/lib/features/intent/data/rule_intent_engine.dart"
PLAN_INTERPRETER = ROOT / "app/lib/features/flight/application/plan_interpreter.dart"
TRANSCRIPT = ROOT / "app/lib/features/voice/domain/command_transcript.dart"
EDGE_INTENT = ROOT / "edge/app/intent.py"


@pytest.fixture(scope="module")
def grammar():
    return gen.load_grammar(gen.GRAMMAR_DIR)


@pytest.fixture(scope="module")
def outputs(grammar):
    return gen.build_outputs(grammar)


@pytest.fixture(scope="module")
def tables(grammar):
    return gen.kotlin_tables(grammar)


@pytest.fixture(scope="module")
def manifest(outputs):
    return json.loads(outputs[gen.ASSETS_DIR + "/grammar_manifest.json"])


def _asset(outputs, name):
    return json.loads(outputs[f"{gen.ASSETS_DIR}/{name}"])


def _copy_mini(tmp_path):
    target = tmp_path / "grammar"
    shutil.copytree(MINI, target)
    return target


def _edit(path, old, new):
    text = path.read_text(encoding="utf-8")
    assert old in text, old
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


# --- YAML and Dart readers ----------------------------------------------------------------------

def test_yaml_keeps_english_words_as_strings(tmp_path):
    f = tmp_path / "x.yaml"
    f.write_text("words: [no, yes, on, off, y, n, null-ish]\nflag: true\nother: false\nhex: 0x0C00\n", encoding="utf-8")
    data = gen.load_yaml(f)
    assert data["words"] == ["no", "yes", "on", "off", "y", "n", "null-ish"]
    assert data["flag"] is True and data["other"] is False
    assert data["hex"] == 0x0C00


def test_dart_reader_decodes_literals_spreads_and_comments():
    source = (
        "const x = [\n  'a', \"b'c\", 'd\\u200Ce', r'\\d', 'f' 'g', // a comment 'no'\n"
        "  ...other, ...m['K']!, 'h\\u{1F600}', 'don\\u2019t'];\n"
    )
    assert dart.declaration(source, "x") == [
        "a", "b'c", "d\u200ce", "\\d", "fg", dart.Spread("other"), dart.Spread("m['K']!"), "h\U0001F600",
        "don\u2019t",
    ]
    assert dart.literal_strings(source, "x") == ["a", "b'c", "d\u200ce", "\\d", "fg", "K", "h\U0001F600", "don\u2019t"]


def test_dart_reader_maps_calls_and_regexes():
    source = (
        "const m = {'one': 1, 'two': 2};\n"
        "const s = [SimpleEntry('STOP', ['stop', 'halt'])];\n"
        "static final _r = RegExp(r'^(?:a|b)' r'c', caseSensitive: false);\n"
    )
    assert dart.declaration(source, "m") == {"one": 1, "two": 2}
    assert dart.declaration(source, "s") == [dart.Call("SimpleEntry", ("STOP", ["stop", "halt"]))]
    assert dart.regex_source(source, "_r") == "^(?:a|b)c"


# --- grammar validation -------------------------------------------------------------------------

def test_real_grammar_loads_every_language(grammar):
    assert list(grammar.lexicons) == ["en", "hi", "te", "ta", "kn", "ml"]
    assert grammar.rule_languages == ["en", "hi", "te"]
    assert grammar.vosk_languages == ["en", "hi", "te"]
    assert grammar.ctc_languages == ["te", "ta", "kn", "ml"]


def test_non_nfc_entry_is_rejected(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "move_verbs: [move]", "move_verbs: [move, \"cafe\\u0301\"]")
    with pytest.raises(gen.GrammarError, match="NFC"):
        gen.load_grammar(g)


def test_unknown_key_is_rejected(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  negators: [not]", "  negators: [not]\n  negatorz: [nope]")
    with pytest.raises(gen.GrammarError, match="negatorz"):
        gen.load_grammar(g)


def test_duplicate_word_in_a_table_is_rejected(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "move_verbs: [move]", "move_verbs: [move, move]")
    with pytest.raises(gen.GrammarError, match="duplicate"):
        gen.load_grammar(g)


def test_template_class_must_exist(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", '"{hold}"', '"{hovering}"')
    with pytest.raises(gen.GrammarError, match="hovering"):
        gen.load_grammar(g)


def test_template_literal_must_be_a_lexicon_word(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", '"{verb} {dir} {num} {unit}"', '"{verb} {dir} by {num} {unit}"')
    with pytest.raises(gen.GrammarError, match='"by"'):
        gen.load_grammar(g)


def test_numeral_gap_is_rejected(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "two: 2, ", "")
    with pytest.raises(gen.GrammarError, match="2"):
        gen.load_grammar(g)


def test_loanword_must_not_shadow_a_rule_form(tmp_path, grammar):
    g = tmp_path / "grammar"
    shutil.copytree(gen.GRAMMAR_DIR, g)
    _edit(g / "lexicon/te.yaml", "{loan: హోల్డ్,", "{loan: హోవర్,")
    with pytest.raises(gen.GrammarError, match="హోవర్"):
        gen.load_grammar(g)


def test_provisional_flags(grammar):
    te = grammar.lexicons["te"]
    assert not te.is_provisional("ముందుకు")  # Task 3 rule table
    assert te.is_provisional("అప్పుడు")  # PROVISIONAL connective in plan_interpreter.dart
    assert not te.is_provisional("మరియు")  # reviewed connective
    assert te.is_provisional("వద్దు")  # PROVISIONAL negator
    assert all(e.provisional for e in grammar.lexicons["ta"].entries())
    assert not any(e.provisional for e in grammar.lexicons["en"].entries())


# --- numerals -----------------------------------------------------------------------------------

@pytest.mark.parametrize("lang", ["en", "hi", "te", "ta", "kn", "ml"])
def test_every_language_covers_0_to_100_and_the_halves(grammar, lang):
    numerals = gen.numerals(grammar.lexicons[lang])
    native = {n.value for n in numerals if n.kind != "LOAN"}
    assert set(range(0, 101)) <= native
    # 0.4.0 (README "Spoken numbers"): the halves are read as their value, no longer refused.
    halves = {n.value for n in numerals if n.kind == "FRACTION" and n.value % 1}
    assert {0.5, 1.5, 2.5} <= halves
    assert not any(n.value % 1 for n in numerals if n.kind != "FRACTION")


@pytest.mark.parametrize("lang", ["en", "hi", "te", "ta", "kn", "ml"])
def test_numeral_phrases_have_one_value(grammar, lang):
    seen = {}
    for n in gen.numerals(grammar.lexicons[lang]):
        assert seen.setdefault(n.phrase, n.value) == n.value, n.phrase


def test_numeral_kinds(grammar):
    def kinds(lang):
        return {n.phrase: (n.value, n.kind) for n in gen.numerals(grammar.lexicons[lang])}
    en, te, hi, ta = kinds("en"), kinds("te"), kinds("hi"), kinds("ta")
    assert en["twenty three"] == (23, "RULE")
    assert en["one and a half"] == (1.5, "FRACTION")
    assert en["hundred"] == (100, "REJECT")  # a scale word: v0.2.0 refuses it
    assert te["ఇరవై మూడు"] == (23, "RULE")
    assert te["అర"] == (0.5, "FRACTION")
    assert te["హాఫ్"] == (0.5, "FRACTION")
    assert te["త్రీ"] == (3, "LOAN")
    assert te["సున్నా"] == (0, "EXTRA")
    assert hi["सौ"] == (100, "RULE")
    assert hi["ढाई"] == (2.5, "FRACTION")
    assert ta["ஒன்றரை"] == (1.5, "FRACTION")


def test_english_composes_spoken_decimals_and_halves(grammar):
    """0.4.0 (README "Spoken numbers"): "two point five", "point five", "four and a half", "half a" metre."""
    en = {n.phrase: (n.value, n.kind) for n in gen.numerals(grammar.lexicons["en"])}
    assert en["two point five"] == (2.5, "FRACTION")
    assert en["nine point nine"] == (9.9, "FRACTION")
    assert en["zero point three"] == (0.3, "FRACTION")
    assert en["point three"] == (0.3, "FRACTION")
    assert en["four and a half"] == (4.5, "FRACTION")
    assert en["four and half"] == (4.5, "FRACTION")
    assert en["half a"] == (0.5, "FRACTION")
    # Bounded by intents.v1.yaml spoken_numbers.below: no 10.5, no 0.0, no "point zero".
    assert "ten point five" not in en and "ten and a half" not in en and "zero point zero" not in en
    assert "two point zero" not in en and "zero and a half" not in en
    fractions = [v for v, kind in en.values() if kind == "FRACTION"]
    assert max(fractions) == 9.9 and min(fractions) == 0.1
    # Only en has decimal-point and "and a half" words; the Indic lexicons keep their fraction words.
    for lang in ("hi", "te", "ta", "kn", "ml"):
        lex = grammar.lexicons[lang]
        assert not lex.table("numerals.decimal_point") and not lex.table("numerals.and_half"), lang


def test_english_decimals_agree_with_word2number(grammar):
    """Optional host cross-check: Python word2number, when installed, reads the integers and every composed
    "<n> point <d>" numeral as the generator does (the app's Kotlin reader is checked against the same table)."""
    w2n = pytest.importorskip("word2number.w2n")
    for n in gen.numerals(grammar.lexicons["en"]):
        if n.kind == "RULE" or (n.kind == "FRACTION" and " point " in f" {n.phrase} " and not n.phrase.startswith("point")):
            assert w2n.word_to_num(n.phrase) == pytest.approx(n.value), n.phrase


def test_mini_decimal_point_composes_numerals_below_the_bound(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  fractions: {half: 0.5}",
          "  fractions: {half: 0.5}\n  decimal_point: [point]\n  and_half: [and a half]")
    _edit(g / "lexicon/en.yaml", "  scale_words: [half]", "  scale_words: [half, point]")
    _edit(g / "lexicon/en.yaml", "stop_lane: [stop]", "plan:\n  filler: [and, a]\nstop_lane: [stop]")
    _edit(g / "intents.v1.yaml", "halves: [0.5]", "halves: [0.5]\nspoken_numbers: {below: 3}")
    lex = gen.load_grammar(g).lexicons["en"]
    composed = {n.phrase: n.value for n in lex.numerals if n.kind == "FRACTION" and n.phrase != "half"}
    expected = {f"{a} point {b}": i + j / 10 for i, a in enumerate(["zero", "one", "two"])
                for j, b in enumerate(["one", "two", "three"], 1)}
    expected |= {f"point {b}": j / 10 for j, b in enumerate(["one", "two", "three"], 1)}
    expected |= {f"{a} and a half": i + 0.5 for i, a in enumerate(["one", "two"], 1)}
    assert composed == pytest.approx(expected)


# --- outputs ------------------------------------------------------------------------------------

def test_outputs_are_deterministic_lf_and_sorted(grammar, outputs):
    assert gen.build_outputs(gen.load_grammar(gen.GRAMMAR_DIR)) == outputs
    for path, data in outputs.items():
        text = data.decode("utf-8")
        assert text.endswith("\n") and "\r" not in text, path
        if path.endswith(".json"):
            value = json.loads(text)
            if isinstance(value, dict):
                assert list(value) == sorted(value), path
            assert "timestamp" not in text and "generated_at" not in text


def test_output_paths(outputs):
    names = set(outputs)
    assert gen.KOTLIN_DIR + "/GrammarTables.kt" in names
    assert gen.ASSETS_DIR + "/grammar_manifest.json" in names
    for lang in ["en", "hi", "te"]:
        assert f"{gen.ASSETS_DIR}/vosk_{lang}.json" in names
        assert f"{gen.ASSETS_DIR}/stop_{lang}.json" in names
    for lang in ["te", "ta", "kn", "ml"]:
        assert f"{gen.ASSETS_DIR}/ctc_lexicon_{lang}.json" in names
    for lang in ["en", "hi", "te", "ta", "kn", "ml"]:
        assert f"{gen.FIXTURES_DIR}/fixtures_{lang}.json" in names
    assert not any("vosk_ta" in n or "ctc_lexicon_en" in n for n in names)


def test_mini_vosk_phrase_list_is_template_and_negation_pairs_lexicon_words_fillers_and_unk():
    mini = gen.load_grammar(MINI)
    phrases = json.loads(gen.build_outputs(mini)[gen.ASSETS_DIR + "/vosk_en.json"])
    nums = ["zero", "one", "two", "three", "half"]
    dirs = ["forward", "back", "left", "right", "up", "down"]
    expected = {"take off"} | {f"off {n}" for n in nums} | {f"{n} meters" for n in nums}
    expected |= {f"move {d}" for d in dirs} | {f"{d} {n}" for d in dirs for n in nums}
    # The negator right before and right after each command position (README "Negation in the grammar").
    expected |= {"not take", "off not", "not move", "move not", "not hover", "hover not", "not stop", "stop not",
                 "not land", "land not"}
    expected |= {"move", "meters", "stop", "land", "hover", "not", *nums, *dirs}  # lexicon words
    expected |= {"apple", "banana", "[unk]"}
    assert set(phrases) == expected
    assert phrases == sorted(phrases)


def test_mini_connective_pairs_join_two_commands(tmp_path):
    """README "Plans": a template's last word before each connective, and each connective before a first word."""
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "stop_lane: [stop]", "plan:\n  connectives: [and then, then]\nstop_lane: [stop]")
    lex = gen.load_grammar(g).lexicons["en"]
    lasts, firsts = ["meters", "hover", "stop", "land"], ["take", "move", "hover", "stop", "land"]
    expected = {f"{w} {c}" for w in lasts for c in ("and", "then")} | {f"then {w}" for w in firsts}
    assert gen.vosk_connective_phrases(lex) == expected
    phrases = set(json.loads(gen.build_outputs(gen.load_grammar(g))[gen.ASSETS_DIR + "/vosk_en.json"]))
    assert expected | {"and then"} <= phrases
    assert gen.vosk_connective_phrases(gen.load_grammar(MINI).lexicons["en"]) == set()


@pytest.mark.parametrize("lang", ["en", "hi", "te"])
def test_two_step_plans_are_vosk_word_pairs(outputs, lang):
    vosk = set(_asset(outputs, f"vosk_{lang}.json"))
    fx = json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_{lang}.json"])
    assert fx["plans"]
    for case in fx["plans"]:
        words = case["text"].split()
        missing = [f"{a} {b}" for a, b in zip(words, words[1:]) if f"{a} {b}" not in vosk]
        assert not missing, (case, missing)
    assert {"meters and", "then land", "and then"} <= set(_asset(outputs, "vosk_en.json"))


def test_vosk_negators_must_be_negation_words(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  fillers: [apple, banana]", "  fillers: [apple, banana]\n  negators: [land]")
    with pytest.raises(gen.GrammarError, match="land"):
        gen.load_grammar(g)


@pytest.mark.parametrize("lang", ["en", "hi", "te"])
def test_negated_commands_are_vosk_word_pairs(grammar, outputs, lang):
    """Spec 4.8: a spoken negation must decode, so "don't take off" never becomes "take off" (W1: it was dropped)."""
    vosk = set(_asset(outputs, f"vosk_{lang}.json"))
    fx = json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_{lang}.json"])
    assert len(fx["negated"]) >= 8
    for case in fx["negated"]:
        words = case["text"].split()
        missing = [f"{a} {b}" for a, b in zip(words, words[1:]) if f"{a} {b}" not in vosk]
        assert not missing, (case, missing)
    negators = {w for p in gen.vosk_negators(grammar.lexicons[lang]) for w in p.split()}
    assert {w for c in fx["negated"] for w in c["text"].split()} & negators


def test_negated_fixture_forms(outputs):
    def texts(lang):
        return {c["text"] for c in json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_{lang}.json"])["negated"]}
    assert {"don't land", "do not land", "never hover"} <= texts("en")
    assert {"लैंड करो मत", "लैंड मत करो"} <= texts("hi")  # after the verb, and before it
    assert "ల్యాండ్ వద్దు" in texts("te")


def _refused_as_negation(lex, text):
    """Whether the v0.2.0 rule tables refuse `text` as a negated command (negator word, suffix or fused form)."""
    words = text.split()
    whole = set(lex.table("rules.negators")) | set(lex.table("transcript.negators")) | set(
        lex.table("transcript.negation_words")) | set(lex.table("plan.negation_words"))
    suffixes = lex.table("rules.negator_suffixes") + lex.grammar.lexicons["en"].table("rules.negator_suffixes")
    fused = lex.table("transcript.fused_negators")
    return (any(" ".join(words[i:i + len(n.split())]) == n for n in whole for i in range(len(words)))
            or any(w.endswith(s) for w in words for s in suffixes) or any(f in text for f in fused))


def _parser_words(lex):
    """Every word a v0.2.0 parser table of this language reads (rules, plan, transcript; all languages' plan)."""
    words = set()
    for lx in lex.grammar.lexicons.values():
        for e in lx.entries():
            if e.path.startswith(("rules.", "plan.", "transcript.")) and (lx is lex or e.path.startswith("plan.")):
                words |= set(e.word.split())
    return words


def _refused(lex, text):
    """The v2 parser refuses `text`: as a negation, or because it holds a grammar negator no parser table reads (te
    negative imperatives such as వెళ్ళకు: no rule reading, which v0.2.0 sent to its AI fallback and the v2 port must
    reject; README "Negation in the grammar")."""
    unread = set(lex.table("grammar_negators")) - _parser_words(lex)
    return _refused_as_negation(lex, text) or bool(set(text.split()) & unread)


@pytest.mark.parametrize("lang", ["en", "hi", "te", "ta", "kn", "ml"])
def test_every_grammar_negation_is_refused_by_the_rule_tables(grammar, outputs, lang):
    lex = grammar.lexicons[lang]
    for phrase in lex.table("grammar_negators") + list(gen.vosk_negators(lex) if lang in grammar.vosk else []):
        assert _refused(lex, phrase), phrase
    for case in json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_{lang}.json"])["negated"]:
        assert _refused(lex, case["text"]), case


def test_telugu_negative_imperatives_are_in_the_grammar(grammar, outputs):
    """A fused "don't go" (వెళ్ళకు) must decode as itself, never as the move verb వెళ్ళు (spec 4.8)."""
    te = set(_asset(outputs, "vosk_te.json"))
    assert {"ముందుకు వెళ్ళకు", "ముందుకు వెళ్లకు", "ముందుకు వెళ్ళవద్దు", "ముందుకు వెళ్లొద్దు", "హోవర్ చేయవద్దు"} <= te
    fx = {c["text"] for c in json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_te.json"])["negated"]}
    assert any(t.endswith(" వెళ్ళకు") for t in fx)
    lex = grammar.lexicons["te"]
    for word in ("ఆపకు", "ఆపొద్దు", "ఆగవద్దు"):  # "don't stop" stays out: a STOP is never absorbed (README)
        assert word not in lex.table("grammar_negators") and word not in te
    assert all(lex.is_provisional(w) for w in lex.table("grammar_negators"))


def test_english_grammar_holds_do_not(grammar, outputs):
    en = set(_asset(outputs, "vosk_en.json"))
    assert {"do not", "not take", "don't move", "never land"} <= en
    assert "do" not in grammar.lexicons["en"].table("vosk.fillers")
    assert "do not" in gen.vosk_negators(grammar.lexicons["en"])


def test_mini_stop_grammar_has_stop_decoys_fillers_and_unk():
    mini = gen.load_grammar(MINI)
    out = gen.build_outputs(mini)
    stop = json.loads(out[gen.ASSETS_DIR + "/stop_en.json"])
    assert "stop" in stop and "[unk]" in stop and {"apple", "banana", "hover", "land"} <= set(stop)
    assert all(" " not in p or p == "take off" for p in stop)
    manifest = json.loads(out[gen.ASSETS_DIR + "/grammar_manifest.json"])
    assert manifest["languages"]["en"]["stop_lane"] == ["stop"]


# Acoustic neighbours of a stop-lane word (README "Stop lane and keyword spotting"). Measured: as en decoys,
# "start" took 2 of 18 spoken "stop"s (vosk_eval.py stop).
NEAR_STOP = {"en": {"start", "started", "step", "steps", "stuff", "top", "drop"}, "hi": {"रूप"},
             "te": {"ఆరు", "అప్", "స్టార్"}}


@pytest.mark.parametrize("lang", ["en", "hi", "te"])
def test_near_stop_words_are_never_fillers_or_stop_decoys(grammar, outputs, lang):
    lex = grammar.lexicons[lang]
    assert NEAR_STOP[lang] <= set(lex.table("vosk.near_stop"))
    stop = set(_asset(outputs, f"stop_{lang}.json"))
    fillers = set(lex.table("vosk.fillers"))
    for word in lex.table("vosk.near_stop"):
        assert gen.near_stop(lex, word)
        assert word not in stop and word not in fillers, word
    lane = {t for p in lex.table("stop_lane") for t in p.split()}
    assert not any(gen.near_stop(lex, w) for w in lane)
    assert not [w for p in stop | fillers for w in p.split() if w not in lane and gen.near_stop(lex, w)]


def test_near_stop_inflections_and_listed_words(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  fillers: [apple, banana]", "  near_stop: [step]\n  fillers: [apple, banana]")
    lex = gen.load_grammar(g).lexicons["en"]
    assert gen.near_stop(lex, "step") and gen.near_stop(lex, "stops") and gen.near_stop(lex, "stopped")
    assert not gen.near_stop(lex, "stop") and not gen.near_stop(lex, "hover") and not gen.near_stop(lex, "apple")
    assert gen.suggest_fillers(lex, {"step": 1.0, "apple": 2.0, "stops": 3.0, "banana": 4.0}, 5) == ["apple", "banana"]


def test_a_near_stop_filler_is_rejected(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  fillers: [apple, banana]", "  near_stop: [apple]\n  fillers: [apple, banana]")
    with pytest.raises(gen.GrammarError, match="apple"):
        gen.build_outputs(gen.load_grammar(g))


def test_a_near_stop_lexicon_word_is_no_stop_decoy(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  fillers: [apple, banana]", "  near_stop: [land]\n  fillers: [apple, banana]")
    out = gen.build_outputs(gen.load_grammar(g))
    assert "land" not in json.loads(out[gen.ASSETS_DIR + "/stop_en.json"])
    assert "land" in json.loads(out[gen.ASSETS_DIR + "/vosk_en.json"])  # the command grammar keeps it


def test_every_direction_keeps_a_vosk_form(tmp_path, capsys):
    """vosk.exclude may drop a form only when another form of its slot remains (README "Running the generator")."""
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  fillers: [apple, banana]", "  exclude: [forward]\n  fillers: [apple, banana]")
    with pytest.raises(gen.GrammarError, match="direction 'forward'"):
        gen.build_outputs(gen.load_grammar(g))
    assert _run(tmp_path, grammar_dir=g, words=MINI_WORDS_MISSING) == 1
    assert "direction 'forward'" in capsys.readouterr().err
    _edit(g / "lexicon/en.yaml", "forward: [forward]", "forward: [forward, ahead]")
    words = tmp_path / "words.txt"
    words.write_text(MINI_WORDS_MISSING.read_text(encoding="utf-8") + "ahead\n", encoding="utf-8")
    assert _run(tmp_path, grammar_dir=g, words=words) == 0  # another forward form covers it
    assert "ahead" in json.loads((tmp_path / "out" / gen.ASSETS_DIR / "vosk_en.json").read_text(encoding="utf-8"))


def test_every_template_class_keeps_a_vosk_form(tmp_path):
    g = _copy_mini(tmp_path)
    _edit(g / "lexicon/en.yaml", "  fillers: [apple, banana]", "  exclude: [move]\n  fillers: [apple, banana]")
    with pytest.raises(gen.GrammarError, match="class {verb}"):
        gen.build_outputs(gen.load_grammar(g))


def test_vosk_lists_are_plain_json_arrays(grammar, outputs):
    for lang in grammar.vosk_languages:
        for kind in ("vosk", "stop"):
            phrases = _asset(outputs, f"{kind}_{lang}.json")
            assert isinstance(phrases, list) and all(isinstance(p, str) for p in phrases)
            assert "[unk]" in phrases
            if lang != "en":
                assert not any(c.isascii() and c.isalpha() for p in phrases if p != "[unk]" for c in p), lang
    fillers = grammar.lexicons["en"].table("vosk.fillers")
    assert 200 <= len(fillers) <= 2000


def test_vosk_grammars_hold_numbers_negations_and_reject_words(grammar, outputs):
    en = set(_asset(outputs, "vosk_en.json"))
    assert {"fifteen meters", "fifty", "not", "never", "centimeters", "minus"} <= en
    assert "and a" in en and "a half" in en  # "two and a half" is decoded and read as 2.5 (0.4.0)
    te = set(_asset(outputs, "vosk_te.json"))
    assert {"వద్దు", "పదిహేను మీటర్లు", "సెంటీమీటర్లు"} <= te
    hi = set(_asset(outputs, "vosk_hi.json"))
    assert {"मत", "नहीं", "पंद्रह मीटर"} <= hi


NATURAL_EN = [
    # 0.4.0 (README "Spoken numbers", "Natural phrasing"): each must be word pairs of the en grammar.
    "move two point five meters left", "move forward two and a half meters", "move forward one and a half meters",
    "move forward two and half meters", "move forward half a meter", "move left one point five meters",
    "move forward point five meters", "take off to two point five meters", "climb two meters", "ascend two meters",
    "rise two meters", "go higher two meters", "descend two meters", "drop two meters", "lower two meters",
    "go lower two meters", "advance two meters", "ahead two meters", "reverse two meters", "retreat two meters",
    "back up two meters", "move three meters to the left", "go towards the right two meters", "go up by two meters",
    "climb by three meters", "move to the right three meters", "move two meters towards the left",
    "climb two meters and then land", "take off to three meters then climb two meters",
]


def test_english_grammar_holds_the_natural_phrasings(outputs):
    vosk = set(_asset(outputs, "vosk_en.json"))
    for text in NATURAL_EN:
        words = text.split()
        missing = [f"{a} {b}" for a, b in zip(words, words[1:]) if f"{a} {b}" not in vosk]
        assert not missing, (text, missing)
    # Sideways phrasing is left and right only: "to the up" would be refused ("say how far to move").
    assert not {"the up", "the down", "the forward"} & vosk
    # Out-of-grammar speech still decodes as fillers or [unk] (spec 4.6).
    fx = json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_en.json"])
    assert "[unk]" in vosk and len(fx["out_of_grammar"]) == 4
    assert all(set(c["text"].split()) <= vosk for c in fx["out_of_grammar"])


def test_natural_fixtures_cover_every_synonym(outputs, grammar):
    fx = json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_en.json"])
    moves = {c["text"]: c["arguments"]["direction"] for c in fx["positive"] if c["intent"] == "move"}
    assert moves["climb two meters"] == "up" and moves["back up two meters"] == "backward"
    assert moves["descend by two meters"] == "down" and moves["ahead two meters"] == "forward"
    assert moves["move higher two meters"] == "up" and moves["move lower by two meters"] == "down"
    assert moves["move two meters to the left"] == "left" and moves["move towards the right two meters"] == "right"
    assert moves["move forward by two meters"] == "forward"
    natural = grammar.lexicons["en"].value("natural.direction_verbs")
    for direction, phrases in natural.items():
        for phrase in phrases:
            assert moves[f"{phrase} two meters"] == direction, phrase
    assert not [t for t in moves if " the up" in t or " the down" in t]


def test_natural_tables_are_validated(tmp_path):
    def broken(old, new, match):
        g = _copy_mini(tmp_path / match)
        _edit(g / "lexicon/en.yaml", "stop_lane: [stop]",
              "stop_lane: [stop]\nnatural:\n  direction_verbs: {up: [climb]}\n  comparatives: {up: [higher]}")
        _edit(g / "lexicon/en.yaml", old, new)
        with pytest.raises(gen.GrammarError, match=match):
            gen.load_grammar(g)

    broken("{up: [climb]}", "{upward: [climb]}", "upward")  # not a canonical direction
    broken("{up: [climb]}", "{up: [left]}", "left")  # a form of another direction
    broken("{up: [higher]}", "{up: [move]}", "move")  # a move verb
    broken("{up: [climb]}", "{up: [climb], down: [climb]}", "climb")  # in two directions
    g = _copy_mini(tmp_path / "ctc")
    _edit(g / "lexicon/en.yaml", "stop_lane: [stop]", "stop_lane: [stop]\nnatural:\n  direction_verbs: {up: [climb]}")
    _edit(g / "lexicon/en.yaml", '"{verb} {dir} {num} {unit}"]', '"{verb} {dir} {num} {unit}", "{dirverb} {num} {unit}"]')
    gen.load_grammar(g)  # a rule-parser template may use it
    _edit(g / "intents.v1.yaml", "ctc: []", "ctc: [en]")
    with pytest.raises(gen.GrammarError, match="dirverb"):
        gen.load_grammar(g)  # the CTC template matcher has no such slot


# --- check mode and the Vosk vocabulary gate ----------------------------------------------------

def _run(tmp_path, *extra, grammar_dir=MINI, words=MINI_WORDS):
    args = ["--root", str(tmp_path / "out"), "--grammar", str(grammar_dir)]
    if words:
        args += ["--vosk-words", f"en={words}"]
    return gen.main(args + list(extra))


def test_check_mode_is_the_drift_gate(tmp_path, capsys):
    assert _run(tmp_path) == 0
    assert _run(tmp_path, "--check") == 0
    out = tmp_path / "out"
    vosk = out / gen.ASSETS_DIR / "vosk_en.json"
    vosk.write_bytes(vosk.read_bytes().replace(b"apple", b"apples"))
    assert _run(tmp_path, "--check") == 1
    assert "vosk_en.json" in capsys.readouterr().out
    assert _run(tmp_path) == 0 and _run(tmp_path, "--check") == 0
    (out / gen.KOTLIN_DIR / "GrammarTables.kt").unlink()
    assert _run(tmp_path, "--check") == 1
    assert _run(tmp_path) == 0
    stale = out / gen.ASSETS_DIR / "vosk_xx.json"
    stale.write_text("[]\n", encoding="utf-8")
    assert _run(tmp_path, "--check") == 1
    assert "vosk_xx.json" in capsys.readouterr().out
    assert _run(tmp_path) == 0 and not stale.exists()


def test_vosk_check_passes_with_complete_words(tmp_path, capsys):
    assert _run(tmp_path) == 0
    assert "en: ok" in capsys.readouterr().out


def test_vosk_check_fails_when_a_command_word_is_missing(tmp_path, capsys):
    assert _run(tmp_path, words=MINI_WORDS_MISSING) == 1
    err = capsys.readouterr().err
    assert '"forward"' in err and "command word" in err and "mini-model" in err and "en" in err
    assert not (tmp_path / "out").exists()  # nothing written from a failing grammar
    assert _run(tmp_path, "--check", words=MINI_WORDS_MISSING) == 1


def test_vosk_check_reads_a_model_directory(tmp_path, capsys):
    models = tmp_path / "models"
    _write_ngram_fst(models / "mini-model" / "graph" / "Gr.fst", MINI_WORDS.read_text().split(), {})
    assert gen.main(["--root", str(tmp_path / "out"), "--grammar", str(MINI), "--vosk-models", str(models)]) == 0
    assert "en: ok" in capsys.readouterr().out


def test_missing_model_is_skipped_unless_required(tmp_path, capsys):
    args = ["--root", str(tmp_path / "out"), "--grammar", str(MINI), "--vosk-models", str(tmp_path / "none")]
    assert gen.main(args) == 0
    assert "skipped" in capsys.readouterr().out
    assert gen.main(args + ["--require-vosk"]) == 1


# --- NGramFst readers and filler suggestion -----------------------------------------------------

def _symbol_table(words):
    out = struct.pack("<i", 2125658996) + _fst_string(b"syms") + struct.pack("<qq", len(words), len(words))
    for i, w in enumerate(words):
        out += _fst_string(w.encode("utf-8")) + struct.pack("<q", i)
    return out


def _fst_string(b):
    return struct.pack("<i", len(b)) + b


def _write_ngram_fst(path, words, costs):
    """A minimal OpenFst NGramFst: the root state's futures are `costs` (word -> -ln p)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    head = struct.pack("<i", 2125659606) + _fst_string(b"ngram") + _fst_string(b"standard")
    head += struct.pack("<ii", 4, 3) + struct.pack("<Qqqq", 0, 0, 1, 0)
    head += _symbol_table(words) + _symbol_table(words)
    futures = list(costs)
    ns, nf, nfin = 1, len(futures), 0
    bits = [0] + [1] * nf + [0]
    future_bits = sum(b << i for i, b in enumerate(bits))
    words64 = lambda n: (n + 63) // 64  # noqa: E731
    data = struct.pack("<QQQ", ns, nf, nfin)
    data += b"\0" * 8 * words64(2 * ns + 1)
    data += future_bits.to_bytes(8 * words64(nf + ns + 1), "little")
    data += b"\0" * 8 * words64(ns)
    data += struct.pack(f"<{ns + 1}i", *([0] * (ns + 1)))
    data += struct.pack(f"<{nf}i", *[words.index(w) for w in futures])
    data += struct.pack(f"<{ns + 1}f", *([0.0] * (ns + 1)))
    data += struct.pack(f"<{nf}f", *[costs[w] for w in futures])
    path.write_bytes(head + data)


def test_fst_readers(tmp_path):
    f = tmp_path / "Gr.fst"
    _write_ngram_fst(f, ["<eps>", "the", "stop", "stops", "apple"], {"the": 3.0, "stops": 4.0, "apple": 5.0, "stop": 6.0})
    assert gen.read_fst_symbols(f) == ["<eps>", "the", "stop", "stops", "apple"]
    assert gen.read_unigram_costs(f) == pytest.approx({"the": 3.0, "stops": 4.0, "apple": 5.0, "stop": 6.0})


def test_suggest_fillers_skips_lexicon_words_and_stop_and_hold_variants():
    mini = gen.load_grammar(MINI)
    costs = {"the": 1.0, "stops": 2.0, "stopped": 2.5, "hovering": 2.7, "forward": 3.0, "apple": 4.0, "x1": 4.5,
             "<unk>": 0.5, "banana": 5.0, "cherry": 6.0}
    assert gen.suggest_fillers(mini.lexicons["en"], costs, 3) == ["the", "apple", "banana"]


@pytest.mark.parametrize("lang", ["en", "hi", "te"])
def test_fillers_are_the_models_most_frequent_free_words(grammar, lang):
    """README "Fillers": vosk.fillers is exactly `gen.py --suggest-fillers <lang> --count 300` for the model."""
    fst = gen.DEFAULT_VOSK_MODELS / grammar.vosk[lang]["model"] / "graph" / "Gr.fst"
    if not fst.exists():
        pytest.skip(f"{fst} not on disk")
    lex = grammar.lexicons[lang]
    assert lex.table("vosk.fillers") == gen.suggest_fillers(lex, gen.read_unigram_costs(fst), 300)


def test_suggest_fillers_prints_native_script_to_a_non_utf8_stdout(tmp_path):
    import os
    import subprocess
    fst = tmp_path / "Gr.fst"
    _write_ngram_fst(fst, ["<eps>", "కూడా", "ఆరు", "ఇది"], {"కూడా": 1.0, "ఆరు": 1.5, "ఇది": 2.0})
    env = {**os.environ, "PYTHONIOENCODING": "cp1252"}
    run = subprocess.run([sys.executable, "-I", str(HERE / "gen.py"), "--suggest-fillers", "te", "--count", "5",
                          "--vosk-words", f"te={fst}"], capture_output=True, env=env)
    assert run.returncode == 0, run.stderr.decode("utf-8", "replace")
    out = run.stdout.decode("utf-8")
    assert '"కూడా", "ఇది"]' in out and "ఆరు" not in out  # ఆరు is a numeral (and near-stop): never a filler


def test_fillers_are_disjoint_from_lexicon_words(grammar):
    for lang in grammar.vosk_languages:
        lex = grammar.lexicons[lang]
        words = {w for e in lex.entries() if not e.path.startswith("vosk.") for w in e.word.split()}
        assert not words & set(lex.table("vosk.fillers")), lang


# --- the W1 stop-lane / keyword-spotting decision ----------------------------------------------

def test_kws_keywords_are_stop_only(manifest):
    assert manifest["kws"]["keywords"] == ["stop"]
    for lang, info in manifest["languages"].items():
        assert info["kws_keywords"] == ["stop"], lang
    assert manifest["kws"]["lines"]["kws-gigaspeech-3.3M-chunk-16"] == ["▁ST O P @stop"]


def test_hover_and_hold_never_trigger_the_stop_lane(grammar, manifest):
    for lang, lex in grammar.lexicons.items():
        stop = [c for c in lex.commands() if c[0] == "STOP"][0][1]
        hover = [c for c in lex.commands() if c[0] == "HOVER"][0][1]
        lane = manifest["languages"][lang]["stop_lane"]
        assert set(lane) <= set(stop), lang
        assert not set(lane) & set(hover) and "hold" not in lane and "hover" not in lane, lang


# --- CTC lexicons and fixtures ------------------------------------------------------------------

@pytest.mark.parametrize("lang", ["te", "ta", "kn", "ml"])
def test_ctc_lexicon(outputs, lang):
    ctc = _asset(outputs, f"ctc_lexicon_{lang}.json")
    assert ctc["language"] == lang
    assert set(range(0, 101)) <= set(ctc["slots"]["num"].values())
    # 0.4.0 (README "Spoken numbers"): the halves are slot values now, not refused numbers.
    assert {0.5, 1.5, 2.5} <= set(ctc["slots"]["num"].values())
    assert not any(v % 1 for v in ctc["reject_numbers"].values())
    assert not set(ctc["slots"]["num"]) & set(ctc["reject_numbers"])
    assert set(ctc["slots"]["dir"].values()) == {"forward", "backward", "left", "right", "up", "down"}
    for t in ctc["templates"]:
        for tok in t["tokens"]:
            if tok.startswith("{"):
                name = tok[1:-1]
                assert name in ctc["slots"] or name in ctc["fixed"], tok
            assert " " not in tok
    for forms in ctc["fixed"].values():
        assert all(" " not in f for f in forms)
    assert ctc["negation"]["words"] and ctc["stop_words"]
    stop_tokens = {tok for t in ctc["templates"] if t["intent"] == "stop" for tok in t["tokens"]}
    for phrase in ctc["stop_words"]:
        assert set(phrase.split()) <= stop_tokens | set(ctc["fixed"].get("stop", [])), phrase


@pytest.mark.parametrize("lang", ["en", "hi", "te", "ta", "kn", "ml"])
def test_fixtures_use_lexicon_words(grammar, outputs, lang):
    fx = json.loads(outputs[f"{gen.FIXTURES_DIR}/fixtures_{lang}.json"])
    lex = grammar.lexicons[lang]
    known = {w for e in lex.entries() for w in e.word.split()} | {"[unk]"}
    for group in ("positive", "negated", "unk"):
        assert fx[group], group
        for case in fx[group]:
            unknown = [w for w in case["text"].split() if w not in known]
            assert not unknown, (group, case, unknown)
    assert {n["value"] for n in fx["numbers"]} >= set(range(0, 101))
    intents = {c["intent"] for c in fx["positive"]}
    assert {"takeoff", "move", "hold", "stop", "land"} <= intents


# --- Kotlin -------------------------------------------------------------------------------------

def test_kotlin_string_escapes():
    assert gen.kotlin_string('a$b"c\\d') == '"a\\$b\\"c\\\\d"'
    assert gen.kotlin_string("డ్రోన్‌ను") == '"డ్రోన్\\u200Cను"'


def test_kotlin_output(outputs):
    kt = outputs[gen.KOTLIN_DIR + "/GrammarTables.kt"].decode("utf-8")
    assert kt.startswith("// GENERATED by tools/grammar-gen/gen.py")
    assert "package com.aeronex.app.grammar" in kt
    for lang in ["En", "Hi", "Te", "Ta", "Kn", "Ml"]:
        assert f"object Lexicon{lang}" in kt
    assert "‌" not in kt and "‍" not in kt  # written as \\u escapes


# --- parity with the v0.2.0 Dart tables and the edge reference ----------------------------------

needs_dart = pytest.mark.skipif(not (RULE_ENGINE.exists() and PLAN_INTERPRETER.exists() and TRANSCRIPT.exists()),
                                reason="v0.2.0 Dart parser removed")


def _src(path):
    return path.read_text(encoding="utf-8")


def _lex(tables, lang, field):
    return tables[f"Lexicon.{lang}.{field}"]


def _upper_keys(directions):
    return {k.upper(): v for k, v in directions.items()}


@pytest.mark.skipif(not RULE_ENGINE.exists(), reason="v0.2.0 Dart rule engine removed")
@pytest.mark.parametrize("lang", ["en", "hi", "te"])
def test_rule_tables_match_dart(tables, lang):
    src = _src(RULE_ENGINE)
    assert _lex(tables, lang, "moveVerbs") == dart.declaration(src, f"{lang}MoveVerbs")
    assert _upper_keys(_lex(tables, lang, "directions")) == dart.declaration(src, f"{lang}Directions")
    assert list(_upper_keys(_lex(tables, lang, "directions"))) == list(dart.declaration(src, f"{lang}Directions"))
    assert _lex(tables, lang, "units") == dart.declaration(src, f"{lang}Units")
    assert _lex(tables, lang, "commands") == [(c.args[0], c.args[1]) for c in dart.declaration(src, f"{lang}Simple")]
    for field, name in [("negators", "Negators"), ("negatorSuffixes", "NegatorSuffixes"), ("minusWords", "MinusWords"),
                        ("otherUnits", "OtherUnits"), ("scaleWords", "ScaleWords")]:
        assert _lex(tables, lang, field) == dart.declaration(src, lang + name), field
    if lang == "en":
        assert list(_lex(tables, lang, "ones").items()) == list(dart.declaration(src, "enOnes").items())
        assert list(_lex(tables, lang, "tens").items()) == list(dart.declaration(src, "enTens").items())
        assert _lex(tables, lang, "numbers") == {}
    else:
        assert list(_lex(tables, lang, "numbers").items()) == list(dart.declaration(src, f"{lang}Numbers").items())
    if lang == "te":
        assert _lex(tables, lang, "tensValues") == dart.declaration(src, "teTens")
        assert list(_lex(tables, lang, "units19").items()) == list(dart.declaration(src, "teUnits19").items())


@needs_dart
def test_plan_tables_match_dart(tables):
    src, rie = _src(PLAN_INTERPRETER), _src(RULE_ENGINE)

    def strings(name):
        return [x for x in dart.declaration(src, name) if isinstance(x, str)]

    def rule(name):
        return dart.declaration(rie, name)

    scale = rule("enScaleWords") + rule("hiScaleWords") + rule("teScaleWords") + strings("_scaleWords")
    assert set(tables["Plan.SCALE_WORDS"]) == set(scale)
    assert tables["Plan.CONNECTIVES"] == dart.declaration(src, "_connectives")
    first = dart.regex_source(src, "_firstWord")
    assert first == "^(?:" + "|".join(tables["Plan.FIRST_WORDS"]).replace("firstly|first", "first(?:ly)?") + r")(?:[\s:]+|$)"
    assert tables["Plan.COURTESY"] == set(strings("_courtesy"))
    assert tables["Plan.ORDINALS"] == set(strings("_ordinals"))
    assert tables["Plan.MOTION_VERBS"] == set(strings("_motionVerbs")) | set(rule("hiMoveVerbs")) | set(rule("teMoveVerbs"))
    assert tables["Plan.MINUS_WORDS"] == set(rule("enMinusWords") + rule("hiMinusWords") + rule("teMinusWords"))
    assert tables["Plan.OTHER_UNITS"] == set(rule("enOtherUnits") + rule("hiOtherUnits") + rule("teOtherUnits")
                                             + strings("_otherUnits"))
    for key, name in [("MEASURE_WORDS", "_measureWords"), ("COUNTED_WORDS", "_countedWords"),
                      ("SEQUENCE_WORDS", "_sequenceWords"), ("INDIC_FILLER", "_indicFiller"),
                      ("STRAIGHT_WORDS", "_straightWords"), ("MORE_WORDS", "_moreWords"), ("HEDGE_WORDS", "_hedgeWords"),
                      ("CORRECTION_WORDS", "_correctionWords"), ("NEGATION_WORDS", "_negationWords"),
                      ("ALTITUDE_WORDS", "_altitudeWords"), ("FILLER", "_filler")]:
        assert tables["Plan." + key] == set(strings(name)), key
    simple_hi_te = rule("hiSimple") + rule("teSimple")
    takeoff = set(w for w in dart.literal_strings(src, "_takeoffWords") if w not in ("TAKEOFF", " "))
    takeoff |= {w for c in simple_hi_te if c.args[0] == "TAKEOFF" for p in c.args[1] for w in p.split(" ")}
    assert tables["Plan.TAKEOFF_WORDS"] == takeoff
    simple = set(w for w in dart.literal_strings(src, "_simpleWords") if w not in ("TAKEOFF", " "))
    simple |= {w for c in simple_hi_te if c.args[0] != "TAKEOFF" for p in c.args[1] for w in p.split(" ")}
    assert tables["Plan.SIMPLE_WORDS"] == simple
    land = simple | {"finally"} | set(rule("hiDirections")["DOWN"]) | set(rule("teDirections")["DOWN"])
    assert tables["Plan.LAND_WORDS"] == land
    assert tables["Plan.UNITS"] == set(rule("enUnits") + rule("hiUnits") + rule("teUnits") + ["m"])
    en_move = set(rule("enMoveVerbs")) | {f for v in rule("enDirections").values() for f in v} | set(rule("enOnes")) | set(
        rule("enTens"))
    assert tables["Plan.ENGLISH_MOVE_WORDS"] == en_move
    indic = [*rule("hiMoveVerbs"), *rule("teMoveVerbs"), *rule("hiNumbers"), *rule("teNumbers"),
             *[f for v in rule("hiDirections").values() for f in v], *[f for v in rule("teDirections").values() for f in v]]
    assert tables["Plan.INDIC_MOVE_WORDS"] == {w for p in indic for w in p.split(" ")}
    assert tables["Plan.FORWARD_WORDS"] == set(rule("enDirections")["FORWARD"] + rule("hiDirections")["FORWARD"]
                                               + rule("teDirections")["FORWARD"])
    numerals = {"en": set(rule("enOnes")) | set(rule("enTens")), "hi": set(rule("hiNumbers")), "te": set(rule("teNumbers"))}
    assert tables["Plan.NUMERAL_WORDS"] == numerals
    light = dart.regex_source(src, "_lightVerb")
    assert "कर(?:वा)?" in light and "(?:" + "|".join(tables["Plan.LIGHT_VERB_AUXILIARIES"]) + ")" in light
    assert tables["Plan.LIGHT_VERB_STEMS"] == ["कर", "करवा"] and tables["Plan.LIGHT_VERB_REPLACEMENT"] == "करो"
    assert [(c.word, c.after, c.before) for c in tables["Plan.CONTEXT_FILLERS"]] == [
        ("से", {"धीरे", "फिर"}, set()), ("फिर", set(), {"से"}),
        ("ले", set(), set(rule("hiMoveVerbs"))), ("चले", set(), set(rule("hiMoveVerbs")))]
    assert "{'धीरे', 'फिर'}" in src and "'ले' || 'चले'" in src and "words[i + 1] == 'से'" in src
    correction = dart.regex_source(src, "_correctionPhrase")
    assert correction == r"(?:^| )i meant? |^make (?:it|that) "
    assert tables["Plan.CORRECTION_PHRASES_ANYWHERE"] == ["i mean", "i meant"]
    assert tables["Plan.CORRECTION_PHRASES_LEADING"] == ["make it", "make that"]
    assert tables["Plan.STATUS_WORDS"] == {"status", "telemetry"} and "{'status', 'telemetry'}" in src
    assert tables["Plan.BEFORE_DIRECTION"] == {"to", "toward", "towards", "your"}
    assert "{'to', 'toward', 'towards', 'your'}" in src and "{'the', 'your'}" in src
    assert tables["Plan.BEFORE_DIRECTION_SKIP"] == {"the", "your"}
    assert tables["Plan.RIGHT_HERE_FOLLOWERS"] == {"here", "now"} and "{'here', 'now'}" in src
    assert tables["Plan.SLASH_UNITS"] == ["sec", "min", "hour", "hr"] and "(?!sec|min|hour|hr)" in src
    assert dart.regex_source(src, "_wakePhrase") == r"\bhey[\s,:;.!?…।]+drone\b"
    assert tables["Plan.WAKE_GREETINGS"] == ["hey"] and tables["Plan.WAKE_NAMES"] == ["drone"]
    assert tables["Plan.SCRIPT_TIE_ORDER"] == ["te", "hi", "ta", "kn", "ml"]


@needs_dart
def test_transcript_tables_match_dart(tables):
    src = _src(TRANSCRIPT)
    assert tables["Transcript.NEGATORS"] == set(dart.declaration(src, "_negators"))
    assert "RegExp(r'" + "|".join(tables["Transcript.FUSED_NEGATORS"]) + "')" in src
    english = "|".join(tables["Transcript.NEGATION_WORDS"])
    apostrophes = "".join(tables["Transcript.NEGATION_APOSTROPHES"])
    assert f'RegExp(r"\\b(?:{english}|\\w*n[{apostrophes}]t)\\b")' in src
    numbers = dict(tables["Transcript.NUMBERS"])
    assert numbers == {w: i + 1 for i, w in enumerate("one two three four five six seven eight nine ten".split())}
    assert "(?:" + "|".join(tables["Transcript.NUMBER_PRECEDERS"]) + r")\s+)(" + "|".join(numbers) + ")" in src
    assert tables["Transcript.SPELLING"] == {"metres": "meters", "metre": "meter"}
    assert r"RegExp(r'\bmetres\b'), 'meters'" in src and r"RegExp(r'\bmetre\b'), 'meter'" in src
    assert "const ['hover', 'hold position'].contains(value)" in src
    assert tables["Transcript.HOLD_ALIASES"] == ["hover", "hold position"]
    assert "const ['fly', 'take off', 'launch'].contains(value)" in src
    assert tables["Transcript.BARE_TAKEOFF"] == ["fly", "take off", "launch"]
    assert tables["Transcript.MISHEARINGS"] == {"headrooms slide": "Hey drone, fly"}
    assert "headrooms slide" in src and "suggestion: 'Hey drone, fly'" in src
    ranges = tables["Transcript.FOREIGN_SCRIPTS"]["te"]
    assert ranges == [(0x0600, 0x06FF), (0x0750, 0x077F), (0x08A0, 0x08FF), (0xFB50, 0xFDFF), (0xFE70, 0xFEFF)]
    assert "[\\u0600-\\u06ff\\u0750-\\u077f\\u08a0-\\u08ff\\ufb50-\\ufdff\\ufe70-\\ufeff]" in src


needs_edge = pytest.mark.skipif(not EDGE_INTENT.exists(), reason="edge/app/intent.py removed")


@needs_edge
@pytest.mark.parametrize("lang", ["ta", "kn", "ml"])
def test_ta_kn_ml_rule_tables_match_the_edge_reference(tables, lang):
    spec = importlib.util.spec_from_file_location("edge_intent_ref", EDGE_INTENT)
    edge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(edge)
    up = lang.upper()
    assert _lex(tables, lang, "moveVerbs") == list(getattr(edge, f"{up}_MOVE_VERBS"))
    assert _upper_keys(_lex(tables, lang, "directions")) == {k: list(v) for k, v in getattr(edge, f"{up}_DIRECTIONS").items()}
    assert _lex(tables, lang, "units") == list(getattr(edge, f"{up}_UNITS"))
    assert _lex(tables, lang, "commands") == [(c, list(p)) for c, p in getattr(edge, f"{up}_SIMPLE")]
    for field, name in [("negators", "NEGATORS"), ("negatorSuffixes", "NEGATOR_SUFFIXES"), ("minusWords", "MINUS_WORDS"),
                        ("otherUnits", "OTHER_UNITS"), ("scaleWords", "SCALE_WORDS")]:
        assert _lex(tables, lang, field) == list(getattr(edge, f"{up}_{name}")), field
    assert list(_lex(tables, lang, "numbers").items()) == list(getattr(edge, f"{up}_NUMBERS").items())
    assert set(_lex(tables, lang, "tensValues")) == getattr(edge, f"{up}_TENS")
    assert list(_lex(tables, lang, "units19").items()) == list(getattr(edge, f"{up}_UNITS_1_9").items())


LANGUAGE_CODES = ROOT / "app/lib/features/language/data/language_codes.dart"


@pytest.mark.skipif(not LANGUAGE_CODES.exists(), reason="language_codes.dart removed")
def test_language_names_match_dart(grammar):
    src = _src(LANGUAGE_CODES)
    names = {code: lex.name for code, lex in grammar.lexicons.items()}
    assert dart.declaration(src, "languageNames") == names
    # The spoken-name aliases are the lower-case names (Lexicon.name in Kotlin).
    assert dart.declaration(src, "languageAliases") == {name.lower(): code for code, name in names.items()}


def test_readme_documents_every_section_the_sources_cite():
    readme = (gen.GRAMMAR_DIR / "README.md").read_text(encoding="utf-8")
    headings = {line.lstrip("#").strip() for line in readme.splitlines() if line.startswith("#")}
    sources = [gen.GRAMMAR_DIR / "intents.v1.yaml", HERE / "gen.py",
               *sorted((gen.GRAMMAR_DIR / "lexicon").glob("*.yaml"))]
    cited = set()
    for path in sources:
        text = re.sub(r"\s*\n\s*(?:#|//|\*)?\s*", " ", path.read_text(encoding="utf-8"))  # join wrapped comments
        cited |= set(re.findall(r'README "([^"]+)"', text))
    assert cited, "no README citations found"
    assert not cited - headings, sorted(cited - headings)


def test_all_grammar_strings_are_nfc(grammar):
    for lex in grammar.lexicons.values():
        for e in lex.entries():
            assert unicodedata.normalize("NFC", e.word) == e.word, e
