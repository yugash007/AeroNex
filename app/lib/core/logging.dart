/// Minimal timestamped logger. No packages; keep diagnostics off the UI path.
library;

/// Log severity, least to most severe.
enum LogLevel { debug, info, warn, error }

/// Tiny static logger writing to stdout with an ISO-8601 timestamp.
class AppLog {
  static LogLevel level = LogLevel.info;

  static void debug(String message) => _write(LogLevel.debug, message);
  static void info(String message) => _write(LogLevel.info, message);
  static void warn(String message) => _write(LogLevel.warn, message);
  static void error(String message) => _write(LogLevel.error, message);

  static void _write(LogLevel at, String message) {
    if (at.index < level.index) return;
    final stamp = DateTime.now().toIso8601String();
    // ignore: avoid_print
    print('[$stamp] ${at.name.toUpperCase()} $message');
  }
}
