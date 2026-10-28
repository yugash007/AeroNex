/// Fake speech engine: returns typed text plus a language tag.
/// Stands in for the on-device STT model until it lands.
library;

import '../domain/speech_engine.dart';

class FakeSpeechEngine implements SpeechEngine {
  bool _initialized = false;

  /// Text returned by the next [transcribe] call.
  String typedText = '';

  /// Language tag returned with the text. Defaults to English.
  String language = 'en';

  bool get isInitialized => _initialized;

  @override
  Future<void> initialize() async {
    _initialized = true;
  }

  @override
  Future<Transcript> transcribe({String? typedFallback}) async {
    if (!_initialized) {
      throw StateError('FakeSpeechEngine not initialized');
    }
    final text = (typedFallback ?? typedText).trim();
    if (text.isEmpty) {
      throw StateError('no speech input: type text or set typedText');
    }
    return Transcript(text, language);
  }

  @override
  Future<void> dispose() async {
    _initialized = false;
  }
}
