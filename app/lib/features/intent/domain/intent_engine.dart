/// Intent engine abstraction. Kept independent from the transport
/// layer. Interface from SRS section 29.
library;

/// A parsed voice command. Plugs directly into the COMMAND payload shape:
/// {command, parameters, confidence}.
class DroneIntent {
  final String command;
  final Map<String, dynamic> parameters;
  final double confidence;

  const DroneIntent({
    required this.command,
    required this.parameters,
    required this.confidence,
  });

  Map<String, dynamic> toCommandPayload() => {
        'command': command,
        'parameters': Map<String, dynamic>.from(parameters),
        'confidence': confidence,
      };

  @override
  String toString() =>
      'DroneIntent(command: $command, parameters: $parameters, confidence: $confidence)';
}

abstract class IntentEngine {
  Future<DroneIntent> parse(String transcript, String language);
}
