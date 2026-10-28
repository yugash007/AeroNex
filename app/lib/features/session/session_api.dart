/// The Dart side of the flight session service (architecture v2, Phases 1a
/// and 1b).
///
/// A hand-written typed wrapper (plan deviation: no Pigeon) over the
/// `aeronex/session` MethodChannel and the `aeronex/session/state`
/// EventChannel. The schema is `SessionSchema.kt` (version
/// [sessionSchemaVersion]); `SessionChannel.kt` and `SessionApi.kt` answer the
/// calls. The service owns speech, parsing, validation, confirmation,
/// execution and STOP: this client sends push-to-talk edges, typed text and
/// tap-pad plans, and shows snapshots.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import '../flight/domain/flight_plan.dart';

/// `SessionSchema.VERSION`: the only snapshot version this client reads.
const sessionSchemaVersion = 1;

/// A snapshot or reply that does not have the agreed shape.
FormatException _bad(String what) =>
    FormatException('The flight service sent unreadable data ($what).');

T _req<T>(Map map, String key) {
  final value = map[key];
  if (value is T) return value;
  throw _bad(key);
}

T? _opt<T>(Map map, String key) {
  final value = map[key];
  if (value == null || value is T) return value as T?;
  throw _bad(key);
}

double? _optDouble(Map map, String key) => _opt<num>(map, key)?.toDouble();

Map<String, Object?> _copy(Map map) =>
    {for (final e in map.entries) '${e.key}': _plain(e.value)};

/// Codec maps and lists as plain `String`-keyed Dart values.
Object? _plain(Object? value) => switch (value) {
      Map() => _copy(value),
      List() => [for (final item in value) _plain(item)],
      _ => value,
    };

/// `connect`'s `transport`.
enum LinkTransport { udp, tcp }

/// The `connect` request (`SessionConfig.fromMap`). UDP sends from
/// [localPort] (0 = any free port) to [host]:[remotePort]; TCP dials
/// [host]:[remotePort]. [radioLink]: the link crosses a telemetry radio (ELRS
/// Backpack, a Wi-Fi bridge on a SiK link), so the radio telemetry profile
/// applies.
class LinkConfig {
  const LinkConfig({
    this.transport = LinkTransport.udp,
    required this.host,
    this.remotePort = 14550,
    this.localPort = 14551,
    this.radioLink = false,
  });

  final LinkTransport transport;
  final String host;
  final int remotePort, localPort;
  final bool radioLink;

  Map<String, Object> toMap() => {
        'transport': transport.name,
        'host': host,
        'remotePort': remotePort,
        'localPort': localPort,
        'radioLink': radioLink,
      };

  @override
  bool operator ==(Object other) =>
      other is LinkConfig &&
      other.transport == transport &&
      other.host == host &&
      other.remotePort == remotePort &&
      other.localPort == localPort &&
      other.radioLink == radioLink;

  @override
  int get hashCode =>
      Object.hash(transport, host, remotePort, localPort, radioLink);
}

/// Who proposed a plan: the plan card's source chip. [wire] and [label] are
/// the Kotlin `PlanSource` spelling and label (spec 4.10: Phone, Typed, Tap);
/// speech on this phone is `phone`.
enum PlanSource {
  voice('phone', 'Phone'),
  typed('typed', 'Typed'),
  tap('tap', 'Tap'),

  /// 0.4.0: a plan of the service's own AI path (shown as "AI"). The service
  /// refuses it from this client; it only ever arrives in a snapshot.
  ai('ai', 'AI');

  const PlanSource(this.wire, this.label);
  final String wire, label;

  static PlanSource? fromWire(Object? wire) {
    for (final source in values) {
      if (source.wire == wire) return source;
    }
    return null;
  }
}

/// A runtime permission's state: POST_NOTIFICATIONS for the session
/// notification and its STOP action, or RECORD_AUDIO for voice
/// (`NotificationPermission.State` in Kotlin, by name).
enum NotificationPermission {
  /// Granted, or not needed (before Android 13).
  granted,

  /// Never asked: asking opens the system dialog.
  askable,

  /// Asked before and not granted; never asked again.
  denied,
}

/// `link.state`.
enum LinkState {
  /// No session: nothing is connected or connecting.
  down,

  /// A session is open but no vehicle heartbeat in the last 3 s.
  connecting,

  /// A vehicle heartbeat in the last 3 s.
  up,
}

/// The snapshot's `link` section.
class LinkStatus {
  const LinkStatus({
    required this.state,
    this.transport,
    this.description,
    this.profile,
    this.unsigned = true,
    this.heartbeatAgeMs,
    this.txErrors = 0,
    this.droppedDatagrams,
    this.foregroundService = false,
  });

  factory LinkStatus.fromMap(Map map) => LinkStatus(
        state: LinkState.values.firstWhere(
            (s) => s.name == _req<String>(map, 'state'),
            orElse: () => throw _bad('link.state')),
        transport: _opt<String>(map, 'transport'),
        description: _opt<String>(map, 'description'),
        profile: _opt<String>(map, 'profile'),
        unsigned: _req<bool>(map, 'unsigned'),
        heartbeatAgeMs: _opt<int>(map, 'heartbeatAgeMs'),
        txErrors: _req<int>(map, 'txErrors'),
        droppedDatagrams: _opt<int>(map, 'droppedDatagrams'),
        foregroundService: _req<bool>(map, 'foregroundService'),
      );

  final LinkState state;

  /// `udp` | `tcp`; null while down.
  final String? transport;

  /// For diagnostics, e.g. "UDP 192.168.4.1:14550 local *:14551".
  final String? description;

  /// `wifi` | `radio`; null while down.
  final String? profile;

  /// MAVLink signing is off.
  final bool unsigned;
  final int? heartbeatAgeMs;
  final int txErrors;
  final int? droppedDatagrams;

  /// The foreground service runs, so the link and STOP survive the app going
  /// to the background.
  final bool foregroundService;

  Map<String, Object?> toMap() => {
        'state': state.name,
        'transport': transport,
        'description': description,
        'profile': profile,
        'unsigned': unsigned,
        'heartbeatAgeMs': heartbeatAgeMs,
        'txErrors': txErrors,
        'droppedDatagrams': droppedDatagrams,
        'foregroundService': foregroundService,
      };
}

/// `vehicle.lastStatusText`.
class StatusText {
  const StatusText(this.severity, this.text, this.ageMs);
  factory StatusText.fromMap(Map map) => StatusText(_req<int>(map, 'severity'),
      _req<String>(map, 'text'), _req<int>(map, 'ageMs'));

  /// MAV_SEVERITY: 0 is emergency.
  final int severity;
  final String text;
  final int ageMs;

  Map<String, Object?> toMap() =>
      {'severity': severity, 'text': text, 'ageMs': ageMs};
}

/// The snapshot's `vehicle` section: the last received values. Check
/// [connected] and [ages] before showing a value as current.
class VehicleSnapshot {
  const VehicleSnapshot({
    this.connected = false,
    this.mode,
    this.modeName,
    this.armed,
    this.landedState,
    this.landedStateName,
    this.modeOwner,
    this.modeOwnerLabel,
    this.altitudeRelM,
    this.speed,
    this.batteryPercent,
    this.healthy,
    this.positionReady = false,
    this.homeKnown = false,
    this.homeDistanceM,
    this.lastStatusText,
    this.ages = const {},
  });

  factory VehicleSnapshot.fromMap(Map map) {
    final text = _opt<Map>(map, 'lastStatusText');
    final ages = _req<Map>(map, 'ages');
    return VehicleSnapshot(
      connected: _req<bool>(map, 'connected'),
      mode: _opt<int>(map, 'mode'),
      modeName: _opt<String>(map, 'modeName'),
      armed: _opt<bool>(map, 'armed'),
      landedState: _opt<int>(map, 'landedState'),
      landedStateName: _opt<String>(map, 'landedStateName'),
      modeOwner: _opt<String>(map, 'modeOwner'),
      modeOwnerLabel: _opt<String>(map, 'modeOwnerLabel'),
      altitudeRelM: _optDouble(map, 'altitudeRelM'),
      speed: _optDouble(map, 'speed'),
      batteryPercent: _opt<int>(map, 'batteryPercent'),
      healthy: _opt<bool>(map, 'healthy'),
      positionReady: _req<bool>(map, 'positionReady'),
      homeKnown: _req<bool>(map, 'homeKnown'),
      homeDistanceM: _optDouble(map, 'homeDistanceM'),
      lastStatusText: text == null ? null : StatusText.fromMap(text),
      ages: {for (final key in ages.keys) '$key': _opt<int>(ages, '$key')},
    );
  }

  /// A vehicle heartbeat within the last 3 s.
  final bool connected;

  /// ArduCopter mode number and its Kotlin name, e.g. 4 / "GUIDED".
  final int? mode;
  final String? modeName;
  final bool? armed;
  final int? landedState;

  /// UNDEFINED | ON_GROUND | IN_AIR | TAKEOFF | LANDING.
  final String? landedStateName;

  /// `APP` | `EXTERNAL`; null while not connected or no mode is known.
  final String? modeOwner;

  /// While EXTERNAL after a mode change: "Autopilot failsafe" or "Pilot mode".
  final String? modeOwnerLabel;
  final double? altitudeRelM, speed;
  final int? batteryPercent;
  final bool? healthy;
  final bool positionReady, homeKnown;
  final double? homeDistanceM;
  final StatusText? lastStatusText;

  /// Per message, ms since it was last received; null if never.
  final Map<String, int?> ages;

  /// The flight mode is set outside this app (pilot or autopilot).
  bool get external => modeOwner == 'EXTERNAL';

  Map<String, Object?> toMap() => {
        'connected': connected,
        'mode': mode,
        'modeName': modeName,
        'armed': armed,
        'landedState': landedState,
        'landedStateName': landedStateName,
        'modeOwner': modeOwner,
        'modeOwnerLabel': modeOwnerLabel,
        'altitudeRelM': altitudeRelM,
        'speed': speed,
        'batteryPercent': batteryPercent,
        'healthy': healthy,
        'positionReady': positionReady,
        'homeKnown': homeKnown,
        'homeDistanceM': homeDistanceM,
        'lastStatusText': lastStatusText?.toMap(),
        'ages': {...ages},
      };
}

/// One step of the plan waiting for CONFIRM.
class PendingStep {
  const PendingStep(this.name, this.arguments, this.source, this.description);
  factory PendingStep.fromMap(Map map) => PendingStep(
      _req<String>(map, 'name'),
      _copy(_req<Map>(map, 'arguments')),
      _req<String>(map, 'source'),
      _req<String>(map, 'description'));

  final String name;
  final Map<String, Object?> arguments;

  /// `rules` | `fixture`.
  final String source;

  /// Operator wording, e.g. "Move forward 2 m".
  final String description;

  Map<String, Object?> toMap() => {
        'name': name,
        'arguments': {...arguments},
        'source': source,
        'description': description,
      };
}

/// The snapshot's `pending` section: the plan waiting for CONFIRM.
class PendingPlan {
  const PendingPlan(this.token, this.summary, this.steps, this.sourceWire,
      this.expiresInMs);
  factory PendingPlan.fromMap(Map map) => PendingPlan(
      _req<String>(map, 'token'),
      _req<String>(map, 'summary'),
      [for (final step in _req<List>(map, 'steps')) PendingStep.fromMap(step as Map)],
      _req<String>(map, 'source'),
      _req<int>(map, 'expiresInMs'));

  /// Pass it to `confirm`; it works once.
  final String token;
  final String summary;
  final List<PendingStep> steps;
  final String sourceWire;

  /// Until the token expires, at the snapshot's `atMs`; 0 when due.
  final int expiresInMs;

  PlanSource? get source => PlanSource.fromWire(sourceWire);

  Map<String, Object?> toMap() => {
        'token': token,
        'summary': summary,
        'steps': [for (final step in steps) step.toMap()],
        'source': sourceWire,
        'expiresInMs': expiresInMs,
      };
}

/// A step of the running or last run plan. [outcome] is PENDING | RUNNING |
/// COMPLETED | NOT_SENT | REJECTED | UNKNOWN | SKIPPED; [message] the v0.2.0
/// text, e.g. "COMPLETED — …".
class StepResult {
  const StepResult(this.step, this.outcome, this.message);
  factory StepResult.fromMap(Map map) => StepResult(_req<String>(map, 'step'),
      _req<String>(map, 'outcome'), _opt<String>(map, 'message'));

  final String step, outcome;
  final String? message;

  Map<String, Object?> toMap() =>
      {'step': step, 'outcome': outcome, 'message': message};
}

/// The snapshot's `run` section: the plan running or last run.
class PlanRun {
  const PlanRun(this.phase, this.sourceWire, this.summary, this.stepIndex,
      this.stepCount, this.outcomes, this.message);
  factory PlanRun.fromMap(Map map) => PlanRun(
      _req<String>(map, 'phase'),
      _opt<String>(map, 'source'),
      _req<String>(map, 'summary'),
      _req<int>(map, 'stepIndex'),
      _req<int>(map, 'stepCount'),
      [for (final o in _req<List>(map, 'outcomes')) StepResult.fromMap(o as Map)],
      _opt<String>(map, 'message'));

  /// EXECUTING | FINISHED | FAILED.
  final String phase;
  final String? sourceWire;
  final String summary;
  final int stepIndex, stepCount;
  final List<StepResult> outcomes;

  /// The v0.2.0 result summary once the plan ended.
  final String? message;

  bool get executing => phase == 'EXECUTING';
  bool get failed => phase == 'FAILED';
  PlanSource? get source => PlanSource.fromWire(sourceWire);

  Map<String, Object?> toMap() => {
        'phase': phase,
        'source': sourceWire,
        'summary': summary,
        'stepIndex': stepIndex,
        'stepCount': stepCount,
        'outcomes': [for (final o in outcomes) o.toMap()],
        'message': message,
      };
}

/// The snapshot's `stop` section: the last STOP decision.
class StopStatus {
  const StopStatus({
    required this.id,
    required this.phase,
    required this.trigger,
    this.action,
    required this.message,
    this.alarm = false,
    this.override = false,
    this.needsHoldForExternal = false,
    this.modeOwnerLabel,
  });

  factory StopStatus.fromMap(Map map) => StopStatus(
        id: _req<int>(map, 'id'),
        phase: _req<String>(map, 'phase'),
        trigger: _req<String>(map, 'trigger'),
        action: _opt<String>(map, 'action'),
        message: _req<String>(map, 'message'),
        alarm: _req<bool>(map, 'alarm'),
        override: _req<bool>(map, 'override'),
        needsHoldForExternal: _req<bool>(map, 'needsHoldForExternal'),
        modeOwnerLabel: _opt<String>(map, 'modeOwnerLabel'),
      );

  /// New for every STOP.
  final int id;

  /// NOTHING_SENT | SENDING | CONFIRMED | NOT_CONFIRMED.
  final String phase;
  final String trigger;

  /// DISARM | BRAKE | LAND; null when nothing was sent.
  final String? action;

  /// The banner, e.g. "STOP confirmed: BRAKE".
  final String message;

  /// Sound the alarm until `acknowledgeAlarm`.
  final bool alarm;
  final bool override;

  /// An armed vehicle's mode is set outside the app: a STOP press sends
  /// nothing unless the on-screen STOP is held 1 s.
  final bool needsHoldForExternal;
  final String? modeOwnerLabel;

  Map<String, Object?> toMap() => {
        'id': id,
        'phase': phase,
        'trigger': trigger,
        'action': action,
        'message': message,
        'alarm': alarm,
        'override': override,
        'needsHoldForExternal': needsHoldForExternal,
        'modeOwnerLabel': modeOwnerLabel,
      };
}

/// The speech languages (`SessionSchema` `speech.language`) and their names.
const speechLanguages = {
  'en': 'Indian English',
  'hi': 'Hindi',
  'te': 'Telugu',
  'ta': 'Tamil',
  'kn': 'Kannada',
  'ml': 'Malayalam',
};

/// The snapshot's `speech` section: voice in the service (spec 4.11 level,
/// push-to-talk, the last utterance).
class SpeechStatus {
  const SpeechStatus({
    this.language = 'te',
    this.state = 'OFF',
    this.level = 'NO_VOICE',
    this.reason = 'NOT_STARTED',
    this.message,
    this.engine,
    this.spotter,
    this.nativeVoiceStop = false,
    this.partialStop = false,
    this.spotterArmed = false,
    this.pttHeld = false,
    this.utterance = 0,
    this.partial,
    this.heard,
    this.error,
  });

  factory SpeechStatus.fromMap(Map map) => SpeechStatus(
        language: _req<String>(map, 'language'),
        state: _req<String>(map, 'state'),
        level: _req<String>(map, 'level'),
        reason: _opt<String>(map, 'reason'),
        message: _opt<String>(map, 'message'),
        engine: _opt<String>(map, 'engine'),
        spotter: _opt<String>(map, 'spotter'),
        nativeVoiceStop: _req<bool>(map, 'nativeVoiceStop'),
        partialStop: _req<bool>(map, 'partialStop'),
        spotterArmed: _req<bool>(map, 'spotterArmed'),
        pttHeld: _req<bool>(map, 'pttHeld'),
        utterance: _req<int>(map, 'utterance'),
        partial: _opt<String>(map, 'partial'),
        heard: _opt<String>(map, 'heard'),
        error: _opt<String>(map, 'error'),
      );

  /// A code of [speechLanguages].
  final String language;

  /// OFF | LOADING | READY | LISTENING | DECODING.
  final String state;

  /// OK | DEGRADED | NO_VOICE (spec 4.11).
  final String level;

  /// Why voice is not OK (`VoiceReason`), e.g. NO_MICROPHONE,
  /// NEEDS_NEWER_PHONE; [message] is its operator text.
  final String? reason, message;

  /// VOSK_GRAMMAR | SHERPA_CTC; VOSK_STOP_GRAMMAR | KWS.
  final String? engine, spotter;

  /// Voice STOP in this language (false: English "stop" only, or none).
  final bool nativeVoiceStop;

  /// A stop word said while push-to-talk is held stops at once.
  final bool partialStop;

  /// The voice stop spotter listens now (armed or airborne, app mode).
  final bool spotterArmed;
  final bool pttHeld;

  /// Push-to-talk presses so far: a new one means a new [heard] / [error].
  final int utterance;

  /// The live partial transcript while push-to-talk is held.
  final String? partial;

  /// The last utterance's transcript, and why it did not become a plan.
  final String? heard, error;

  bool get ok => level == 'OK';
  bool get degraded => level == 'DEGRADED';
  bool get listening => state == 'LISTENING';
  bool get decoding => state == 'DECODING';

  /// Push-to-talk needs the microphone permission first.
  bool get needsMicrophone => reason == 'NO_MICROPHONE';

  Map<String, Object?> toMap() => {
        'language': language,
        'state': state,
        'level': level,
        'reason': reason,
        'message': message,
        'engine': engine,
        'spotter': spotter,
        'nativeVoiceStop': nativeVoiceStop,
        'partialStop': partialStop,
        'spotterArmed': spotterArmed,
        'pttHeld': pttHeld,
        'utterance': utterance,
        'partial': partial,
        'heard': heard,
        'error': error,
      };
}

/// The snapshot's `ai` section (0.4.0): the on-phone AI path for a command
/// the rules did not understand. The service glosses it to English, plans
/// it, grounds and validates the plan, and shows it with the AI source for
/// one CONFIRM; STOP, Talk, new text or Cancel end the thinking.
class AiStatus {
  const AiStatus({
    this.available = false,
    this.reason,
    this.state = 'IDLE',
    this.request = 0,
    this.heard,
    this.gloss,
    this.understoodAs,
    this.error,
  });

  factory AiStatus.fromMap(Map map) => AiStatus(
        available: _req<bool>(map, 'available'),
        reason: _opt<String>(map, 'reason'),
        state: _req<String>(map, 'state'),
        request: _req<int>(map, 'request'),
        heard: _opt<String>(map, 'heard'),
        gloss: _opt<String>(map, 'gloss'),
        understoodAs: _opt<String>(map, 'understoodAs'),
        error: _opt<String>(map, 'error'),
      );

  /// The AI can help on this phone; otherwise [reason] says why not, in
  /// plain words (e.g. "Needs a 64-bit ARM phone").
  final bool available;
  final String? reason;

  /// IDLE | THINKING.
  final String state;

  /// AI requests so far: a new one means new [heard] … [error].
  final int request;

  /// What the AI was asked, and its English gloss when that differs.
  final String? heard, gloss;

  /// The planner's steps, e.g. "take off to 3 meters, then move forward 2
  /// meters".
  final String? understoodAs;

  /// Why the AI gave no plan, or why its plan was refused (shown as is).
  final String? error;

  bool get thinking => state == 'THINKING';

  Map<String, Object?> toMap() => {
        'available': available,
        'reason': reason,
        'state': state,
        'request': request,
        'heard': heard,
        'gloss': gloss,
        'understoodAs': understoodAs,
        'error': error,
      };
}

/// One AI model row of the Models screen (`aiModels`).
class AiModel {
  const AiModel(
      {required this.id,
      required this.file,
      required this.bytes,
      required this.state,
      this.importPath});

  factory AiModel.fromMap(Map map) => AiModel(
      id: _req<String>(map, 'id'),
      file: _req<String>(map, 'file'),
      bytes: _req<int>(map, 'bytes'),
      state: _req<String>(map, 'state'),
      importPath: _opt<String>(map, 'importPath'));

  /// `lfm-q4` (the planner) or `needle3` (the tool caller).
  final String id, file;
  final int bytes;

  /// ready | missing | invalid | unreadable | checking.
  final String state;

  /// Where a copied file is imported from (the adb push folder).
  final String? importPath;

  bool get ready => state == 'ready';
  bool get broken => state == 'invalid' || state == 'unreadable';
}

/// A download or import of an AI model that runs now.
class AiModelTransfer {
  const AiModelTransfer(this.id, this.source, this.receivedBytes, this.totalBytes);

  factory AiModelTransfer.fromMap(Map map) => AiModelTransfer(
      _req<String>(map, 'id'),
      _req<String>(map, 'source'),
      _req<int>(map, 'receivedBytes'),
      _req<int>(map, 'totalBytes'));

  final String id;

  /// `download` | `import`.
  final String source;
  final int receivedBytes, totalBytes;

  bool get download => source == 'download';
  double get fraction =>
      totalBytes <= 0 ? 0 : (receivedBytes / totalBytes).clamp(0.0, 1.0);
}

/// The `aiModels` answer: AI availability and the two model rows.
class AiModelsStatus {
  const AiModelsStatus(
      {this.available = false,
      this.reason,
      this.models = const [],
      this.transfer,
      this.errorId,
      this.error});

  factory AiModelsStatus.fromMap(Map map) {
    final transfer = _opt<Map>(map, 'transfer');
    final error = _opt<Map>(map, 'error');
    return AiModelsStatus(
      available: _req<bool>(map, 'available'),
      reason: _opt<String>(map, 'reason'),
      models: [
        for (final model in _req<List>(map, 'models')) AiModel.fromMap(model as Map)
      ],
      transfer: transfer == null ? null : AiModelTransfer.fromMap(transfer),
      errorId: error == null ? null : _req<String>(error, 'id'),
      error: error == null ? null : _req<String>(error, 'message'),
    );
  }

  final bool available;
  final String? reason;
  final List<AiModel> models;
  final AiModelTransfer? transfer;

  /// The model whose last download or import failed, and why.
  final String? errorId, error;
}

/// One `latency` stage over the newest spans.
class LatencyStat {
  const LatencyStat(this.p50Ms, this.p95Ms, this.maxMs, this.n);
  factory LatencyStat.fromMap(Map map) => LatencyStat(
      _optDouble(map, 'p50Ms') ?? (throw _bad('p50Ms')),
      _optDouble(map, 'p95Ms') ?? (throw _bad('p95Ms')),
      _optDouble(map, 'maxMs') ?? (throw _bad('maxMs')),
      _req<int>(map, 'n'));

  final double p50Ms, p95Ms, maxMs;
  final int n;

  Map<String, Object?> toMap() =>
      {'p50Ms': p50Ms, 'p95Ms': p95Ms, 'maxMs': maxMs, 'n': n};
}

/// One `aeronex/session/state` snapshot (also the `connect` and `getSnapshot`
/// answer).
class SessionSnapshot {
  const SessionSnapshot({
    this.schemaVersion = sessionSchemaVersion,
    required this.seq,
    this.atMs = 0,
    required this.link,
    this.executionEnabled = false,
    this.vehicle = const VehicleSnapshot(),
    this.pending,
    this.run,
    this.stop,
    this.notice,
    this.latency = const {},
    this.speech = const SpeechStatus(),
    this.ai = const AiStatus(),
    this.raw = const {},
  });

  /// Shown before the first snapshot arrives: nothing connected, dry run.
  static const none =
      SessionSnapshot(seq: -1, link: LinkStatus(state: LinkState.down));

  /// Throws [FormatException] for another schema version or a malformed map.
  factory SessionSnapshot.fromMap(Map map) {
    final version = map['schemaVersion'];
    if (version != sessionSchemaVersion) {
      throw FormatException(
          'The flight service uses data version $version; this app reads '
          'version $sessionSchemaVersion. Install matching versions.');
    }
    final pending = _opt<Map>(map, 'pending');
    final run = _opt<Map>(map, 'run');
    final stop = _opt<Map>(map, 'stop');
    final latency = _req<Map>(map, 'latency');
    final speech = _opt<Map>(map, 'speech');
    final ai = _opt<Map>(map, 'ai');
    try {
      return SessionSnapshot(
        seq: _req<int>(map, 'seq'),
        atMs: _req<int>(map, 'atMs'),
        link: LinkStatus.fromMap(_req<Map>(map, 'link')),
        executionEnabled: _req<bool>(_req<Map>(map, 'execution'), 'enabled'),
        vehicle: VehicleSnapshot.fromMap(_req<Map>(map, 'vehicle')),
        pending: pending == null ? null : PendingPlan.fromMap(pending),
        run: run == null ? null : PlanRun.fromMap(run),
        stop: stop == null ? null : StopStatus.fromMap(stop),
        notice: _opt<String>(map, 'notice'),
        latency: {
          for (final key in latency.keys)
            '$key': latency[key] == null
                ? null
                : LatencyStat.fromMap(_req<Map>(latency, '$key')),
        },
        speech: speech == null ? const SpeechStatus() : SpeechStatus.fromMap(speech),
        ai: ai == null ? const AiStatus() : AiStatus.fromMap(ai),
        raw: _copy(map),
      );
    } on TypeError {
      throw _bad('snapshot');
    }
  }

  final int schemaVersion;

  /// Increases with every snapshot built; drop one older than the last shown.
  final int seq;

  /// Monotonic ms on the service's clock; only differences mean anything.
  final int atMs;
  final LinkStatus link;

  /// Live execution for this connection; false is dry run.
  final bool executionEnabled;
  final VehicleSnapshot vehicle;
  final PendingPlan? pending;
  final PlanRun? run;
  final StopStatus? stop;

  /// Why the last pending plan went away (expired, link lost, background …).
  final String? notice;

  /// Per stage (`confirmToTx`, `stopToAck`, …); null before the first span.
  final Map<String, LatencyStat?> latency;

  /// Voice in the service (absent from older services: voice off).
  final SpeechStatus speech;

  /// The AI path (0.4.0; absent from older services: no AI).
  final AiStatus ai;

  /// The map as received, for diagnostics.
  final Map<String, Object?> raw;

  Map<String, Object?> toMap() => {
        'schemaVersion': schemaVersion,
        'seq': seq,
        'atMs': atMs,
        'link': link.toMap(),
        'execution': {'enabled': executionEnabled},
        'vehicle': vehicle.toMap(),
        'pending': pending?.toMap(),
        'run': run?.toMap(),
        'stop': stop?.toMap(),
        'notice': notice,
        'latency': {
          for (final e in latency.entries) e.key: e.value?.toMap(),
        },
        'speech': speech.toMap(),
        'ai': ai.toMap(),
      };
}

/// The `submitPlan` and `submitText` answer.
sealed class SubmitResult {
  const SubmitResult();

  factory SubmitResult.fromMap(Map? map) {
    if (map == null) throw _bad('submitPlan');
    if (map['error'] is String) return RejectedResult(map['error'] as String);
    if (map['immediate'] == true) {
      return ImmediateResult(
          _req<String>(map, 'outcome'), _opt<String>(map, 'message'));
    }
    if (map['token'] is String) {
      return AwaitingConfirmation(_req<String>(map, 'token'),
          _req<int>(map, 'expiresInMs'), _req<String>(map, 'summary'));
    }
    if (map['thinking'] == true) return ThinkingResult(_req<int>(map, 'request'));
    throw _bad('submitPlan');
  }
}

/// `submitText` only (0.4.0): the rules did not understand the text and the
/// AI is thinking about it; the outcome arrives in the snapshot's `ai`
/// section (and an AI plan as the waiting plan).
class ThinkingResult extends SubmitResult {
  const ThinkingResult(this.request);
  final int request;
}

/// A plan the service shows for CONFIRM; [token] works once.
class AwaitingConfirmation extends SubmitResult {
  const AwaitingConfirmation(this.token, this.expiresInMs, this.summary);
  final String token;
  final int expiresInMs;
  final String summary;
}

/// HOLD and status: run at once, no confirmation.
class ImmediateResult extends SubmitResult {
  const ImmediateResult(this.outcome, this.message);
  final String outcome;
  final String? message;
}

/// The service refused the plan; [error] is the operator message (v0.2.0
/// text), shown as is.
class RejectedResult extends SubmitResult {
  const RejectedResult(this.error);
  final String error;
}

/// The `confirm` answer.
class ConfirmResult {
  const ConfirmResult(this.accepted, [this.error]);
  factory ConfirmResult.fromMap(Map? map) {
    if (map == null || map['accepted'] is! bool) throw _bad('confirm');
    return ConfirmResult(map['accepted'] as bool, _opt<String>(map, 'error'));
  }

  static const started = ConfirmResult(true);

  final bool accepted;
  final String? error;
}

/// The flight session as the UI uses it. The service decides everything a
/// call does; a STOP call is never refused by the client.
abstract class SessionApi {
  /// Snapshots as the service publishes them (5–10 Hz, and on every change).
  Stream<SessionSnapshot> get snapshots;

  /// The state now; the session survives the app's screens being recreated.
  Future<SessionSnapshot> getSnapshot();

  /// Opens the link, always in dry run. Throws [PlatformException]
  /// (BAD_ARGS, ALREADY_CONNECTED, CONNECT_FAILED, INTERNAL) with the
  /// operator message.
  Future<SessionSnapshot> connect(LinkConfig config);

  Future<void> disconnect();

  /// Live execution for this connection; the resulting state.
  Future<bool> setExecution(bool enabled);

  Future<SubmitResult> submitPlan(FlightPlan plan, PlanSource source,
      {String? utterance});

  /// Typed text in [language] (a code or tag), parsed by the service's
  /// Kotlin parser, then shown or run exactly like [submitPlan]; a parser
  /// refusal is a [RejectedResult] with its message.
  Future<SubmitResult> submitText(
      String text, String language, PlanSource source);

  /// Push-to-talk pressed and released (the PTT bar; Volume-Up is handled
  /// natively). The result arrives in [SessionSnapshot.speech] and, when it
  /// is a plan, as the pending plan with the Phone source.
  Future<void> pttDown();
  Future<void> pttUp();

  /// The speech language (saved by the service); its code.
  Future<String> setLanguage(String language);

  /// Whether the Flight screen is in front: Volume-Up is push-to-talk only
  /// then.
  Future<void> setFlightScreenVisible(bool visible);

  /// RECORD_AUDIO, like [notificationPermission]: with [ask], opens the
  /// system dialog if it was never asked. The dialog covers STOP: ask only
  /// while the vehicle is not armed.
  Future<NotificationPermission> microphonePermission({bool ask = false});

  Future<ConfirmResult> confirm(String token);

  /// STOP while a plan runs; otherwise drops the pending plan.
  Future<void> cancel();

  /// The on-screen STOP; [heldOneSecond] overrides a pilot- or
  /// autopilot-owned mode.
  Future<void> stop({required bool heldOneSecond});

  Future<void> acknowledgeAlarm();

  /// Asks the vehicle for its telemetry again; false when not queued.
  Future<bool> requestTelemetry();

  /// The notification permission; with [ask], opens the system dialog if it
  /// was never asked, and answers after it. The dialog covers the whole app,
  /// STOP included: ask only while no link is open.
  Future<NotificationPermission> notificationPermission({bool ask = false});

  /// Ends the AI thinking about the last command (0.4.0); a late answer is
  /// never shown.
  Future<void> cancelAi();

  /// The AI models on this phone and whether the AI can help.
  Future<AiModelsStatus> aiModels();

  /// Starts the HTTPS download (or the import from the import folder) of an
  /// AI model; the progress and outcome show in [aiModels].
  Future<void> downloadAiModel(String id);
  Future<void> importAiModel(String id);

  /// Stops the running AI model download or import.
  Future<void> cancelAiModel();

  /// Deletes an AI model (the AI stops first); whether it was there.
  Future<bool> removeAiModel(String id);
}

/// [SessionApi] over the `aeronex/session` channels (`SessionChannel.kt`).
class ChannelSessionApi implements SessionApi {
  ChannelSessionApi(
      {MethodChannel methods = const MethodChannel(methodsName),
      EventChannel events = const EventChannel(eventsName)})
      : _methods = methods,
        _events = events;

  static const methodsName = 'aeronex/session';
  static const eventsName = 'aeronex/session/state';

  final MethodChannel _methods;
  final EventChannel _events;

  /// The `submitPlan` arguments (`SessionSchema.parsePlan`).
  static Map<String, Object?> planRequest(FlightPlan plan, PlanSource source,
          {String? utterance}) =>
      {
        'steps': [
          for (final step in plan.steps)
            {
              'name': step.command.name,
              'arguments': <String, Object?>{...step.command.arguments},
              'source': step.source.name,
              if (step.clause.isNotEmpty) 'clause': step.clause,
            }
        ],
        'source': source.wire,
        if (utterance != null) 'utterance': utterance,
      };

  /// One platform subscription while anyone listens; each event is parsed
  /// once and the same [SessionSnapshot] goes to every listener (STOP and the
  /// open screen both listen, at 5–10 Hz on the UI thread).
  @override
  late final Stream<SessionSnapshot> snapshots = _shared();

  Stream<SessionSnapshot> _shared() {
    StreamSubscription<Object?>? source;
    late final StreamController<SessionSnapshot> shared;
    shared = StreamController<SessionSnapshot>.broadcast(
      onListen: () => source = _events.receiveBroadcastStream().listen(
          (event) {
            try {
              shared.add(event is Map
                  ? SessionSnapshot.fromMap(event)
                  : throw _bad('snapshot event'));
            } catch (error, stack) {
              shared.addError(error, stack);
            }
          },
          onError: shared.addError),
      onCancel: () {
        source?.cancel();
        source = null;
      },
    );
    return shared.stream;
  }

  Future<SessionSnapshot> _snapshot(String method, [Object? arguments]) async {
    final map = await _methods.invokeMethod<Object?>(method, arguments);
    if (map is! Map) throw _bad(method);
    return SessionSnapshot.fromMap(map);
  }

  @override
  Future<SessionSnapshot> getSnapshot() => _snapshot('getSnapshot');

  @override
  Future<SessionSnapshot> connect(LinkConfig config) =>
      _snapshot('connect', config.toMap());

  @override
  Future<void> disconnect() => _methods.invokeMethod<void>('disconnect');

  @override
  Future<bool> setExecution(bool enabled) async {
    final reply = await _methods
        .invokeMethod<Object?>('setExecution', {'enabled': enabled});
    if (reply is Map && reply['enabled'] is bool) return reply['enabled'] as bool;
    throw _bad('setExecution');
  }

  @override
  Future<SubmitResult> submitPlan(FlightPlan plan, PlanSource source,
      {String? utterance}) async {
    final reply = await _methods.invokeMethod<Object?>(
        'submitPlan', planRequest(plan, source, utterance: utterance));
    return SubmitResult.fromMap(reply is Map ? reply : null);
  }

  @override
  Future<SubmitResult> submitText(
      String text, String language, PlanSource source) async {
    final reply = await _methods.invokeMethod<Object?>('submitText',
        {'text': text, 'language': language, 'source': source.wire});
    return SubmitResult.fromMap(reply is Map ? reply : null);
  }

  @override
  Future<void> pttDown() => _methods.invokeMethod<void>('pttDown');

  @override
  Future<void> pttUp() => _methods.invokeMethod<void>('pttUp');

  @override
  Future<String> setLanguage(String language) async {
    final reply = await _methods
        .invokeMethod<Object?>('setLanguage', {'language': language});
    if (reply is Map && reply['language'] is String) {
      return reply['language'] as String;
    }
    throw _bad('setLanguage');
  }

  @override
  Future<void> setFlightScreenVisible(bool visible) =>
      _methods.invokeMethod<void>('flightScreen', {'visible': visible});

  @override
  Future<NotificationPermission> microphonePermission(
          {bool ask = false}) =>
      _permission('microphonePermission', ask);

  @override
  Future<ConfirmResult> confirm(String token) async {
    final reply =
        await _methods.invokeMethod<Object?>('confirm', {'token': token});
    return ConfirmResult.fromMap(reply is Map ? reply : null);
  }

  @override
  Future<void> cancel() => _methods.invokeMethod<void>('cancel');

  @override
  Future<void> stop({required bool heldOneSecond}) =>
      _methods.invokeMethod<void>('stop', {'heldOneSecond': heldOneSecond});

  @override
  Future<void> acknowledgeAlarm() =>
      _methods.invokeMethod<void>('acknowledgeAlarm');

  @override
  Future<bool> requestTelemetry() async =>
      await _methods.invokeMethod<bool>('requestTelemetry') ?? false;

  @override
  Future<NotificationPermission> notificationPermission(
          {bool ask = false}) =>
      _permission('notificationPermission', ask);

  Future<NotificationPermission> _permission(String method, bool ask) async {
    final reply = await _methods.invokeMethod<Object?>(method, {'ask': ask});
    return NotificationPermission.values.firstWhere((p) => p.name == reply,
        orElse: () => throw _bad(method));
  }

  @override
  Future<void> cancelAi() => _methods.invokeMethod<void>('cancelAi');

  @override
  Future<AiModelsStatus> aiModels() async {
    final reply = await _methods.invokeMethod<Object?>('aiModels');
    if (reply is! Map) throw _bad('aiModels');
    return AiModelsStatus.fromMap(reply);
  }

  @override
  Future<void> downloadAiModel(String id) =>
      _methods.invokeMethod<void>('downloadAiModel', {'id': id});

  @override
  Future<void> importAiModel(String id) =>
      _methods.invokeMethod<void>('importAiModel', {'id': id});

  @override
  Future<void> cancelAiModel() => _methods.invokeMethod<void>('cancelAiModel');

  @override
  Future<bool> removeAiModel(String id) async {
    final reply = await _methods.invokeMethod<Object?>('removeAiModel', {'id': id});
    return reply is Map && reply['removed'] == true;
  }
}
