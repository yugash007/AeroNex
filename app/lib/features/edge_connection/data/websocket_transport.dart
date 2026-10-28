/// WebSocket transport to the Task 2 edge server at ws://<ip>:8000/ws.
///
/// - PING heartbeat every 5 s; [ConnectionState.connected] is reported
///   only after a PONG answers the proving PING. Once connected, any
///   valid envelope serves as liveness; three heartbeat windows without
///   one is treated as a drop. A proving PING with no PONG within one
///   heartbeat window fails the dial and follows the reconnect path.
/// - Drops reconnect with 1 s doubling backoff capped at 30 s.
/// - [WebSocketEdgeTransport.send] throws [TransportException] unless the
///   link is heartbeat-confirmed; nothing is dropped silently.
/// - Incoming COMMAND_STATUS payloads are surfaced as-is; the `command`
///   field is OPTIONAL (Task 2 DUP path omits it) — see [CommandStatus].
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../../core/constants.dart';
import '../../../core/constants.dart' as defaults;
import '../../../core/errors.dart';
import '../../../core/logging.dart';
import '../../../core/network/edge_transport.dart';
import '../../../shared/models/edge_message.dart';
import '../domain/connection_state.dart';

/// Minimal text-socket surface. Implemented by [_IoEdgeSocket] and by
/// fakes in tests, so reconnect/backoff logic is testable without I/O.
abstract class EdgeSocket {
  Stream<String> get texts;
  Future<void> sendText(String text);
  Future<void> close();
}

class _IoEdgeSocket implements EdgeSocket {
  final WebSocket _ws;
  _IoEdgeSocket(this._ws);

  @override
  Stream<String> get texts => _ws.where((event) => event is String).cast<String>();

  @override
  Future<void> sendText(String text) async {
    _ws.add(text);
  }

  @override
  Future<void> close() => _ws.close();
}

/// Opens a live socket for [url]. Swappable in tests.
Future<EdgeSocket> openEdgeSocket(String url) async {
  final ws = await WebSocket.connect(url);
  return _IoEdgeSocket(ws);
}

class WebSocketEdgeTransport implements EdgeTransport {
  final String ip;
  final int port;
  final Future<EdgeSocket> Function(String url) openSocket;
  final Duration heartbeatInterval;
  final Duration initialBackoff;
  final Duration maxBackoff;

  final _messages = StreamController<EdgeMessage>.broadcast();
  final _states = StreamController<ConnectionState>.broadcast();
  final _latencies = StreamController<int?>.broadcast();

  ConnectionState _state = ConnectionState.disconnected;
  EdgeSocket? _socket;
  StreamSubscription<String>? _subscription;
  Timer? _heartbeat;
  Timer? _confirmTimer;
  Timer? _reconnectTimer;
  DateTime? _lastPingSent;
  DateTime? _lastInbound;
  int? _lastLatencyMs;
  int _backoffAttempt = 0;
  bool _manualClose = false;
  bool _heartbeatConfirmed = false;

  /// Bumped by connect(), disconnect() and every dial; a dial whose
  /// epoch is no longer current must not touch shared state.
  int _dialEpoch = 0;

  WebSocketEdgeTransport(
    this.ip, {
    this.port = defaultPort,
    this.openSocket = openEdgeSocket,
    this.heartbeatInterval = defaults.heartbeatInterval,
    this.initialBackoff = defaults.initialBackoff,
    this.maxBackoff = defaults.maxBackoff,
  });

  String get url => edgeWsUrl(ip, port);

  /// Last measured PING/PONG round trip in ms, if any.
  int? get lastLatencyMs => _lastLatencyMs;

  /// Latency updates for the connection screen readout.
  Stream<int?> get latency => _latencies.stream;

  ConnectionState get currentState => _state;

  @override
  Stream<EdgeMessage> get messages => _messages.stream;

  @override
  Stream<ConnectionState> get connectionState => _states.stream;

  void _emit(ConnectionState next) {
    if (_state == next) return;
    _state = next;
    if (!_states.isClosed) _states.add(next);
  }

  @override
  Future<void> connect() async {
    // Supersede any in-flight dial, pending redial or live socket so a
    // stale attempt can neither clobber this one nor leak beside it.
    _dialEpoch++;
    _manualClose = false;
    _backoffAttempt = 0;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _stopHeartbeat();
    await _closeSocket();
    await _dial();
  }

  Future<void> _dial() async {
    if (_manualClose) return;
    final epoch = ++_dialEpoch;
    _emit(ConnectionState.connecting);
    try {
      final socket = await openSocket(url);
      if (_manualClose || epoch != _dialEpoch) {
        await socket.close();
        return;
      }
      _socket = socket;
      // Events from a socket that is no longer current are ignored.
      _subscription = socket.texts.listen(
        (raw) {
          if (identical(_socket, socket)) _onText(raw);
        },
        onError: (_) {
          if (identical(_socket, socket)) _onDrop();
        },
        onDone: () {
          if (identical(_socket, socket)) _onDrop();
        },
        cancelOnError: true,
      );
      // Proving PING: connecting -> connected happens only when a PONG
      // answers it (see _onText). No PONG within one heartbeat window
      // fails this dial (see _startConfirmTimeout).
      _heartbeatConfirmed = false;
      _lastInbound = null;
      await _sendRaw(EdgeMessage.create('PING', {}));
      if (_manualClose || epoch != _dialEpoch) return;
      _lastPingSent = DateTime.now();
      _startHeartbeat();
      _startConfirmTimeout();
    } catch (e) {
      AppLog.warn('edge dial failed: $e');
      if (epoch == _dialEpoch) _scheduleReconnect();
    }
  }

  void _onText(String raw) {
    final Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } on FormatException {
      AppLog.warn('ignoring non-JSON edge frame');
      return;
    }
    if (decoded is! Map<String, dynamic>) {
      AppLog.warn('ignoring non-object edge frame');
      return;
    }
    EdgeMessage msg;
    try {
      msg = EdgeMessage.fromJson(decoded);
    } on EdgeProtocolException catch (e) {
      AppLog.warn('ignoring invalid envelope: $e');
      return;
    }
    _lastInbound = DateTime.now();
    if (msg.type == 'PONG') {
      if (_lastPingSent != null) {
        _lastLatencyMs =
            DateTime.now().difference(_lastPingSent!).inMilliseconds;
        if (!_latencies.isClosed) _latencies.add(_lastLatencyMs);
      }
      if (!_heartbeatConfirmed) {
        // Strict confirmation: only a PONG answering the proving PING
        // promotes connecting -> connected.
        _heartbeatConfirmed = true;
        _confirmTimer?.cancel();
        _confirmTimer = null;
        _backoffAttempt = 0;
        _emit(ConnectionState.connected);
      }
    } else if (!_heartbeatConfirmed) {
      // No link proof yet: a non-PONG envelope cannot confirm it.
      AppLog.warn('ignoring ${msg.type} before heartbeat confirmation');
      return;
    }
    if (!_messages.isClosed) _messages.add(msg);
  }

  void _onDrop() {
    if (_manualClose) return;
    _confirmTimer?.cancel();
    _confirmTimer = null;
    _stopHeartbeat();
    // Detach the dead socket now: dart:io silently discards writes to a
    // peer-closed socket, so nothing may reach it during backoff.
    unawaited(_closeSocket());
    _emit(ConnectionState.reconnecting);
    _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (_manualClose) return;
    _emit(ConnectionState.reconnecting);
    _reconnectTimer?.cancel();
    var shift = _backoffAttempt;
    if (shift > 10) shift = 10; // avoid overflow; cap clamps anyway
    var delay = initialBackoff * (1 << shift);
    if (delay > maxBackoff) delay = maxBackoff;
    _backoffAttempt++;
    AppLog.info('reconnecting in ${delay.inMilliseconds} ms');
    _reconnectTimer = Timer(delay, () {
      _closeSocket();
      _dial();
    });
  }

  void _startHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = Timer.periodic(heartbeatInterval, (_) async {
      if (_socket == null || _manualClose) return;
      final lastInbound = _lastInbound;
      if (_heartbeatConfirmed &&
          lastInbound != null &&
          DateTime.now().difference(lastInbound) > heartbeatInterval * 3) {
        // Writes can still "succeed" on a half-open link; silence is the
        // only signal, so treat it as a drop.
        AppLog.warn('edge heartbeat stale; dropping link');
        _onDrop();
        return;
      }
      try {
        await _sendRaw(EdgeMessage.create('PING', {}));
        _lastPingSent = DateTime.now();
      } catch (e) {
        AppLog.warn('heartbeat send failed: $e');
        _onDrop();
      }
    });
  }

  void _stopHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = null;
  }

  /// Fails the dial when the proving PING gets no PONG within one
  /// heartbeat window, following the existing reconnect path.
  void _startConfirmTimeout() {
    _confirmTimer?.cancel();
    _confirmTimer = Timer(heartbeatInterval, () {
      if (_manualClose || _heartbeatConfirmed) return;
      AppLog.warn('proving PING unanswered; failing dial');
      _closeSocket();
      _scheduleReconnect();
    });
  }

  Future<void> _sendRaw(EdgeMessage message) async {
    final socket = _socket;
    if (socket == null) {
      throw const TransportException('not connected');
    }
    await socket.sendText(jsonEncode(message.toJson()));
  }

  @override
  Future<void> send(EdgeMessage message) async {
    if (!EdgeMessage.mobileToEdge.contains(message.type)) {
      throw TransportException('cannot send edge-to-mobile type ${message.type}');
    }
    // Only a heartbeat-confirmed link carries messages; while connecting
    // or reconnecting fail loudly rather than drop a COMMAND.
    if (_state != ConnectionState.connected) {
      throw const TransportException('not connected');
    }
    await _sendRaw(message);
  }

  Future<void> _closeSocket() async {
    _confirmTimer?.cancel();
    _confirmTimer = null;
    // Detach synchronously so nothing writes to or reacts to this socket
    // while the cancel/close below is pending.
    final subscription = _subscription;
    final socket = _socket;
    _subscription = null;
    _socket = null;
    await subscription?.cancel();
    if (socket != null) {
      try {
        await socket.close();
      } catch (e) {
        AppLog.warn('socket close failed: $e');
      }
    }
  }

  @override
  Future<void> disconnect() async {
    _manualClose = true;
    _dialEpoch++;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    _stopHeartbeat();
    await _closeSocket();
    _emit(ConnectionState.disconnected);
  }

  /// Release all stream controllers. Call once when the app shuts down.
  Future<void> dispose() async {
    await disconnect();
    await _messages.close();
    await _states.close();
    await _latencies.close();
  }
}
