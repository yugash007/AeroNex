/// Speech engine abstraction. STT models plug in here without
/// changing the command or communication layers. Interface from
/// SRS section 28, with a typed-text fallback for devices without STT.
library;

/// Transcript plus the language tag the engine detected (or was told).
class Transcript {
  final String text;
  final String language;
  final String? suggestion;

  const Transcript(this.text, this.language, {this.suggestion});

  @override
  String toString() => 'Transcript($language: $text)';
}

abstract class SpeechEngine {
  Future<void> initialize();

  /// Returns the transcript. When [typedFallback] is given, the engine
  /// returns it verbatim instead of listening (typed-text fallback).
  Future<Transcript> transcribe({String? typedFallback});

  Future<void> dispose();
}
