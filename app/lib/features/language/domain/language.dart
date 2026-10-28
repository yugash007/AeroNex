/// App-side language value. Codes mirror Task 3 SUPPORTED/STUBBED.
library;

/// A BCP-47-ish short code plus helpers shared by STT and intent.
class AppLanguage {
  /// Fully supported by the rule intent engine (Task 3 SUPPORTED).
  static const List<String> supported = ['en', 'hi', 'te'];

  /// Known but stubbed: intent raises, UI shows "not currently supported".
  /// Mirrors Task 3 STUBBED.
  static const List<String> stubbed = ['ta', 'kn', 'ml'];

  final String code;

  const AppLanguage(this.code);

  bool get isSupported => supported.contains(code);
  bool get isStubbed => stubbed.contains(code);

  @override
  String toString() => 'AppLanguage($code)';

  @override
  bool operator ==(Object other) =>
      other is AppLanguage && other.code == code;

  @override
  int get hashCode => code.hashCode;
}
