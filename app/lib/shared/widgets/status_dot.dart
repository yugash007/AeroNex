/// Colored connection dot shared by the voice and connection screens.
library;

import 'package:flutter/material.dart' hide ConnectionState;

import '../../features/edge_connection/domain/connection_state.dart';

/// Green when connected, amber while connecting/reconnecting, grey when off.
class StatusDot extends StatelessWidget {
  final ConnectionState state;

  const StatusDot({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    final color = switch (state) {
      ConnectionState.connected => Colors.green,
      ConnectionState.connecting => Colors.amber,
      ConnectionState.reconnecting => Colors.orange,
      ConnectionState.disconnected => Colors.grey,
    };
    return Container(
      width: 12,
      height: 12,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
    );
  }
}
