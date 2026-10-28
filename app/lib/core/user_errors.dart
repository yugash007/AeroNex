import 'package:flutter/services.dart';

String userErrorMessage(Object error) {
  if (error is PlatformException) {
    switch (error.code) {
      case 'SPEECH_RUNTIME':
        return 'Offline speech could not start. Restart the app after updating. '
            'Your downloaded speech packs are still available.';
      case 'NO_SPEECH':
        return 'No speech was recognized. Tap the microphone and try speaking again.';
      case 'PERMISSION':
        return 'Allow microphone access in the app settings, then try again.';
      case 'MODEL_MISSING':
        return 'The selected local model is not installed. Open model setup to download or import it.';
    }
    return error.message?.trim().isNotEmpty == true
        ? error.message!
        : 'The operation could not finish. Please try again.';
  }
  if (error is FormatException) return error.message;
  if (error is StateError) return '${error.message}';
  return 'The operation could not finish. Please try again.';
}
