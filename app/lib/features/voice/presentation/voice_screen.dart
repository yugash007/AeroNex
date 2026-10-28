/// Voice-first command screen (SRS section 30): mic button with a
/// typed-text fallback, transcript, detected language, parsed command,
/// and send state, plus recent command history.
library;

import 'dart:async';

import 'package:flutter/material.dart' hide ConnectionState;

import '../../../core/network/edge_transport.dart';
import '../../../shared/models/edge_message.dart';
import '../../command_history/data/history_store.dart';
import '../../command_history/presentation/history_list.dart';
import '../../edge_connection/domain/connection_state.dart';
import '../../intent/domain/intent_engine.dart';
import '../../voice/domain/speech_engine.dart';
import '../../../shared/widgets/status_dot.dart';

/// Send pipeline stage shown under the parsed command.
enum SendStage { idle, listening, parsing, sending, sent, failed }

class VoiceScreen extends StatefulWidget {
  final SpeechEngine speech;
  final IntentEngine intent;
  final EdgeTransport transport;
  final HistoryStore history;
  final ConnectionState connection;
  final String edgeName;

  const VoiceScreen({
    super.key,
    required this.speech,
    required this.intent,
    required this.transport,
    required this.history,
    required this.connection,
    this.edgeName = 'EDGE AI',
  });

  @override
  State<VoiceScreen> createState() => _VoiceScreenState();
}

class _VoiceScreenState extends State<VoiceScreen> {
  final _typed = TextEditingController();
  StreamSubscription<EdgeMessage>? _incoming;
  String? _transcript;
  String? _language;
  DroneIntent? _parsed;
  SendStage _stage = SendStage.idle;
  String? _statusLine;

  @override
  void initState() {
    super.initState();
    _incoming = widget.transport.messages.listen(_onEdgeMessage);
  }

  @override
  void dispose() {
    _incoming?.cancel();
    _typed.dispose();
    super.dispose();
  }

  void _onEdgeMessage(EdgeMessage msg) {
    if (msg.type == 'COMMAND_STATUS') {
      try {
        final status = CommandStatus.fromPayload(msg.payload);
        widget.history.updateStatus(status.requestId, status.status);
        setState(() => _statusLine = status.status);
      } catch (_) {
        // Malformed status payload: history keeps the SENT row.
      }
    }
  }

  Future<void> _listen() async {
    setState(() {
      _stage = SendStage.listening;
      _statusLine = null;
    });
    try {
      final fallback =
          _typed.text.trim().isEmpty ? null : _typed.text.trim();
      final result = await widget.speech.transcribe(typedFallback: fallback);
      setState(() {
        _transcript = result.text;
        _language = result.language;
        _stage = SendStage.parsing;
      });
      final parsed =
          await widget.intent.parse(result.text, result.language);
      setState(() {
        _parsed = parsed;
        _stage = SendStage.sending;
      });
      final envelope =
          EdgeMessage.create('COMMAND', parsed.toCommandPayload());
      await widget.transport.send(envelope);
      widget.history.add(HistoryEntry(
        messageId: envelope.messageId,
        command: parsed.command,
        status: 'SENT',
        at: DateTime.now(),
      ));
      setState(() => _stage = SendStage.sent);
    } catch (e) {
      setState(() {
        _stage = SendStage.failed;
        _statusLine = '$e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('AERONEX ONE')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                StatusDot(state: widget.connection),
                const SizedBox(width: 8),
                Text('${widget.edgeName} ${widget.connection.name.toUpperCase()}'),
              ],
            ),
            const SizedBox(height: 24),
            Center(
              child: IconButton(
                iconSize: 72,
                icon: const Icon(Icons.mic),
                onPressed: _stage == SendStage.listening ? null : _listen,
                tooltip: 'Tap to talk',
              ),
            ),
            const Center(child: Text('TAP TO TALK')),
            const SizedBox(height: 16),
            TextField(
              controller: _typed,
              decoration: const InputDecoration(
                labelText: 'Or type a command (fallback)',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            if (_transcript != null) Text('Heard: "$_transcript"'),
            if (_language != null) Text('Language: $_language'),
            if (_parsed != null)
              Text(
                  'Parsed: ${_parsed!.command} ${_parsed!.parameters} (${_parsed!.confidence})'),
            Text('Send state: ${_stage.name}'
                '${_statusLine != null ? ' — $_statusLine' : ''}'),
            const SizedBox(height: 16),
            const Text('Recent commands'),
            HistoryList(entries: widget.history.snapshot),
          ],
        ),
      ),
    );
  }
}
