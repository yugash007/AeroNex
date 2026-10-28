/// Shared constants. Single source for values the brief fixes verbatim.
library;

/// Protocol version. Must match Task 1 (protocol.VERSION).
const int protocolVersion = 1;

/// Manual-IP WebSocket endpoint of the Task 2 edge server.
String edgeWsUrl(String ip, [int port = defaultPort]) => 'ws://$ip:$port/ws';

/// Default IP shown in the connection screen (SRS section 31 example).
const String defaultIp = '192.168.4.1';

/// Default edge port. The Task 2 server listens on 8000.
const int defaultPort = 8000;

/// Default edge device name shown in the connection screen.
const String defaultDeviceName = 'AERONEX-EDGE-01';

/// PING heartbeat period. The transport sends PING every 5 s.
const Duration heartbeatInterval = Duration(seconds: 5);

/// Initial reconnect delay. Doubles per attempt up to [maxBackoff].
const Duration initialBackoff = Duration(seconds: 1);

/// Reconnect delay cap: 1 s doubling capped at 30 s.
const Duration maxBackoff = Duration(seconds: 30);

/// Mirrors edge.app.commands.MAX_DISTANCE_M (duplicated, not imported).
const double maxDistanceM = 500;

/// Confidence for exact canonical phrasing (mirrors Task 3).
const double exactConfidence = 0.9;

/// Confidence fallback when the distance uses numeral words or extra
/// filler words surround the command (mirrors Task 3).
const double fallbackConfidence = 0.7;
