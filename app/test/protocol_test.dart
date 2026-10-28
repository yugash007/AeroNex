/// Dart mirror of Task 1 (edge/tests/test_protocol.py).
/// Run on a Flutter machine: `flutter test test/protocol_test.dart`.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/core/errors.dart';
import 'package:aeronex/shared/models/edge_message.dart';

void main() {
  test('accepts canonical move', () {
    final msg = {
      'version': 1,
      'message_id': 'cmd-001',
      'type': 'COMMAND',
      'timestamp': 1791123456789,
      'payload': {
        'command': 'MOVE',
        'parameters': {'direction': 'FORWARD', 'distance_m': 10},
        'confidence': 0.96,
      },
    };
    expect(EdgeMessage.validate(msg), isNull);
    final parsed = EdgeMessage.fromJson(msg);
    expect(parsed.toJson(), equals(msg));
  });

  test('rejects missing message_id', () {
    expect(
      EdgeMessage.validate({
        'version': 1,
        'type': 'COMMAND',
        'timestamp': 1791123456789,
        'payload': {'command': 'MOVE'},
      }),
      isNotNull,
    );
  });

  test('rejects unknown type', () {
    final msg = EdgeMessage.create('FLY_TO_MOON', {}).toJson();
    expect(EdgeMessage.validate(msg), isNotNull);
  });

  test('rejects version 2', () {
    final msg = EdgeMessage.create('PING', {}).toJson();
    msg['version'] = 2;
    expect(EdgeMessage.validate(msg), isNotNull);
  });

  test('rejects non-int version (1.0, true) in validate and fromJson', () {
    for (final version in <Object>[1.0, true]) {
      final msg = EdgeMessage.create('PING', {}).toJson();
      msg['version'] = version;
      expect(EdgeMessage.validate(msg), equals('version must be 1'));
      expect(() => EdgeMessage.fromJson(msg),
          throwsA(isA<EdgeProtocolException>()));
    }
  });

  test('rejects message_id over the shared length cap', () {
    final ok = EdgeMessage.create('PING', {},
            messageId: 'm' * EdgeMessage.maxMessageIdLength)
        .toJson();
    expect(EdgeMessage.validate(ok), isNull);
    final msg = EdgeMessage.create('PING', {},
            messageId: 'm' * (EdgeMessage.maxMessageIdLength + 1))
        .toJson();
    expect(EdgeMessage.validate(msg),
        equals('message_id must be at most 128 chars'));
    expect(() => EdgeMessage.fromJson(msg),
        throwsA(isA<EdgeProtocolException>()));
  });

  test('roundtrip new envelope', () {
    final msg =
        EdgeMessage.create('COMMAND', {'command': 'MOVE'}, messageId: 'cmd-001');
    final json = msg.toJson();
    expect(json.keys.toSet(), equals(EdgeMessage.envelopeKeys));
    expect(json['version'], equals(1));
    expect(json['message_id'], equals('cmd-001'));
    expect(json['type'], equals('COMMAND'));
    expect(json['timestamp'], isA<int>());
    expect(json['payload'], equals({'command': 'MOVE'}));
    expect(EdgeMessage.validate(json), isNull);
  });

  test('type allow-lists mirror Task 1', () {
    expect(EdgeMessage.mobileToEdge,
        equals({'PING', 'COMMAND', 'STATUS_REQUEST', 'CANCEL'}));
    expect(
        EdgeMessage.edgeToMobile,
        equals(
            {'PONG', 'ACK', 'COMMAND_STATUS', 'STATUS', 'ERROR'}));
  });
}
