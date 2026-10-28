"""Command phrases for the Phase 0 engine bench, per language.

Words come from the rule-parser lexicons (app/lib/features/intent/data/
rule_intent_engine.dart for en/hi/te, edge/app/intent.py for the PROVISIONAL
ta/kn/ml tables; test_bench.py checks every phrase against that parser). Numbers
are spelled as words so the TTS speaks the lexicon form. Indic word orders
follow the parser tests ("3 మీటర్లు టేకాఫ్", "2 मीटर आगे जाओ").

`expect` is the reference parse; None means the parser must refuse it (negation).
"""

CORE_IDS = ("takeoff_3", "forward_3", "land", "stop")

# Unicode blocks of each Indic script (letters and combining signs).
SCRIPTS = {
    "hi": (0x0900, 0x097F),
    "te": (0x0C00, 0x0C7F),
    "ta": (0x0B80, 0x0BFF),
    "kn": (0x0C80, 0x0CFF),
    "ml": (0x0D00, 0x0D7F),
}

_TAKEOFF = {"command": "TAKEOFF"}
_FWD3 = {"command": "MOVE", "parameters": {"direction": "FORWARD", "distance_m": 3}}
_LEFT2 = {"command": "MOVE", "parameters": {"direction": "LEFT", "distance_m": 2}}
_LAND = {"command": "LAND"}
_STOP = {"command": "STOP"}
_HOVER = {"command": "HOVER"}


def _rows(takeoff, forward, left, land, stop, hold, negated):
    return [
        {"id": "takeoff_3", "intent": "takeoff", "text": takeoff, "expect": _TAKEOFF},
        {"id": "forward_3", "intent": "move", "text": forward, "expect": _FWD3},
        {"id": "left_2", "intent": "move", "text": left, "expect": _LEFT2},
        {"id": "land", "intent": "land", "text": land, "expect": _LAND},
        {"id": "stop", "intent": "stop", "text": stop, "expect": _STOP},
        {"id": "hold", "intent": "hold", "text": hold, "expect": _HOVER},
        {"id": "neg_takeoff", "intent": "negated", "text": negated, "expect": None},
    ]


PHRASES = {
    "en": _rows("take off to three metres", "move forward three metres", "go left two metres",
                "land", "stop", "hold", "do not take off"),
    "hi": _rows("टेकऑफ तीन मीटर", "तीन मीटर आगे जाओ", "दो मीटर बाएं जाओ",
                "लैंड", "रुको", "होवर", "टेकऑफ मत करो"),
    "te": _rows("మూడు మీటర్లు టేకాఫ్", "మూడు మీటర్లు ముందుకు వెళ్ళు", "రెండు మీటర్లు ఎడమకు వెళ్ళు",
                "ల్యాండ్", "ఆపు", "హోవర్", "టేకాఫ్ చేయకు"),
    # PROVISIONAL lexicon (edge/app/intent.py, needs native-speaker review).
    "ta": _rows("மூன்று மீட்டர் டேக்ஆஃப்", "மூன்று மீட்டர் முன்னால் செல்", "இரண்டு மீட்டர் இடது செல்",
                "தரையிறங்கு", "நிறுத்து", "ஹோவர்", "டேக்ஆஃப் வேண்டாம்"),
    "kn": _rows("ಮೂರು ಮೀಟರ್ ಟೇಕಾಫ್", "ಮೂರು ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "ಎರಡು ಮೀಟರ್ ಎಡಕ್ಕೆ ಹೋಗು",
                "ಲ್ಯಾಂಡ್", "ನಿಲ್ಲಿಸು", "ಹೋವರ್", "ಟೇಕಾಫ್ ಬೇಡ"),
    "ml": _rows("മൂന്ന് മീറ്റർ ടേക്ക്ഓഫ്", "മൂന്ന് മീറ്റർ മുന്നോട്ട് പോകൂ", "രണ്ട് മീറ്റർ ഇടത്തേക്ക് പോകൂ",
                "ലാൻഡ്", "നിർത്തൂ", "ഹോവർ", "ടേക്ക്ഓഫ് വേണ്ട"),
}

# eSpeak NG voices per language: the default voice and a female variant.
VOICES = {
    "en": ("en-us", "en+f3"),
    "hi": ("hi", "hi+f3"),
    "te": ("te", "te+f3"),
    "ta": ("ta", "ta+f3"),
    "kn": ("kn", "kn+f3"),
    "ml": ("ml", "ml+f3"),
}

# English stop words for the sherpa keyword spotter (spec 4.12 / 7.5).
KWS_KEYWORDS = ("stop", "hold")

# --- Vosk bench grammars (en-IN, hi, te: the languages with a Vosk model) ------
# A bench grammar, not the production one (T9 generates that from the canonical
# lexicon): numbers 1-10 as in the first host experiment, every bench phrase,
# negation phrases, and the legacy en phrases of that experiment (return home,
# status, drone stop, come down N, bare "forward N meters").

_EN_NUMBERS = "one two three four five six seven eight nine ten".split()
_EN_DIRS = "forward back backward left right up down".split()
_EN_UNITS = ("meter", "meters")  # the en-in model has no "metre"/"metres"
_HI_NUMBERS = "एक दो तीन चार पांच छह सात आठ नौ दस".split()
_HI_DIRS = "आगे पीछे बाएं दाएं ऊपर नीचे".split()
_TE_NUMBERS = "ఒకటి రెండు మూడు నాలుగు ఐదు ఆరు ఏడు ఎనిమిది తొమ్మిది పది".split()
_TE_DIRS = "ముందుకు వెనక్కి ఎడమకు కుడికి పైకి కిందికి".split()
_TE_UNITS = ("మీటర్లు", "మీటర్")

# Common English words (not command words) used as single-word fillers so
# out-of-grammar speech has somewhere to go (spec 4.6, vosk_filler experiment).
EN_FILLERS = """the be to of and a in that have i it for on with he as you at this but his by from
they we say her she or an will my all would there their what so if about who get which when make can
like time just him know people into year your good some could them see other than then now look only
its over think also after use how our work first well way even new want because any these give day most
us is was are were been has had did said made find where much too very still own while such here why
place again few those always show part against thing small same great big high every between under
last city world home water room mother area money story fact month lot study book eye job word business
issue side kind head house service friend father power hour game line end member law car name president
team minute idea kid body information school face others level office door health person art war history
party result change morning reason research girl guy moment air teacher force education photo field
weather today picture camera video music phone call open close light please thanks okay yes hello hi""".split()


def _en_grammar() -> list:
    simple = ["stop", "hold", "hold position", "hover", "land", "take off", "takeoff", "launch",
              "status", "return home", "drone stop"]
    out = list(simple)
    for n in _EN_NUMBERS:
        for u in _EN_UNITS:
            out += [f"take off to {n} {u}", f"take off {n} {u}", f"come down {n} {u}"]
            for d in _EN_DIRS:
                out += [f"{v} {d} {n} {u}" for v in ("move", "go", "fly")] + [f"{d} {n} {u}"]
    for c in ("take off", "land", "move", "fly", "hover"):
        out += [f"do not {c}", f"don't {c}", f"never {c}"]
    return out


def _hi_grammar() -> list:
    out = ["रुको", "रुक जाओ", "लैंड", "लैंड करो", "उतरो", "होवर", "टेकऑफ", "टेकऑफ करो"]
    for n in _HI_NUMBERS:
        out += [f"टेकऑफ {n} मीटर", f"{n} मीटर टेकऑफ करो"]
        out += [f"{n} मीटर {d} {v}" for d in _HI_DIRS for v in ("जाओ", "चलो")]
    out += [f"{c} मत करो" for c in ("टेकऑफ", "लैंड", "होवर")]
    return out


def _te_grammar() -> list:
    simple = ["ఆపు", "ఆగు", "స్టాప్", "టేకాఫ్", "ల్యాండ్", "దిగు", "హోవర్"]
    out = list(simple)
    for n in _TE_NUMBERS:
        for u in _TE_UNITS:
            out.append(f"{n} {u} టేకాఫ్")
            out += [f"{n} {u} {d} {v}" for d in _TE_DIRS for v in ("వెళ్ళు", "వెళ్లు")]
    out += [f"{c} {neg}" for c in ("టేకాఫ్", "ల్యాండ్", "హోవర్") for neg in ("చేయకు", "వద్దు")]
    return out


_GRAMMARS = {"en": _en_grammar, "hi": _hi_grammar, "te": _te_grammar}


def vosk_grammar(lang: str, fillers: bool = False) -> list:
    """Phrase list for a Vosk grammar recognizer, ending with [unk]."""
    out = _GRAMMARS[lang]()
    if fillers:
        command_words = {w for p in out for w in p.split()}
        out += [w for w in dict.fromkeys(EN_FILLERS) if w not in command_words]
    return out + ["[unk]"]


_VOSK_SPELLING = {"en": {"metres": "meters", "metre": "meter"}}


def vosk_spelling(lang: str, text: str) -> str:
    """A bench phrase as the Vosk model spells it (en-in knows only "meters")."""
    table = _VOSK_SPELLING.get(lang, {})
    return " ".join(table.get(w, w) for w in text.split())
