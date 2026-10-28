import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../core/user_errors.dart';
import '../../link/data/link_settings.dart';
import '../../session/session_api.dart';
import '../domain/flight_command.dart';
import '../domain/flight_plan.dart';
import 'widgets/command_input.dart';
import 'widgets/flight_memory_card.dart';
import 'widgets/plan_card.dart';
import 'widgets/status_line.dart';
import 'widgets/stop_button.dart';
import 'widgets/tap_pad.dart';

/// The screens the Flight screen opens (the app shell routes them).
enum FlightDestination { linkSetup, models, diagnostics }

enum _Menu { linkSetup, models, diagnostics, licenses }

/// Home: the vehicle line, the plan waiting for CONFIRM (or running), the
/// ways to give a command (push-to-talk, typing, the tap pad) and the flight
/// memory (return home, go back, retrace, points, run again). The giant STOP
/// sits above it, and above every other route, in the app's [StopLane]. The
/// flight session service listens, parses, decides and executes everything;
/// this screen sends push-to-talk edges, typed text and tap-pad plans, and
/// shows its snapshots. While it is the route in front, Volume-Up is
/// push-to-talk too (the service is told through `setFlightScreenVisible`).
class FlightScreen extends StatefulWidget {
  const FlightScreen({
    super.key,
    required this.session,
    required this.settings,
    required this.open,
  });

  final SessionApi session;
  final LinkSettingsStore settings;

  /// Opens another screen; completes when it closes.
  final Future<void> Function(FlightDestination destination) open;

  @override
  State<FlightScreen> createState() => _FlightScreenState();
}

class _FlightScreenState extends State<FlightScreen> with WidgetsBindingObserver {
  SessionSnapshot _snap = SessionSnapshot.none;
  StreamSubscription<SessionSnapshot>? _snapshots;

  /// The service could not be read (no answer, or another schema version).
  Object? _serviceError;

  /// Why the last command or connect did not go through, shown as is.
  String? _error;

  /// A command is being read or sent: the inputs are locked until the
  /// service answered, so the plan it shows always matches the text shown.
  bool _busy = false;
  bool _connecting = false;

  /// The pending plan this screen confirmed: one CONFIRM per plan.
  String? _confirmedToken;

  /// The pending plan an edit already cancelled: one cancel per plan.
  String? _editCancelledToken;

  /// AI answers up to this request are no longer shown (new input came).
  int _aiSeen = 0;
  int _remainingMs = 0;
  Timer? _countdown;
  final _confirmRow = GlobalKey(), _cancelRow = GlobalKey(), _errorCard = GlobalKey();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _snapshots = widget.session.snapshots.listen(_apply,
        onError: (Object error) {
      if (mounted) setState(() => _serviceError = error);
    });
    _start();
  }

  /// Whether this screen is the route in front, as last told to the service.
  bool? _inFront;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // A route pushed on top (a dialog, a sheet, another screen) hands
    // Volume-Up back to the system volume.
    _setInFront(ModalRoute.of(context)?.isCurrent ?? true);
  }

  void _setInFront(bool inFront) {
    if (_inFront == inFront) return;
    _inFront = inFront;
    widget.session.setFlightScreenVisible(inFront).catchError((Object _) {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _snapshots?.cancel();
    _countdown?.cancel();
    if (_inFront == true) {
      widget.session.setFlightScreenVisible(false).catchError((Object _) {});
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The service kept running; show where it is now.
    if (state == AppLifecycleState.resumed) _refresh();
  }

  /// The session may still be running in the service from before (S7):
  /// show it, and only then decide about Link setup or reconnecting.
  Future<void> _start() async {
    await _refresh();
    final saved = await _load();
    if (!mounted) return;
    if (saved == null) {
      await widget.open(FlightDestination.linkSetup);
      return;
    }
    if (saved.autoConnect && _snap.link.state == LinkState.down) {
      await _connect(saved);
    }
  }

  Future<LinkSettings?> _load() async {
    try {
      return await widget.settings.load();
    } catch (_) {
      return null;
    }
  }

  /// A failed save never blocks the link (v0.2.0); it is reported.
  void _save(LinkSettings settings) => widget.settings
      .save(settings)
      .catchError((Object _) => _showError(linkNotSaved));

  Future<void> _refresh() async {
    try {
      _apply(await widget.session.getSnapshot());
    } catch (error) {
      if (mounted) setState(() => _serviceError = error);
    }
  }

  void _apply(SessionSnapshot snapshot) {
    if (!mounted || snapshot.seq < _snap.seq) return;
    final previous = _snap;
    final pending = snapshot.pending;
    setState(() {
      _snap = snapshot;
      _serviceError = null;
      // An AI answer from before this screen (an app restart) or before the
      // latest press is not news: only newer requests show.
      if (previous.seq == 0 || snapshot.speech.utterance != previous.speech.utterance) {
        _aiSeen = math.max(_aiSeen, snapshot.ai.request - (snapshot.ai.thinking ? 1 : 0));
      }
      if (pending == null) {
        _countdown?.cancel();
        _countdown = null;
      } else if (pending.token != previous.pending?.token) {
        // A new plan: its own countdown, ticking from its arrival, so it never
        // runs ahead of the service with the phase of an earlier plan.
        _remainingMs = pending.expiresInMs;
        _countdown?.cancel();
        _countdown = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
      } else {
        // The service's time left; between snapshots it only counts down.
        _remainingMs = math.min(_remainingMs, pending.expiresInMs);
      }
    });
    if (pending != null && pending.token != previous.pending?.token) {
      _reveal(_confirmRow);
    }
    if (snapshot.run?.executing == true && previous.run?.executing != true) {
      _reveal(_cancelRow);
    }
  }

  void _tick() {
    if (!mounted) return;
    setState(() => _remainingMs = math.max(0, _remainingMs - 1000));
    if (_remainingMs == 0 || _snap.pending == null) {
      _countdown?.cancel();
      _countdown = null;
    }
  }

  /// Brings [key]'s widget on screen once laid out, scrolling up or down as
  /// little as needed. A jump, not an animation: an animating scroll ignores
  /// taps, and CONFIRM or Cancel must work at once.
  void _reveal(GlobalKey key) => WidgetsBinding.instance.addPostFrameCallback((_) {
        final target = key.currentContext;
        if (!mounted || target == null) return;
        for (final policy in const [
          ScrollPositionAlignmentPolicy.keepVisibleAtStart,
          ScrollPositionAlignmentPolicy.keepVisibleAtEnd,
        ]) {
          Scrollable.ensureVisible(target, alignmentPolicy: policy);
        }
      });

  void _showError(String message) {
    if (!mounted) return;
    setState(() => _error = message);
    _reveal(_errorCard);
  }

  // --- link ---

  Future<void> _connect([LinkSettings? saved]) async {
    if (_connecting) return;
    // Set before the settings load, so a second tap meanwhile is a no-op.
    setState(() {
      _connecting = true;
      _error = null;
    });
    final settings = saved ?? await _load();
    if (!mounted) return;
    if (settings == null) {
      setState(() => _connecting = false);
      await widget.open(FlightDestination.linkSetup);
      return;
    }
    try {
      _apply(await widget.session.connect(settings.link));
      if (!settings.autoConnect) _save(settings.copyWith(autoConnect: true));
    } catch (error) {
      _showError(userErrorMessage(error));
      await _refresh();
    } finally {
      if (mounted) setState(() => _connecting = false);
    }
  }

  /// Disconnecting ends the link, and with it STOP, voice STOP and the
  /// heartbeat; the drone keeps its last command until its own failsafe acts.
  /// The button sits right under STOP, so while the drone may be flying, a
  /// plan runs or a STOP is still being sent, it asks first.
  bool get _disconnectIsRisky {
    final snap = _snap;
    return snap.vehicle.connected && snap.vehicle.armed != false ||
        snap.run?.executing == true ||
        snap.stop?.phase == 'SENDING';
  }

  Future<void> _disconnect() async {
    if (_disconnectIsRisky) {
      final sure = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
                key: const Key('disconnectConfirm'),
                title: const Text('Disconnect while flying?'),
                content: const Text('STOP, voice STOP and the heartbeat end with the link. The '
                    'drone keeps its last command until its own failsafe acts. Use RC.'),
                actions: [
                  TextButton(
                      key: const Key('stayConnected'),
                      onPressed: () => Navigator.pop(context, false),
                      child: const Text('Stay connected')),
                  TextButton(
                      key: const Key('disconnectAnyway'),
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('Disconnect')),
                ],
              ));
      if (sure != true || !mounted) return;
    }
    setState(() => _error = null);
    try {
      await widget.session.disconnect();
      final settings = await _load();
      if (settings != null && settings.autoConnect) {
        _save(settings.copyWith(autoConnect: false));
      }
    } catch (error) {
      _showError(userErrorMessage(error));
    }
    await _refresh();
  }

  /// Cancel: drops a waiting plan, and is STOP while one runs (S5).
  void _cancelPlan() {
    widget.session.cancel().catchError((Object _) => _showError(stopUnreachable));
  }

  // --- plans ---

  /// Typed text: the service's Kotlin parser reads it in the selected
  /// language, validates it and shows it for CONFIRM, or says why not. Text
  /// the rules do not understand may go to the on-phone AI (0.4.0): the
  /// screen then shows "Thinking…" until the AI plan or its reason arrives.
  /// Sending is new input, so a waiting plan goes first.
  Future<void> _submitText(String text, String language, PlanSource source) async {
    if (_busy) return;
    _inputEdited();
    await _locked(() => _sent(() => widget.session.submitText(text, language, source)));
  }

  void _tap(FlightCommand command) {
    final plan = FlightPlan.single(command, source: StepSource.rules);
    // HOLD acts at once (it is the STOP action): never held back by the lock.
    if (command.name == 'hold') {
      _send(plan, PlanSource.tap);
      return;
    }
    _locked(() => _send(plan, PlanSource.tap));
  }

  /// The flight memory's "Run again": the service shows the recorded plan
  /// again, validated against the vehicle now, for CONFIRM.
  void _runAgain(MemoryPlan plan) =>
      _locked(() => _sent(() => widget.session.runAgain(plan.planId)));

  /// Forgetting the flown path and points cannot be undone: it asks first.
  Future<void> _clearMemory() async {
    final sure = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
              key: const Key('clearMemoryConfirm'),
              title: const Text('Clear the flight memory?'),
              content: const Text('The flown path, the saved points and the recent commands are '
                  'forgotten. Return home still works.'),
              actions: [
                TextButton(
                    key: const Key('keepMemory'),
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('Keep')),
                TextButton(
                    key: const Key('clearMemory'),
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('Clear')),
              ],
            ));
    if (sure != true || !mounted) return;
    try {
      await widget.session.clearMemory();
    } catch (error) {
      _showError(userErrorMessage(error));
    }
    await _refresh();
  }

  /// Runs [work] with the inputs locked ([_busy]); one at a time.
  Future<void> _locked(Future<void> Function() work) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await work();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _send(FlightPlan plan, PlanSource source) =>
      _sent(() => widget.session.submitPlan(plan, source));

  /// Shows a plan the service refused with its reason, verbatim.
  Future<void> _sent(Future<SubmitResult> Function() submit) async {
    setState(() {
      _error = null;
      _confirmedToken = null;
    });
    try {
      final result = await submit();
      if (result is RejectedResult) _showError(result.error);
      await _refresh();
    } catch (error) {
      _showError(userErrorMessage(error));
    }
  }

  Future<void> _confirm(String token) async {
    if (_confirmedToken == token) return;
    setState(() {
      _confirmedToken = token;
      _error = null;
    });
    try {
      final result = await widget.session.confirm(token);
      if (!result.accepted) _showError(result.error ?? 'The plan did not start.');
      await _refresh();
    } catch (error) {
      _showError(userErrorMessage(error));
    }
  }

  /// Cancel while the AI thinks: the service drops the request, and a late
  /// answer is never shown.
  void _cancelAi() {
    widget.session.cancelAi().catchError((Object error) => _showError(userErrorMessage(error)));
  }

  /// New input invalidates a plan that was not confirmed (S2). Once
  /// confirmed, an edit never cancels: during a run that would be a STOP.
  void _inputEdited() {
    if (_error != null) setState(() => _error = null);
    if (_aiSeen != _snap.ai.request) setState(() => _aiSeen = _snap.ai.request);
    final pending = _snap.pending;
    if (pending == null ||
        pending.token == _confirmedToken ||
        pending.token == _editCancelledToken ||
        _busy) {
      return;
    }
    _editCancelledToken = pending.token;
    _cancelPlan();
  }

  String _serviceMessage(Object error) => error is FormatException
      ? error.message
      : 'The flight service is not answering. Restart the app, and use RC if '
          'the drone is flying.';

  Future<void> _menu(_Menu item) async {
    switch (item) {
      case _Menu.linkSetup:
        await widget.open(FlightDestination.linkSetup);
      case _Menu.models:
        await widget.open(FlightDestination.models);
      case _Menu.diagnostics:
        await widget.open(FlightDestination.diagnostics);
      case _Menu.licenses:
        showLicensePage(context: context, applicationName: 'AeroNex');
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final snap = _snap;
    final pending = snap.pending;
    final run = snap.run;
    final serviceError = _serviceError;
    final error = _error;
    final ai = snap.ai;
    // The AI's latest answer, until new input: its reason, or what it
    // understood beside the plan it shows.
    final aiAnswer = !ai.thinking && ai.request > _aiSeen && ai.error != null;
    return Scaffold(
      appBar: AppBar(title: const Text('AeroNex'), actions: [
        if (_connecting)
          const Padding(
              padding: EdgeInsets.symmetric(horizontal: 12),
              child: Center(child: Text('Connecting…')))
        else if (snap.link.state == LinkState.down)
          FilledButton.tonal(onPressed: _connect, child: const Text('Connect'))
        else
          TextButton(onPressed: _disconnect, child: const Text('Disconnect')),
        PopupMenuButton<_Menu>(
          tooltip: 'Menu',
          onSelected: _menu,
          itemBuilder: (_) => const [
            PopupMenuItem(value: _Menu.linkSetup, child: Text('Link setup')),
            PopupMenuItem(value: _Menu.models, child: Text('Models')),
            PopupMenuItem(value: _Menu.diagnostics, child: Text('Diagnostics')),
            PopupMenuItem(value: _Menu.licenses, child: Text('Licenses')),
          ],
        ),
      ]),
      body: SafeArea(
        top: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Padding(
              padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
              child: StatusLine(snapshot: snap)),
          const Divider(height: 1),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(12),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                if (serviceError != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(_serviceMessage(serviceError),
                        key: const Key('serviceError'),
                        style: TextStyle(color: theme.colorScheme.error)),
                  ),
                if (pending != null)
                  KeyedSubtree(
                    key: const Key('planCard'),
                    child: PendingPlanCard(
                        pending: pending,
                        executionEnabled: snap.executionEnabled,
                        secondsLeft: (_remainingMs + 999) ~/ 1000,
                        canConfirm: _confirmedToken != pending.token,
                        onConfirm: () => _confirm(pending.token),
                        onCancel: _cancelPlan,
                        confirmRowKey: _confirmRow,
                        ai: ai,
                        showAiHeard: ai.heard != snap.speech.heard),
                  )
                else if (run != null)
                  KeyedSubtree(
                    key: const Key('planCard'),
                    child: RunCard(run: run, onCancel: _cancelPlan, cancelRowKey: _cancelRow),
                  ),
                if (ai.thinking)
                  Card(
                    key: const Key('aiThinking'),
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                        Row(children: [
                          const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2)),
                          const SizedBox(width: 12),
                          Expanded(
                              child: Text('Thinking…', style: theme.textTheme.titleMedium)),
                          TextButton(
                              key: const Key('aiCancel'),
                              onPressed: _cancelAi,
                              child: const Text('Cancel')),
                        ]),
                        if (ai.heard != null && ai.heard != snap.speech.heard)
                          Text('Heard: ${ai.heard}', key: const Key('aiHeard')),
                        const Text('STOP still works at once.'),
                      ]),
                    ),
                  ),
                if (aiAnswer && pending == null)
                  Card(
                    key: const Key('aiError'),
                    color: theme.colorScheme.errorContainer,
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: DefaultTextStyle.merge(
                        style: TextStyle(color: theme.colorScheme.onErrorContainer),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                          AiUnderstanding(ai: ai, showHeard: ai.heard != snap.speech.heard),
                          Text(ai.error!),
                        ]),
                      ),
                    ),
                  ),
                if (pending == null && snap.notice != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Text(snap.notice!, key: const Key('notice')),
                  ),
                if (error != null)
                  KeyedSubtree(
                    key: _errorCard,
                    child: Card(
                      key: const Key('planError'),
                      color: theme.colorScheme.errorContainer,
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Text(error,
                            style: TextStyle(color: theme.colorScheme.onErrorContainer)),
                      ),
                    ),
                  ),
                const SizedBox(height: 8),
                CommandInput(
                    session: widget.session,
                    snapshot: snap,
                    onCommand: _submitText,
                    onEdited: _inputEdited,
                    enabled: !_busy,
                    onOpenModels: () => widget.open(FlightDestination.models)),
                const SizedBox(height: 8),
                TapPad(onCommand: _tap, enabled: !_busy),
                const SizedBox(height: 8),
                FlightMemoryCard(
                    memory: snap.memory,
                    enabled: !_busy,
                    onCommand: _tap,
                    onRunAgain: _runAgain,
                    onClear: _clearMemory),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}
