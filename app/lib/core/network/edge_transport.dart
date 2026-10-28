/// Transport abstraction. Isolates the communication implementation
/// so future transports (UDP/QUIC) slot in without touching the app.
/// Interface verbatim from SRS section 27.
library;

import '../../shared/models/edge_message.dart';
import '../../features/edge_connection/domain/connection_state.dart';

abstract class EdgeTransport {
  Future<void> connect();

  Future<void> disconnect();

  Future<void> send(EdgeMessage message);

  Stream<EdgeMessage> get messages;

  Stream<ConnectionState> get connectionState;
}
