/// Language codes, display names, and spoken-name aliases.
/// Aliases mirror Task 3 _LANGUAGE_ALIASES exactly; do not expand
/// without native-speaker review.
library;

/// Code -> display name. Only supported + stubbed codes are listed.
const Map<String, String> languageNames = {
  'en': 'English',
  'hi': 'Hindi',
  'te': 'Telugu',
  'ta': 'Tamil',
  'kn': 'Kannada',
  'ml': 'Malayalam',
};

/// Spoken language names -> code. Mirrors Task 3 _LANGUAGE_ALIASES.
const Map<String, String> languageAliases = {
  'english': 'en',
  'hindi': 'hi',
  'telugu': 'te',
  'tamil': 'ta',
  'kannada': 'kn',
  'malayalam': 'ml',
};

/// Resolve free text (code or spoken name, any case/whitespace) to a code.
/// Returns null when unknown.
String? resolveLanguageCode(String input) {
  final key = input.trim().toLowerCase();
  if (languageNames.containsKey(key)) return key;
  return languageAliases[key];
}
