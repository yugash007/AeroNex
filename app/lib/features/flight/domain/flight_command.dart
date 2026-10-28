/// A dispatch refused before any packet was sent: the vehicle state is unchanged.
class NotSentError extends StateError {
  NotSentError(super.message);
}

/// A validated tool proposal, not authorization to send a flight command.
class FlightCommand {
  final String name;
  final Map<String, dynamic> arguments;

  FlightCommand._(this.name, Map<String, dynamic> arguments)
      : arguments = Map.unmodifiable(arguments);

  factory FlightCommand.fromToolCalls(Object? calls) {
    if (calls is! List || calls.length != 1) {
      throw const FormatException('Exactly one tool call is required.');
    }
    final call = calls.single;
    if (call is! Map || !_keys(call, {'name', 'arguments'})) {
      throw const FormatException('Expected tool name and arguments only.');
    }
    final name = call['name'];
    final args = call['arguments'];
    if (name is! String || args is! Map) {
      throw const FormatException('Invalid tool name or arguments.');
    }
    switch (name) {
      case 'get_status':
      case 'land':
      case 'hold':
      case 'return_home':
      case 'go_back':
      case 'retrace':
        if (args.isNotEmpty) {
          throw const FormatException('This tool accepts no arguments.');
        }
      case 'goto_point':
      case 'mark_point':
        if (!_keys(args, {'point'}) || !_pointNumber(args['point'])) {
          throw const FormatException('Specify a point number 1–9.');
        }
      case 'takeoff':
        if (!_keys(args, {'altitude_m'}) ||
            !_number(args['altitude_m'], minimum: 1)) {
          throw const FormatException('Specify a takeoff altitude of 1–10 m.');
        }
      case 'move_relative':
        if (!_keys(args, {'direction', 'distance_m'}) ||
            !const {'forward', 'backward', 'left', 'right', 'up', 'down'}
                .contains(args['direction']) ||
            !_number(args['distance_m'], minimum: 0, exclusive: true)) {
          throw const FormatException(
              'Specify a direction and distance >0–10 m.');
        }
      default:
        throw const FormatException('Tool is not allowed.');
    }
    return FlightCommand._(name, Map<String, dynamic>.from(args));
  }

  /// Flight-memory steps that fly to a remembered position (2026-10-28); the
  /// service resolves them from its own memory, so none carries coordinates.
  static const memoryMotion = {'return_home', 'go_back', 'retrace', 'goto_point'};

  /// A flight-memory step that flies the vehicle.
  bool get isMemoryMotion => memoryMotion.contains(name);

  /// A whole point number 1–9.
  static bool _pointNumber(Object? value) =>
      value is num && value.isFinite && value % 1 == 0 && value >= 1 && value <= 9;

  static bool _keys(Map value, Set<String> keys) =>
      value.length == keys.length && keys.every(value.containsKey);

  // Simulator test limits only; real flight needs a separately validated profile.
  static bool _number(Object? value,
          {required double minimum, bool exclusive = false}) =>
      value is num &&
      value.isFinite &&
      value <= 10 &&
      (exclusive ? value > minimum : value >= minimum);

  /// Operator-facing wording, e.g. 'Move forward 3 m'.
  String get description => switch (name) {
        'takeoff' => 'Take off to ${_metres(arguments['altitude_m'])} m',
        'move_relative' => 'Move ${arguments['direction']} '
            '${_metres(arguments['distance_m'])} m',
        'hold' => 'Hold position',
        'land' => 'Land',
        'get_status' => 'Report status',
        'return_home' => 'Return home',
        'go_back' => 'Go back to the previous position',
        'retrace' => 'Retrace the flown path',
        'goto_point' => 'Go to point ${(arguments['point'] as num).toInt()}',
        'mark_point' => 'Mark point ${(arguments['point'] as num).toInt()}',
        _ => toString(),
      };

  static String _metres(num value) =>
      '${value % 1 == 0 ? value.toInt() : value}';

  @override
  String toString() => '$name($arguments)';
}
