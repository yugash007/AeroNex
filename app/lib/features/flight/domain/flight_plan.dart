import 'flight_command.dart';

enum StepSource { rules, ai, fixture }

class PlanStep {
  final FlightCommand command;
  final StepSource source;

  /// The spoken or typed clause this step came from.
  final String clause;

  const PlanStep(this.command, {required this.source, this.clause = ''});
}

/// An ordered, structurally valid list of proposals, confirmed once as a whole.
class FlightPlan {
  static const maxSteps = 5;
  final List<PlanStep> steps;

  FlightPlan(List<PlanStep> steps) : steps = List.unmodifiable(steps) {
    if (this.steps.isEmpty) throw const FormatException('Say a command first.');
    if (this.steps.length > maxSteps) {
      throw const FormatException('Say at most 5 steps at a time.');
    }
    final names = [for (final step in this.steps) step.command.name];
    if (names.length > 1 && names.contains('get_status')) {
      throw const FormatException('Ask for status on its own.');
    }
    if (names.lastIndexOf('takeoff') > 0) {
      throw const FormatException('Takeoff can only be the first step.');
    }
    final land = names.indexOf('land');
    if (land >= 0 && land != names.length - 1) {
      throw const FormatException('Land can only be the last step.');
    }
    if (names.first == 'takeoff') {
      _checkAltitude(this.steps.first.command.arguments['altitude_m'] as num);
    }
  }

  /// One step. [source] has no default: the service refuses `ai` steps from
  /// the channel (only its own AI path makes them), so every caller says
  /// where the step came from.
  factory FlightPlan.single(FlightCommand command,
          {required StepSource source, String clause = ''}) =>
      FlightPlan([PlanStep(command, source: source, clause: clause)]);

  /// Validates the plan against live vehicle state before it is shown.
  void checkVehicle({required bool landed, num? altitude}) {
    final first = steps.first.command.name;
    if (landed && first != 'takeoff' && first != 'get_status') {
      throw const FormatException(
          'The drone is on the ground. Start with a takeoff height, '
          'for example: take off to 3 m and fly forward 3 m.');
    }
    if (!landed && steps.any((step) => step.command.name == 'takeoff')) {
      throw const FormatException(
          'The drone is already flying. Remove the takeoff step.');
    }
    if (!landed && altitude != null) _checkAltitude(altitude);
  }

  /// Simulates up/down moves from [start]; the executor still re-checks each.
  void _checkAltitude(num start) {
    var altitude = start;
    for (var i = 0; i < steps.length; i++) {
      final arguments = steps[i].command.arguments;
      final direction = arguments['direction'];
      if (direction != 'up' && direction != 'down') continue;
      final distance = arguments['distance_m'] as num;
      altitude += direction == 'up' ? distance : -distance;
      // Tolerate float rounding, e.g. 1.4 m down 0.4 m lands on the 1 m floor.
      if (altitude < 1 - 1e-9 || altitude > 10 + 1e-9) {
        throw FormatException(
            'Step ${i + 1} would leave the 1–10 m altitude range.');
      }
    }
  }

  @override
  String toString() => [
        for (var i = 0; i < steps.length; i++)
          '${i + 1}. ${steps[i].command.description}',
      ].join('; ');
}
