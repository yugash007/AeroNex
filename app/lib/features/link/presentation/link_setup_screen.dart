import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/user_errors.dart';
import '../../session/session_api.dart';
import '../data/link_settings.dart';

const badAddress =
    'Enter an address like 192.168.4.1: four numbers from 0 to 255.';
const badDronePort = 'Enter a drone port from 1 to 65535.';
const badPhonePort = 'Enter a phone port from 0 to 65535.';

const telemetryNotRequested = "The drone's telemetry could not be requested, "
    'so the app stays in Practice. Check the link and try again.';
const linkChangedWhileEnabling =
    'The link changed while this was turning on, so the app stays in Practice. '
    'Try again.';
const executionRefused = 'The flight service kept Practice because the link '
    'is not ready. Check the link and try again.';
const executionStillOn = 'Fly the drone could not be turned back off, so it '
    'is still on. Turn it off here, or disconnect.';

/// Shown under Save & Connect while the permission was never asked.
const notificationsReason = 'Save & Connect asks once to allow notifications, '
    'so STOP is also in the notification when the app is in the background.';

/// A dotted quad without leading zeros (v0.2.0's check): the service opens UDP
/// to an address and never looks a name up.
final _ipv4 = RegExp(
    r'^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$');
final _digits = RegExp(r'^\d{1,5}$');

String? addressProblem(String text) =>
    _ipv4.hasMatch(text.trim()) ? null : badAddress;

int? _port(String text, int min) {
  final trimmed = text.trim();
  if (!_digits.hasMatch(trimmed)) return null;
  final value = int.parse(trimmed);
  return value >= min && value <= 65535 ? value : null;
}

String? dronePortProblem(String text) =>
    _port(text, 1) == null ? badDronePort : null;

/// 0 lets the phone pick any free port.
String? phonePortProblem(String text) =>
    _port(text, 0) == null ? badPhonePort : null;

/// Link setup: the saved link (address, ports, UDP or TCP, telemetry radio),
/// and this connection's execution switch. The link is saved and reused, so
/// setup is not repeated; execution is never saved (S6) and needs the
/// operator's explicit agreement each time it is turned on.
class LinkSetupScreen extends StatefulWidget {
  const LinkSetupScreen({
    super.key,
    required this.session,
    this.settings = const DeviceLinkSettingsStore(),
  });

  final SessionApi session;

  /// Where the link is saved; the app shell's store by default.
  final LinkSettingsStore settings;

  @override
  State<LinkSetupScreen> createState() => _LinkSetupScreenState();
}

class _LinkSetupScreenState extends State<LinkSetupScreen>
    with WidgetsBindingObserver {
  final _form = GlobalKey<FormState>();
  final _host = TextEditingController(),
      _remotePort = TextEditingController(),
      _localPort = TextEditingController();
  LinkTransport _transport = LinkTransport.udp;
  bool _radioLink = false, _loaded = false, _saving = false;
  LinkSettings? _saved;

  /// The result of the last Save, shown under the button.
  String? _message;
  bool _messageIsError = false;

  SessionSnapshot _snap = SessionSnapshot.none;
  StreamSubscription<SessionSnapshot>? _snapshots;

  /// Turning execution on: the attempt in progress; Cancel or leaving the
  /// screen abandons it.
  bool _enabling = false;
  int _attempt = 0;

  /// The attempt whose `setExecution(true)` has not answered yet.
  int? _sendingAttempt;

  /// A cancelled attempt's `setExecution(true)`, or its undo, has not
  /// answered yet: the switch waits, so the undo can never reach the service
  /// after a newer turn-on.
  bool _settling = false;

  /// The service's `setExecution` answer, until a newer snapshot agrees or a
  /// fresh read shows the service changed it since.
  bool? _executionReply;
  int _replySeq = -1;
  String? _replyLink;
  bool _checkingReply = false;
  String? _executionError;

  /// POST_NOTIFICATIONS as the service reports it; null when unknown.
  NotificationPermission? _notifications;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _snapshots = widget.session.snapshots.listen(_apply, onError: (Object _) {});
    _refresh();
    _load();
    _loadNotifications();
  }

  @override
  void dispose() {
    _attempt++; // leaving abandons turning execution on
    WidgetsBinding.instance.removeObserver(this);
    _snapshots?.cancel();
    _host.dispose();
    _remotePort.dispose();
    _localPort.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      _apply(await widget.session.getSnapshot());
    } catch (_) {
      // The Flight screen explains a service it cannot read.
    }
  }

  void _apply(SessionSnapshot snapshot) {
    if (!mounted || snapshot.seq < _snap.seq) return;
    setState(() => _snap = snapshot);
    final reply = _executionReply;
    if (reply == null || snapshot.seq <= _replySeq) return;
    if (snapshot.executionEnabled == reply) {
      setState(() => _executionReply = null);
    } else {
      _checkReply();
    }
  }

  /// A snapshot newer than the last `setExecution` answer disagrees with it.
  /// It may have been built just before that call ran, so read the service
  /// again (`getSnapshot` runs after `setExecution` on the service's worker).
  /// If the service did change it since, say why; either way the switch
  /// follows the service from then on.
  Future<void> _checkReply() async {
    if (_checkingReply) return;
    _checkingReply = true;
    final reply = _executionReply!, replySeq = _replySeq, link = _replyLink;
    SessionSnapshot? fresh;
    try {
      fresh = await widget.session.getSnapshot();
    } catch (_) {
      // The contradicting snapshot stands.
    }
    _checkingReply = false;
    // A newer answer came meanwhile: it decides.
    if (!mounted || _executionReply != reply || _replySeq != replySeq) return;
    setState(() {
      if (fresh != null && fresh.seq >= _snap.seq) _snap = fresh;
      _executionReply = null;
      if (_snap.executionEnabled == reply) return;
      if (!reply) {
        _executionError = executionStillOn;
      } else if (_executionError == executionStillOn) {
        _executionError = null; // the off it could not confirm did happen
      } else {
        _executionError = _linkLive && _snap.link.description == link
            ? executionRefused
            : linkChangedWhileEnabling;
      }
    });
  }

  Future<void> _loadNotifications() async {
    try {
      final state = await widget.session.notificationPermission();
      if (mounted) setState(() => _notifications = state);
    } catch (_) {
      // Unknown: nothing is asked.
    }
  }

  /// Once, before a link opens: the system dialog covers the whole app,
  /// STOP included. Connecting goes on whatever the answer.
  Future<void> _askNotifications() async {
    if (_notifications != NotificationPermission.askable) return;
    NotificationPermission? state;
    try {
      state = await widget.session.notificationPermission(ask: true);
    } catch (_) {
      state = null;
    }
    if (mounted) setState(() => _notifications = state);
  }

  Future<void> _load() async {
    LinkSettings? saved;
    try {
      saved = await widget.settings.load();
    } catch (_) {
      saved = null;
    }
    if (!mounted) return;
    final link = (saved ?? LinkSettings.defaults).link;
    setState(() {
      _saved = saved;
      _transport = link.transport;
      _host.text = link.host;
      _remotePort.text = '${link.remotePort}';
      // TCP keeps no phone port; UDP starts from the usual one.
      _localPort.text = link.transport == LinkTransport.udp
          ? '${link.localPort}'
          : '${LinkSettings.defaults.link.localPort}';
      _radioLink = link.radioLink;
      _loaded = true;
    });
  }

  bool get _connected => _snap.link.state != LinkState.down;

  LinkConfig _config() => LinkConfig(
        transport: _transport,
        host: _host.text.trim(),
        remotePort: _port(_remotePort.text, 1)!,
        localPort: _transport == LinkTransport.udp ? _port(_localPort.text, 0)! : 0,
        radioLink: _radioLink,
      );

  /// False when the store refused; the link is never blocked by it.
  Future<bool> _store(LinkSettings settings) async {
    try {
      await widget.settings.save(settings);
      _saved = settings;
      return true;
    } catch (_) {
      return false;
    }
  }

  void _show(String message, {bool error = false}) {
    if (!mounted) return;
    setState(() {
      _message = message;
      _messageIsError = error;
    });
  }

  /// Save while a link is open: it applies at the next connect, and the open
  /// link is left alone.
  Future<void> _saveOnly(LinkConfig link) async {
    final previous = _saved;
    final unchanged = previous != null && previous.link == link;
    // An untried link never connects by itself at launch.
    final stored = await _store(
        LinkSettings(link: link, autoConnect: unchanged && previous.autoConnect));
    if (!stored) {
      _show(linkNotSaved, error: true);
    } else {
      _show(unchanged
          ? 'Saved.'
          : 'Saved. It is used the next time you connect: disconnect, '
              'then connect on the Flight screen.');
    }
  }

  /// Save & Connect; while a link is open, Save only.
  Future<void> _save() async {
    if (_saving || !_form.currentState!.validate()) return;
    final link = _config();
    setState(() {
      _saving = true;
      _message = null;
    });
    try {
      if (_connected) return await _saveOnly(link);
      await _askNotifications();
      if (!mounted) return;
      Object? failure;
      try {
        _apply(await widget.session.connect(link));
      } catch (error) {
        failure = error;
      }
      if (failure is PlatformException && failure.code == 'ALREADY_CONNECTED') {
        // A link opened before this screen heard of it (its first snapshot,
        // or the Flight screen's own connect): keep it, and save as when
        // connected, so a launch still reconnects to an unchanged link.
        await _refresh();
        return await _saveOnly(link);
      }
      // Saved either way, so setup is not asked for again; only a link that
      // connected reconnects at launch, always in Practice.
      final stored =
          await _store(LinkSettings(link: link, autoConnect: failure == null));
      if (failure != null) {
        _show(userErrorMessage(failure), error: true);
        await _refresh();
      } else if (!stored) {
        _show(linkNotSaved, error: true);
      } else if (mounted) {
        await Navigator.of(context).maybePop();
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  // --- execution ---

  /// A vehicle heartbeat on an open link: what turning execution on needs.
  bool get _linkLive =>
      _snap.link.state == LinkState.up && _snap.vehicle.connected;

  bool get _executionOn => _executionReply ?? _snap.executionEnabled;

  void _reply(bool enabled) {
    if (!mounted) return;
    setState(() {
      _executionReply = enabled;
      _replySeq = _snap.seq;
      _replyLink = _snap.link.description;
    });
  }

  Future<void> _turnOn() async {
    if (_enabling || _settling) return;
    setState(() => _executionError = null);
    final link = _snap.link.description;
    final agreed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
              // Large text scrolls the notice; the buttons stay on screen.
              scrollable: true,
              // v0.2.0's notice: real hardware stays locked until the spec's
              // 8.4 prerequisites pass (S6), so it is about the simulator
              // from its title on.
              title: const Text('Send plans to the simulator?'),
              content: Text(
                  'Confirmed plans will be sent over MAVLink to '
                  '${link ?? 'the simulator'} and flown. '
                  'Takeoff starts the motors.\n\n'
                  'Use only an isolated ArduPilot simulator (SITL): this app '
                  'cannot verify that the link leads to a simulator. Never '
                  'use this with a real aircraft.\n\n'
                  'This stays on until you turn it off or disconnect.'),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('Cancel')),
                TextButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('Fly the drone')),
              ],
            ));
    if (!mounted || agreed != true) return;
    // The agreement was for the link shown in the notice.
    if (!_linkLive || _snap.link.description != link) {
      setState(() => _executionError = linkChangedWhileEnabling);
      return;
    }
    final attempt = ++_attempt;
    setState(() => _enabling = true);
    var sent = false;
    try {
      // The executor needs the telemetry streams; asking sends no flight
      // command.
      final queued = await widget.session.requestTelemetry();
      if (!mounted || attempt != _attempt) return;
      if (!queued) throw StateError(telemetryNotRequested);
      if (!_linkLive || _snap.link.description != link) {
        throw StateError(linkChangedWhileEnabling);
      }
      sent = true;
      _sendingAttempt = attempt;
      final on = await widget.session.setExecution(true);
      if (attempt != _attempt) {
        // Cancelled, or the screen closed, while the service turned it on:
        // turn it back off.
        if (on) await _undo();
        return;
      }
      _reply(on);
      if (!on) setState(() => _executionError = executionRefused);
    } catch (error) {
      if (mounted && attempt == _attempt) {
        setState(() => _executionError = userErrorMessage(error));
      }
    } finally {
      if (_sendingAttempt == attempt) _sendingAttempt = null;
      if (mounted && attempt == _attempt) setState(() => _enabling = false);
      // A cancelled attempt has fully answered: the switch is free again.
      if (mounted && sent && attempt != _attempt) {
        setState(() => _settling = false);
      }
    }
  }

  /// Turns execution back off after an abandoned attempt the service had
  /// already turned on. If it cannot, the switch shows it on and says so;
  /// after leaving this screen, the Flight screen shows it (no 'Dry run' chip,
  /// and CONFIRM without '(dry run)').
  Future<void> _undo() async {
    try {
      _reply(await widget.session.setExecution(false));
    } catch (_) {
      if (!mounted) return;
      _reply(true);
      setState(() => _executionError = executionStillOn);
    }
  }

  /// Practice again: no notice. During a plan the service stops the drone.
  Future<void> _turnOff() async {
    _attempt++;
    setState(() => _executionError = null);
    try {
      _reply(await widget.session.setExecution(false));
    } catch (error) {
      if (mounted) setState(() => _executionError = userErrorMessage(error));
    }
  }

  void _cancelEnabling() => setState(() {
        // Already asked to turn on: wait for that answer and its undo.
        _settling = _sendingAttempt == _attempt;
        _attempt++;
        _enabling = false;
      });

  String _connectionLine() {
    final description = _snap.link.description;
    return switch (_snap.link.state) {
      LinkState.down => 'Not connected.',
      LinkState.connecting =>
        'Waiting for the drone to answer${description == null ? '' : ' on $description'}.',
      LinkState.up => 'Connected${description == null ? '' : ': $description'}.',
    };
  }

  Widget _executionCard(ThemeData theme) {
    final on = _executionOn;
    final live = _linkLive;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('This connection', style: theme.textTheme.titleMedium),
          Text(_connectionLine(), key: const Key('connectionLine')),
          SwitchListTile(
            key: const Key('executionSwitch'),
            contentPadding: EdgeInsets.zero,
            title: const Text('Fly the drone'),
            subtitle: Text(on
                ? 'On: confirmed plans are flown. Turning this off stops the '
                    'drone if a plan is running. It turns off when you '
                    'disconnect.'
                : live
                    ? 'Off: Practice (dry run). Confirmed plans are checked '
                        'but not sent to the drone.'
                    : 'Off: Practice (dry run). Connect first; the drone must '
                        'be answering.'),
            value: on,
            onChanged: _enabling || _settling
                ? null
                : on
                    ? (_) => _turnOff()
                    : live
                        ? (_) => _turnOn()
                        : null,
          ),
          if (_enabling)
            Row(children: [
              const Expanded(child: Text('Asking the drone for its telemetry…')),
              TextButton(
                  key: const Key('cancelExecution'),
                  onPressed: _cancelEnabling,
                  child: const Text('Cancel')),
            ]),
          if (_executionError case final error?)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(error, style: TextStyle(color: theme.colorScheme.error)),
            ),
        ]),
      ),
    );
  }

  Widget _linkCard(ThemeData theme) {
    final editable = _loaded && !_saving;
    final udp = _transport == LinkTransport.udp;
    const gap = SizedBox(height: 12);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Form(
          key: _form,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('Saved link', style: theme.textTheme.titleMedium),
            if (!_loaded) const Text('Reading the saved link…'),
            const SizedBox(height: 8),
            SegmentedButton<LinkTransport>(
              segments: const [
                ButtonSegment(value: LinkTransport.udp, label: Text('UDP')),
                ButtonSegment(value: LinkTransport.tcp, label: Text('TCP')),
              ],
              selected: {_transport},
              onSelectionChanged: editable
                  ? (selection) => setState(() => _transport = selection.single)
                  : null,
            ),
            const SizedBox(height: 4),
            Text(
                udp
                    ? 'UDP: most Wi-Fi bridges and the simulator.'
                    : 'TCP: a TCP server on the drone or on the PC.',
                style: theme.textTheme.bodySmall),
            gap,
            TextFormField(
              controller: _host,
              enabled: editable,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              autovalidateMode: AutovalidateMode.onUserInteraction,
              validator: (text) => addressProblem(text ?? ''),
              decoration: const InputDecoration(
                labelText: 'Drone address',
                helperText: "The drone's Wi-Fi bridge, or the PC running the "
                    'simulator: 192.168.x.x. On the Android emulator, the PC '
                    'is 10.0.2.2.',
                helperMaxLines: 4,
                errorMaxLines: 3,
              ),
            ),
            gap,
            TextFormField(
              controller: _remotePort,
              enabled: editable,
              keyboardType: TextInputType.number,
              autovalidateMode: AutovalidateMode.onUserInteraction,
              validator: (text) => dronePortProblem(text ?? ''),
              decoration: InputDecoration(
                labelText: 'Drone port',
                helperText: udp
                    ? 'Where the drone or the simulator listens; usually 14550.'
                    : 'Where the TCP server listens; often 5760.',
                helperMaxLines: 3,
                errorMaxLines: 3,
              ),
            ),
            if (udp) ...[
              gap,
              TextFormField(
                controller: _localPort,
                enabled: editable,
                keyboardType: TextInputType.number,
                autovalidateMode: AutovalidateMode.onUserInteraction,
                validator: (text) => phonePortProblem(text ?? ''),
                decoration: const InputDecoration(
                  labelText: 'Phone port',
                  helperText: 'Where this phone listens; usually 14551. '
                      '0 lets the phone pick a free port.',
                  helperMaxLines: 3,
                  errorMaxLines: 3,
                ),
              ),
            ],
            const SizedBox(height: 4),
            SwitchListTile(
              key: const Key('radioLinkSwitch'),
              contentPadding: EdgeInsets.zero,
              title: const Text('This link goes through a telemetry radio'),
              subtitle: const Text('Turn on for an ELRS Backpack or a Wi-Fi '
                  'bridge on a SiK radio. The app then asks for less '
                  'telemetry, so the radio keeps up.'),
              value: _radioLink,
              onChanged: editable ? (on) => setState(() => _radioLink = on) : null,
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 52,
              child: FilledButton(
                key: const Key('saveLink'),
                onPressed: editable ? _save : null,
                child: Text(switch ((_saving, _connected)) {
                  (true, true) => 'Saving…',
                  (true, false) => 'Connecting…',
                  (false, true) => 'Save',
                  (false, false) => 'Save & Connect',
                }),
              ),
            ),
            if (_message case final message?)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(message,
                    key: const Key('linkMessage'),
                    style: _messageIsError
                        ? TextStyle(color: theme.colorScheme.error)
                        : null),
              ),
            if (_notifications == NotificationPermission.askable && !_connected)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(notificationsReason,
                    key: const Key('notificationsReason'),
                    style: theme.textTheme.bodySmall),
              ),
          ]),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return PopScope(
      // Leaving abandons turning execution on at once, not after the route's
      // exit animation.
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) _attempt++;
      },
      child: Scaffold(
        appBar: AppBar(title: const Text('Link setup')),
        body: SafeArea(
          top: false,
          child: ListView(padding: const EdgeInsets.all(12), children: [
            _executionCard(theme),
            const SizedBox(height: 8),
            _linkCard(theme),
          ]),
        ),
      ),
    );
  }
}
