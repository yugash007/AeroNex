import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/core/user_errors.dart';

void main() {
  test(
      'speech runtime failure explains recovery without leaking native class names',
      () {
    for (final detail in [
      'org.vosk.LibVosk',
      "Can't obtain peer field ID for class com.sun.jna.Pointer"
    ]) {
      final message = userErrorMessage(
          PlatformException(code: 'SPEECH_RUNTIME', message: detail));
      expect(message, isNot(contains('Whisper')));
      expect(message, contains('speech packs'));
      expect(message, isNot(contains(detail)));
    }
  });

  test('no error offers the removed Whisper or Android recognizer paths', () {
    for (final code in [
      'SPEECH_RUNTIME', 'SPEECH_13', 'SPEECH_12', 'UNAVAILABLE', 'SPEECH_1',
      'SPEECH_2', 'MODEL_MISSING'
    ]) {
      final message = userErrorMessage(
          PlatformException(code: code, message: 'Could not finish.'));
      expect(message, isNot(contains('Whisper')), reason: code);
      expect(message, isNot(contains('Android')), reason: code);
    }
  });
}
