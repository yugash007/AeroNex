class LocalModel {
  final String id, name, file;
  final int bytes;
  const LocalModel(this.id, this.name, this.file, this.bytes);
  String get sizeLabel => '${(bytes / 1048576).toStringAsFixed(1)} MiB';
}

// Display metadata for the Vosk speech packs (en-IN, te, hi). Android owns
// source URLs and size/hash validation.
const localModels = {
  'vosk-en-in': LocalModel('vosk-en-in', 'Vosk Indian English',
      'vosk-model-small-en-in-0.4.zip', 37573330),
  'vosk-te': LocalModel(
      'vosk-te', 'Vosk Telugu', 'vosk-model-small-te-0.42.zip', 60544249),
  'vosk-hi': LocalModel(
      'vosk-hi', 'Vosk Hindi', 'vosk-model-small-hi-0.22.zip', 44458845),
};

String? voskProfileForLanguage(String language) =>
    switch (language.toLowerCase()) {
      'en' || 'en-in' || 'en-us' => 'vosk-en-in',
      'te' || 'te-in' => 'vosk-te',
      'hi' || 'hi-in' => 'vosk-hi',
      _ => null,
    };
