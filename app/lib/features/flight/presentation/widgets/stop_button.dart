import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart' show CustomSemanticsAction;
import 'package:flutter/services.dart';

import '../../../session/session_api.dart';

/// Shown when a STOP call itself fails (the service is not answering).
const stopUnreachable = 'STOP did not reach the flight service. Use RC.';

/// The alarm's "Got it" did not reach the service (the STOP itself may have).
const acknowledgeUnreachable =
    'The alarm could not be silenced: the flight service did not answer.';

/// The accessibility action that sends STOP over a pilot or failsafe mode,
/// as the deliberate 1 s hold does (TalkBack and switch access cannot hold).
const overrideActionLabel = 'Override and stop';

/// The giant, always-enabled STOP. It calls [onStop] the moment a finger
/// touches it (no release, no confirmation); the service decides what to
/// send. With [holdToOverride] (the pilot or autopilot set the flight mode),
/// holding it for 1 s also sends the override, with a fill that shows the
/// hold (spec 4.12). It grows past its usual height when large text needs
/// more lines for the hint, rather than cutting the hint off.
class StopButton extends StatefulWidget {
  const StopButton({
    super.key,
    required this.onStop,
    required this.holdToOverride,
    this.ownerLabel,
  });

  final void Function({required bool heldOneSecond}) onStop;
  final bool holdToOverride;

  /// Who set the flight mode (`modeOwnerLabel`): "Autopilot failsafe" or
  /// "Pilot mode".
  final String? ownerLabel;

  /// What holding means, by who has the flight mode.
  static String holdHint(String? ownerLabel) =>
      ownerLabel == 'Autopilot failsafe'
          ? 'Autopilot failsafe active — hold 1 s to override'
          : 'Pilot has control — hold 1 s to override';

  /// The spoken label: the hold, or the actions menu's [overrideActionLabel].
  static String semanticsHint(String? ownerLabel) =>
      '${holdHint(ownerLabel)}, or choose "$overrideActionLabel" from the actions menu';

  @override
  State<StopButton> createState() => _StopButtonState();
}

class _StopButtonState extends State<StopButton>
    with SingleTickerProviderStateMixin {
  late final _hold = AnimationController(
      vsync: this, duration: const Duration(seconds: 1))
    ..addStatusListener(_held);
  bool _pressed = false;

  void _held(AnimationStatus status) {
    if (status == AnimationStatus.completed && _pressed && widget.holdToOverride) {
      _pressed = false; // once per hold
      widget.onStop(heldOneSecond: true);
    }
  }

  void _down(PointerDownEvent _) {
    widget.onStop(heldOneSecond: false);
    if (!widget.holdToOverride) return;
    _pressed = true;
    _hold.forward(from: 0);
  }

  void _release([PointerEvent? _]) {
    _pressed = false;
    _hold.reset();
  }

  @override
  void didUpdateWidget(StopButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.holdToOverride) _release();
  }

  @override
  void dispose() {
    _hold.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final hint = StopButton.holdHint(widget.ownerLabel);
    return Semantics(
      container: true,
      button: true,
      label: widget.holdToOverride ? 'STOP. ${StopButton.semanticsHint(widget.ownerLabel)}' : 'STOP',
      // The override is the deliberate 1 s hold of a real press, or, for
      // TalkBack and switch access (which cannot hold), a named action picked
      // from the actions menu: never an instant long-press (audit F3).
      onTap: () => widget.onStop(heldOneSecond: false),
      customSemanticsActions: widget.holdToOverride
          ? {
              const CustomSemanticsAction(label: overrideActionLabel): () =>
                  widget.onStop(heldOneSecond: true),
            }
          : null,
      excludeSemantics: true,
      child: Listener(
        key: const Key('stopButton'),
        behavior: HitTestBehavior.opaque,
        onPointerDown: _down,
        onPointerUp: _release,
        onPointerCancel: _release,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(16),
          child: Material(
            color: scheme.error,
            child: ConstrainedBox(
              constraints:
                  BoxConstraints(minHeight: widget.holdToOverride ? 120 : 104),
              child: Stack(alignment: Alignment.center, children: [
                Positioned.fill(
                  child: AnimatedBuilder(
                      animation: _hold,
                      builder: (context, _) => Align(
                          alignment: Alignment.centerLeft,
                          child: FractionallySizedBox(
                              key: const Key('stopHoldFill'),
                              widthFactor: _hold.value,
                              heightFactor: 1,
                              child: ColoredBox(
                                  color:
                                      scheme.onError.withValues(alpha: 0.3))))),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    Text('STOP',
                        style: text.displaySmall?.copyWith(
                            color: scheme.onError,
                            fontWeight: FontWeight.w900,
                            letterSpacing: 4)),
                    if (widget.holdToOverride)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                        child: Text(hint,
                            textAlign: TextAlign.center,
                            style: text.titleSmall
                                ?.copyWith(color: scheme.onError)),
                      ),
                  ]),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

/// STOP NOT CONFIRMED (or another STOP alarm): red until the operator taps
/// Got it, which tells the service the alarm was seen.
class StopAlarmBanner extends StatelessWidget {
  const StopAlarmBanner(
      {super.key, required this.stop, required this.onAcknowledge});

  final StopStatus stop;
  final VoidCallback onAcknowledge;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final style = TextStyle(color: scheme.onError);
    return Material(
      color: scheme.error,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        child: Row(children: [
          Icon(Icons.warning_rounded, color: scheme.onError, size: 32),
          const SizedBox(width: 8),
          Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(stop.message,
                style: style.copyWith(fontWeight: FontWeight.bold, fontSize: 16)),
            if (stop.modeOwnerLabel case final label?) Text(label, style: style),
          ])),
          TextButton(
              onPressed: onAcknowledge,
              style: TextButton.styleFrom(foregroundColor: scheme.onError),
              child: const Text('Got it')),
        ]),
      ),
    );
  }
}

/// STOP above every screen. The app's `MaterialApp.builder` puts it over the
/// navigator, so no pushed screen, dialog, sheet or menu ever covers it
/// (spec 4.12 trigger 1, "always visible"). Below STOP: the STOP alarm,
/// which also vibrates until Got it; what the last STOP did, for a few
/// seconds; and a STOP call that did not reach the service.
class StopLane extends StatefulWidget {
  const StopLane({super.key, required this.session, required this.child});

  final SessionApi session;

  /// The navigator.
  final Widget child;

  @override
  State<StopLane> createState() => _StopLaneState();
}

class _StopLaneState extends State<StopLane> with WidgetsBindingObserver {
  SessionSnapshot _snap = SessionSnapshot.none;
  StreamSubscription<SessionSnapshot>? _snapshots;
  String? _stopError;
  /// The STOP id when [_stopError] was raised: a later STOP that reached the
  /// service (a new id) clears it (audit F6).
  int? _stopErrorAt;
  int? _stopId;
  /// The alarm the operator acknowledged: its STOP id, phase and action. An
  /// escalation of the same STOP (another phase or action) alarms again (F4).
  String? _seenAlarm;
  bool _stopRecent = false;
  Timer? _stopFade, _buzz;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // The Flight screen explains a service it cannot read; STOP still calls it.
    _snapshots = widget.session.snapshots.listen(_apply, onError: (Object _) {});
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _snapshots?.cancel();
    _stopFade?.cancel();
    _buzz?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      _apply(await widget.session.getSnapshot());
    } catch (_) {}
  }

  void _apply(SessionSnapshot snapshot) {
    if (!mounted || snapshot.seq < _snap.seq) return;
    final stop = snapshot.stop;
    setState(() {
      _snap = snapshot;
      if (_stopError != null && stop != null && stop.id != _stopErrorAt) {
        _stopError = null;
        _stopErrorAt = null;
      }
      if (stop != null && stop.id != _stopId) {
        _stopId = stop.id;
        _stopRecent = true;
        _stopFade?.cancel();
        _stopFade = Timer(const Duration(seconds: 8), () {
          if (mounted) setState(() => _stopRecent = false);
        });
      }
    });
    _alarm(stop != null && stop.alarm && _alarmKey(stop) != _seenAlarm);
  }

  static String _alarmKey(StopStatus stop) => '${stop.id}/${stop.phase}/${stop.action}';

  /// The alarm is loud (spec 4.0): a vibration every second until the
  /// operator taps Got it or the service clears it. The service's own tone,
  /// for when the app is in the background, is a follow-up.
  void _alarm(bool on) {
    if (!on) {
      _buzz?.cancel();
      _buzz = null;
    } else if (_buzz == null) {
      _vibrate();
      _buzz = Timer.periodic(const Duration(seconds: 1), (_) => _vibrate());
    }
  }

  void _vibrate() => HapticFeedback.vibrate().catchError((Object _) {});

  void _stop({required bool heldOneSecond}) {
    if (_stopError != null) setState(() => _stopError = null);
    widget.session.stop(heldOneSecond: heldOneSecond).catchError((Object _) {
      if (mounted) {
        setState(() {
          _stopError = stopUnreachable;
          _stopErrorAt = _snap.stop?.id;
        });
      }
    });
  }

  void _acknowledge() {
    final stop = _snap.stop;
    _seenAlarm = stop == null ? null : _alarmKey(stop);
    _alarm(false);
    widget.session.acknowledgeAlarm().catchError((Object _) {
      if (mounted) {
        setState(() {
          _stopError = acknowledgeUnreachable;
          _stopErrorAt = _snap.stop?.id;
        });
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final stop = _snap.stop;
    final vehicle = _snap.vehicle;
    Widget below(Widget child) =>
        Padding(padding: const EdgeInsets.only(top: 6), child: child);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Material(
        color: theme.colorScheme.surface,
        child: SafeArea(
          bottom: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 6),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              StopButton(
                  onStop: _stop,
                  // As the service (needsHoldForExternal): only an armed
                  // vehicle, or one whose arming is not known yet, in a mode
                  // set outside the app; a disarmed one gets nothing anyway.
                  holdToOverride: stop?.needsHoldForExternal == true ||
                      (vehicle.external && vehicle.armed != false),
                  // Who has the mode now; else who had it at the last STOP.
                  ownerLabel: vehicle.external
                      ? vehicle.modeOwnerLabel
                      : stop?.modeOwnerLabel),
              if (stop != null && stop.alarm)
                below(StopAlarmBanner(
                    key: const Key('stopAlarm'),
                    stop: stop,
                    onAcknowledge: _acknowledge)),
              if (_stopError case final message?)
                below(Text(message,
                    key: const Key('stopError'),
                    style: TextStyle(
                        color: theme.colorScheme.error,
                        fontWeight: FontWeight.bold))),
              if (stop != null &&
                  !stop.alarm &&
                  (_stopRecent || stop.phase == 'SENDING'))
                below(Text(stop.message,
                    key: const Key('stopMessage'),
                    style: theme.textTheme.titleMedium)),
            ]),
          ),
        ),
      ),
      // Every route's modal barrier blocks the semantics painted before it in
      // its container; its own container keeps STOP out of that reach, so
      // TalkBack and switch access still find STOP.
      Expanded(
          child: Semantics(
              container: true,
              child: MediaQuery.removePadding(
                  context: context, removeTop: true, child: widget.child))),
    ]);
  }
}
