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
}
