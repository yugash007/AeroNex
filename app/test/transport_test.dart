/// Transport state-transition tests with a fake socket (no I/O).
/// Also pins the Task 2 DUP ledger pointer: COMMAND_STATUS payloads
/// without `command` must parse.
/// Run on a Flutter machine: `flutter test test/transport_test.dart`.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/core/errors.dart';
import 'package:aeronex/features/edge_connection/data/websocket_transport.dart';
import 'package:aeronex/features/edge_connection/domain/connection_state.dart';
import 'package:aeronex/shared/models/edge_message.dart';

class FakeEdgeSocket implements EdgeSocket {
  final controller = StreamController<String>.broadcast();
  final sent = <String>[];

  bool get closed => controller.isClosed;

  void feed(EdgeMessage msg) =>
      controller.add(jsonEncode(msg.toJson()));

  void feedRaw(String raw) => controller.add(raw);

  @override
  Stream<String> get texts => controller.stream;

  @override
  Future<void> sendText(String text) async {
    sent.add(text);
  }

  @override
  Future<void> close() async {
    await controller.close();
  }
}

WebSocketEdgeTransport makeTransport(
  List<FakeEdgeSocket> sockets, {
  Duration backoff = const Duration(milliseconds: 10),
  Duration heartbeat = const Duration(seconds: 5),
}) {
  return WebSocketEdgeTransport(
    '192.168.4.1',
    openSocket: (_) async {
      final socket = FakeEdgeSocket();
      sockets.add(socket);
      return socket;
    },
    heartbeatInterval: heartbeat,
    initialBackoff: backoff,
    maxBackoff: backoff,
  );
}

/// openSocket stand-in whose dials the test resolves by hand, so stale
/// in-flight dials can complete or fail in any order.
class ManualDialer {
  final dials = <Completer<EdgeSocket>>[];

  Future<EdgeSocket> open(String url) {
    final dial = Completer<EdgeSocket>();
    dials.add(dial);
    return dial.future;
  }

  FakeEdgeSocket succeed(int index) {
    final socket = FakeEdgeSocket();
    dials[index].complete(socket);
    return socket;
  }

  void fail(int index) =>
      dials[index].completeError(const TransportException('dial failed'));
}

WebSocketEdgeTransport makeManualTransport(
  ManualDialer dialer, {
  Duration backoff = const Duration(milliseconds: 20),
}) {
  return WebSocketEdgeTransport(
    '192.168.4.1',
    openSocket: dialer.open,
    initialBackoff: backoff,
    maxBackoff: backoff,
  );
}

Future<void> settle([int ms = 0]) =>
    Future<void>.delayed(Duration(milliseconds: ms));

void main() {
  test('stays connecting until heartbeat confirmation, then connected',
      () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    final states = <ConnectionState>[];
    final sub = transport.connectionState.listen(states.add);

    await transport.connect();
    await Future<void>.delayed(const Duration(milliseconds: 50));
    // Dial sent a proving PING but nothing is confirmed yet.
    expect(states, contains(ConnectionState.connecting));
    expect(states, isNot(contains(ConnectionState.connected)));

    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(states, contains(ConnectionState.connected));

    await sub.cancel();
    await transport.dispose();
  });

  test('non-PONG envelope does not confirm the link', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    final states = <ConnectionState>[];
    final sub = transport.connectionState.listen(states.add);

    await transport.connect();
    sockets.single.feed(EdgeMessage.create('STATUS', {
      'edge': 'READY',
      'version': 1,
      'uptime_ms': 1,
      'ai_status': 'OK',
    }));
    await Future<void>.delayed(const Duration(milliseconds: 50));
    // A valid non-PONG envelope is not heartbeat proof: still connecting.
    expect(states, isNot(contains(ConnectionState.connected)));

    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(states, contains(ConnectionState.connected));

    await sub.cancel();
    await transport.dispose();
  });

  test('unanswered proving PING fails the dial and redials', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(
      sockets,
      heartbeat: const Duration(milliseconds: 50),
    );
    final states = <ConnectionState>[];
    final sub = transport.connectionState.listen(states.add);

    await transport.connect();
    // Never answer: the confirm window lapses and the dial must fail
    // onto the reconnect path.
    await Future<void>.delayed(const Duration(milliseconds: 250));
    expect(states, contains(ConnectionState.reconnecting));
    expect(sockets.length, greaterThanOrEqualTo(2));

    await sub.cancel();
    await transport.dispose();
  });

  test('send emits a valid COMMAND envelope', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    await transport.connect();
    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await Future<void>.delayed(const Duration(milliseconds: 50));

    final cmd = EdgeMessage.create('COMMAND', {
      'command': 'MOVE',
      'parameters': {'direction': 'FORWARD', 'distance_m': 10},
    }, messageId: 'cmd-1');
    await transport.send(cmd);
    final sent = sockets.single.sent
        .map((raw) => jsonDecode(raw) as Map<String, dynamic>)
        .toList();
    final match =
        sent.where((m) => m['message_id'] == 'cmd-1').single;
    expect(EdgeMessage.validate(match), isNull);
    expect(match['type'], equals('COMMAND'));

    await transport.dispose();
  });

  test('drop moves to reconnecting and redials', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    final states = <ConnectionState>[];
    final sub = transport.connectionState.listen(states.add);

    await transport.connect();
    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await Future<void>.delayed(const Duration(milliseconds: 50));
    expect(states, contains(ConnectionState.connected));

    await sockets.single.close();
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(states, contains(ConnectionState.reconnecting));
    expect(sockets.length, greaterThanOrEqualTo(2));

    await sub.cancel();
    await transport.dispose();
  });

  test('disconnect reports disconnected without redial', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    final states = <ConnectionState>[];
    final sub = transport.connectionState.listen(states.add);

    await transport.connect();
    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await Future<void>.delayed(const Duration(milliseconds: 50));
    final opens = sockets.length;
    await transport.disconnect();
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(states.last, equals(ConnectionState.disconnected));
    expect(sockets.length, equals(opens));

    await sub.cancel();
    await transport.dispose();
  });

  test('send fails loudly before heartbeat confirmation', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    await transport.connect();
    expect(transport.currentState, equals(ConnectionState.connecting));

    final cmd = EdgeMessage.create('COMMAND', {'command': 'LAND'},
        messageId: 'cmd-early');
    await expectLater(
        transport.send(cmd), throwsA(isA<TransportException>()));
    expect(sockets.single.sent.where((raw) => raw.contains('cmd-early')),
        isEmpty);

    await transport.dispose();
  });

  test('send fails loudly while reconnecting instead of dropping', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport =
        makeTransport(sockets, backoff: const Duration(seconds: 1));
    await transport.connect();
    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await settle(20);
    expect(transport.currentState, equals(ConnectionState.connected));

    // Peer closed; the redial is still waiting out its backoff.
    await sockets.single.close();
    await settle(20);
    expect(transport.currentState, equals(ConnectionState.reconnecting));

    final cmd = EdgeMessage.create('COMMAND', {'command': 'LAND'},
        messageId: 'cmd-lost');
    await expectLater(
        transport.send(cmd), throwsA(isA<TransportException>()));
    expect(sockets.single.sent.where((raw) => raw.contains('cmd-lost')),
        isEmpty);

    await transport.dispose();
  });

  test('heartbeat silence after confirmation drops and redials', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport =
        makeTransport(sockets, heartbeat: const Duration(milliseconds: 50));
    final states = <ConnectionState>[];
    final sub = transport.connectionState.listen(states.add);

    await transport.connect();
    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await settle(20);
    expect(transport.currentState, equals(ConnectionState.connected));

    // The edge answered once, then went silent (half-open link): PINGs
    // still "send" but nothing comes back.
    await settle(400);
    expect(states.skipWhile((s) => s != ConnectionState.connected),
        contains(ConnectionState.reconnecting));
    expect(sockets.first.closed, isTrue);
    expect(sockets.length, greaterThanOrEqualTo(2));

    await sub.cancel();
    await transport.dispose();
  });

  test('a link that keeps answering heartbeats stays connected', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport =
        makeTransport(sockets, heartbeat: const Duration(milliseconds: 50));
    await transport.connect();
    final pongs = Timer.periodic(const Duration(milliseconds: 25), (_) {
      if (!sockets.last.closed) {
        sockets.last.feed(EdgeMessage.create('PONG', {}));
      }
    });

    await settle(400);
    pongs.cancel();
    expect(transport.currentState, equals(ConnectionState.connected));
    expect(sockets.length, equals(1));

    await transport.dispose();
  });

  test('non-object frames and non-int version are ignored, not thrown',
      () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    final states = <ConnectionState>[];
    final received = <EdgeMessage>[];
    final uncaught = <Object>[];
    final stateSub = transport.connectionState.listen(states.add);
    final msgSub = transport.messages.listen(received.add);

    // The socket listener runs in the zone that dialed, so an error
    // escaping the frame handler is captured here.
    await runZonedGuarded(transport.connect, (e, _) => uncaught.add(e));
    for (final raw in [
      '[1,2,3]',
      '"x"',
      '42',
      'null',
      '{"version":1.0,"message_id":"pong-1","type":"PONG",'
          '"timestamp":1,"payload":{}}',
    ]) {
      sockets.single.feedRaw(raw);
    }
    await settle(20);
    expect(uncaught, isEmpty);
    expect(received, isEmpty);
    // A version-1.0 PONG is not a valid envelope, so it cannot confirm.
    expect(states, isNot(contains(ConnectionState.connected)));

    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await settle(20);
    expect(uncaught, isEmpty);
    expect(states, contains(ConnectionState.connected));
    expect(received.single.type, equals('PONG'));

    await stateSub.cancel();
    await msgSub.cancel();
    await transport.dispose();
  });

  test('stale in-flight dial cannot clobber or leak beside a fresh connect',
      () async {
    final dialer = ManualDialer();
    final transport = makeManualTransport(dialer);

    final first = transport.connect();
    await settle();
    dialer.fail(0);
    await first;
    await settle(60); // the backoff redial is now hung in openSocket
    expect(dialer.dials.length, equals(2));

    // RECONNECT (disconnect(); connect()) while that redial is in flight.
    await transport.disconnect();
    final fresh = transport.connect();
    await settle();
    expect(dialer.dials.length, equals(3));

    final stale = dialer.succeed(1);
    final healthy = dialer.succeed(2);
    await fresh;
    healthy.feed(EdgeMessage.create('PONG', {}));
    await settle(10);
    expect(transport.currentState, equals(ConnectionState.connected));
    expect(stale.closed, isTrue);
    expect(stale.sent, isEmpty);

    // The stale socket going away must not tear down the healthy link.
    await stale.close();
    await settle(60);
    expect(transport.currentState, equals(ConnectionState.connected));
    expect(healthy.closed, isFalse);
    expect(dialer.dials.length, equals(3));

    await transport.dispose();
  });

  test('stale dial failing late does not tear down a fresh connection',
      () async {
    final dialer = ManualDialer();
    final transport = makeManualTransport(dialer);
    final states = <ConnectionState>[];
    final sub = transport.connectionState.listen(states.add);

    final first = transport.connect();
    await settle();
    dialer.fail(0);
    await first;
    await settle(60);
    expect(dialer.dials.length, equals(2));

    await transport.disconnect();
    final fresh = transport.connect();
    await settle();
    final healthy = dialer.succeed(2);
    await fresh;
    healthy.feed(EdgeMessage.create('PONG', {}));
    await settle(10);
    expect(transport.currentState, equals(ConnectionState.connected));

    states.clear();
    dialer.fail(1);
    await settle(60);
    expect(states, isEmpty);
    expect(transport.currentState, equals(ConnectionState.connected));
    expect(healthy.closed, isFalse);
    expect(dialer.dials.length, equals(3));

    await sub.cancel();
    await transport.dispose();
  });

  test('connect() cancels a pending backoff redial', () async {
    final dialer = ManualDialer();
    final transport = makeManualTransport(dialer,
        backoff: const Duration(milliseconds: 40));

    final first = transport.connect();
    await settle();
    dialer.fail(0);
    await first; // a backoff redial is now pending

    final second = transport.connect();
    await settle();
    final healthy = dialer.succeed(1);
    await second;
    healthy.feed(EdgeMessage.create('PONG', {}));
    await settle(10);
    expect(transport.currentState, equals(ConnectionState.connected));

    await settle(80); // well past the old backoff
    expect(transport.currentState, equals(ConnectionState.connected));
    expect(healthy.closed, isFalse);
    expect(dialer.dials.length, equals(2));

    await transport.dispose();
  });

  test('connect() on a live transport closes the previous socket', () async {
    final sockets = <FakeEdgeSocket>[];
    final transport = makeTransport(sockets);
    await transport.connect();
    sockets.single.feed(EdgeMessage.create('PONG', {}));
    await settle(10);
    expect(transport.currentState, equals(ConnectionState.connected));

    await transport.connect();
    expect(sockets.length, equals(2));
    expect(sockets.first.closed, isTrue);
    sockets.last.feed(EdgeMessage.create('PONG', {}));
    await settle(10);
    expect(transport.currentState, equals(ConnectionState.connected));

    await transport.dispose();
  });

  test('COMMAND_STATUS without command parses (Task 2 DUP pointer)', () {
    // First-seen payloads carry `command`; DUP/CANCELLED payloads omit it.
    final full = CommandStatus.fromPayload({
      'request_id': 'cmd-dup-1',
      'command': 'MOVE',
      'status': 'EXECUTING',
    });
    expect(full.command, equals('MOVE'));

    final dup = CommandStatus.fromPayload({
      'request_id': 'cmd-dup-1',
      'status': 'EXECUTING',
    });
    expect(dup.command, isNull);
    expect(dup.status, equals('EXECUTING'));

    final cancelled = CommandStatus.fromPayload({
      'request_id': 'cmd-cancel-1',
      'status': 'CANCELLED',
    });
    expect(cancelled.command, isNull);
  });
}
