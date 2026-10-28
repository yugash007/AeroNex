"""Task 3: rule-based intent tests (TDD RED first).

Reference cases the Flutter/Dart side (Task 4) will mirror.
"""

import pytest

import edge.app.intent as intent_module
from edge.app.intent import (
    SUPPORTED,
    IntentError,
    UnsupportedLanguageError,
    parse_transcript,
)


def test_en_move_forward_10m():
    assert parse_transcript("move forward 10 meters", "en") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_te_move_forward_10m():
    assert parse_transcript("10 మీటర్లు ముందుకు వెళ్ళు", "te") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_hi_move_forward_10m():
    assert parse_transcript("10 मीटर आगे जाओ", "hi") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_takeoff_and_land():
    assert parse_transcript("take off", "en")["command"] == "TAKEOFF"
    assert parse_transcript("land", "en")["command"] == "LAND"
    assert parse_transcript("టేకాఫ్", "te")["command"] == "TAKEOFF"
    assert parse_transcript("लैंड", "hi")["command"] == "LAND"


def test_hover_and_stop():
    assert parse_transcript("hover", "en")["command"] == "HOVER"
    assert parse_transcript("stop", "en")["command"] == "STOP"
    assert parse_transcript("హోవర్", "te")["command"] == "HOVER"
    assert parse_transcript("रुको", "hi")["command"] == "STOP"


def test_gibberish_raises_intent_error():
    with pytest.raises(IntentError):
        parse_transcript("banana photosynthesize purple", "en")


def test_negative_distance_rejected():
    with pytest.raises(IntentError):
        parse_transcript("move forward -5 meters", "en")


def test_unsupported_language_tamil_stub():
    # Task 2: ta/kn/ml promoted from stubs to supported (see
    # test_all_six_supported_and_no_stubs); unknown codes still raise.
    assert SUPPORTED == ("en", "hi", "te", "ta", "kn", "ml")
    assert parse_transcript("நில்", "ta")["command"] == "STOP"
    assert parse_transcript("ನಿಲ್ಲು", "kn")["command"] == "STOP"
    assert parse_transcript("നിർത്തൂ", "ml")["command"] == "STOP"
    with pytest.raises(UnsupportedLanguageError):
        parse_transcript("take off", "xx")


# --- Task 1: script detection + auto hint (appended; existing tests untouched) ---

from edge.app.intent import detect_language


def test_detect_language_telugu_block():
    assert detect_language("10 మీటర్లు ముందుకు వెళ్ళు") == "te"


def test_detect_language_hindi_block():
    assert detect_language("10 मीटर आगे जाओ") == "hi"


def test_detect_language_tamil_block():
    assert detect_language("10 மீட்டர் முன்னால் செல்") == "ta"


def test_detect_language_kannada_block():
    assert detect_language("10 ಮೀಟರ್ ಮುಂದೆ ಹೋಗು") == "kn"


def test_detect_language_malayalam_block():
    assert detect_language("10 മീറ്റർ മുന്നോട്ട് പോകൂ") == "ml"


def test_detect_language_latin_fallback():
    assert detect_language("move forward 10 meters") == "en"
    assert detect_language("10") == "en"
    assert detect_language("...!!!") == "en"


def test_auto_detect_parses_english_move():
    assert parse_transcript("move forward 10 meters", "auto") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }
    assert parse_transcript("move forward 10 meters") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_explicit_overrides_auto():
    with pytest.raises(IntentError):
        parse_transcript("10 மீட்டர் முன்னால் செல்", "hi")


def test_pure_digits_auto_fallback_raises_intent_error():
    with pytest.raises(IntentError):
        parse_transcript("10", "auto")


# --- Task 2: provisional ta/kn/ml tables (appended; existing tests untouched) ---

from edge.app.intent import STUBBED


def test_ta_move_forward_10m():
    assert parse_transcript("10 மீட்டர் முன்னால் செல்", "ta") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_kn_move_forward_10m():
    assert parse_transcript("10 ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_ml_move_forward_10m():
    assert parse_transcript("10 മീറ്റർ മുന്നോട്ട് പോകൂ", "ml") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_ta_simples():
    assert parse_transcript("டேக்ஆஃப்", "ta")["command"] == "TAKEOFF"
    assert parse_transcript("தரையிறங்கு", "ta")["command"] == "LAND"
    assert parse_transcript("ஹோவர்", "ta")["command"] == "HOVER"
    assert parse_transcript("நில்", "ta")["command"] == "STOP"


def test_kn_simples():
    assert parse_transcript("ಟೇಕಾಫ್", "kn")["command"] == "TAKEOFF"
    assert parse_transcript("ಇಳಿ", "kn")["command"] == "LAND"
    assert parse_transcript("ಹೋವರ್", "kn")["command"] == "HOVER"
    assert parse_transcript("ನಿಲ್ಲು", "kn")["command"] == "STOP"


def test_ml_simples():
    assert parse_transcript("ടേക്ക്ഓഫ്", "ml")["command"] == "TAKEOFF"
    assert parse_transcript("ഇറങ്ങൂ", "ml")["command"] == "LAND"
    assert parse_transcript("ഹോവർ", "ml")["command"] == "HOVER"
    assert parse_transcript("നിർത്തൂ", "ml")["command"] == "STOP"


def test_ta_kn_ml_tens_composition_21():
    ta = parse_transcript("இருபத்தி ஒன்று மீட்டர் முன்னால் செல்", "ta")
    assert ta["command"] == "MOVE"
    assert ta["parameters"]["distance_m"] == 21
    kn = parse_transcript("ಇಪ್ಪತ್ತು ಒಂದು ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn")
    assert kn["command"] == "MOVE"
    assert kn["parameters"]["distance_m"] == 21
    ml = parse_transcript("ഇരുപത് ഒന്ന് മീറ്റർ മുന്നോട്ട് പോകൂ", "ml")
    assert ml["command"] == "MOVE"
    assert ml["parameters"]["distance_m"] == 21


def test_gibberish_ta_kn_ml_raise():
    with pytest.raises(IntentError):
        parse_transcript("வாழைப்பழம் ஊதா மேகம்", "ta")
    with pytest.raises(IntentError):
        parse_transcript("ಬಾಳೆಹಣ್ಣು ನೇರಳೆ ಮೋಡ", "kn")
    with pytest.raises(IntentError):
        parse_transcript("വാഴപ്പഴം ധൂമ്ര മേഘം", "ml")


def test_negative_distance_ta_rejected():
    with pytest.raises(IntentError):
        parse_transcript("-5 மீட்டர் முன்னால் செல்", "ta")


def test_all_six_supported_and_no_stubs():
    assert SUPPORTED == ("en", "hi", "te", "ta", "kn", "ml")
    assert STUBBED == ()


def test_transliterated_codemix_raises():
    with pytest.raises(IntentError):
        parse_transcript("move munduku 10 meters", "auto")


def test_tamil_punctuation_strips():
    assert parse_transcript("\u0964\"10 மீட்டர் முன்னால் செல்\"\u0964", "ta") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


# --- Review fixes: reject rather than guess (appended; existing tests untouched) ---


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("do not take off", "en"),
        ("don't land", "en"),
        ("don\u2019t land", "en"),
        ("dont hover", "en"),
        ("never launch", "en"),
        ("you cannot land", "en"),
        ("no take off", "en"),
        ("don't stop", "en"),  # a negated STOP is refused too, never guessed either way
        ("don't move forward 5 meters", "en"),
        ("won't land", "en"),
        ("you shouldn't take off", "en"),
        ("didn't land", "en"),
        ("don\u2018t land", "en"),
        ("wont land", "en"),
        ("टेकऑफ मत करो", "hi"),
        ("लैंड नहीं", "hi"),
        ("टेकऑफ ना करो", "hi"),
        ("लैंड न करो", "hi"),
        ("लैंड नही करो", "hi"),
        ("టేకాఫ్ వద్దు", "te"),
        ("ల్యాండ్ చేయకు", "te"),
        ("టేకాఫ్ చేయవద్దు", "te"),  # fused negative imperative
        ("టేకాఫ్ చేయకండి", "te"),  # polite prohibitive
        ("టేకాఫ్ చెయ్యకు", "te"),  # colloquial spelling
        ("ల్యాండ్ చేయొద్దు", "te"),
        ("ల్యాండ్ చెయ్యొద్దు", "te"),
        ("10 మీటర్లు ముందుకు వెళ్ళు కాదు", "te"),
        ("டேக்ஆஃப் வேண்டாம்", "ta"),
        ("ಟೇಕಾಫ್ ಬೇಡ", "kn"),
        ("ടേക്ക്ഓഫ് വേണ്ട", "ml"),
        ("டேக்ஆஃப் செய்யக்கூடாது", "ta"),
        ("ಟೇಕಾಫ್ ಮಾಡಬೇಡ", "kn"),
        ("ടേക്ക്ഓഫ് ചെയ്യരുത്", "ml"),
    ],
)
def test_negated_utterance_rejected(text, lang):
    with pytest.raises(IntentError):
        parse_transcript(text, lang)


def test_negators_never_match_command_vocabulary():
    for code, vocab in intent_module._PER_LANG.items():
        forms = [*vocab["verbs"], *vocab["units"], *intent_module._NUMERAL_WORDS[code]]
        forms += [form for group in vocab["directions"].values() for form in group]
        forms += [phrase for _, phrases in vocab["simple"] for phrase in phrases]
        negators = vocab["negators"] + intent_module.EN_NEGATORS
        suffixes = vocab["negator_suffixes"] + intent_module.EN_NEGATOR_SUFFIXES
        for form in forms:
            for tok in form.casefold().split():
                assert tok not in negators and not tok.endswith(suffixes), (code, tok)


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("take off and move forward 5 meters", "en"),
        ("fly forward 10 meters then land", "en"),
        ("take off then land", "en"),
        ("stop and land", "en"),
        ("move forward and back 5 meters", "en"),
        ("move forward 5 meters and then move left 3 meters", "en"),
        ("land 5 meters forward", "en"),  # distance + direction, no verb
        ("take off and fly 5 meters", "en"),
        ("take off and go up", "en"),
        ("go ahead and land", "en"),  # "ahead" is FORWARD
        ("टेकऑफ और लैंड", "hi"),
        ("रुको और आगे 5 मीटर जाओ", "hi"),
        ("టేకాఫ్ తర్వాత ల్యాండ్", "te"),
        ("5 మీటర్లు ముందుకు వెనక్కి వెళ్ళు", "te"),
        ("டேக்ஆஃப் தரையிறங்கு", "ta"),
        ("ಟೇಕಾಫ್ ಇಳಿ", "kn"),
        ("ടേക്ക്ഓഫ് ഇറങ്ങൂ", "ml"),
    ],
)
def test_compound_utterance_rejected(text, lang):
    with pytest.raises(IntentError):
        parse_transcript(text, lang)


@pytest.mark.parametrize(
    ("text", "lang", "command", "confidence"),
    [
        ("hold position", "en", "HOVER", 0.9),
        ("hold position hold", "en", "HOVER", 0.7),  # one command, counted once
        ("please stop now", "en", "STOP", 0.7),
        ("stop moving", "en", "STOP", 0.7),  # a lone motion word is not a second request
        ("stop flying", "en", "STOP", 0.7),
        ("stop right now", "en", "STOP", 0.7),
        ("चलो लैंड करो", "hi", "LAND", 0.7),
        ("रुक जाओ", "hi", "STOP", 0.9),  # STOP phrase contains the verb जाओ
        ("रुको रुक जाओ", "hi", "STOP", 0.7),
        ("ऊपर उठो", "hi", "TAKEOFF", 0.9),  # contains UP ऊपर
        ("పైకి లే", "te", "TAKEOFF", 0.9),  # contains UP పైకి
        ("மேலே எழு", "ta", "TAKEOFF", 0.9),
        ("ಮೇಲಕ್ಕೆ ಏರು", "kn", "TAKEOFF", 0.9),
        ("മുകളിലേക്ക് പൊങ്ങൂ", "ml", "TAKEOFF", 0.9),
    ],
)
def test_single_command_with_overlapping_tokens_still_parses(text, lang, command, confidence):
    assert parse_transcript(text, lang) == {
        "command": command,
        "parameters": {},
        "confidence": confidence,
    }


def test_overlapping_forms_of_one_direction_still_parse():
    assert parse_transcript("ఎడమ వైపుకు 5 మీటర్లు వెళ్ళు", "te") == {
        "command": "MOVE",
        "parameters": {"direction": "LEFT", "distance_m": 5},
        "confidence": 0.9,
    }


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("move forward 50 centimeters", "en"),
        ("go left 30 cm", "en"),
        ("move forward 10 feet", "en"),
        ("move forward 1 foot", "en"),
        ("move up 2 kilometers", "en"),
        ("move back 5 inches", "en"),
        ("move right 3 yards", "en"),
        ("move forward 1 mile", "en"),
        ("move forward 500 mm", "en"),
        ("move forward 50cm", "en"),
        ("move forward 50-cm", "en"),
        ("move forward 3 milli meters", "en"),  # split prefix, never 3 m
        ("move forward 50 centi meters", "en"),
        ("move forward 2 kilo meters", "en"),
        ("move forward 50-centimeter", "en"),
        ("move forward 50\u2013cm", "en"),
        ("50-सेंटीमीटर आगे जाओ", "hi"),
        ("50 सेंटीमीटर आगे जाओ", "hi"),
        ("2 किलोमीटर आगे जाओ", "hi"),
        ("10 फीट आगे जाओ", "hi"),
        ("50 cm आगे जाओ", "hi"),  # Latin unit inside a Hindi transcript
        ("50 సెంటీమీటర్లు ముందుకు వెళ్ళు", "te"),
        ("10 అడుగులు ముందుకు వెళ్ళు", "te"),
        ("50 சென்டிமீட்டர் முன்னால் செல்", "ta"),
        ("50 ಸೆಂಟಿಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn"),
        ("50 സെന്റിമീറ്റർ മുന്നോട്ട് പോകൂ", "ml"),
    ],
)
def test_non_metre_unit_rejected(text, lang):
    with pytest.raises(IntentError):
        parse_transcript(text, lang)


def test_metre_abbreviation_and_missing_unit_still_parse():
    assert parse_transcript("move forward 10 m", "en")["parameters"] == {
        "direction": "FORWARD",
        "distance_m": 10,
    }
    assert parse_transcript("move forward 10m", "en")["parameters"]["distance_m"] == 10
    assert parse_transcript("move forward 10 metres", "en")["confidence"] == 0.9
    assert parse_transcript("move forward 10", "en") == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.7,
    }


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("move forward 1,000 meters", "en"),
        ("move forward 1e3 meters", "en"),
        ("move forward 5-10 meters", "en"),
        ("move forward 1 000 meters", "en"),
        ("move forward 2,5 meters", "en"),
        ("move forward 10 meters then 20 meters", "en"),
        ("move forward two hundred meters", "en"),
        ("move forward two thousand meters", "en"),
        ("move forward one hundred fifty meters", "en"),
        ("move forward two point five meters", "en"),
        ("move forward two and a half meters", "en"),
        ("move forward 10 hundred meters", "en"),
        ("move forward .5 meters", "en"),  # would read as 5
        ("move forward 1\u00bd meters", "en"),  # would read as 1
        (".५ मीटर आगे जाओ", "hi"),
        ("दो सौ मीटर आगे जाओ", "hi"),
        ("एक सौ मीटर आगे जाओ", "hi"),
        ("दो हज़ार मीटर आगे जाओ", "hi"),
        ("१,००० मीटर आगे जाओ", "hi"),
        ("రెండు వందల మీటర్లు ముందుకు వెళ్ళు", "te"),
        ("రెండు వేల మీటర్లు ముందుకు వెళ్ళు", "te"),
        ("இரண்டு நூறு மீட்டர் முன்னால் செல்", "ta"),
        ("ಎರಡು ನೂರು ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn"),
        ("രണ്ട് നൂറ് മീറ്റർ മുന്നോട്ട് പോകൂ", "ml"),
    ],
)
def test_truncated_number_form_rejected(text, lang):
    with pytest.raises(IntentError):
        parse_transcript(text, lang)


@pytest.mark.parametrize(
    ("text", "lang", "distance"),
    [
        ("move forward 2.5 meters", "en", 2.5),
        ("move forward 10 meters.", "en", 10),
        ("move forward one hundred meters", "en", 100),
        ("move forward hundred meters", "en", 100),
        ("move forward twenty three meters", "en", 23),
        ("move forward twenty-three meters", "en", 23),
        ("सौ मीटर आगे जाओ", "hi", 100),
        ("వంద మీటర్లు ముందుకు వెళ్ళు", "te", 100),
        ("ఇరవై ఒక మీటర్లు ముందుకు వెళ్ళు", "te", 21),
        ("நூறு மீட்டர் முன்னால் செல்", "ta", 100),
    ],
)
def test_supported_number_forms_still_parse(text, lang, distance):
    assert parse_transcript(text, lang)["parameters"]["distance_m"] == distance


def test_huge_digit_string_raises_intent_error():
    for digits in ("9" * 20, "9" * 5000, "9" * 5000 + ".5"):
        with pytest.raises(IntentError):
            parse_transcript(f"move forward {digits} meters", "en")


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("move forward negative five meters", "en"),
        ("move forward - 5 meters", "en"),
        ("move forward \u22125 meters", "en"),
        ("move forward \u20135 meters", "en"),  # en dash
        ("move forward \uff0d5 meters", "en"),  # fullwidth hyphen-minus
        ("move forward \u20105 meters", "en"),  # hyphen
        ("माइनस पाँच मीटर आगे जाओ", "hi"),
        ("ऋण 5 मीटर आगे जाओ", "hi"),
        ("minus 5 मीटर आगे जाओ", "hi"),
        ("మైనస్ 5 మీటర్లు ముందుకు వెళ్ళు", "te"),
        ("மைனஸ் 5 மீட்டர் முன்னால் செல்", "ta"),
        ("ಮೈನಸ್ 5 ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn"),
        ("മൈനസ് 5 മീറ്റർ മുന്നോട്ട് പോകൂ", "ml"),
    ],
)
def test_spoken_negative_distance_rejected(text, lang):
    with pytest.raises(IntentError):
        parse_transcript(text, lang)


KN_NINETY = "\u0ca4\u0cca\u0c82\u0cac\u0ca4\u0ccd\u0ca4\u0cc1"  # ninety


def test_kn_ninety_word():
    ninety = parse_transcript(f"{KN_NINETY} ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn")
    assert ninety["parameters"] == {"direction": "FORWARD", "distance_m": 90}
    ninety_five = parse_transcript(f"{KN_NINETY} ಐದು ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn")
    assert ninety_five["parameters"]["distance_m"] == 95


def _table_strings(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for key, value in obj.items():
            yield from _table_strings(key)
            yield from _table_strings(value)
    elif isinstance(obj, (tuple, list, set, frozenset)):
        for item in obj:
            yield from _table_strings(item)


def test_native_tables_are_single_script():
    blocks = {
        "TE_": (0x0C00, 0x0C7F),
        "HI_": (0x0900, 0x097F),
        "TA_": (0x0B80, 0x0BFF),
        "KN_": (0x0C80, 0x0CFF),
        "ML_": (0x0D00, 0x0D7F),
    }
    for name in dir(intent_module):
        if name[:3] not in blocks:
            continue
        start, end = blocks[name[:3]]
        for form in _table_strings(getattr(intent_module, name)):
            if form.isascii():  # canonical names such as "FORWARD" / "STOP"
                continue
            stray = [c for c in form if not (start <= ord(c) <= end or c in " \u200c\u200d")]
            assert not stray, (name, ascii(form))


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("౧౦ మీటర్లు ముందుకు వెళ్ళు", "te"),
        ("१० मीटर आगे जाओ", "hi"),
        ("௧௦ மீட்டர் முன்னால் செல்", "ta"),
        ("೧೦ ಮೀಟರ್ ಮುಂದೆ ಹೋಗು", "kn"),
        ("൧൦ മീറ്റർ മുന്നോട്ട് പോകൂ", "ml"),
    ],
)
def test_native_digits_get_exact_confidence(text, lang):
    assert parse_transcript(text, lang) == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }


def test_native_digits_with_filler_keep_fallback_confidence():
    assert parse_transcript("कृपया १० मीटर आगे जाओ", "hi")["confidence"] == 0.7
