import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/flight/domain/flight_command.dart';

void main() {
  test('validates one immutable tool call', () {
    final arguments = <String, dynamic>{'altitude_m': 3};
    final command = FlightCommand.fromToolCalls([
      {'name': 'takeoff', 'arguments': arguments},
    ]);
    arguments['altitude_m'] = 100;
    expect(command.name, 'takeoff');
    expect(command.arguments, {'altitude_m': 3});
    expect(() => command.arguments['altitude_m'] = 100, throwsUnsupportedError);
  });

  test('rejects malformed calls and unsafe simulator arguments', () {
    final invalid = <Object?>[
      null,
      [],
      [
        {'name': 'land', 'arguments': {}},
        {
          'name': 'takeoff',
          'arguments': {'altitude_m': 3}
        },
      ],
      [
        {'name': 'disarm', 'arguments': {}}
      ],
      [
        {'name': 'takeoff', 'arguments': {}}
      ],
      [
        {
          'name': 'takeoff',
          'arguments': {'altitude_m': '3'}
        }
      ],
      [
        {
          'name': 'takeoff',
          'arguments': {'altitude_m': double.nan}
        }
      ],
      [
        {
          'name': 'takeoff',
          'arguments': {'altitude_m': double.infinity}
        }
      ],
      [
        {
          'name': 'takeoff',
          'arguments': {'altitude_m': 0}
        }
      ],
      [
        {
          'name': 'takeoff',
          'arguments': {'altitude_m': 11}
        }
      ],
      [
        {
          'name': 'land',
          'arguments': {'force': true}
        }
      ],
      [
        {'name': 'land', 'arguments': {}, 'execute': true}
      ],
      [
        {
          'name': 'move_relative',
          'arguments': {'direction': 'north', 'distance_m': 1}
        }
      ],
      [
        {
          'name': 'move_relative',
          'arguments': {'direction': 'forward', 'distance_m': 0}
        }
      ],
      [
        {
          'name': 'move_relative',
          'arguments': {'direction': 'forward', 'distance_m': 11}
        }
      ],
    ];
    for (final calls in invalid) {
      expect(() => FlightCommand.fromToolCalls(calls), throwsFormatException,
          reason: '$calls');
    }
  });

  test('allows bounded movements and parameterless status land hold', () {
    for (final name in ['get_status', 'land', 'hold']) {
      expect(
          FlightCommand.fromToolCalls([
            {'name': name, 'arguments': {}},
          ]).name,
          name);
    }
    for (final direction in [
      'forward',
      'backward',
      'left',
      'right',
      'up',
      'down'
    ]) {
      expect(
          FlightCommand.fromToolCalls([
            {
              'name': 'move_relative',
              'arguments': {'direction': direction, 'distance_m': 10}
            },
          ]).arguments['direction'],
          direction);
    }
  });

  test('accepts the flight memory tools with the service\'s rules (2026-10-28)', () {
    FlightCommand call(String name, [Map<String, Object?> arguments = const {}]) =>
        FlightCommand.fromToolCalls([
          {'name': name, 'arguments': arguments}
        ]);
    expect(call('return_home').description, 'Return home');
    expect(call('go_back').description, 'Go back to the previous position');
    expect(call('retrace').description, 'Retrace the flown path');
    expect(call('goto_point', {'point': 2}).description, 'Go to point 2');
    expect(call('mark_point', {'point': 9.0}).description, 'Mark point 9');
    expect(call('retrace').isMemoryMotion, isTrue);
    expect(call('mark_point', {'point': 1}).isMemoryMotion, isFalse);
    expect(() => call('return_home', {'x': 1}),
        throwsA(isA<FormatException>().having((e) => e.message, 'message', 'This tool accepts no arguments.')));
    for (final bad in <Object?>[0, 10, 2.5, '2', null, -1]) {
      expect(() => call('goto_point', {'point': bad}),
          throwsA(isA<FormatException>().having((e) => e.message, 'message', 'Specify a point number 1–9.')),
          reason: '$bad');
    }
    expect(() => call('mark_point'), throwsFormatException);
  });
}
