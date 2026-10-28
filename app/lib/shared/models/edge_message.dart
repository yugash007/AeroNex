/// v1 message envelope model. Keys verbatim from Task 1 (protocol.py):
/// version / message_id / type / timestamp / payload.
library;

import 'dart:math';

import '../../core/errors.dart';

/// A validated v1 envelope.
class EdgeMessage {
  static const int version1 = 1;

  /// Mirrors protocol.MAX_MESSAGE_ID_LEN.
  static const int maxMessageIdLength = 128;

  static const Set<String> envelopeKeys = {
    'version',
    'message_id',
    'type',
    'timestamp',
    'payload',
  };

  /// Types the phone may send (Task 1 MOBILE_TO_EDGE).
  static const Set<String> mobileToEdge = {
    'PING',
    'COMMAND',
    'STATUS_REQUEST',
    'CANCEL',
  };

  /// Types the edge may send (Task 1 EDGE_TO_MOBILE).
  static const Set<String> edgeToMobile = {
    'PONG',
    'ACK',
    'COMMAND_STATUS',
    'STATUS',
    'ERROR',
  };

  /// All valid types (Task 1 MESSAGE_TYPES).
  static const Set<String> messageTypes = {
    'PING',
    'COMMAND',
    'STATUS_REQUEST',
    'CANCEL',
    'PONG',
    'ACK',
    'COMMAND_STATUS',
    'STATUS',
    'ERROR',
  };

  final int version;
  final String messageId;
  final String type;
  final int timestamp;
  final Map<String, dynamic> payload;

  const EdgeMessage({
    required this.version,
    required this.messageId,
    required this.type,
    required this.timestamp,
    required this.payload,
  });

  /// Build a v1 envelope, generating a unique message_id when not given.
  /// Mirrors protocol.new_envelope.
  factory EdgeMessage.create(String type, Map<String, dynamic> payload,
      {String? messageId}) {
    final id = messageId ??
        '${DateTime.now().microsecondsSinceEpoch}-${Random().nextInt(1 << 32).toRadixString(16)}';
    return EdgeMessage(
      version: version1,
      messageId: id,
      type: type,
      timestamp: DateTime.now().millisecondsSinceEpoch,
      payload: Map<String, dynamic>.from(payload),
    );
  }

  /// Validate a decoded-JSON map. Returns null when valid, else the reason.
  /// Reasons mirror protocol.validate_envelope verbatim.
  static String? validate(Map<String, dynamic> msg) {
    if (msg.keys.toSet().difference(envelopeKeys).isNotEmpty ||
        envelopeKeys.difference(msg.keys.toSet()).isNotEmpty) {
      return 'envelope keys must be exactly ${envelopeKeys.toList()..sort()}';
    }
    // `is int` first: 1.0 == 1 in Dart, and fromJson casts `as int`.
    final version = msg['version'];
    if (version is! int || version != version1) {
      return 'version must be 1';
    }
    final id = msg['message_id'];
    if (id is! String || id.isEmpty) {
      return 'message_id must be a non-empty string';
    }
    if (id.length > maxMessageIdLength) {
      return 'message_id must be at most $maxMessageIdLength chars';
    }
    if (msg['type'] is! String || !messageTypes.contains(msg['type'])) {
      return 'unknown type: ${msg['type']}';
    }
    if (msg['timestamp'] is! int) {
      return 'timestamp must be int ms epoch';
    }
    if (msg['payload'] is! Map) {
      return 'payload must be an object';
    }
    return null;
  }

  /// Parse decoded JSON, throwing [EdgeProtocolException] when invalid.
  factory EdgeMessage.fromJson(Map<String, dynamic> json) {
    final reason = validate(json);
    if (reason != null) {
      throw EdgeProtocolException(reason);
    }
    return EdgeMessage(
      version: json['version'] as int,
      messageId: json['message_id'] as String,
      type: json['type'] as String,
      timestamp: json['timestamp'] as int,
      payload: Map<String, dynamic>.from(json['payload'] as Map),
    );
  }

  /// Serialize with the exact envelope keys.
  Map<String, dynamic> toJson() => {
        'version': version,
        'message_id': messageId,
        'type': type,
        'timestamp': timestamp,
        'payload': Map<String, dynamic>.from(payload),
      };
}

/// Parsed COMMAND_STATUS payload.
///
/// NOTE (Task 2 ledger pointer): the DUP path omits the `command` field —
/// the server sends `{request_id, status}` only — and CANCEL sends
/// `{request_id, status: CANCELLED}`. So [command] is OPTIONAL here;
/// never assume it is present.
class CommandStatus {
  final String requestId;
  final String status;
  final String? command;
  final String? reason;

  const CommandStatus({
    required this.requestId,
    required this.status,
    this.command,
    this.reason,
  });

  factory CommandStatus.fromPayload(Map<String, dynamic> payload) {
    final requestId = payload['request_id'];
    final status = payload['status'];
    if (requestId is! String || requestId.isEmpty) {
      throw const EdgeProtocolException(
          'COMMAND_STATUS payload needs a request_id string');
    }
    if (status is! String || status.isEmpty) {
      throw const EdgeProtocolException(
          'COMMAND_STATUS payload needs a status string');
    }
    final command = payload['command'];
    return CommandStatus(
      requestId: requestId,
      status: status,
      command: command is String ? command : null,
      reason: payload['reason'] is String ? payload['reason'] as String : null,
    );
  }
}
