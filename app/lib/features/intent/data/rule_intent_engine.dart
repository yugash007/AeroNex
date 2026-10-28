/// Rule-based intent: transcript + language -> COMMAND payload.
///
/// Pure Dart mirror of Task 3 (edge/app/intent.py). The vocabulary tables
/// below are copied verbatim — do NOT expand hi/te without native-speaker
/// review (project ledger constraint).
///
/// Confidence: 0.9 for exact canonical phrasing, 0.7 fallback when the
/// distance uses numeral words or extra filler words surround the command.
///
/// Input that does not map to exactly one command throws IntentException
/// rather than being guessed: negated or compound utterances, non-metre
/// units, spoken minus signs, and number forms the tables would truncate.
library;

import '../../../core/errors.dart';
import '../domain/intent_engine.dart';
import 'package:aeronex/features/language/data/language_codes.dart';

// ---------------------------------------------------------------------------
// Vocabulary tables (canonical value -> spoken forms). Verbatim from Task 3.
// ---------------------------------------------------------------------------

const List<String> enMoveVerbs = [
  'move',
  'moves',
  'moving',
  'go',
  'goes',
  'going',
  'fly',
  'flies',
  'flying',
];

const List<String> teMoveVerbs = [
  'వెళ్ళు',
  'వెళ్లు',
  'వెళ్ళండి',
  'వెళ్లండి',
  'పంపు',
  'పంపండి',
  'కదులు',
];

const List<String> hiMoveVerbs = ['जाओ', 'जाइए', 'जाइये', 'चलो', 'बढ़ो'];

const Map<String, List<String>> enDirections = {
  'FORWARD': ['forward', 'forwards', 'ahead'],
  'BACKWARD': ['backward', 'backwards', 'back'],
  'LEFT': ['left'],
  'RIGHT': ['right'],
  'UP': ['up', 'upward', 'upwards'],
  'DOWN': ['down', 'downward', 'downwards'],
};

const Map<String, List<String>> teDirections = {
  'FORWARD': ['ముందుకు'],
  'BACKWARD': ['వెనక్కి', 'వెనుకకు'],
  'LEFT': ['ఎడమకు', 'ఎడమవైపుకు', 'ఎడమ వైపుకు', 'ఎడమ'],
  'RIGHT': ['కుడికి', 'కుడివైపుకు', 'కుడి వైపుకు', 'కుడి'],
  'UP': ['పైకి'],
  'DOWN': ['కిందికి', 'క్రిందికి'],
};

const Map<String, List<String>> hiDirections = {
  'FORWARD': ['आगे'],
  'BACKWARD': ['पीछे'],
  'LEFT': ['बाएं', 'बाएँ', 'बायें'],
  'RIGHT': ['दाएं', 'दाएँ', 'दायें'],
  'UP': ['ऊपर'],
  'DOWN': ['नीचे'],
};

const List<String> enUnits = ['meters', 'meter', 'metres', 'metre'];
const List<String> teUnits = ['మీటర్లు', 'మీటర్ల', 'మీటరు', 'మీటర్'];
const List<String> hiUnits = ['मीटर'];

/// One non-MOVE command plus its spoken phrases.
class SimpleEntry {
  final String command;
  final List<String> phrases;
  const SimpleEntry(this.command, this.phrases);
}

/// Non-MOVE commands. A transcript naming two of them is rejected, so the
/// order only decides which phrase of one command matches first (and its
/// confidence).
const List<SimpleEntry> enSimple = [
  SimpleEntry('STOP', ['stop']),
  SimpleEntry('TAKEOFF', ['take off', 'takeoff', 'launch']),
  SimpleEntry('LAND', ['land']),
  SimpleEntry('HOVER', ['hover', 'hold position', 'hold']),
];

const List<SimpleEntry> teSimple = [
  SimpleEntry('STOP', ['ఆపు', 'ఆగు', 'స్టాప్']),
  SimpleEntry('TAKEOFF', ['టేకాఫ్', 'పైకి లే']),
  SimpleEntry('LAND', ['ల్యాండ్', 'దిగు']),
  SimpleEntry('HOVER', ['హోవర్']),
];

const List<SimpleEntry> hiSimple = [
  SimpleEntry('STOP', ['रुको', 'रुक जाओ', 'स्टॉप']),
  SimpleEntry('TAKEOFF', ['टेकऑफ़', 'टेकऑफ', 'उड़ान भरो', 'ऊपर उठो']),
  SimpleEntry('LAND', ['लैंड', 'उतरो']),
  SimpleEntry('HOVER', ['हॉवर', 'होवर']),
];

// Reject-only tables (mirror edge/app/intent.py): a match throws and never
// yields a command, so an unreviewed entry can only cause a refusal. The
// English forms are checked for every language (ASR keeps Latin "cm" etc.).

/// Negation markers, whole tokens: "do not take off" must never be TAKEOFF.
const List<String> enNegators = [
  'not',
  'no',
  'never',
  'dont',
  "don't",
  'don\u2019t',
  'cannot',
  'cant',
  "can't",
  'can\u2019t',
  'wont',
  'shouldnt',
  'wouldnt',
  'couldnt',
  'mustnt',
  'didnt',
  'doesnt',
  'isnt',
  'arent',
  'wasnt',
  'werent',
  'hasnt',
  'havent',
];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> hiNegators = ['मत', 'नहीं', 'नही', 'ना', 'न'];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> teNegators = [
  'వద్దు',
  'చేయకు',
  'చేయకండి',
  'చెయ్యకు',
  'చెయ్యకండి',
  'కాదు',
  'ఎగరవద్దు',
  'ఎగరకు',
  'లేదు',
];

/// Negation fused onto the word before it ("won't", "చేయవద్దు"): any token
/// ending in one of these is a negator.
const List<String> enNegatorSuffixes = [
  "n't",
  'n\u2019t',
  'n\u2018t',
  'n\u02bct',
  'n\uff07t',
];
const List<String> hiNegatorSuffixes = [];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> teNegatorSuffixes = ['వద్దు', 'వద్దండి', 'ొద్దు', 'ొద్దండి'];

/// Spoken minus signs: a negative distance is rejected, never made positive.
const List<String> enMinusWords = ['minus', 'negative'];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> hiMinusWords = ['माइनस', 'ऋण'];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> teMinusWords = ['మైనస్'];

/// Explicit non-metre units: "50 cm" is never read as 50 m. Bare "m" and the
/// metre words stay accepted; "in" is left out (ordinary filler).
const List<String> enOtherUnits = [
  'cm',
  'cms',
  'centimeter',
  'centimeters',
  'centimetre',
  'centimetres',
  'centi',
  'mm',
  'millimeter',
  'millimeters',
  'millimetre',
  'millimetres',
  'milli',
  'km',
  'kms',
  'kilometer',
  'kilometers',
  'kilometre',
  'kilometres',
  'kilo',
  'ft',
  'foot',
  'feet',
  'inch',
  'inches',
  'yd',
  'yard',
  'yards',
  'mile',
  'miles',
];
// PROVISIONAL (2026-10-10): needs native-speaker review. Split prefixes
// ("सेंटी मीटर") are listed so the trailing metre word cannot pass alone.
const List<String> hiOtherUnits = [
  'सेंटीमीटर',
  'मिलीमीटर',
  'किलोमीटर',
  'सेंटी',
  'मिली',
  'किलो',
  'फुट',
  'फीट',
  'इंच',
  'गज',
  'मील',
];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> teOtherUnits = [
  'సెంటీమీటర్లు',
  'సెంటీమీటర్',
  'మిల్లీమీటర్లు',
  'మిల్లీమీటర్',
  'కిలోమీటర్లు',
  'కిలోమీటర్',
  'సెంటీ',
  'మిల్లీ',
  'కిలో',
  'అడుగులు',
  'అడుగు',
  'అంగుళాలు',
  'అంగుళం',
  'గజాలు',
  'మైళ్ళు',
  'మైలు',
];

// ---------------------------------------------------------------------------
// Numerals: digits + words for 1-100. Verbatim from Task 3.
// ---------------------------------------------------------------------------

const Map<String, int> enOnes = {
  'zero': 0,
  'one': 1,
  'two': 2,
  'three': 3,
  'four': 4,
  'five': 5,
  'six': 6,
  'seven': 7,
  'eight': 8,
  'nine': 9,
  'ten': 10,
  'eleven': 11,
  'twelve': 12,
  'thirteen': 13,
  'fourteen': 14,
  'fifteen': 15,
  'sixteen': 16,
  'seventeen': 17,
  'eighteen': 18,
  'nineteen': 19,
};

const Map<String, int> enTens = {
  'twenty': 20,
  'thirty': 30,
  'forty': 40,
  'fifty': 50,
  'sixty': 60,
  'seventy': 70,
  'eighty': 80,
  'ninety': 90,
};

const Map<String, int> teNumbers = {
  'ఒక': 1,
  'ఒకటి': 1,
  'రెండు': 2,
  'మూడు': 3,
  'నాలుగు': 4,
  'ఐదు': 5,
  'ఆరు': 6,
  'ఏడు': 7,
  'ఎనిమిది': 8,
  'తొమ్మిది': 9,
  'పది': 10,
  'పదకొండు': 11,
  'పన్నెండు': 12,
  'పదమూడు': 13,
  'పద్నాలుగు': 14,
  'పదిహేను': 15,
  'పదహారు': 16,
  'పదిహేడు': 17,
  'పద్దెనిమిది': 18,
  'పందొమ్మిది': 19,
  'ఇరవై': 20,
  'ముప్పై': 30,
  'నలభై': 40,
  'యాభై': 50,
  'అరవై': 60,
  'డెబ్బై': 70,
  'ఎనభై': 80,
  'తొంభై': 90,
  'వంద': 100,
  'నూరు': 100,
};

const Set<int> teTens = {20, 30, 40, 50, 60, 70, 80, 90};

const Map<String, int> teUnits19 = {
  'ఒక': 1,
  'ఒకటి': 1,
  'రెండు': 2,
  'మూడు': 3,
  'నాలుగు': 4,
  'ఐదు': 5,
  'ఆరు': 6,
  'ఏడు': 7,
  'ఎనిమిది': 8,
  'తొమ్మిది': 9,
};

const Map<String, int> hiNumbers = {
  'एक': 1,
  'दो': 2,
  'तीन': 3,
  'चार': 4,
  'पाँच': 5,
  'पांच': 5,
  'छह': 6,
  'छः': 6,
  'सात': 7,
  'आठ': 8,
  'नौ': 9,
  'दस': 10,
  'ग्यारह': 11,
  'बारह': 12,
  'तेरह': 13,
  'चौदह': 14,
  'पंद्रह': 15,
  'सोलह': 16,
  'सत्रह': 17,
  'अठारह': 18,
  'उन्नीस': 19,
  'बीस': 20,
  'इक्कीस': 21,
  'बाईस': 22,
  'तेईस': 23,
  'चौबीस': 24,
  'पच्चीस': 25,
  'छब्बीस': 26,
  'सत्ताईस': 27,
  'अट्ठाईस': 28,
  'उनतीस': 29,
  'तीस': 30,
  'इकतीस': 31,
  'बत्तीस': 32,
  'तैंतीस': 33,
  'चौंतीस': 34,
  'पैंतीस': 35,
  'छत्तीस': 36,
  'सैंतीस': 37,
  'अड़तीस': 38,
  'उनतालीस': 39,
  'चालीस': 40,
  'इकतालीस': 41,
  'बयालीस': 42,
  'तैंतालीस': 43,
  'चवालीस': 44,
  'पैंतालीस': 45,
  'छियालीस': 46,
  'सैंतालीस': 47,
  'अड़तालीस': 48,
  'उनचास': 49,
  'पचास': 50,
  'इक्यावन': 51,
  'बावन': 52,
  'तिरपन': 53,
  'चौवन': 54,
  'पचपन': 55,
  'छप्पन': 56,
  'सत्तावन': 57,
  'अट्ठावन': 58,
  'उनसठ': 59,
  'साठ': 60,
  'इकसठ': 61,
  'बासठ': 62,
  'तिरसठ': 63,
  'चौंसठ': 64,
  'पैंसठ': 65,
  'छियासठ': 66,
  'सड़सठ': 67,
  'अड़सठ': 68,
  'उनहत्तर': 69,
  'सत्तर': 70,
  'इकहत्तर': 71,
  'बहत्तर': 72,
  'तिहत्तर': 73,
  'चौहत्तर': 74,
  'पचहत्तर': 75,
  'छिहत्तर': 76,
  'सतहत्तर': 77,
  'अठहत्तर': 78,
  'उन्यासी': 79,
  'अस्सी': 80,
  'इक्यासी': 81,
  'बयासी': 82,
  'तिरासी': 83,
  'चौरासी': 84,
  'पचासी': 85,
  'छियासी': 86,
  'सत्तासी': 87,
  'अट्ठासी': 88,
  'नवासी': 89,
  'नब्बे': 90,
  'इक्यानबे': 91,
  'बानबे': 92,
  'तिरानबे': 93,
  'चौरानबे': 94,
  'पचानबे': 95,
  'छियानबे': 96,
  'सत्तानबे': 97,
  'अट्ठानबे': 98,
  'निन्यानबे': 99,
  'सौ': 100,
};

/// Scale, decimal and fraction words the tables cannot compose. Reject-only:
/// these and every numeral word above must be consumed by the one matched
/// number, otherwise "two hundred" would be read as 2.
const List<String> enScaleWords = [
  'hundred',
  'thousand',
  'lakh',
  'million',
  'point',
  'half',
];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> hiScaleWords = [
  'हज़ार',
  'हजार',
  'लाख',
  'दशमलव',
  'डेढ़',
  'ढाई',
  'साढ़े',
  'सवा',
  'पौने',
];
// PROVISIONAL (2026-10-10): needs native-speaker review.
const List<String> teScaleWords = [
  'వందల',
  'వందలు',
  'వేయి',
  'వెయ్యి',
  'వేల',
  'వేలు',
  'దశాంశం',
  'అర',
];

final Map<String, Set<String>> _numeralWords = {
  'en': {...enOnes.keys, ...enTens.keys, ...enScaleWords},
  'te': {...teNumbers.keys, ...teScaleWords},
  'hi': {...hiNumbers.keys, ...hiScaleWords},
};

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

class _LangVocab {
  final List<String> verbs;
  final Map<String, List<String>> directions;
  final List<String> units;
  final List<SimpleEntry> simple;
  final List<String> negators;
  final List<String> negatorSuffixes;
  final List<String> minusWords;
  final List<String> otherUnits;
  const _LangVocab(this.verbs, this.directions, this.units, this.simple,
      this.negators, this.negatorSuffixes, this.minusWords, this.otherUnits);
}

const Map<String, _LangVocab> _perLang = {
  'en': _LangVocab(enMoveVerbs, enDirections, enUnits, enSimple, enNegators,
      enNegatorSuffixes, enMinusWords, enOtherUnits),
  'te': _LangVocab(teMoveVerbs, teDirections, teUnits, teSimple, teNegators,
      teNegatorSuffixes, teMinusWords, teOtherUnits),
  'hi': _LangVocab(hiMoveVerbs, hiDirections, hiUnits, hiSimple, hiNegators,
      hiNegatorSuffixes, hiMinusWords, hiOtherUnits),
};

const List<String> supportedLanguages = ['en', 'hi', 'te'];
const List<String> stubbedLanguages = ['ta', 'kn', 'ml'];

/// The transcript names no command at all. The only rejection another
/// interpreter may take over; the Python mirror raises plain IntentError.
class IntentNotRecognized extends IntentException {
  const IntentNotRecognized(super.message);
}

/// The explicit metre value in [transcript] ("take off to 3 m"), or null when
/// it has no number with a metre unit. Applies the MOVE distance checks, so a
/// negative value, a non-metre unit or a truncated number form throws
/// IntentException; range checks are the caller's.
num? explicitMetres(String transcript, String language) {
  final code = resolveLanguageCode(language);
  final vocab = _perLang[code];
  if (code == null || vocab == null) {
    throw UnsupportedLanguageException("unsupported language: '$language'");
  }
  final number = _checkedNumber(transcript, code, _tokens(transcript));
  if (number == null ||
      !_containsAny(_unitWords(transcript), [...vocab.units, ...enUnits, 'm'])) {
    return null;
  }
  final value = number.value;
  return value % 1 == 0 ? value.toInt() : value;
}

/// Rule-based intent engine mirroring Task 3 parse_transcript.
class RuleBasedIntentEngine implements IntentEngine {
  static const double maxDistanceM = 500;
  static const double exactConfidence = 0.9;
  static const double fallbackConfidence = 0.7;

  @override
  Future<DroneIntent> parse(String transcript, String language) async {
    return _parse(transcript, language);
  }

  DroneIntent _parse(String transcript, String language) {
    if (transcript.trim().isEmpty) {
      throw const IntentException('empty transcript');
    }
    final rawCode = resolveLanguageCode(language);
    if (rawCode == null || !_perLang.containsKey(rawCode)) {
      if (rawCode != null && stubbedLanguages.contains(rawCode)) {
        throw UnsupportedLanguageException(
            "language '$rawCode' is not yet supported");
      }
      throw UnsupportedLanguageException(
          "unsupported language: '$language'");
    }
    final code = rawCode;
    final vocab = _perLang[code]!;

    final words = _tokens(transcript);

    final negatorSuffixes = [...vocab.negatorSuffixes, ...enNegatorSuffixes];
    if (_containsAny(words, [...vocab.negators, ...enNegators]) ||
        words.any((tok) => negatorSuffixes.any(tok.endsWith))) {
      throw IntentException("negated command in: '$transcript'");
    }

    // One hit per distinct command ("hold position" and "hold" are one HOVER).
    final simpleHits = <(SimpleEntry, List<String>)>[];
    for (final entry in vocab.simple) {
      for (final phrase in entry.phrases) {
        final rest = _removeSubseq(words, _tokens(phrase));
        if (rest != null) {
          simpleHits.add((entry, rest));
          break;
        }
      }
    }
    if (simpleHits.length > 1) {
      throw IntentException("more than one command in: '$transcript'");
    }
    if (simpleHits.isNotEmpty) {
      final (entry, rest) = simpleHits.first;
      // Two of verb, direction and distance make a second (MOVE) request;
      // one alone is filler, so "stop moving" stays STOP. Strip the command's
      // own phrases first: "रुक जाओ" contains the verb "जाओ".
      final others = _stripAll(words, entry.phrases);
      final moveParts = [
        _containsAny(others, vocab.verbs),
        _containsAny(
            others, [for (final forms in vocab.directions.values) ...forms]),
        others.any((tok) =>
            RegExp(r'[0-9]').hasMatch(_normalizeDigits(tok)) ||
            _numeralWords[code]!.contains(tok)),
      ].where((part) => part).length;
      if (moveParts >= 2) {
        throw IntentException("more than one command in: '$transcript'");
      }
      final conf = rest.isEmpty ? exactConfidence : fallbackConfidence;
      return DroneIntent(
          command: entry.command, parameters: {}, confidence: conf);
    }

    List<String>? verbTokens;
    for (final verb in vocab.verbs) {
      if (_removeSubseq(words, _tokens(verb)) != null) {
        verbTokens = _tokens(verb);
        break;
      }
    }
    if (verbTokens == null) {
      throw IntentNotRecognized("no motion verb found in: '$transcript'");
    }

    String? direction;
    List<String>? dirTokens;
    for (final canonical in vocab.directions.keys) {
      for (final form in vocab.directions[canonical]!) {
        if (_removeSubseq(words, _tokens(form)) != null) {
          if (direction != null) {
            throw IntentException("more than one direction in: '$transcript'");
          }
          direction = canonical;
          dirTokens = _tokens(form);
          break;
        }
      }
    }
    if (direction == null || dirTokens == null) {
      throw IntentException("no direction found in: '$transcript'");
    }

    final number = _checkedNumber(transcript, code, words);
    if (number == null) {
      throw IntentException("no distance found in: '$transcript'");
    }
    final value = number.value;
    if (!(value > 0 && value <= maxDistanceM)) {
      throw IntentException(
          'distance_m must satisfy 0 < d <= $maxDistanceM, got $value');
    }

    var unitFound = false;
    List<String>? unitTokens;
    for (final unit in vocab.units) {
      if (_removeSubseq(words, _tokens(unit)) != null) {
        unitFound = true;
        unitTokens = _tokens(unit);
        break;
      }
    }

    List<String>? rest = List.of(words);
    for (final part in [verbTokens, dirTokens, unitTokens ?? <String>[], number.consumed]) {
      final next = _removeSubseq(rest!, part);
      if (next == null) break;
      rest = next;
    }
    final residue = rest ?? List.of(words);
    final usedWords = !RegExp(r'[0-9]').hasMatch(_normalizeDigits(transcript));
    final confidence =
        (residue.isNotEmpty || usedWords || !unitFound) ? fallbackConfidence : exactConfidence;
    final num distance = value % 1 == 0 ? value.toInt() : value;
    return DroneIntent(
      command: 'MOVE',
      parameters: {'direction': direction, 'distance_m': distance},
      confidence: confidence,
    );
  }
}

/// A matched number plus the tokens it consumed (mirrors _match_number).
class _NumberMatch {
  final num value;
  final List<String> consumed;
  const _NumberMatch(this.value, this.consumed);
}

/// Sign, unit and number-form checks shared by MOVE distances and
/// [explicitMetres]; null when [transcript] holds no number.
_NumberMatch? _checkedNumber(
    String transcript, String code, List<String> words) {
  final vocab = _perLang[code]!;
  // A minus word, or a sign before a digit ("- 5", "−5", "–5"), is negative.
  if (_containsAny(words, [...vocab.minusWords, ...enMinusWords]) ||
      RegExp('(?<!\\d)[$_dashes]\\s*\\d')
          .hasMatch(_normalizeDigits(transcript))) {
    throw IntentException("negative distance in: '$transcript'");
  }
  if (_containsAny(
      _unitWords(transcript), [...vocab.otherUnits, ...enOtherUnits])) {
    throw IntentException(
        "unsupported distance unit (metres only) in: '$transcript'");
  }
  final number = _matchNumber(transcript, code);
  if (number == null) return null;
  final leftover = _removeSubseq(words, number.consumed) ?? words;
  if (leftover.any(_numeralWords[code]!.contains)) {
    // "two hundred", "दो सौ": the matched number alone would truncate it.
    throw IntentException("unsupported number form in: '$transcript'");
  }
  return number;
}

/// Tokens with digits split off a glued unit ("50cm", "50-cm" -> "cm").
List<String> _unitWords(String transcript) => [
      for (final tok in _tokens(_normalizeDigits(transcript)))
        tok.replaceFirst(RegExp('^[\\d.,]+[$_dashes]?'), ''),
    ];

_NumberMatch? _matchNumber(String text, String language) {
  final normalized = _normalizeDigits(text);
  if (RegExp(r'(?<!\d)[.,]\d').hasMatch(normalized) ||
      RegExp(r'[\p{No}\p{Nl}]', unicode: true).hasMatch(normalized)) {
    // ".5" would read as 5 and "1½" as 1: never drop part of a number.
    throw IntentException("unsupported number form in: '$text'");
  }
  final digits = RegExp(r'-?\d+(?:\.\d+)?').allMatches(normalized).toList();
  if (digits.length > 1) {
    // "1,000", "1e3", "5-10", "10 then 20": never guess which part counts.
    throw IntentException("unsupported number form in: '$text'");
  }
  if (digits.isNotEmpty) {
    final digit = digits.first;
    final raw = digit.group(0)!;
    // Past the 64-bit int range: a huge double the range check rejects.
    final num value = raw.contains('.')
        ? double.parse(raw)
        : (int.tryParse(raw) ?? double.parse(raw));
    // Consume the original-script text so native digits leave no residue.
    return _NumberMatch(value, [text.substring(digit.start, digit.end)]);
  }
  final words = _tokens(text);
  if (language == 'en') {
    for (var i = 0; i < words.length; i++) {
      final tok = words[i];
      if (enOnes.containsKey(tok)) {
        if (enOnes[tok] == 1 &&
            i + 1 < words.length &&
            words[i + 1] == 'hundred') {
          return _NumberMatch(100, [tok, 'hundred']);
        }
        return _NumberMatch(enOnes[tok]!, [tok]);
      }
      if (enTens.containsKey(tok)) {
        if (i + 1 < words.length &&
            enOnes.containsKey(words[i + 1]) &&
            enOnes[words[i + 1]]! < 10) {
          return _NumberMatch(
              enTens[tok]! + enOnes[words[i + 1]]!, [tok, words[i + 1]]);
        }
        return _NumberMatch(enTens[tok]!, [tok]);
      }
      if (tok == 'hundred') {
        return const _NumberMatch(100, ['hundred']);
      }
    }
  } else if (language == 'te') {
    for (var i = 0; i < words.length; i++) {
      final tok = words[i];
      if (teNumbers.containsKey(tok)) {
        final value = teNumbers[tok]!;
        if (teTens.contains(value) &&
            i + 1 < words.length &&
            teUnits19.containsKey(words[i + 1])) {
          return _NumberMatch(
              value + teUnits19[words[i + 1]]!, [tok, words[i + 1]]);
        }
        return _NumberMatch(value, [tok]);
      }
    }
  } else if (language == 'hi') {
    for (final tok in words) {
      if (hiNumbers.containsKey(tok)) {
        return _NumberMatch(hiNumbers[tok]!, [tok]);
      }
    }
  }
  return null;
}

/// Hyphen, dash and minus characters, as a regex class body: a sign before a
/// digit, or the joiner in a glued unit ("50-cm").
const String _dashes = r'\-\u2010-\u2015\u2212\uFE63\uFF0D';

/// Devanagari (U+0966-096F) and Telugu (U+0C66-0C6F) digits -> ASCII.
String _normalizeDigits(String text) {
  final sb = StringBuffer();
  for (final unit in text.codeUnits) {
    if (unit >= 0x0966 && unit <= 0x096F) {
      sb.writeCharCode(0x30 + (unit - 0x0966));
    } else if (unit >= 0x0C66 && unit <= 0x0C6F) {
      sb.writeCharCode(0x30 + (unit - 0x0C66));
    } else {
      sb.writeCharCode(unit);
    }
  }
  return sb.toString();
}

/// Whitespace-separated, edge-punctuation-stripped, lowercased tokens.
/// Splits on whitespace (never on Indic vowel signs) so Indic words stay
/// whole; ASCII-letter hyphen compounds ("twenty-three") become two tokens.
List<String> _tokens(String text) {
  final out = <String>[];
  for (final raw in _splitHyphens(text).toLowerCase().split(RegExp(r'\s+'))) {
    final tok = _stripEdge(raw);
    if (tok.isNotEmpty) out.add(tok);
  }
  return out;
}

String _splitHyphens(String text) {
  final sb = StringBuffer();
  for (var i = 0; i < text.length; i++) {
    final c = text[i];
    if (c == '-' &&
        i > 0 &&
        i < text.length - 1 &&
        _isAsciiLetter(text[i - 1]) &&
        _isAsciiLetter(text[i + 1])) {
      sb.write(' ');
    } else {
      sb.write(c);
    }
  }
  return sb.toString();
}

bool _isAsciiLetter(String c) {
  final unit = c.codeUnitAt(0);
  return (unit >= 0x41 && unit <= 0x5A) || (unit >= 0x61 && unit <= 0x7A);
}

/// Strip edge punctuation. Approximation of Python's unicodedata-category
/// strip (P/S/Z/C): Dart RegExp has no Unicode property escapes, so a
/// fixed set of ASCII + Indic punctuation is stripped. Behavior is
/// identical on all Task 3 reference inputs.
const String _edgePunct = ' \t\n\r!"#\$%&\'()*+,-./:;<=>?@[\\]^_`{|}~।॥‘’“”–—…?!;:';

String _stripEdge(String tok) {
  var i = 0;
  var j = tok.length;
  while (i < j && _edgePunct.contains(tok[i])) {
    i++;
  }
  while (j > i && _edgePunct.contains(tok[j - 1])) {
    j--;
  }
  return tok.substring(i, j);
}

/// Remove the first contiguous occurrence of [sub]; null if absent.
List<String>? _removeSubseq(List<String> tokens, List<String> sub) {
  for (var i = 0; i + sub.length <= tokens.length; i++) {
    var match = true;
    for (var k = 0; k < sub.length; k++) {
      if (tokens[i + k] != sub[k]) {
        match = false;
        break;
      }
    }
    if (match) {
      return [...tokens.sublist(0, i), ...tokens.sublist(i + sub.length)];
    }
  }
  return null;
}

/// True when any form occurs as a contiguous run of whole tokens.
bool _containsAny(List<String> tokens, List<String> forms) =>
    forms.any((form) => _removeSubseq(tokens, _tokens(form)) != null);

/// Remove every occurrence of every phrase, longest phrases first.
List<String> _stripAll(List<String> tokens, List<String> phrases) {
  final ordered = [...phrases]
    ..sort((a, b) => _tokens(b).length.compareTo(_tokens(a).length));
  var out = tokens;
  for (final phrase in ordered) {
    final sub = _tokens(phrase);
    var rest = _removeSubseq(out, sub);
    while (rest != null) {
      out = rest;
      rest = _removeSubseq(out, sub);
    }
  }
  return out;
}
