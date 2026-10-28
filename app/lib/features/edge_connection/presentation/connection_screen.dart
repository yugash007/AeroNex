/// Edge connection screen (SRS section 31): device name, manual IP
/// fallback, port, connect button, latency readout, and reconnect.
library;

import 'package:flutter/material.dart' hide ConnectionState;

import '../../../core/constants.dart';
import '../../../shared/widgets/status_dot.dart';
import '../../edge_connection/domain/connection_state.dart';

class ConnectionScreen extends StatefulWidget {
  final ConnectionState state;
  final int? latencyMs;
  final Future<void> Function(String ip, int port) onConnect;
  final Future<void> Function() onReconnect;
  final Future<void> Function() onDisconnect;

  const ConnectionScreen({
    super.key,
    required this.state,
    required this.onConnect,
    required this.onReconnect,
    required this.onDisconnect,
    this.latencyMs,
  });

  @override
  State<ConnectionScreen> createState() => _ConnectionScreenState();
}

class _ConnectionScreenState extends State<ConnectionScreen> {
  final _device = TextEditingController(text: defaultDeviceName);
  final _ip = TextEditingController(text: defaultIp);
  final _port = TextEditingController(text: '$defaultPort');
  bool _busy = false;

  @override
  void dispose() {
    _device.dispose();
    _ip.dispose();
    _port.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final latency = widget.latencyMs == null ? '—' : '${widget.latencyMs} ms';
    return Scaffold(
      appBar: AppBar(title: const Text('AERONEX EDGE')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                StatusDot(state: widget.state),
                const SizedBox(width: 8),
                Text(widget.state.name.toUpperCase()),
                const Spacer(),
                Text(latency),
              ],
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _device,
              decoration: const InputDecoration(labelText: 'Device'),
            ),
            TextField(
              controller: _ip,
              decoration: const InputDecoration(labelText: 'IP'),
              keyboardType: TextInputType.number,
            ),
            TextField(
              controller: _port,
              decoration: const InputDecoration(labelText: 'Port'),
              keyboardType: TextInputType.number,
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _busy
                  ? null
                  : () => _run(() => widget.onConnect(
                        _ip.text.trim(),
                        int.tryParse(_port.text.trim()) ?? defaultPort,
                      )),
              child: const Text('CONNECT'),
            ),
            OutlinedButton(
              onPressed: _busy ? null : () => _run(widget.onReconnect),
              child: const Text('RECONNECT'),
            ),
            TextButton(
              onPressed: _busy ? null : () => _run(widget.onDisconnect),
              child: const Text('DISCONNECT'),
            ),
          ],
        ),
      ),
    );
  }
}
