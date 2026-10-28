import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:aeronex/features/flight/domain/flight_command.dart';
import 'package:aeronex/features/flight/domain/flight_plan.dart';
import 'package:aeronex/features/link/data/link_settings.dart';
import 'package:aeronex/features/session/session_api.dart';

/// One call as the channel would carry it: the method and its arguments.
typedef SessionCall = (String method, Object? arguments);

/// A T7 fixture (test/fixtures/session/<name>.json) with [patch] merged in:
/// maps merge key by key, anything else (null included) replaces.
Map<String, Object?> fixtureMap(String name,
    [Map<String, Object?> patch = const {}]) {
  final base = jsonDecode(
          File('test/fixtures/session/$name.json').readAsStringSync())
      as Map<String, Object?>;
  return _merge(base, patch);
}

Map<String, Object?> _merge(Map<String, Object?> base, Map<String, Object?> patch) {
  final merged = {...base};
  for (final MapEntry(:key, :value) in patch.entries) {
    final current = merged[key];
    merged[key] = value is Map<String, Object?> && current is Map<String, Object?>
        ? _merge(current, value)
        : value;
  }
  return merged;
}

/// [fixtureMap] parsed.
SessionSnapshot fixture(String name, [Map<String, Object?> patch = const {}]) =>
    SessionSnapshot.fromMap(fixtureMap(name, patch));

/// A [SessionApi] double: records every call with the arguments
/// [ChannelSessionApi] would send, and lets a test push snapshots. It never
/// decides anything about flight; the Kotlin service does.
class FakeSessionApi implements SessionApi {
  FakeSessionApi([SessionSnapshot? initial])
      : current = initial ?? fixture('idle_disconnected');

  final calls = <SessionCall>[];
  final _controller = StreamController<SessionSnapshot>.broadcast();

  /// What `getSnapshot` answers; [push] updates it.
  SessionSnapshot current;
  int _seq = 1000, _tokens = 0;

  /// The answer to the next `submitPlan`; null builds a pending plan
  /// ([pendingFor]) and publishes it, like the service.
  SubmitResult? submitResult;
  ConfirmResult confirmResult = ConfirmResult.started;

  /// `connect` answers this snapshot (default: connected_landed).
  SessionSnapshot? connectResult;
  Object? connectError, stopError, submitError, snapshotError, acknowledgeError;

  /// Pending `connect` until completed, to observe the connecting state.
  Completer<void>? connectGate;

  /// Pending `submitPlan` until completed, to observe a submit in flight.
  Completer<void>? submitGate;

  /// The POST_NOTIFICATIONS state `notificationPermission` answers. An
  /// `ask` while [NotificationPermission.askable] waits for
  /// [notificationDialog] (the operator's answer; denied when null).
  NotificationPermission notifications = NotificationPermission.granted;
  Completer<NotificationPermission>? notificationDialog;

  /// RECORD_AUDIO, like [notifications]; an `ask` while askable answers
  /// [microphoneDialog]'s result (denied when null).
  NotificationPermission microphone = NotificationPermission.granted;
  NotificationPermission? microphoneDialog;

  /// The plan `submitText` shows for a text (the service's Kotlin parser);
  /// any other text shows a one-step Land plan.
  final textPlans = <String, FlightPlan>{};

  List<String> get methods => [for (final (method, _) in calls) method];
  Iterable<Object?> argumentsOf(String method) => [
        for (final (m, arguments) in calls)
          if (m == method) arguments
      ];

  /// Publishes [snapshot] on the stream, as the service does.
  void push(SessionSnapshot snapshot) {
    current = snapshot;
    _controller.add(snapshot);
  }

  /// [fixture] with a fresh `seq`, pushed.
  SessionSnapshot pushFixture(String name, [Map<String, Object?> patch = const {}]) {
    final snapshot = fixture(name, {...patch, 'seq': ++_seq});
    push(snapshot);
    return snapshot;
  }

  /// The pending snapshot the service would publish for [plan]: the steps
  /// with their descriptions, the source chip and a 15 s expiry.
  SessionSnapshot pendingFor(FlightPlan plan, PlanSource source,
          {String token = 'token-1', bool execution = false}) =>
      fixture('pending_plan', {
        'seq': ++_seq,
        'execution': {'enabled': execution},
        'pending': {
          'token': token,
          'summary': plan.toString(),
          'steps': [
            for (final step in plan.steps)
              {
                'name': step.command.name,
                'arguments': {...step.command.arguments},
                'source': step.source.name,
                'description': step.command.description,
              }
          ],
          'source': source.wire,
          'expiresInMs': 15000,
        },
        // Submitting a plan does not change voice.
        'speech': current.speech.toMap(),
      });

  void addError(Object error) => _controller.addError(error);

  @override
  Stream<SessionSnapshot> get snapshots => _controller.stream;

  @override
  Future<SessionSnapshot> getSnapshot() async {
    calls.add(('getSnapshot', null));
    if (snapshotError != null) throw snapshotError!;
    return current;
  }

  @override
  Future<SessionSnapshot> connect(LinkConfig config) async {
    calls.add(('connect', config.toMap()));
    if (connectGate != null) await connectGate!.future;
    if (connectError != null) throw connectError!;
    final connected = connectResult ?? fixture('connected_landed', {'seq': ++_seq});
    current = connected;
    return connected;
  }

  @override
  Future<void> disconnect() async {
    calls.add(('disconnect', null));
    current = fixture('idle_disconnected', {'seq': ++_seq});
  }

  @override
  Future<bool> setExecution(bool enabled) async {
    calls.add(('setExecution', {'enabled': enabled}));
    return enabled;
  }

  @override
  Future<SubmitResult> submitPlan(FlightPlan plan, PlanSource source,
      {String? utterance}) async {
    calls.add(('submitPlan',
        ChannelSessionApi.planRequest(plan, source, utterance: utterance)));
    if (submitGate != null) await submitGate!.future;
    if (submitError != null) throw submitError!;
    final answer = submitResult;
    if (answer != null) return answer;
    final token = 'token-${++_tokens}';
    push(pendingFor(plan, source,
        token: token, execution: current.executionEnabled));
    return AwaitingConfirmation(token, 15000, plan.toString());
  }

  @override
  Future<SubmitResult> submitText(
      String text, String language, PlanSource source) async {
    calls.add(('submitText',
        {'text': text, 'language': language, 'source': source.wire}));
    if (submitGate != null) await submitGate!.future;
    if (submitError != null) throw submitError!;
    final answer = submitResult;
    if (answer != null) return answer;
    final plan = textPlans[text] ??
        FlightPlan.single(
            FlightCommand.fromToolCalls([
              {'name': 'land', 'arguments': <String, Object?>{}}
            ]),
            source: StepSource.rules,
            clause: text);
    final token = 'token-${++_tokens}';
    push(pendingFor(plan, source,
        token: token, execution: current.executionEnabled));
    return AwaitingConfirmation(token, 15000, plan.toString());
  }

  /// pttDown() throws this, when set (the service did not answer).
  Object? pttError;

  @override
  Future<void> pttDown() async {
    calls.add(('pttDown', null));
    if (pttError != null) throw pttError!;
  }

  @override
  Future<void> pttUp() async => calls.add(('pttUp', null));

  @override
  Future<String> setLanguage(String language) async {
    calls.add(('setLanguage', {'language': language}));
    final code = language.split('-').first;
    pushSpeech({'language': code});
    return code;
  }

  @override
  Future<void> setFlightScreenVisible(bool visible) async =>
      calls.add(('flightScreen', {'visible': visible}));

  @override
  Future<NotificationPermission> microphonePermission({bool ask = false}) async {
    calls.add(('microphonePermission', {'ask': ask}));
    if (ask && microphone == NotificationPermission.askable) {
      microphone = microphoneDialog ?? NotificationPermission.denied;
    }
    return microphone;
  }

  /// Pushes the current snapshot with its `speech` section patched.
  SessionSnapshot pushSpeech(Map<String, Object?> speech) {
    final snapshot = SessionSnapshot.fromMap({
      ...current.toMap(),
      'seq': ++_seq,
      'speech': {...current.speech.toMap(), ...speech},
    });
    push(snapshot);
    return snapshot;
  }

  @override
  Future<ConfirmResult> confirm(String token) async {
    calls.add(('confirm', {'token': token}));
    return confirmResult;
  }

  /// Like `SafetySupervisor.cancel`: STOP while a plan runs (recorded only),
  /// otherwise the waiting plan goes and the notice says so.
  @override
  Future<void> cancel() async {
    calls.add(('cancel', null));
    if (current.pending == null || current.run?.executing == true) return;
    push(SessionSnapshot.fromMap({
      ...current.toMap(),
      'seq': ++_seq,
      'pending': null,
      'notice': 'Plan cancelled.',
    }));
  }

  @override
  Future<void> stop({required bool heldOneSecond}) async {
    calls.add(('stop', {'heldOneSecond': heldOneSecond}));
    if (stopError != null) throw stopError!;
  }

  @override
  Future<void> acknowledgeAlarm() async {
    calls.add(('acknowledgeAlarm', null));
    if (acknowledgeError != null) throw acknowledgeError!;
  }

  @override
  Future<bool> requestTelemetry() async {
    calls.add(('requestTelemetry', null));
    return true;
  }

  @override
  Future<NotificationPermission> notificationPermission({bool ask = false}) async {
    calls.add(('notificationPermission', {'ask': ask}));
    if (ask && notifications == NotificationPermission.askable) {
      notifications = await notificationDialog?.future ??
          NotificationPermission.denied;
    }
    return notifications;
  }

  /// Pushes the current snapshot with its `ai` section patched.
  SessionSnapshot pushAi(Map<String, Object?> ai) {
    final snapshot = SessionSnapshot.fromMap({
      ...current.toMap(),
      'seq': ++_seq,
      'ai': {...current.ai.toMap(), ...ai},
    });
    push(snapshot);
    return snapshot;
  }

  /// What `aiModels` answers (0.4.0), or [aiModelsError] it throws.
  AiModelsStatus aiModelsStatus = const AiModelsStatus(
      reason: 'Download the AI models in Models');
  Object? aiModelsError, aiModelError;

  /// Like the service: Cancel ends the thinking without an error.
  @override
  Future<void> cancelAi() async {
    calls.add(('cancelAi', null));
    if (current.ai.thinking) pushAi({'state': 'IDLE'});
  }

  @override
  Future<AiModelsStatus> aiModels() async {
    calls.add(('aiModels', null));
    if (aiModelsError != null) throw aiModelsError!;
    return aiModelsStatus;
  }

  @override
  Future<void> downloadAiModel(String id) async {
    calls.add(('downloadAiModel', {'id': id}));
    if (aiModelError != null) throw aiModelError!;
  }

  @override
  Future<void> importAiModel(String id) async {
    calls.add(('importAiModel', {'id': id}));
    if (aiModelError != null) throw aiModelError!;
  }

  @override
  Future<void> cancelAiModel() async => calls.add(('cancelAiModel', null));

  @override
  Future<bool> removeAiModel(String id) async {
    calls.add(('removeAiModel', {'id': id}));
    return true;
  }

  Future<void> close() => _controller.close();
}

/// [LinkSettingsStore] in memory; [saved] records every save.
class MemoryLinkSettingsStore implements LinkSettingsStore {
  MemoryLinkSettingsStore([this.settings]);

  LinkSettings? settings;
  final saved = <LinkSettings>[];
  Object? saveError;

  @override
  Future<LinkSettings?> load() async => settings;

  @override
  Future<void> save(LinkSettings value) async {
    if (saveError != null) throw saveError!;
    saved.add(value);
    settings = value;
  }
}
