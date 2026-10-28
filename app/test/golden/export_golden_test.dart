/// Golden plan vectors (architecture v2): the frozen contract the Kotlin
/// parser (`PlanParserGoldenTest`) and validator (`PlanValidatorGoldenTest`)
/// are gated on. The v0.2.0 Dart parser that recorded them was removed in T12
/// (speech and parsing run in the session service), so this is now a
/// fixture-integrity test: the file keeps its shape, its size, its spec-only
/// vectors and its plan-rule coverage, and every recorded plan still gives
/// its recorded outcome under the Dart FlightPlan rules (the client's
/// tap-pad plans). README.md states the contract. To change a vector, edit
/// the JSON by hand, update [vectorCount], and run both Kotlin gates.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/flight/domain/flight_command.dart';
import 'package:aeronex/features/flight/domain/flight_plan.dart';

final _vectors = File('test/golden/plan_vectors.json');

/// The vectors recorded from v0.2.0 plus the spec's [unk] vectors.
const vectorCount = 3431;

/// The selectable languages, as the v0.2.0 screen passed them.
const goldenLanguages = ['en-IN', 'hi-IN', 'te-IN', 'ta-IN', 'kn-IN', 'ml-IN'];

const Map<String, Object?> _landed = {'landed': true, 'altitude': 0};
const Map<String, Object?> _airborne3 = {'landed': false, 'altitude': 3};
const _standardVehicles = <Map<String, Object?>?>[null, _landed, _airborne3];

/// Spec 4.9's `[unk]` vectors (not v0.2.0 behaviour), recorded verbatim.
final specVectors = <Map<String, Object?>>[
  for (final vehicle in [null, _airborne3])
    {
      'input': '[unk] hold position',
      'language': 'en-IN',
      'vehicle': vehicle,
      'expect': {
        'steps': [
          {'name': 'hold', 'arguments': {}, 'source': 'rules'},
        ],
      },
      'spec_only': true,
      'note': 'spec 4.9: a leading [unk] is tolerated before an exact '
          'STOP, HOLD or status template',
    },
  for (final vehicle in _standardVehicles)
    {
      'input': '[unk] what is the [unk] fly',
      'language': 'en-IN',
      'vehicle': vehicle,
      'expect': {'error': 'rejected'},
      'spec_only': true,
      'note': 'spec 4.9: [unk] beside anything but an exact STOP, HOLD or '
          'status template rejects the utterance',
    },
];

/// Every FlightPlan and checkVehicle rejection, which the plan vectors cover.
const _planRuleMessages = [
  'Say a command first.',
  'Say at most 5 steps at a time.',
  'Ask for status on its own.',
  'Takeoff can only be the first step.',
  'Land can only be the last step.',
  'Step 1 would leave the 1–10 m altitude range.',
  'Step 2 would leave the 1–10 m altitude range.',
  'Step 3 would leave the 1–10 m altitude range.',
  'The drone is on the ground. Start with a takeoff height, '
      'for example: take off to 3 m and fly forward 3 m.',
  'The drone is already flying. Remove the takeoff step.',
];

/// The PlanValidator gate: a vector's `plan` alone, through the FlightPlan
/// rules and the vehicle check, gives the vector's `expect`.
Map<String, Object?> validatePlan(List plan, Map? vehicle) {
  try {
    final checked = FlightPlan([
      for (final step in plan)
        PlanStep(FlightCommand.fromToolCalls([step]), source: StepSource.rules),
    ]);
    if (vehicle != null) {
      checked.checkVehicle(
          landed: vehicle['landed'] as bool,
          altitude: vehicle['altitude'] as num?);
    }
    return {
      'steps': [
        for (final step in checked.steps)
          {
            'name': step.command.name,
            'arguments': step.command.arguments,
            'source': step.source.name,
          },
      ],
    };
  } on FormatException catch (e) {
    return {'error': e.message};
  }
}

/// JSON with map keys sorted, for comparing values read back from the file.
String _canonical(Object? value) => jsonEncode(_sorted(value));

Object? _sorted(Object? value) => switch (value) {
      Map() => {
          for (final key in value.keys.map((key) => '$key').toList()..sort())
            key: _sorted(value[key]),
        },
      List() => [for (final item in value) _sorted(item)],
      _ => value,
    };

void main() {
  late List<Map<String, dynamic>> recorded;

  setUpAll(() {
    recorded = [
      for (final vector in jsonDecode(_vectors.readAsStringSync()) as List)
        vector as Map<String, dynamic>,
    ];
  });

  test('plan_vectors.json keeps its size, languages, vehicles and the [unk] '
      'spec vectors', () {
    expect(recorded, hasLength(vectorCount));
    expect({for (final vector in recorded) vector['language']},
        containsAll(goldenLanguages));
    expect({for (final vector in recorded) _canonical(vector['vehicle'])},
        containsAll([for (final v in _standardVehicles) _canonical(v)]));
    expect([
      for (final vector in recorded)
        if (vector['spec_only'] == true) _canonical(vector),
    ], [
      for (final vector in specVectors) _canonical(vector)
    ]);
    final keys = {'input', 'language', 'vehicle', 'plan', 'expect', 'spec_only', 'note'};
    for (final vector in recorded) {
      expect(keys, containsAll(vector.keys), reason: _canonical(vector));
      expect(vector['input'], isA<String>());
      expect(goldenLanguages, contains(vector['language']));
      final expected = vector['expect'] as Map;
      expect(expected.containsKey('steps') != expected.containsKey('error'),
          isTrue,
          reason: _canonical(vector));
      if (expected['error'] == 'ai_required') {
        expect(expected['clause'], isA<String>(), reason: _canonical(vector));
      }
    }
    expect({
      for (final vector in recorded)
        if (vector.containsKey('plan')) (vector['expect'] as Map)['error'],
    }, containsAll(_planRuleMessages));
  });

  test('every vector line is unique', () {
    final seen = <String>{};
    final repeated = [
      for (final vector in recorded)
        if (!seen.add(_canonical(
            [vector['input'], vector['language'], vector['vehicle']])))
          _canonical(vector),
    ];
    expect(repeated, isEmpty);
  });

  test('every recorded plan alone gives its recorded outcome', () {
    final mismatched = [
      for (final vector in recorded)
        if (vector.containsKey('plan') &&
            _canonical(validatePlan(
                    vector['plan'] as List, vector['vehicle'] as Map?)) !=
                _canonical(vector['expect']))
          _canonical(vector),
    ];
    expect(mismatched, isEmpty,
        reason: 'Mismatched ${mismatched.length}: ${mismatched.take(5)}');
  });
}
