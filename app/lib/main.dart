import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'app_shell.dart';
import 'features/flight/presentation/widgets/stop_button.dart';
import 'features/link/data/link_settings.dart';
import 'features/session/session_api.dart';

void main() {
  registerLicenses();
  runApp(AeroNexApp(session: ChannelSessionApi()));
}

/// Notices for the third-party code in the APK and the speech and AI models it runs.
void registerLicenses() {
  LicenseRegistry.addLicense(() async* {
    yield LicenseEntryWithLineBreaks(['Vosk', 'JNA', 'Vosk language models'],
        await rootBundle.loadString('assets/licenses/vosk.txt'));
    yield LicenseEntryWithLineBreaks(
        ['sherpa-onnx', 'GigaSpeech keyword spotter model'],
        await rootBundle.loadString('assets/licenses/sherpa-onnx.txt'));
    yield LicenseEntryWithLineBreaks(['ONNX Runtime'],
        await rootBundle.loadString('assets/licenses/onnxruntime.txt'));
    yield LicenseEntryWithLineBreaks(['IndicConformer speech models'],
        await rootBundle.loadString('assets/licenses/indicconformer.txt'));
    yield LicenseEntryWithLineBreaks(['Cactus Needle'],
        await rootBundle.loadString('assets/licenses/cactus.txt'));
    yield LicenseEntryWithLineBreaks(['llama.cpp', 'nlohmann/json'],
        await rootBundle.loadString('assets/licenses/llama_cpp.txt'));
  });
}

class AeroNexApp extends StatelessWidget {
  const AeroNexApp({
    super.key,
    required this.session,
    this.settings = const DeviceLinkSettingsStore(),
  });

  /// The one flight session client for every screen.
  final SessionApi session;
  final LinkSettingsStore settings;

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'AeroNex',
        theme: ThemeData(useMaterial3: true),
        // STOP above the navigator: no screen, dialog, sheet or menu covers it.
        builder: (context, navigator) =>
            StopLane(session: session, child: navigator!),
        home: AppShell(session: session, settings: settings),
      );
}
