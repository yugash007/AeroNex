"""Rule-based intent: transcript + language -> COMMAND payload (reference).

Pure function with no imports from the edge server. Returned dicts plug
directly into the Task 2 COMMAND payload shape::

    {"command", "parameters", "confidence"}

Confidence: 0.9 for exact canonical phrasing, 0.7 fallback when the
distance uses numeral words or extra filler words surround the command.

Input that does not map to exactly one command raises IntentError rather
than being guessed: negated or compound utterances, non-metre units, spoken
minus signs, and number forms the tables would truncate ("two hundred").

The ``*_DIRECTIONS`` / ``*_VERBS`` / ``*_TAKEOFF`` ... tables are kept
explicit and flat so the Flutter side (Task 4) can mirror them in Dart.
"""

from __future__ import annotations

import re
import unicodedata

__all__ = [
    "SUPPORTED",
    "STUBBED",
    "AUTO_HINTS",
    "IntentError",
    "UnsupportedLanguageError",
    "detect_language",
    "parse_transcript",
]

SUPPORTED = ("en", "hi", "te", "ta", "kn", "ml")
STUBBED = ()

# Language-hint values that resolve via detect_language() instead of
# selecting a vocabulary table directly.
AUTO_HINTS = ("auto", "")

# The grammar's numeral range (spec 7.4: whole numbers 0-100). The parser
# reads what was said; the safety envelope (moves of at most 10 m, takeoff to
# 1-10 m, README "Safety model") is enforced where commands run:
# edge.app.commands here, the app's validator on the phone.
MAX_DISTANCE_M = 100
MAX_TAKEOFF_ALTITUDE_M = 100

EXACT_CONFIDENCE = 0.9
FALLBACK_CONFIDENCE = 0.7


class IntentError(Exception):
    """Transcript could not be mapped to a command."""


class UnsupportedLanguageError(IntentError):
    """Language code outside SUPPORTED (unknown or alias miss)."""


# ---------------------------------------------------------------------------
# Vocabulary tables (canonical value -> spoken forms)
# ---------------------------------------------------------------------------

EN_MOVE_VERBS = (
    "move",
    "moves",
    "moving",
    "go",
    "goes",
    "going",
    "fly",
    "flies",
    "flying",
)
TE_MOVE_VERBS = (
    "వెళ్ళు",
    "వెళ్లు",
    "వెళ్ళండి",
    "వెళ్లండి",
    "పంపు",
    "పంపండి",
    "కదులు",
)
HI_MOVE_VERBS = ("जाओ", "जाइए", "जाइये", "चलो", "बढ़ो")

EN_DIRECTIONS = {
    "FORWARD": ("forward", "forwards", "ahead"),
    "BACKWARD": ("backward", "backwards", "back"),
    "LEFT": ("left",),
    "RIGHT": ("right",),
    "UP": ("up", "upward", "upwards"),
    "DOWN": ("down", "downward", "downwards"),
}
TE_DIRECTIONS = {
    "FORWARD": ("ముందుకు",),
    "BACKWARD": ("వెనక్కి", "వెనుకకు"),
    "LEFT": ("ఎడమకు", "ఎడమవైపుకు", "ఎడమ వైపుకు", "ఎడమ"),
    "RIGHT": ("కుడికి", "కుడివైపుకు", "కుడి వైపుకు", "కుడి"),
    "UP": ("పైకి",),
    "DOWN": ("కిందికి", "క్రిందికి"),
}
HI_DIRECTIONS = {
    "FORWARD": ("आगे",),
    "BACKWARD": ("पीछे",),
    "LEFT": ("बाएं", "बाएँ", "बायें"),
    "RIGHT": ("दाएं", "दाएँ", "दायें"),
    "UP": ("ऊपर",),
    "DOWN": ("नीचे",),
}

EN_UNITS = ("meters", "meter", "metres", "metre")
TE_UNITS = ("మీటర్లు", "మీటర్ల", "మీటరు", "మీటర్")
HI_UNITS = ("मीटर",)

# Non-MOVE commands. A transcript naming two of them is rejected, so the order
# only decides which phrase of one command matches first (and its confidence).
EN_SIMPLE = (
    ("STOP", ("stop",)),
    ("TAKEOFF", ("take off", "takeoff", "launch")),
    ("LAND", ("land",)),
    ("HOVER", ("hover", "hold position", "hold")),
)
TE_SIMPLE = (
    ("STOP", ("ఆపు", "ఆగు", "స్టాప్")),
    ("TAKEOFF", ("టేకాఫ్", "పైకి లే")),
    ("LAND", ("ల్యాండ్", "దిగు")),
    ("HOVER", ("హోవర్",)),
)
HI_SIMPLE = (
    ("STOP", ("रुको", "रुक जाओ", "स्टॉप")),
    ("TAKEOFF", ("टेकऑफ़", "टेकऑफ", "उड़ान भरो", "ऊपर उठो")),
    ("LAND", ("लैंड", "उतरो")),
    ("HOVER", ("हॉवर", "होवर")),
)

# PROVISIONAL (2026-10-07): needs native-speaker review.
TA_MOVE_VERBS = (
    "செல்",
    "செல்லு",
    "போ",
    "பற",
    "நகர்",
)
# PROVISIONAL (2026-10-07): needs native-speaker review.
KN_MOVE_VERBS = (
    "ಹೋಗು",
    "ಹಾರು",
    "ಚಲಿಸು",
    "ನಡೆ",
)
# PROVISIONAL (2026-10-07): needs native-speaker review.
ML_MOVE_VERBS = (
    "പോകൂ",
    "പോകുക",
    "പറക്കൂ",
    "നീങ്ങൂ",
)

# PROVISIONAL (2026-10-07): needs native-speaker review.
TA_DIRECTIONS = {
    "FORWARD": ("முன்னால்", "முன்னோக்கி"),
    "BACKWARD": ("பின்னால்", "பின்னோக்கி"),
    "LEFT": ("இடதுபுறம்", "இடது"),
    "RIGHT": ("வலதுபுறம்", "வலது"),
    "UP": ("மேலே", "மேல்நோக்கி"),
    "DOWN": ("கீழே", "கீழ்நோக்கி"),
}
# PROVISIONAL (2026-10-07): needs native-speaker review.
KN_DIRECTIONS = {
    "FORWARD": ("ಮುಂದೆ", "ಮುಂದಕ್ಕೆ"),
    "BACKWARD": ("ಹಿಂದೆ", "ಹಿಂದಕ್ಕೆ"),
    "LEFT": ("ಎಡಕ್ಕೆ", "ಎಡ"),
    "RIGHT": ("ಬಲಕ್ಕೆ", "ಬಲ"),
    "UP": ("ಮೇಲೆ", "ಮೇಲಕ್ಕೆ"),
    "DOWN": ("ಕೆಳಗೆ", "ಕೆಳಕ್ಕೆ"),
}
# PROVISIONAL (2026-10-07): needs native-speaker review.
ML_DIRECTIONS = {
    "FORWARD": ("മുന്നോട്ട്", "മുന്നിലേക്ക്"),
    "BACKWARD": ("പിന്നോട്ട്", "പിന്നിലേക്ക്"),
    "LEFT": ("ഇടത്തേക്ക്", "ഇടത്"),
    "RIGHT": ("വലത്തേക്ക്", "വലത്"),
    "UP": ("മുകളിലേക്ക്", "മുകളിൽ"),
    "DOWN": ("താഴേക്ക്", "താഴെ"),
}

# PROVISIONAL (2026-10-07): needs native-speaker review.
TA_UNITS = ("மீட்டர்", "மீட்டர்கள்", "மீ")
# PROVISIONAL (2026-10-07): needs native-speaker review.
KN_UNITS = ("ಮೀಟರ್", "ಮೀಟರ್‌ಗಳು", "ಮೀ")
# PROVISIONAL (2026-10-07): needs native-speaker review.
ML_UNITS = ("മീറ്റർ", "മീറ്ററുകൾ", "മീ")

# Non-MOVE commands; order as for EN_SIMPLE.
# PROVISIONAL (2026-10-07): needs native-speaker review.
TA_SIMPLE = (
    ("STOP", ("நில்", "நிறுத்து", "ஸ்டாப்")),
    ("TAKEOFF", ("டேக்ஆஃப்", "புறப்படு", "மேலே எழு")),
    ("LAND", ("தரையிறங்கு", "லேண்ட்")),
    ("HOVER", ("ஹோவர்",)),
)
# PROVISIONAL (2026-10-07): needs native-speaker review.
KN_SIMPLE = (
    ("STOP", ("ನಿಲ್ಲು", "ನಿಲ್ಲಿಸು", "ಸ್ಟಾಪ್")),
    ("TAKEOFF", ("ಟೇಕಾಫ್", "ಮೇಲಕ್ಕೆ ಏರು")),
    ("LAND", ("ಇಳಿ", "ಲ್ಯಾಂಡ್")),
    ("HOVER", ("ಹೋವರ್",)),
)
# PROVISIONAL (2026-10-07): needs native-speaker review.
ML_SIMPLE = (
    ("STOP", ("നിർത്തൂ", "നിൽക്കൂ", "സ്റ്റോപ്പ്")),
    ("TAKEOFF", ("ടേക്ക്ഓഫ്", "മുകളിലേക്ക് പൊങ്ങൂ")),
    ("LAND", ("ഇറങ്ങൂ", "ലാൻഡ്")),
    ("HOVER", ("ഹോവർ",)),
)

# Reject-only tables: a match raises IntentError and never yields a command,
# so an unreviewed entry can only cause a refusal. The English forms are
# checked for every language (ASR keeps Latin words such as "cm" in Indic text).

# Negation markers, whole tokens: "do not take off" must never be TAKEOFF.
EN_NEGATORS = (
    "not",
    "no",
    "never",
    "dont",
    "don't",
    "don\u2019t",
    "cannot",
    "cant",
    "can't",
    "can\u2019t",
    "wont",
    "shouldnt",
    "wouldnt",
    "couldnt",
    "mustnt",
    "didnt",
    "doesnt",
    "isnt",
    "arent",
    "wasnt",
    "werent",
    "hasnt",
    "havent",
)
# PROVISIONAL (2026-10-10): needs native-speaker review.
HI_NEGATORS = ("मत", "नहीं", "नही", "ना", "न")
# PROVISIONAL (2026-10-10): needs native-speaker review.
TE_NEGATORS = (
    "వద్దు",
    "చేయకు",
    "చేయకండి",
    "చెయ్యకు",
    "చెయ్యకండి",
    "కాదు",
    "ఎగరవద్దు",
    "ఎగరకు",
    "లేదు",
)
# PROVISIONAL (2026-10-10): needs native-speaker review.
TA_NEGATORS = ("வேண்டாம்", "இல்லை")
# PROVISIONAL (2026-10-10): needs native-speaker review.
KN_NEGATORS = ("ಬೇಡ", "ಇಲ್ಲ")
# PROVISIONAL (2026-10-10): needs native-speaker review.
ML_NEGATORS = ("വേണ്ട", "ഇല്ല")

# Negation fused onto the word before it ("won't", "చేయవద్దు"): any token
# ending in one of these is a negator.
EN_NEGATOR_SUFFIXES = ("n't", "n\u2019t", "n\u2018t", "n\u02bct", "n\uff07t")
HI_NEGATOR_SUFFIXES = ()
# PROVISIONAL (2026-10-10): needs native-speaker review.
TE_NEGATOR_SUFFIXES = ("వద్దు", "వద్దండి", "ొద్దు", "ొద్దండి")
# PROVISIONAL (2026-10-10): needs native-speaker review.
TA_NEGATOR_SUFFIXES = ("வேண்டாம்", "கூடாது")
# PROVISIONAL (2026-10-10): needs native-speaker review.
KN_NEGATOR_SUFFIXES = ("ಬೇಡ", "ಬೇಡಿ")
# PROVISIONAL (2026-10-10): needs native-speaker review.
ML_NEGATOR_SUFFIXES = ("രുത്",)

# Spoken minus signs: a negative distance is rejected, never made positive.
EN_MINUS_WORDS = ("minus", "negative")
# PROVISIONAL (2026-10-10): needs native-speaker review.
HI_MINUS_WORDS = ("माइनस", "ऋण")
# PROVISIONAL (2026-10-10): needs native-speaker review.
TE_MINUS_WORDS = ("మైనస్",)
# PROVISIONAL (2026-10-10): needs native-speaker review.
TA_MINUS_WORDS = ("மைனஸ்",)
# PROVISIONAL (2026-10-10): needs native-speaker review.
KN_MINUS_WORDS = ("ಮೈನಸ್",)
# PROVISIONAL (2026-10-10): needs native-speaker review.
ML_MINUS_WORDS = ("മൈനസ്",)

# Explicit non-metre units: "50 cm" is never read as 50 m. Bare "m" and the
# metre words stay accepted; "in" is left out (ordinary filler).
EN_OTHER_UNITS = (
    "cm",
    "cms",
    "centimeter",
    "centimeters",
    "centimetre",
    "centimetres",
    "centi",
    "mm",
    "millimeter",
    "millimeters",
    "millimetre",
    "millimetres",
    "milli",
    "km",
    "kms",
    "kilometer",
    "kilometers",
    "kilometre",
    "kilometres",
    "kilo",
    "ft",
    "foot",
    "feet",
    "inch",
    "inches",
    "yd",
    "yard",
    "yards",
    "mile",
    "miles",
)
# PROVISIONAL (2026-10-10): needs native-speaker review. Split prefixes
# ("सेंटी मीटर") are listed so the trailing metre word cannot pass alone.
HI_OTHER_UNITS = (
    "सेंटीमीटर",
    "मिलीमीटर",
    "किलोमीटर",
    "सेंटी",
    "मिली",
    "किलो",
    "फुट",
    "फीट",
    "इंच",
    "गज",
    "मील",
)
# PROVISIONAL (2026-10-10): needs native-speaker review.
TE_OTHER_UNITS = (
    "సెంటీమీటర్లు",
    "సెంటీమీటర్",
    "మిల్లీమీటర్లు",
    "మిల్లీమీటర్",
    "కిలోమీటర్లు",
    "కిలోమీటర్",
    "సెంటీ",
    "మిల్లీ",
    "కిలో",
    "అడుగులు",
    "అడుగు",
    "అంగుళాలు",
    "అంగుళం",
    "గజాలు",
    "మైళ్ళు",
    "మైలు",
)
# PROVISIONAL (2026-10-10): needs native-speaker review.
TA_OTHER_UNITS = (
    "சென்டிமீட்டர்",
    "மில்லிமீட்டர்",
    "கிலோமீட்டர்",
    "சென்டி",
    "மில்லி",
    "கிலோ",
    "அடி",
    "அங்குலம்",
    "மைல்",
)
# PROVISIONAL (2026-10-10): needs native-speaker review.
KN_OTHER_UNITS = (
    "ಸೆಂಟಿಮೀಟರ್",
    "ಮಿಲಿಮೀಟರ್",
    "ಕಿಲೋಮೀಟರ್",
    "ಸೆಂಟಿ",
    "ಮಿಲಿ",
    "ಕಿಲೋ",
    "ಅಡಿ",
    "ಇಂಚು",
    "ಮೈಲಿ",
)
# PROVISIONAL (2026-10-10): needs native-speaker review.
ML_OTHER_UNITS = (
    "സെന്റിമീറ്റർ",
    "മില്ലിമീറ്റർ",
    "കിലോമീറ്റർ",
    "സെന്റി",
    "മില്ലി",
    "കിലോ",
    "അടി",
    "ഇഞ്ച്",
    "മൈൽ",
)

_PER_LANG = {
    "en": {
        "verbs": EN_MOVE_VERBS,
        "directions": EN_DIRECTIONS,
        "units": EN_UNITS,
        "simple": EN_SIMPLE,
        "negators": EN_NEGATORS,
        "negator_suffixes": EN_NEGATOR_SUFFIXES,
        "minus": EN_MINUS_WORDS,
        "other_units": EN_OTHER_UNITS,
    },
    "te": {
        "verbs": TE_MOVE_VERBS,
        "directions": TE_DIRECTIONS,
        "units": TE_UNITS,
        "simple": TE_SIMPLE,
        "negators": TE_NEGATORS,
        "negator_suffixes": TE_NEGATOR_SUFFIXES,
        "minus": TE_MINUS_WORDS,
        "other_units": TE_OTHER_UNITS,
    },
    "hi": {
        "verbs": HI_MOVE_VERBS,
        "directions": HI_DIRECTIONS,
        "units": HI_UNITS,
        "simple": HI_SIMPLE,
        "negators": HI_NEGATORS,
        "negator_suffixes": HI_NEGATOR_SUFFIXES,
        "minus": HI_MINUS_WORDS,
        "other_units": HI_OTHER_UNITS,
    },
    "ta": {
        "verbs": TA_MOVE_VERBS,
        "directions": TA_DIRECTIONS,
        "units": TA_UNITS,
        "simple": TA_SIMPLE,
        "negators": TA_NEGATORS,
        "negator_suffixes": TA_NEGATOR_SUFFIXES,
        "minus": TA_MINUS_WORDS,
        "other_units": TA_OTHER_UNITS,
    },
    "kn": {
        "verbs": KN_MOVE_VERBS,
        "directions": KN_DIRECTIONS,
        "units": KN_UNITS,
        "simple": KN_SIMPLE,
        "negators": KN_NEGATORS,
        "negator_suffixes": KN_NEGATOR_SUFFIXES,
        "minus": KN_MINUS_WORDS,
        "other_units": KN_OTHER_UNITS,
    },
    "ml": {
        "verbs": ML_MOVE_VERBS,
        "directions": ML_DIRECTIONS,
        "units": ML_UNITS,
        "simple": ML_SIMPLE,
        "negators": ML_NEGATORS,
        "negator_suffixes": ML_NEGATOR_SUFFIXES,
        "minus": ML_MINUS_WORDS,
        "other_units": ML_OTHER_UNITS,
    },
}

_LANGUAGE_ALIASES = {
    "english": "en",
    "hindi": "hi",
    "telugu": "te",
    "tamil": "ta",
    "kannada": "kn",
    "malayalam": "ml",
}

# ---------------------------------------------------------------------------
# Numerals: digits + words for 1-100
# ---------------------------------------------------------------------------

_NATIVE_DIGITS = {
    **{c: str(i) for i, c in enumerate("०१२३४५६७८९")},  # Devanagari
    **{c: str(i) for i, c in enumerate("౦౧౨౩౪౫౬౭౮౯")},  # Telugu
    # PROVISIONAL (2026-10-07): needs native-speaker review.
    **{c: str(i) for i, c in enumerate("௦௧௨௩௪௫௬௭௮௯")},  # Tamil U+0BE6-U+0BEF
    # PROVISIONAL (2026-10-07): needs native-speaker review.
    **{c: str(i) for i, c in enumerate("೦೧೨೩೪೫೬೭೮೯")},  # Kannada U+0CE6-U+0CEF
    # PROVISIONAL (2026-10-07): needs native-speaker review.
    **{c: str(i) for i, c in enumerate("൦൧൨൩൪൫൬൭൮൯")},  # Malayalam U+0D66-U+0D6F
}

EN_ONES = {
    "zero": 0,
    "one": 1,
    "two": 2,
    "three": 3,
    "four": 4,
    "five": 5,
    "six": 6,
    "seven": 7,
    "eight": 8,
    "nine": 9,
    "ten": 10,
    "eleven": 11,
    "twelve": 12,
    "thirteen": 13,
    "fourteen": 14,
    "fifteen": 15,
    "sixteen": 16,
    "seventeen": 17,
    "eighteen": 18,
    "nineteen": 19,
}
EN_TENS = {
    "twenty": 20,
    "thirty": 30,
    "forty": 40,
    "fifty": 50,
    "sixty": 60,
    "seventy": 70,
    "eighty": 80,
    "ninety": 90,
}

TE_NUMBERS = {
    "ఒక": 1,
    "ఒకటి": 1,
    "రెండు": 2,
    "మూడు": 3,
    "నాలుగు": 4,
    "ఐదు": 5,
    "ఆరు": 6,
    "ఏడు": 7,
    "ఎనిమిది": 8,
    "తొమ్మిది": 9,
    "పది": 10,
    "పదకొండు": 11,
    "పన్నెండు": 12,
    "పదమూడు": 13,
    "పద్నాలుగు": 14,
    "పదిహేను": 15,
    "పదహారు": 16,
    "పదిహేడు": 17,
    "పద్దెనిమిది": 18,
    "పందొమ్మిది": 19,
    "ఇరవై": 20,
    "ముప్పై": 30,
    "నలభై": 40,
    "యాభై": 50,
    "అరవై": 60,
    "డెబ్బై": 70,
    "ఎనభై": 80,
    "తొంభై": 90,
    "వంద": 100,
    "నూరు": 100,
}
TE_TENS = {20, 30, 40, 50, 60, 70, 80, 90}
TE_UNITS_1_9 = {
    "ఒక": 1,
    "ఒకటి": 1,
    "రెండు": 2,
    "మూడు": 3,
    "నాలుగు": 4,
    "ఐదు": 5,
    "ఆరు": 6,
    "ఏడు": 7,
    "ఎనిమిది": 8,
    "తొమ్మిది": 9,
}

HI_NUMBERS = {
    "एक": 1,
    "दो": 2,
    "तीन": 3,
    "चार": 4,
    "पाँच": 5,
    "पांच": 5,
    "छह": 6,
    "छः": 6,
    "सात": 7,
    "आठ": 8,
    "नौ": 9,
    "दस": 10,
    "ग्यारह": 11,
    "बारह": 12,
    "तेरह": 13,
    "चौदह": 14,
    "पंद्रह": 15,
    "सोलह": 16,
    "सत्रह": 17,
    "अठारह": 18,
    "उन्नीस": 19,
    "बीस": 20,
    "इक्कीस": 21,
    "बाईस": 22,
    "तेईस": 23,
    "चौबीस": 24,
    "पच्चीस": 25,
    "छब्बीस": 26,
    "सत्ताईस": 27,
    "अट्ठाईस": 28,
    "उनतीस": 29,
    "तीस": 30,
    "इकतीस": 31,
    "बत्तीस": 32,
    "तैंतीस": 33,
    "चौंतीस": 34,
    "पैंतीस": 35,
    "छत्तीस": 36,
    "सैंतीस": 37,
    "अड़तीस": 38,
    "उनतालीस": 39,
    "चालीस": 40,
    "इकतालीस": 41,
    "बयालीस": 42,
    "तैंतालीस": 43,
    "चवालीस": 44,
    "पैंतालीस": 45,
    "छियालीस": 46,
    "सैंतालीस": 47,
    "अड़तालीस": 48,
    "उनचास": 49,
    "पचास": 50,
    "इक्यावन": 51,
    "बावन": 52,
    "तिरपन": 53,
    "चौवन": 54,
    "पचपन": 55,
    "छप्पन": 56,
    "सत्तावन": 57,
    "अट्ठावन": 58,
    "उनसठ": 59,
    "साठ": 60,
    "इकसठ": 61,
    "बासठ": 62,
    "तिरसठ": 63,
    "चौंसठ": 64,
    "पैंसठ": 65,
    "छियासठ": 66,
    "सड़सठ": 67,
    "अड़सठ": 68,
    "उनहत्तर": 69,
    "सत्तर": 70,
    "इकहत्तर": 71,
    "बहत्तर": 72,
    "तिहत्तर": 73,
    "चौहत्तर": 74,
    "पचहत्तर": 75,
    "छिहत्तर": 76,
    "सतहत्तर": 77,
    "अठहत्तर": 78,
    "उन्यासी": 79,
    "अस्सी": 80,
    "इक्यासी": 81,
    "बयासी": 82,
    "तिरासी": 83,
    "चौरासी": 84,
    "पचासी": 85,
    "छियासी": 86,
    "सत्तासी": 87,
    "अट्ठासी": 88,
    "नवासी": 89,
    "नब्बे": 90,
    "इक्यानबे": 91,
    "बानबे": 92,
    "तिरानबे": 93,
    "चौरानबे": 94,
    "पचानबे": 95,
    "छियानबे": 96,
    "सत्तानबे": 97,
    "अट्ठानबे": 98,
    "निन्यानबे": 99,
    "सौ": 100,
}

# PROVISIONAL (2026-10-07): needs native-speaker review.
TA_NUMBERS = {
    "ஒரு": 1,
    "ஒன்று": 1,
    "இரண்டு": 2,
    "மூன்று": 3,
    "நான்கு": 4,
    "ஐந்து": 5,
    "ஆறு": 6,
    "ஏழு": 7,
    "எட்டு": 8,
    "ஒன்பது": 9,
    "பத்து": 10,
    "பதினொன்று": 11,
    "பன்னிரண்டு": 12,
    "பதிமூன்று": 13,
    "பதினான்கு": 14,
    "பதினைந்து": 15,
    "பதினாறு": 16,
    "பதினேழு": 17,
    "பதினெட்டு": 18,
    "பத்தொன்பது": 19,
    "இருபது": 20,
    "இருபத்தி": 20,
    "முப்பது": 30,
    "முப்பத்தி": 30,
    "நாற்பது": 40,
    "நாற்பத்தி": 40,
    "ஐம்பது": 50,
    "ஐம்பத்தி": 50,
    "அறுபது": 60,
    "அறுபத்தி": 60,
    "எழுபது": 70,
    "எழுபத்தி": 70,
    "எண்பது": 80,
    "எண்பத்தி": 80,
    "தொண்ணூறு": 90,
    "தொண்ணூற்றி": 90,
    "நூறு": 100,
}
# PROVISIONAL (2026-10-07): needs native-speaker review.
TA_TENS = {20, 30, 40, 50, 60, 70, 80, 90}
# PROVISIONAL (2026-10-07): needs native-speaker review.
TA_UNITS_1_9 = {
    "ஒரு": 1,
    "ஒன்று": 1,
    "இரண்டு": 2,
    "மூன்று": 3,
    "நான்கு": 4,
    "ஐந்து": 5,
    "ஆறு": 6,
    "ஏழு": 7,
    "எட்டு": 8,
    "ஒன்பது": 9,
}

# PROVISIONAL (2026-10-07): needs native-speaker review.
KN_NUMBERS = {
    "ಒಂದು": 1,
    "ಎರಡು": 2,
    "ಮೂರು": 3,
    "ನಾಲ್ಕು": 4,
    "ಐದು": 5,
    "ಆರು": 6,
    "ಏಳು": 7,
    "ಎಂಟು": 8,
    "ಒಂಬತ್ತು": 9,
    "ಹತ್ತು": 10,
    "ಹನ್ನೊಂದು": 11,
    "ಹನ್ನೆರಡು": 12,
    "ಹದಿಮೂರು": 13,
    "ಹದಿನಾಲ್ಕು": 14,
    "ಹದಿನೈದು": 15,
    "ಹದಿನಾರು": 16,
    "ಹದಿನೇಳು": 17,
    "ಹದಿನೆಂಟು": 18,
    "ಹತ್ತೊಂಬತ್ತು": 19,
    "ಇಪ್ಪತ್ತು": 20,
    "ಇಪ್ಪತ್ತೊಂದು": 21,
    "ಮೂವತ್ತು": 30,
    "ನಲವತ್ತು": 40,
    "ಐವತ್ತು": 50,
    "ಅರವತ್ತು": 60,
    "ಎಪ್ಪತ್ತು": 70,
    "ಎಂಭತ್ತು": 80,
    "ತೊಂಬತ್ತು": 90,
    "ನೂರು": 100,
}
# PROVISIONAL (2026-10-07): needs native-speaker review.
KN_TENS = {20, 30, 40, 50, 60, 70, 80, 90}
# PROVISIONAL (2026-10-07): needs native-speaker review.
KN_UNITS_1_9 = {
    "ಒಂದು": 1,
    "ಎರಡು": 2,
    "ಮೂರು": 3,
    "ನಾಲ್ಕು": 4,
    "ಐದು": 5,
    "ಆರು": 6,
    "ಏಳು": 7,
    "ಎಂಟು": 8,
    "ಒಂಬತ್ತು": 9,
}

# PROVISIONAL (2026-10-07): needs native-speaker review.
ML_NUMBERS = {
    "ഒരു": 1,
    "ഒന്ന്": 1,
    "രണ്ട്": 2,
    "മൂന്ന്": 3,
    "നാല്": 4,
    "അഞ്ച്": 5,
    "ആറ്": 6,
    "ഏഴ്": 7,
    "എട്ട്": 8,
    "ഒമ്പത്": 9,
    "പത്ത്": 10,
    "പതിനൊന്ന്": 11,
    "പന്ത്രണ്ട്": 12,
    "പതിമൂന്ന്": 13,
    "പതിനാല്": 14,
    "പതിനഞ്ച്": 15,
    "പതിനാറ്": 16,
    "പതിനേഴ്": 17,
    "പതിനെട്ട്": 18,
    "പത്തൊമ്പത്": 19,
    "ഇരുപത്": 20,
    "ഇരുപത്തൊന്ന്": 21,
    "മുപ്പത്": 30,
    "നാല്പത്": 40,
    "അമ്പത്": 50,
    "അറുപത്": 60,
    "എഴുപത്": 70,
    "എൺപത്": 80,
    "തൊണ്ണൂറ്": 90,
    "നൂറ്": 100,
}
# PROVISIONAL (2026-10-07): needs native-speaker review.
ML_TENS = {20, 30, 40, 50, 60, 70, 80, 90}
# PROVISIONAL (2026-10-07): needs native-speaker review.
ML_UNITS_1_9 = {
    "ഒരു": 1,
    "ഒന്ന്": 1,
    "രണ്ട്": 2,
    "മൂന്ന്": 3,
    "നാല്": 4,
    "അഞ്ച്": 5,
    "ആറ്": 6,
    "ഏഴ്": 7,
    "എട്ട്": 8,
    "ഒമ്പത്": 9,
}

# Scale, decimal and fraction words the tables cannot compose. Reject-only:
# these and every numeral word above must be consumed by the one matched
# number, otherwise "two hundred" would be read as 2.
EN_SCALE_WORDS = ("hundred", "thousand", "lakh", "million", "point", "half")
# PROVISIONAL (2026-10-10): needs native-speaker review.
HI_SCALE_WORDS = (
    "हज़ार",
    "हजार",
    "लाख",
    "दशमलव",
    "डेढ़",
    "ढाई",
    "साढ़े",
    "सवा",
    "पौने",
)
# PROVISIONAL (2026-10-10): needs native-speaker review.
TE_SCALE_WORDS = ("వందల", "వందలు", "వేయి", "వెయ్యి", "వేల", "వేలు", "దశాంశం", "అర")
# PROVISIONAL (2026-10-10): needs native-speaker review.
TA_SCALE_WORDS = ("நூற்று", "ஆயிரம்", "புள்ளி", "அரை")
# PROVISIONAL (2026-10-10): needs native-speaker review.
KN_SCALE_WORDS = ("ಸಾವಿರ", "ದಶಮಾಂಶ", "ಅರ್ಧ")
# PROVISIONAL (2026-10-10): needs native-speaker review.
ML_SCALE_WORDS = ("ആയിരം", "ദശാംശം", "അര")

_NUMERAL_WORDS = {
    "en": {*EN_ONES, *EN_TENS, *EN_SCALE_WORDS},
    "te": {*TE_NUMBERS, *TE_SCALE_WORDS},
    "hi": {*HI_NUMBERS, *HI_SCALE_WORDS},
    "ta": {*TA_NUMBERS, *TA_SCALE_WORDS},
    "kn": {*KN_NUMBERS, *KN_SCALE_WORDS},
    "ml": {*ML_NUMBERS, *ML_SCALE_WORDS},
}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Script blocks for language detection, in tie-break priority order.
# (code, start, end) — Telugu U+0C00–U+0C7F, Devanagari U+0900–U+097F,
# Tamil U+0B80–U+0BFF, Kannada U+0C80–U+0CFF, Malayalam U+0D00–U+0D7F.
_SCRIPT_BLOCKS = (
    ("te", 0x0C00, 0x0C7F),
    ("hi", 0x0900, 0x097F),
    ("ta", 0x0B80, 0x0BFF),
    ("kn", 0x0C80, 0x0CFF),
    ("ml", 0x0D00, 0x0D7F),
)


def detect_language(text: str) -> str:
    """Return the language code suggested by the script of ``text``.

    Counts characters per Indic Unicode block and picks the max; ties
    resolve to the earliest block in ``_SCRIPT_BLOCKS`` order
    (te, hi, ta, kn, ml). Digits and punctuation do not vote. Returns
    ``"en"`` when no Indic characters are present. Never raises.
    """
    try:
        if not isinstance(text, str) or not text:
            return "en"
        counts = [0] * len(_SCRIPT_BLOCKS)
        for ch in text:
            cp = ord(ch)
            for i, (_, start, end) in enumerate(_SCRIPT_BLOCKS):
                if start <= cp <= end:
                    counts[i] += 1
                    break
        best = max(counts)
        if best == 0:
            return "en"
        return _SCRIPT_BLOCKS[counts.index(best)][0]
    except Exception:
        return "en"


# Hyphen, dash and minus characters, as a regex class body: a sign before a
# digit, or the joiner in a glued unit ("50-cm").
_DASHES = r"\-\u2010-\u2015\u2212\ufe63\uff0d"


def _normalize_digits(text: str) -> str:
    return "".join(_NATIVE_DIGITS.get(c, c) for c in text)


# Apostrophe look-alikes folded to "'", so "don´t", "don`t" and "don′t" are
# "don't". Invisible characters (zero-width space, soft hyphen, word joiner,
# BOM) are dropped, so "no\u200bt" is "not". ZWNJ and ZWJ stay: the Indic
# tables spell words with them.
_APOSTROPHES = str.maketrans({c: "'" for c in "\u0060\u00b4\u02b9\u02bc\u2018\u2019\u201b\u2032\uff07"})
_INVISIBLE = dict.fromkeys(map(ord, "\u200b\u00ad\u2060\ufeff"), None)


def _clean(text: str) -> str:
    """NFC (decomposed Indic text must match the NFC tables), no invisible
    characters, apostrophe look-alikes folded."""
    return unicodedata.normalize("NFC", text).translate(_INVISIBLE).translate(_APOSTROPHES)


def _tokens(text: str) -> list[str]:
    """Whitespace-separated, edge-punctuation-stripped, casefolded tokens.

    Splits on whitespace (never on Unicode marks) so Indic words with
    dependent vowel signs stay whole; "twenty-three" style ASCII
    compounds are split into two tokens.
    """
    text = re.sub(r"(?<=[A-Za-z])-(?=[A-Za-z])", " ", _clean(text))
    out = []
    for raw in text.casefold().split():
        tok = _strip_edge(raw)
        if tok:
            out.append(tok)
    return out


def _strip_edge(tok: str) -> str:
    i, j = 0, len(tok)
    while i < j and unicodedata.category(tok[i])[0] in "PSZC":
        i += 1
    while j > i and unicodedata.category(tok[j - 1])[0] in "PSZC":
        j -= 1
    return tok[i:j]


def _remove_subseq(tokens: list[str], sub: list[str]) -> list[str] | None:
    """Remove the first contiguous occurrence of sub; None if absent."""
    n = len(sub)
    for i in range(len(tokens) - n + 1):
        if tokens[i : i + n] == sub:
            return tokens[:i] + tokens[i + n :]
    return None


def _contains_any(tokens: list[str], forms) -> bool:
    """True when any form occurs as a contiguous run of whole tokens."""
    return any(_remove_subseq(tokens, _tokens(form)) is not None for form in forms)


def _strip_all(tokens: list[str], phrases) -> list[str]:
    """Remove every occurrence of every phrase, longest phrases first."""
    for phrase in sorted(phrases, key=lambda p: len(_tokens(p)), reverse=True):
        while (rest := _remove_subseq(tokens, _tokens(phrase))) is not None:
            tokens = rest
    return tokens


def _match_number(text: str, language: str) -> tuple[int | float, list[str]] | tuple[None, None]:
    """Return (value, consumed_tokens). Digits win over numeral words."""
    normalized = _normalize_digits(text)
    if re.search(r"(?<!\d)[.,]\d", normalized) or any(
        unicodedata.category(c) in ("No", "Nl") for c in normalized
    ):
        # ".5" would read as 5 and "1½" as 1: never drop part of a number.
        raise IntentError(f"unsupported number form in: {text!r}")
    digits = list(re.finditer(r"-?\d+(?:\.\d+)?", normalized))
    if any(m.start() > 0 and unicodedata.category(normalized[m.start() - 1])[0] in "LM" for m in digits):
        # A digit run glued after a letter ("gate A5", "x2.5") names something; never a distance.
        raise IntentError(f"unsupported number form in: {text!r}")
    if len(digits) > 1:
        # "1,000", "1e3", "5-10", "10 then 20": never guess which part counts.
        raise IntentError(f"unsupported number form in: {text!r}")
    if digits:
        raw = digits[0].group(0)
        try:
            value: int | float = float(raw) if "." in raw else int(raw)
        except ValueError:  # past int()'s digit limit; the range check rejects inf
            value = float(raw)
        # Consume the original-script text so native digits leave no residue.
        return value, [text[digits[0].start() : digits[0].end()]]
    words = _tokens(text)
    if language == "en":
        for i, tok in enumerate(words):
            if tok in EN_ONES:
                if EN_ONES[tok] == 1 and i + 1 < len(words) and words[i + 1] == "hundred":
                    return 100, [tok, "hundred"]
                return EN_ONES[tok], [tok]
            if tok in EN_TENS:
                if i + 1 < len(words) and words[i + 1] in EN_ONES and EN_ONES[words[i + 1]] < 10:
                    return EN_TENS[tok] + EN_ONES[words[i + 1]], [tok, words[i + 1]]
                return EN_TENS[tok], [tok]
            if tok == "hundred":
                return 100, [tok]
    elif language == "te":
        for i, tok in enumerate(words):
            if tok in TE_NUMBERS:
                value = TE_NUMBERS[tok]
                if value in TE_TENS and i + 1 < len(words) and words[i + 1] in TE_UNITS_1_9:
                    return value + TE_UNITS_1_9[words[i + 1]], [tok, words[i + 1]]
                return value, [tok]
    elif language == "hi":
        for tok in words:
            if tok in HI_NUMBERS:
                return HI_NUMBERS[tok], [tok]
    elif language == "ta":
        for i, tok in enumerate(words):
            if tok in TA_NUMBERS:
                value = TA_NUMBERS[tok]
                if value in TA_TENS and i + 1 < len(words) and words[i + 1] in TA_UNITS_1_9:
                    return value + TA_UNITS_1_9[words[i + 1]], [tok, words[i + 1]]
                return value, [tok]
    elif language == "kn":
        for i, tok in enumerate(words):
            if tok in KN_NUMBERS:
                value = KN_NUMBERS[tok]
                if value in KN_TENS and i + 1 < len(words) and words[i + 1] in KN_UNITS_1_9:
                    return value + KN_UNITS_1_9[words[i + 1]], [tok, words[i + 1]]
                return value, [tok]
    elif language == "ml":
        for i, tok in enumerate(words):
            if tok in ML_NUMBERS:
                value = ML_NUMBERS[tok]
                if value in ML_TENS and i + 1 < len(words) and words[i + 1] in ML_UNITS_1_9:
                    return value + ML_UNITS_1_9[words[i + 1]], [tok, words[i + 1]]
                return value, [tok]
    return None, None


def _confidence(residue: list[str], used_words: bool, unit_found: bool) -> float:
    if residue or used_words or not unit_found:
        return FALLBACK_CONFIDENCE
    return EXACT_CONFIDENCE


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def _names_a_quantity(transcript: str, others: list[str], vocab: dict, code: str) -> bool:
    """A digit, numeral word, minus word or non-metre unit outside the command phrase."""
    normalized = _normalize_digits(transcript)
    unit_words = [re.sub(rf"^[\d.,]+[{_DASHES}]?", "", tok) for tok in _tokens(normalized)]
    return (
        any(re.search(r"\d", _normalize_digits(tok)) or tok in _NUMERAL_WORDS[code] for tok in others)
        or _contains_any(others, vocab["minus"] + EN_MINUS_WORDS)
        or _contains_any(unit_words, vocab["other_units"] + EN_OTHER_UNITS)
        or bool(re.search(rf"(?<!\d)[{_DASHES}]\s*\d", normalized))
    )


def _takeoff_altitude(transcript: str, words: list[str], vocab: dict, code: str) -> int | float:
    """The spoken takeoff height in metres (edge.app.commands holds it to 1-10 m)."""
    if _contains_any(words, vocab["minus"] + EN_MINUS_WORDS) or re.search(
        rf"(?<!\d)[{_DASHES}]\s*\d", _normalize_digits(transcript)
    ):
        raise IntentError(f"negative altitude in: {transcript!r}")
    unit_words = [
        re.sub(rf"^[\d.,]+[{_DASHES}]?", "", tok) for tok in _tokens(_normalize_digits(transcript))
    ]
    if _contains_any(unit_words, vocab["other_units"] + EN_OTHER_UNITS):
        raise IntentError(f"unsupported altitude unit (metres only) in: {transcript!r}")
    value, number_tokens = _match_number(transcript, code)
    if value is None or number_tokens is None:
        raise IntentError(f"no altitude found in: {transcript!r}")
    leftover = _remove_subseq(words, number_tokens)
    if any(tok in _NUMERAL_WORDS[code] for tok in (words if leftover is None else leftover)):
        raise IntentError(f"unsupported number form in: {transcript!r}")
    if not 0 < value <= MAX_TAKEOFF_ALTITUDE_M:
        raise IntentError(f"altitude_m must satisfy 0 < h <= {MAX_TAKEOFF_ALTITUDE_M}, got {value!r}")
    return int(value) if float(value).is_integer() else value


def parse_transcript(transcript: str, language: str = "auto") -> dict:
    """Map a transcript to {"command", "parameters", "confidence"}.

    ``language`` may be an explicit code (or alias, e.g. "english"),
    which always overrides detection, or an auto hint (``"auto"``,
    ``""``, or whitespace-only) which resolves via :func:`detect_language`.

    Raises IntentError when no single command fits (including a MOVE
    missing an explicit motion verb or distance, an out-of-range distance,
    and negated, compound, non-metre or unsupported-number input), and
    UnsupportedLanguageError for stub/unknown languages.
    """
    if not isinstance(transcript, str) or not transcript.strip():
        raise IntentError("empty transcript")
    transcript = _clean(transcript)
    if not isinstance(language, str):
        raise UnsupportedLanguageError(f"unsupported language: {language!r}")
    hint = language.strip().casefold()
    if hint in AUTO_HINTS or not hint:
        hint = detect_language(transcript)
    code = _LANGUAGE_ALIASES.get(hint, hint)
    if code not in _PER_LANG:
        if code in STUBBED:
            raise UnsupportedLanguageError(f"language {code!r} is not yet supported")
        raise UnsupportedLanguageError(f"unsupported language: {language!r}")

    vocab = _PER_LANG[code]
    words = _tokens(transcript)

    negator_suffixes = vocab["negator_suffixes"] + EN_NEGATOR_SUFFIXES
    if _contains_any(words, vocab["negators"] + EN_NEGATORS) or any(
        tok.endswith(negator_suffixes) for tok in words
    ):
        raise IntentError(f"negated command in: {transcript!r}")

    # One hit per distinct command ("hold position" and "hold" are one HOVER).
    simple_hits = []
    for command, phrases in vocab["simple"]:
        for phrase in phrases:
            rest = _remove_subseq(words, _tokens(phrase))
            if rest is not None:
                simple_hits.append((command, phrases, rest))
                break
    if len(simple_hits) > 1:
        raise IntentError(f"more than one command in: {transcript!r}")
    if simple_hits:
        command, phrases, rest = simple_hits[0]
        # Two of verb, direction and distance make a second (MOVE) request;
        # one alone is filler, so "stop moving" stays STOP. Strip the command's
        # own phrases first: "रुक जाओ" contains the verb "जाओ".
        others = _strip_all(words, phrases)
        move_parts = (
            _contains_any(others, vocab["verbs"]),
            _contains_any(others, [f for forms in vocab["directions"].values() for f in forms]),
            any(
                re.search(r"\d", _normalize_digits(tok)) or tok in _NUMERAL_WORDS[code]
                for tok in others
            ),
        )
        if sum(move_parts) >= 2:
            raise IntentError(f"more than one command in: {transcript!r}")
        parameters: dict = {}
        if command != "STOP" and _names_a_quantity(transcript, others, vocab, code):
            # Never drop a number, sign or unit silently: "take off to 500
            # meters" or "land in 5 seconds" must not run as a bare command.
            # STOP always runs: refusing a stop is never the safe choice.
            if command != "TAKEOFF":
                raise IntentError(f"{command} takes no number in: {transcript!r}")
            parameters["altitude_m"] = _takeoff_altitude(transcript, words, vocab, code)
        conf = EXACT_CONFIDENCE if not rest else FALLBACK_CONFIDENCE
        return {"command": command, "parameters": parameters, "confidence": conf}

    verb_tokens: list[str] | None = None
    for verb in vocab["verbs"]:
        rest = _remove_subseq(words, _tokens(verb))
        if rest is not None:
            verb_tokens = _tokens(verb)
            break
    if verb_tokens is None:
        raise IntentError(f"no motion verb found in: {transcript!r}")

    direction: str | None = None
    dir_tokens: list[str] | None = None
    for canonical, forms in vocab["directions"].items():
        for form in forms:
            if _remove_subseq(words, _tokens(form)) is not None:
                if direction is not None:
                    raise IntentError(f"more than one direction in: {transcript!r}")
                direction, dir_tokens = canonical, _tokens(form)
                break
    if direction is None or dir_tokens is None:
        raise IntentError(f"no direction found in: {transcript!r}")

    # A minus word, or a sign before a digit ("- 5", "−5", "–5"), is negative.
    if _contains_any(words, vocab["minus"] + EN_MINUS_WORDS) or re.search(
        rf"(?<!\d)[{_DASHES}]\s*\d", _normalize_digits(transcript)
    ):
        raise IntentError(f"negative distance in: {transcript!r}")
    # Split digits off a glued unit ("50cm", "50-cm") so the unit is seen.
    unit_words = [
        re.sub(rf"^[\d.,]+[{_DASHES}]?", "", tok) for tok in _tokens(_normalize_digits(transcript))
    ]
    if _contains_any(unit_words, vocab["other_units"] + EN_OTHER_UNITS):
        raise IntentError(f"unsupported distance unit (metres only) in: {transcript!r}")
    value, number_tokens = _match_number(transcript, code)
    if value is None or number_tokens is None:
        raise IntentError(f"no distance found in: {transcript!r}")
    leftover = _remove_subseq(words, number_tokens)
    if any(tok in _NUMERAL_WORDS[code] for tok in (words if leftover is None else leftover)):
        # "two hundred", "दो सौ": the matched number alone would truncate it.
        raise IntentError(f"unsupported number form in: {transcript!r}")
    if not 0 < value <= MAX_DISTANCE_M:
        raise IntentError(f"distance_m must satisfy 0 < d <= {MAX_DISTANCE_M}, got {value!r}")

    unit_found = False
    unit_tokens: list[str] | None = None
    for unit in vocab["units"]:
        if _remove_subseq(words, _tokens(unit)) is not None:
            unit_found, unit_tokens = True, _tokens(unit)
            break

    rest: list[str] | None = list(words)
    for part in (verb_tokens, dir_tokens, unit_tokens or [], number_tokens):
        assert rest is not None
        rest = _remove_subseq(rest, part)
        if rest is None:
            break
    residue = rest if rest is not None else list(words)
    used_words = not re.search(r"\d", _normalize_digits(transcript))
    distance: int | float = int(value) if float(value).is_integer() else value
    return {
        "command": "MOVE",
        "parameters": {"direction": direction, "distance_m": distance},
        "confidence": _confidence(residue, used_words, unit_found),
    }
