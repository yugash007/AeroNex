/// Dart mirror of Task 3 (edge/tests/test_intent.py).
/// Run on a Flutter machine: `flutter test test/intent_test.dart`.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/core/errors.dart';
import 'package:aeronex/features/intent/data/rule_intent_engine.dart';

void main() {
  final engine = RuleBasedIntentEngine();

  test('en move forward 10m', () async {
    final intent = await engine.parse('move forward 10 meters', 'en');
    expect(intent.command, equals('MOVE'));
    expect(intent.parameters, equals({'direction': 'FORWARD', 'distance_m': 10}));
    expect(intent.confidence, equals(0.9));
  });

  test('te move forward 10m', () async {
    final intent = await engine.parse('10 మీటర్లు ముందుకు వెళ్ళు', 'te');
    expect(intent.command, equals('MOVE'));
    expect(intent.parameters, equals({'direction': 'FORWARD', 'distance_m': 10}));
    expect(intent.confidence, equals(0.9));
  });

  test('hi move forward 10m', () async {
    final intent = await engine.parse('10 मीटर आगे जाओ', 'hi');
    expect(intent.command, equals('MOVE'));
    expect(intent.parameters, equals({'direction': 'FORWARD', 'distance_m': 10}));
    expect(intent.confidence, equals(0.9));
  });

  test('takeoff and land', () async {
    expect((await engine.parse('take off', 'en')).command, equals('TAKEOFF'));
    expect((await engine.parse('land', 'en')).command, equals('LAND'));
    expect((await engine.parse('టేకాఫ్', 'te')).command, equals('TAKEOFF'));
    expect((await engine.parse('लैंड', 'hi')).command, equals('LAND'));
  });

  test('hover and stop', () async {
    expect((await engine.parse('hover', 'en')).command, equals('HOVER'));
    expect((await engine.parse('stop', 'en')).command, equals('STOP'));
    expect((await engine.parse('హోవర్', 'te')).command, equals('HOVER'));
    expect((await engine.parse('रुको', 'hi')).command, equals('STOP'));
  });

  test('gibberish raises intent error', () async {
    await expectLater(
      engine.parse('banana photosynthesize purple', 'en'),
      throwsA(isA<IntentException>()),
    );
  });

  test('negative distance rejected', () async {
    await expectLater(
      engine.parse('move forward -5 meters', 'en'),
      throwsA(isA<IntentException>()),
    );
  });

  test('unsupported language tamil stub', () async {
    expect(supportedLanguages, equals(['en', 'hi', 'te']));
    for (final code in ['ta', 'kn', 'ml']) {
      await expectLater(
        engine.parse('take off', code),
        throwsA(isA<UnsupportedLanguageException>()),
      );
    }
  });

  // --- Review fixes: reject rather than guess (mirrors test_intent.py) ---

  Future<void> expectRejected(String text, String language) => expectLater(
        engine.parse(text, language),
        throwsA(isA<IntentException>()),
        reason: text,
      );

  test('negated utterance rejected', () async {
    for (final c in const [
      ['do not take off', 'en'],
      ["don't land", 'en'],
      ['don\u2019t land', 'en'],
      ['dont hover', 'en'],
      ['never launch', 'en'],
      ['you cannot land', 'en'],
      ['no take off', 'en'],
      ["don't stop", 'en'], // a negated STOP is refused too, never guessed
      ["don't move forward 5 meters", 'en'],
      ["won't land", 'en'],
      ["you shouldn't take off", 'en'],
      ["didn't land", 'en'],
      ['don\u2018t land', 'en'],
      ['wont land', 'en'],
      ['टेकऑफ मत करो', 'hi'],
      ['लैंड नहीं', 'hi'],
      ['टेकऑफ ना करो', 'hi'],
      ['लैंड न करो', 'hi'],
      ['लैंड नही करो', 'hi'],
      ['టేకాఫ్ వద్దు', 'te'],
      ['ల్యాండ్ చేయకు', 'te'],
      ['టేకాఫ్ చేయవద్దు', 'te'], // fused negative imperative
      ['టేకాఫ్ చేయకండి', 'te'], // polite prohibitive
      ['టేకాఫ్ చెయ్యకు', 'te'], // colloquial spelling
      ['ల్యాండ్ చేయొద్దు', 'te'],
      ['ల్యాండ్ చెయ్యొద్దు', 'te'],
      ['10 మీటర్లు ముందుకు వెళ్ళు కాదు', 'te'],
    ]) {
      await expectRejected(c[0], c[1]);
    }
  });

  test('negators never match command vocabulary', () async {
    final negators = [...enNegators, ...hiNegators, ...teNegators];
    final suffixes = [
      ...enNegatorSuffixes,
      ...hiNegatorSuffixes,
      ...teNegatorSuffixes,
    ];
    for (final form in [
      ...enMoveVerbs,
      ...teMoveVerbs,
      ...hiMoveVerbs,
      for (final forms in [
        ...enDirections.values,
        ...teDirections.values,
        ...hiDirections.values,
      ])
        ...forms,
      ...enUnits,
      ...teUnits,
      ...hiUnits,
      for (final entry in [...enSimple, ...teSimple, ...hiSimple])
        ...entry.phrases,
      ...enOnes.keys,
      ...enTens.keys,
      ...teNumbers.keys,
      ...hiNumbers.keys,
    ]) {
      for (final tok in form.toLowerCase().split(' ')) {
        expect(negators.contains(tok) || suffixes.any(tok.endsWith), isFalse,
            reason: tok);
      }
    }
  });

  test('compound utterance rejected', () async {
    for (final c in const [
      ['take off and move forward 5 meters', 'en'],
      ['fly forward 10 meters then land', 'en'],
      ['take off then land', 'en'],
      ['stop and land', 'en'],
      ['move forward and back 5 meters', 'en'],
      ['move forward 5 meters and then move left 3 meters', 'en'],
      ['land 5 meters forward', 'en'], // distance + direction, no verb
      ['take off and fly 5 meters', 'en'],
      ['take off and go up', 'en'],
      ['go ahead and land', 'en'], // "ahead" is FORWARD
      ['टेकऑफ और लैंड', 'hi'],
      ['रुको और आगे 5 मीटर जाओ', 'hi'],
      ['టేకాఫ్ తర్వాత ల్యాండ్', 'te'],
      ['5 మీటర్లు ముందుకు వెనక్కి వెళ్ళు', 'te'],
    ]) {
      await expectRejected(c[0], c[1]);
    }
  });

  test('single command with overlapping tokens still parses', () async {
    for (final c in const [
      ['hold position', 'en', 'HOVER', 0.9],
      ['hold position hold', 'en', 'HOVER', 0.7], // one command, counted once
      ['please stop now', 'en', 'STOP', 0.7],
      ['stop moving', 'en', 'STOP', 0.7], // a lone motion word is not a second request
      ['stop flying', 'en', 'STOP', 0.7],
      ['stop right now', 'en', 'STOP', 0.7],
      ['चलो लैंड करो', 'hi', 'LAND', 0.7],
      ['रुक जाओ', 'hi', 'STOP', 0.9], // STOP phrase contains the verb जाओ
      ['रुको रुक जाओ', 'hi', 'STOP', 0.7],
      ['ऊपर उठो', 'hi', 'TAKEOFF', 0.9], // contains UP ऊपर
      ['పైకి లే', 'te', 'TAKEOFF', 0.9], // contains UP పైకి
    ]) {
      final intent = await engine.parse(c[0] as String, c[1] as String);
      expect(intent.command, equals(c[2]), reason: c[0] as String);
      expect(intent.parameters, isEmpty);
      expect(intent.confidence, equals(c[3]), reason: c[0] as String);
    }
  });

  test('overlapping forms of one direction still parse', () async {
    final intent = await engine.parse('ఎడమ వైపుకు 5 మీటర్లు వెళ్ళు', 'te');
    expect(intent.parameters, equals({'direction': 'LEFT', 'distance_m': 5}));
    expect(intent.confidence, equals(0.9));
  });

  test('non-metre unit rejected', () async {
    for (final c in const [
      ['move forward 50 centimeters', 'en'],
      ['go left 30 cm', 'en'],
      ['move forward 10 feet', 'en'],
      ['move forward 1 foot', 'en'],
      ['move up 2 kilometers', 'en'],
      ['move back 5 inches', 'en'],
      ['move right 3 yards', 'en'],
      ['move forward 1 mile', 'en'],
      ['move forward 500 mm', 'en'],
      ['move forward 50cm', 'en'],
      ['move forward 50-cm', 'en'],
      ['move forward 3 milli meters', 'en'], // split prefix, never 3 m
      ['move forward 50 centi meters', 'en'],
      ['move forward 2 kilo meters', 'en'],
      ['move forward 50-centimeter', 'en'],
      ['move forward 50\u2013cm', 'en'],
      ['50-सेंटीमीटर आगे जाओ', 'hi'],
      ['50 सेंटीमीटर आगे जाओ', 'hi'],
      ['2 किलोमीटर आगे जाओ', 'hi'],
      ['10 फीट आगे जाओ', 'hi'],
      ['50 cm आगे जाओ', 'hi'], // Latin unit inside a Hindi transcript
      ['50 సెంటీమీటర్లు ముందుకు వెళ్ళు', 'te'],
      ['10 అడుగులు ముందుకు వెళ్ళు', 'te'],
    ]) {
      await expectRejected(c[0], c[1]);
    }
  });

  test('metre abbreviation and missing unit still parse', () async {
    expect((await engine.parse('move forward 10 m', 'en')).parameters,
        equals({'direction': 'FORWARD', 'distance_m': 10}));
    expect((await engine.parse('move forward 10m', 'en')).parameters,
        equals({'direction': 'FORWARD', 'distance_m': 10}));
    expect((await engine.parse('move forward 10 metres', 'en')).confidence,
        equals(0.9));
    final bare = await engine.parse('move forward 10', 'en');
    expect(bare.parameters, equals({'direction': 'FORWARD', 'distance_m': 10}));
    expect(bare.confidence, equals(0.7));
  });

  test('truncated number form rejected', () async {
    for (final c in const [
      ['move forward 1,000 meters', 'en'],
      ['move forward 1e3 meters', 'en'],
      ['move forward 5-10 meters', 'en'],
      ['move forward 1 000 meters', 'en'],
      ['move forward 2,5 meters', 'en'],
      ['move forward 10 meters then 20 meters', 'en'],
      ['move forward two hundred meters', 'en'],
      ['move forward two thousand meters', 'en'],
      ['move forward one hundred fifty meters', 'en'],
      ['move forward two point five meters', 'en'],
      ['move forward two and a half meters', 'en'],
      ['move forward 10 hundred meters', 'en'],
      ['move forward .5 meters', 'en'], // would read as 5
      ['move forward 1\u00bd meters', 'en'], // would read as 1
      ['.५ मीटर आगे जाओ', 'hi'],
      ['दो सौ मीटर आगे जाओ', 'hi'],
      ['एक सौ मीटर आगे जाओ', 'hi'],
      ['दो हज़ार मीटर आगे जाओ', 'hi'],
      ['१,००० मीटर आगे जाओ', 'hi'],
      ['రెండు వందల మీటర్లు ముందుకు వెళ్ళు', 'te'],
      ['రెండు వేల మీటర్లు ముందుకు వెళ్ళు', 'te'],
    ]) {
      await expectRejected(c[0], c[1]);
    }
  });

  test('supported number forms still parse', () async {
    for (final c in const [
      ['move forward 2.5 meters', 'en', 2.5],
      ['move forward 10 meters.', 'en', 10],
      ['move forward one hundred meters', 'en', 100],
      ['move forward hundred meters', 'en', 100],
      ['move forward twenty three meters', 'en', 23],
      ['move forward twenty-three meters', 'en', 23],
      ['सौ मीटर आगे जाओ', 'hi', 100],
      ['వంద మీటర్లు ముందుకు వెళ్ళు', 'te', 100],
      ['ఇరవై ఒక మీటర్లు ముందుకు వెళ్ళు', 'te', 21],
    ]) {
      final intent = await engine.parse(c[0] as String, c[1] as String);
      expect(intent.parameters['distance_m'], equals(c[2]),
          reason: c[0] as String);
    }
  });

  test('huge digit string throws IntentException', () async {
    for (final digits in ['9' * 20, '9' * 5000, '${'9' * 5000}.5']) {
      await expectRejected('move forward $digits meters', 'en');
    }
  });

  test('spoken negative distance rejected', () async {
    for (final c in const [
      ['move forward negative five meters', 'en'],
      ['move forward - 5 meters', 'en'],
      ['move forward \u22125 meters', 'en'],
      ['move forward \u20135 meters', 'en'], // en dash
      ['move forward \uff0d5 meters', 'en'], // fullwidth hyphen-minus
      ['move forward \u20105 meters', 'en'], // hyphen
      ['माइनस पाँच मीटर आगे जाओ', 'hi'],
      ['ऋण 5 मीटर आगे जाओ', 'hi'],
      ['minus 5 मीटर आगे जाओ', 'hi'],
      ['మైనస్ 5 మీటర్లు ముందుకు వెళ్ళు', 'te'],
    ]) {
      await expectRejected(c[0], c[1]);
    }
  });

  test('native digits get exact confidence', () async {
    for (final c in const [
      ['౧౦ మీటర్లు ముందుకు వెళ్ళు', 'te'],
      ['१० मीटर आगे जाओ', 'hi'],
    ]) {
      final intent = await engine.parse(c[0], c[1]);
      expect(intent.parameters, equals({'direction': 'FORWARD', 'distance_m': 10}));
      expect(intent.confidence, equals(0.9), reason: c[0]);
    }
    expect((await engine.parse('कृपया १० मीटर आगे जाओ', 'hi')).confidence,
        equals(0.7));
  });
}
