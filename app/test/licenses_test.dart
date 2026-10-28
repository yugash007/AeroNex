import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('every third-party component that ships in 0.4.0 has a notice', () async {
    registerLicenses();
    final entries = await LicenseRegistry.licenses.toList();
    String notice(String package) => entries
        .where((entry) => entry.packages.contains(package))
        .expand((entry) => entry.paragraphs)
        .map((paragraph) => paragraph.text)
        .join('\n');

    expect(notice('Vosk'), contains('Apache License'));
    expect(notice('sherpa-onnx'), contains('Apache License'));
    expect(notice('sherpa-onnx'), contains('TERMS AND CONDITIONS'));
    expect(notice('GigaSpeech keyword spotter model'), contains('Apache License'));
    expect(notice('ONNX Runtime'), contains('Copyright (c) Microsoft Corporation'));
    expect(notice('ONNX Runtime'), contains('MIT License'));
    expect(notice('IndicConformer speech models'), contains('AI4Bharat'));
    expect(notice('IndicConformer speech models'), contains('MIT License'));
    // 0.4.0 AI runtimes (arm64-v8a): Cactus Needle (Apache-2.0) and llama.cpp + nlohmann/json (MIT).
    expect(notice('Cactus Needle'), contains('Apache License'));
    expect(notice('Cactus Needle'), contains('Cactus-Compute/needle3'));
    expect(notice('llama.cpp'), contains('The ggml authors'));
    expect(notice('llama.cpp'), contains('MIT License'));
    expect(notice('nlohmann/json'), contains('Niels Lohmann'));
  });
}
