/// Recent-commands list for the voice screen (SRS section 30).
library;

import 'package:flutter/material.dart';

import '../data/history_store.dart';

/// Renders [HistoryStore.snapshot], newest first.
class HistoryList extends StatelessWidget {
  final List<HistoryEntry> entries;

  const HistoryList({super.key, required this.entries});

  @override
  Widget build(BuildContext context) {
    if (entries.isEmpty) {
      return const Text('No commands yet');
    }
    return ListView.builder(
      shrinkWrap: true,
      itemCount: entries.length,
      itemBuilder: (context, i) {
        final entry = entries[i];
        return ListTile(
          dense: true,
          title: Text(entry.command),
          subtitle: Text(entry.messageId),
          trailing: Text(entry.status),
        );
      },
    );
  }
}
