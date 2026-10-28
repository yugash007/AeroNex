/// Connection lifecycle. The transport never reports [connected]
/// without heartbeat confirmation (see WebSocketEdgeTransport).
library;

/// Phone-side view of the edge link (SRS sections 31 and 40).
enum ConnectionState {
  disconnected,
  connecting,
  connected,
  reconnecting,
}
