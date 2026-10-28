/// In-memory command history. The SRS privacy section allows storing
/// transcript, language, parsed command, and status locally; raw audio
/// is never stored.
library;

import 'dart:async';

/// One row in the recent-commands list.
class HistoryEntry {
  final String messageId;
  final String command;
  final String status;
  final DateTime at;

  const HistoryEntry({
    required this.messageId,
    required this.command,
    required this.status,
    required this.at,
  });

  HistoryEntry withStatus(String next) => HistoryEntry(
        messageId: messageId,
        command: command,
        status: next,
        at: at,
      );
}

/// Newest-first in-memory store with a broadcast snapshot stream.
class HistoryStore {
  final List<HistoryEntry> _entries = [];
  final _controller = StreamController<List<HistoryEntry>>.broadcast();

  Stream<List<HistoryEntry>> get entries => _controller.stream;

  List<HistoryEntry> get snapshot => List.unmodifiable(_entries);

  void add(HistoryEntry entry) {
    _entries.insert(0, entry);
    _emit();
  }

  /// Update the status of a sent command (ACK/COMMAND_STATUS arrivals).
  void updateStatus(String messageId, String status) {
    for (var i = 0; i < _entries.length; i++) {
      if (_entries[i].messageId == messageId) {
        _entries[i] = _entries[i].withStatus(status);
        _emit();
        return;
      }
    }
  }

  void clear() {
    _entries.clear();
    _emit();
  }

  void _emit() {
    if (!_controller.isClosed) _controller.add(snapshot);
  }

  Future<void> dispose() => _controller.close();
}
