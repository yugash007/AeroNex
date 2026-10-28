import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/user_errors.dart';
import '../../session/session_api.dart';

const telemetryRequested = 'Telemetry requested from the drone.';
const telemetryNotSent = 'Not sent: no link is open.';

/// Names for the service's latency stages (`LatencyBook`), in its order. A
/// stage not listed here is shown by its key.
const _stages = {
  'confirmToTx': 'Confirm → command sent',
  'confirmToAck': 'Confirm → drone accepted',
  'confirmToMoveTx': 'Confirm → move sent',
  'moveTxToEcho': 'Move sent → drone echoed',
  'confirmToInAir': 'Confirm → in the air',
  'stopToTx': 'STOP → sent',
  'stopToAck': 'STOP → drone accepted',
};

String _ms(double value) =>
    value < 1 ? value.toStringAsFixed(2) : value.toStringAsFixed(1);

String _yesNo(bool value) => value ? 'yes' : 'no';

/// A raw snapshot value as the service sent it; '—' when it has none.
String _raw(Object? value) => value == null ? '—' : '$value';

/// For testing and incident review: latency per stage, the link's
/// statistics, who owns the flight mode, and the vehicle telemetry as the
/// service reports it. Raw values are fine here; the Flight screen keeps to
/// plain words.
class DiagnosticsScreen extends StatefulWidget {
  const DiagnosticsScreen({super.key, required this.session});

  final SessionApi session;

  @override
  State<DiagnosticsScreen> createState() => _DiagnosticsScreenState();
}

class _DiagnosticsScreenState extends State<DiagnosticsScreen>
    with WidgetsBindingObserver {
  SessionSnapshot _snap = SessionSnapshot.none;
  StreamSubscription<SessionSnapshot>? _snapshots;

  /// The service could not be read (no answer, or another schema version).
  Object? _error;
  String? _telemetryMessage;
  bool _requesting = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _snapshots = widget.session.snapshots.listen(_apply, onError: (Object error) {
      if (mounted) setState(() => _error = error);
    });
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _snapshots?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      _apply(await widget.session.getSnapshot());
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  void _apply(SessionSnapshot snapshot) {
    if (!mounted || snapshot.seq < _snap.seq) return;
    setState(() {
      _snap = snapshot;
      _error = null;
    });
  }

  Future<void> _requestTelemetry() async {
    if (_requesting) return;
    setState(() {
      _requesting = true;
      _telemetryMessage = null;
    });
    String message;
    try {
      message = await widget.session.requestTelemetry()
          ? telemetryRequested
          : telemetryNotSent;
    } catch (error) {
      message = userErrorMessage(error);
    }
    if (!mounted) return;
    setState(() {
      _requesting = false;
      _telemetryMessage = message;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final snap = _snap;
    final link = snap.link;
    final vehicle = snap.vehicle;
    final error = _error;
    final rawVehicle = snap.raw['vehicle'];
    final rawStop = snap.raw['stop'];
    return Scaffold(
      appBar: AppBar(title: const Text('Diagnostics')),
      body: SafeArea(
        top: false,
        child: ListView(padding: const EdgeInsets.all(12), children: [
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                  error is FormatException
                      ? error.message
                      : 'The flight service is not answering.',
                  key: const Key('serviceError'),
                  style: TextStyle(color: theme.colorScheme.error)),
            ),
          _Section('Latency (newest spans)', children: [
            const _LatencyRow.header(),
            for (final stage in {...snap.latency.keys, ..._stages.keys})
              _LatencyRow(stage: stage, stat: snap.latency[stage]),
          ]),
          _Section('Link', children: [
            _Value('link.state', 'State', link.state.name),
            _Value('link.transport', 'Transport', _raw(link.transport)),
            _Value('link.description', 'Address', _raw(link.description)),
            _Value('link.profile', 'Telemetry profile', _raw(link.profile)),
            _Value('link.unsigned', 'Unsigned (no MAVLink signing)',
                _yesNo(link.unsigned)),
            _Value('link.heartbeatAgeMs', 'Heartbeat age',
                link.heartbeatAgeMs == null ? '—' : '${link.heartbeatAgeMs} ms'),
            _Value('link.txErrors', 'Send errors', '${link.txErrors}'),
            _Value('link.droppedDatagrams', 'Dropped datagrams',
                _raw(link.droppedDatagrams)),
            _Value('link.foregroundService', 'Keeps running in the background',
                _yesNo(link.foregroundService)),
            _Value('execution.enabled', 'Execution',
                snap.executionEnabled ? 'on (flying)' : 'off (dry run)'),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton.icon(
                  onPressed: link.state == LinkState.down || _requesting
                      ? null
                      : _requestTelemetry,
                  icon: const Icon(Icons.sync),
                  label: const Text('Request telemetry')),
            ),
            if (_telemetryMessage case final message?)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(message, key: const Key('telemetryMessage')),
              ),
          ]),
          _Section('Flight mode', children: [
            _Value('owner.modeOwner', 'Mode owner', _raw(vehicle.modeOwner)),
            _Value('owner.modeOwnerLabel', 'Set by', _raw(vehicle.modeOwnerLabel)),
            _Value(
                'owner.mode',
                'Mode',
                vehicle.mode == null
                    ? '—'
                    : '${vehicle.modeName ?? 'MODE'} (${vehicle.mode})'),
          ]),
          _Section('Vehicle telemetry (raw)', children: [
            if (rawVehicle is Map<String, Object?>)
              ..._rawRows('vehicle', rawVehicle)
            else
              const Text('No data from the flight service yet.'),
          ]),
          if (rawStop is Map<String, Object?>)
            _Section('Last STOP (raw)', children: [..._rawRows('stop', rawStop)]),
          Text('Snapshot ${snap.seq} · data version ${snap.schemaVersion}',
              style: theme.textTheme.bodySmall),
        ]),
      ),
    );
  }

  /// One row per value; a nested map (ages, last status text) one per entry.
  Iterable<Widget> _rawRows(String prefix, Map<String, Object?> map) sync* {
    for (final MapEntry(:key, :value) in map.entries) {
      if (value is Map<String, Object?>) {
        for (final MapEntry(key: inner, value: innerValue) in value.entries) {
          yield _Value('$prefix.$key.$inner', '$key.$inner', _raw(innerValue));
        }
      } else {
        yield _Value('$prefix.$key', key, _raw(value));
      }
    }
  }
}

class _Section extends StatelessWidget {
  const _Section(this.title, {required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            ...children,
          ]),
        ),
      );
}

/// A label and its value; the key names the field (`section.field`).
class _Value extends StatelessWidget {
  _Value(String key, this.label, this.value) : super(key: ValueKey(key));

  final String label, value;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(flex: 5, child: Text(label, style: text.bodyMedium)),
        const SizedBox(width: 8),
        Expanded(
            flex: 6,
            child: Text(value,
                style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w600))),
      ]),
    );
  }
}

/// A latency stage: p50, p95 and the number of spans; '—' before the first.
class _LatencyRow extends StatelessWidget {
  _LatencyRow({required this.stage, required this.stat})
      : header = false,
        super(key: ValueKey('latency.$stage'));

  const _LatencyRow.header()
      : stage = '',
        stat = null,
        header = true;

  final String stage;
  final LatencyStat? stat;
  final bool header;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final style = header
        ? text.labelLarge
        : text.bodyMedium?.copyWith(fontWeight: FontWeight.w600);
    final stat = this.stat;
    final label = _stages[stage];
    Widget number(String value, int flex) => Expanded(
        flex: flex, child: Text(value, textAlign: TextAlign.end, style: style));
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          flex: 5,
          child: header
              ? Text('Stage', style: style)
              : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(label ?? stage, style: text.bodyMedium),
                  if (label != null) Text(stage, style: text.bodySmall),
                ]),
        ),
        number(header ? 'p50 ms' : stat == null ? '—' : _ms(stat.p50Ms), 2),
        number(header ? 'p95 ms' : stat == null ? '—' : _ms(stat.p95Ms), 2),
        number(header ? 'n' : stat == null ? '—' : '${stat.n}', 1),
      ]),
    );
  }
}
