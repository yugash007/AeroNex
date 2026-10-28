/// Typed errors. Messages mirror the Python reference implementations.
library;

/// Thrown when a v1 envelope fails validation (mirrors Task 1 reasons).
class EdgeProtocolException implements Exception {
  final String reason;
  const EdgeProtocolException(this.reason);

  @override
  String toString() => 'EdgeProtocolException: $reason';
}

/// Thrown when a transcript cannot be mapped to a command.
/// Mirrors intent.IntentError.
class IntentException implements Exception {
  final String message;
  const IntentException(this.message);

  @override
  String toString() => 'IntentException: $message';
}

/// Thrown for stub (ta/kn/ml) or entirely unknown languages.
/// Mirrors intent.UnsupportedLanguageError extends IntentError.
class UnsupportedLanguageException extends IntentException {
  const UnsupportedLanguageException(super.message);

  @override
  String toString() => 'UnsupportedLanguageException: $message';
}

/// Thrown for transport-level failures (connect/send/heartbeat).
class TransportException implements Exception {
  final String message;
  const TransportException(this.message);

  @override
  String toString() => 'TransportException: $message';
}
