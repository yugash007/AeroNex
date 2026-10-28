import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/flight/domain/flight_command.dart';
import 'package:aeronex/features/flight/domain/flight_plan.dart';

FlightCommand tool(String name, [Map<String, dynamic> arguments = const {}]) =>
    FlightCommand.fromToolCalls([
      {'name': name, 'arguments': arguments},
    ]);

FlightCommand takeoff(num altitude) =>
    tool('takeoff', {'altitude_m': altitude});

FlightCommand move(String direction, num distance) =>
    tool('move_relative', {'direction': direction, 'distance_m': distance});

FlightPlan plan(List<FlightCommand> commands) => FlightPlan([
      for (final command in commands)
        PlanStep(command, source: StepSource.rules),
    ]);

Matcher rejects(String message) => throwsA(
    isA<FormatException>().having((e) => e.message, 'message', message));

void main() {
  test('describes commands readably and keeps toString', () {
    expect(takeoff(3).description, 'Take off to 3 m');
    expect(takeoff(3.0).description, 'Take off to 3 m');
    expect(move('forward', 3).description, 'Move forward 3 m');
    expect(move('up', 2.5).description, 'Move up 2.5 m');
    expect(move('backward', 10).description, 'Move backward 10 m');
    expect(tool('hold').description, 'Hold position');
    expect(tool('land').description, 'Land');
    expect(tool('get_status').description, 'Report status');
    expect(takeoff(3).toString(), 'takeoff({altitude_m: 3})');
  });

  test('keeps an immutable ordered plan and numbers it', () {
    final steps = [
      PlanStep(takeoff(3), source: StepSource.rules, clause: 'take off to 3 m'),
      PlanStep(move('forward', 3), source: StepSource.ai),
      PlanStep(tool('land'), source: StepSource.rules),
    ];
    final flightPlan = FlightPlan(steps);
    steps.clear();
    expect(flightPlan.steps, hasLength(3));
    expect(flightPlan.steps.first.clause, 'take off to 3 m');
    expect(flightPlan.steps[1].source, StepSource.ai);
    expect(flightPlan.steps[1].clause, '');
    expect(flightPlan.toString(),
        '1. Take off to 3 m; 2. Move forward 3 m; 3. Land');
    expect(() => flightPlan.steps.add(flightPlan.steps.first),
        throwsUnsupportedError);
  });

  test('wraps a single command', () {
    final single = FlightPlan.single(tool('hold'), source: StepSource.rules);
    expect(single.steps.single.command.name, 'hold');
    expect(single.steps.single.source, StepSource.rules);
    expect(single.steps.single.clause, '');
    final fixture = FlightPlan.single(tool('land'),
        source: StepSource.fixture, clause: 'land');
    expect(fixture.steps.single.source, StepSource.fixture);
    expect(fixture.steps.single.clause, 'land');
    expect(fixture.toString(), '1. Land');
  });

  test('rejects empty and over-long plans', () {
    expect(FlightPlan.maxSteps, 5);
    expect(() => plan([]), rejects('Say a command first.'));
    expect(plan(List.filled(5, move('left', 1))).steps, hasLength(5));
    expect(() => plan(List.filled(6, move('left', 1))),
        rejects('Say at most 5 steps at a time.'));
  });

  test('enforces step positions', () {
    expect(() => plan([tool('get_status'), tool('land')]),
        rejects('Ask for status on its own.'));
    expect(() => plan([move('up', 1), tool('get_status')]),
        rejects('Ask for status on its own.'));
    expect(() => plan([move('forward', 3), takeoff(3)]),
        rejects('Takeoff can only be the first step.'));
    expect(() => plan([takeoff(3), takeoff(4)]),
        rejects('Takeoff can only be the first step.'));
    expect(() => plan([tool('land'), move('forward', 3)]),
        rejects('Land can only be the last step.'));
    expect(() => plan([tool('land'), tool('land')]),
        rejects('Land can only be the last step.'));
    expect(plan([tool('get_status')]).steps, hasLength(1));
    expect(plan([takeoff(3), tool('hold'), tool('land')]).steps, hasLength(3));
  });

  test('projects altitude from the takeoff height', () {
    expect(plan([takeoff(3), move('up', 7)]).steps, hasLength(2));
    expect(plan([takeoff(3), move('forward', 5), move('down', 2)]).steps,
        hasLength(3));
    // 1.4 - 0.4 is 0.9999999999999999 in floating point.
    expect(plan([takeoff(1.4), move('down', 0.4)]).steps, hasLength(2));
    expect(() => plan([takeoff(3), move('up', 7.5)]),
        rejects('Step 2 would leave the 1–10 m altitude range.'));
    expect(() => plan([takeoff(3), move('forward', 5), move('down', 2.5)]),
        rejects('Step 3 would leave the 1–10 m altitude range.'));
    expect(() => plan([takeoff(5), move('up', 4), move('up', 2), tool('land')]),
        rejects('Step 3 would leave the 1–10 m altitude range.'));
    // Without a takeoff the start altitude is unknown here.
    expect(plan([move('up', 10), move('up', 10)]).steps, hasLength(2));
  });

  test('requires a takeoff first when the vehicle is on the ground', () {
    const hint = 'The drone is on the ground. Start with a takeoff height, '
        'for example: take off to 3 m and fly forward 3 m.';
    expect(() => plan([move('forward', 3)]).checkVehicle(landed: true),
        rejects(hint));
    expect(() => plan([tool('hold'), tool('land')]).checkVehicle(landed: true),
        rejects(hint));
    plan([tool('get_status')]).checkVehicle(landed: true);
    plan([takeoff(3), move('up', 2)]).checkVehicle(landed: true, altitude: 0);
    // On the ground the reported altitude is ignored; takeoff sets the start.
    plan([takeoff(3), move('down', 1)]).checkVehicle(landed: true, altitude: 0);
  });

  test('rejects takeoff and projects altitude when airborne', () {
    expect(() => plan([takeoff(3)]).checkVehicle(landed: false),
        rejects('The drone is already flying. Remove the takeoff step.'));
    expect(
        () => plan([move('forward', 3), move('up', 2)])
            .checkVehicle(landed: false, altitude: 9),
        rejects('Step 2 would leave the 1–10 m altitude range.'));
    expect(
        () =>
            plan([move('down', 1.5)]).checkVehicle(landed: false, altitude: 2),
        rejects('Step 1 would leave the 1–10 m altitude range.'));
    plan([move('down', 1), tool('land')])
        .checkVehicle(landed: false, altitude: 2);
    plan([move('up', 10), move('up', 10)]).checkVehicle(landed: false);
    plan([tool('get_status')]).checkVehicle(landed: false, altitude: 0.2);
    // Only up/down moves change the projected altitude.
    plan([move('forward', 3)]).checkVehicle(landed: false, altitude: 0.5);
  });
}
