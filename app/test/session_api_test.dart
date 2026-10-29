import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/flight/domain/flight_command.dart';
import 'package:aeronex/features/flight/domain/flight_plan.dart';
import 'package:aeronex/features/session/session_api.dart';

/// The Kotlin side of the channel (T7), read so a rename there fails here.
const _kotlin = 'android/app/src/main/kotlin/com/aeronex/app';
String _source(String path) => File('$_kotlin/$path').readAsStringSync();

/// Every `"key"` read as `<name>["key"]` in [source] (optionally after [from]).
Set<String> _readKeys(String source, String name, {String? from}) {
  final text = from == null ? source : source.substring(source.indexOf(from));
  return {
    for (final m in RegExp('\\b$name\\["(\\w+)"\\]').allMatches(text)) m[1]!
  };
}

final _fixtures = Directory('test/fixtures/session')
    .listSync()
    .whereType<File>()
    .where((f) => f.path.endsWith('.json'))
    .toList()
  ..sort((a, b) => a.path.compareTo(b.path));

Map<String, Object?> _fixture(String name) =>
    jsonDecode(File('test/fixtures/session/$name.json').readAsStringSync())
        as Map<String, Object?>;

FlightCommand _command(String name, [Map<String, Object?> arguments = const {}]) =>
    FlightCommand.fromToolCalls([
      {'name': name, 'arguments': arguments}
    ]);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  group('SessionSnapshot', () {
    test('there are fixtures for every UI state T7 and T12 recorded', () {
      expect(_fixtures.map((f) => f.uri.pathSegments.last), [
        'ai_plan.json',
        'ai_thinking.json',
        'connected_landed.json',
        'executing_move.json',
        'flight_memory.json',
        'idle_disconnected.json',
        'mission_building.json',
        'mission_checkpoint.json',
        'pending_plan.json',
        'ptt_listening.json',
        'stop_not_confirmed.json',
        'voice_unavailable.json',
      ]);
    });

    test('reads the AI section (0.4.0)', () {
      final idle = SessionSnapshot.fromMap(_fixture('idle_disconnected')).ai;
      expect(idle.available, isFalse);
      expect(idle.thinking, isFalse);
      final thinking = SessionSnapshot.fromMap(_fixture('ai_thinking')).ai;
      expect(thinking.thinking, isTrue);
      expect(thinking.heard, 'go up a bit higher, by two and a half metres');
      final plan = SessionSnapshot.fromMap(_fixture('ai_plan'));
      expect(plan.pending!.source, PlanSource.ai);
      expect(plan.pending!.steps.map((s) => s.source), everyElement('ai'));
      expect(plan.ai.understoodAs, 'move forward 3 meters, then land');
      expect(plan.ai.gloss, 'forward 3 meters then land');
      expect(plan.latency['aiTotal']!.n, 1);
      // An older service without the section: no AI.
      final old = SessionSnapshot.fromMap({..._fixture('connected_landed')}..remove('ai'));
      expect(old.ai.available, isFalse);
    });

    test('reads the flight memory section (2026-10-28)', () {
      final memory = SessionSnapshot.fromMap(_fixture('flight_memory')).memory;
      expect(memory.homeKnown, isTrue);
      expect(memory.vehicle!.northM, 4.0);
      expect(memory.vehicle!.eastM, 2.97);
      expect([for (final p in memory.trail) p.label],
          ['Takeoff', 'Move forward 4 m', 'Move right 3 m']);
      expect(memory.trail.first.place.upM, 2.71);
      expect(memory.points.single.point, 1);
      expect(memory.points.single.place.northM, 4.02);
      expect(memory.goBackTo, 'Move forward 4 m');
      expect(memory.canRetrace, isTrue);
      expect([for (final p in memory.plans) p.planId], [4, 3, 2, 1]);
      expect(memory.plans.first.summary, 'Move right 3 m');
      expect(memory.plans.first.source, PlanSource.typed);
      expect(memory.plans.first.completed, isTrue);
      expect(memory.plans[1].steps.single.arguments, {'point': 1});
      expect(memory.nextFreePoint, 2);
      // Older services without the section, and a session that flew nothing.
      final old = SessionSnapshot.fromMap({..._fixture('connected_landed')}..remove('memory'));
      expect(old.memory.isEmpty, isTrue);
      final idle = SessionSnapshot.fromMap(_fixture('idle_disconnected')).memory;
      expect(idle.homeKnown, isFalse);
      expect(idle.vehicle, isNull);
      expect(idle.goBackTo, isNull);
      expect(idle.nextFreePoint, 1);
    });

    test('reads missions (2026-10-28)', () {
      final held = SessionSnapshot.fromMap(_fixture('mission_checkpoint'));
      expect(held.run!.isMission, isTrue);
      expect(held.run!.stageOfStep, [0, 0, 1, 1]);
      expect(held.run!.source, PlanSource.mission);
      expect(held.mission.checkpoints, isTrue);
      expect(held.mission.checkpoint!.stage, 1);
      expect(held.mission.checkpoint!.stageCount, 2);
      expect(held.mission.checkpoint!.token, '5d3c9a1e-2b7f-4e6a-9c8d-1f2e3a4b5c6d');
      final building = SessionSnapshot.fromMap(_fixture('mission_building')).mission;
      expect(building.building, isTrue);
      expect(building.maxStages, 5);
      expect([for (final s in building.stages) s.source], [PlanSource.voice, PlanSource.tap]);
      expect(building.stages.first.summary, '1. Take off to 3 m; 2. Move forward 4 m');
      expect(building.stages.last.steps.single.arguments, {'point': 1});
      expect(building.resume!.fromStep, 3);
      expect(building.resume!.stepCount, 2);
      final saved = building.saved.single;
      expect([saved.name, saved.stageCount, saved.stepCount], ['Perimeter', 2, 3]);
      expect(saved.summary, 'Take off to 3 m, then Move forward 4 m, then Land');
      // A plan, an older service, and the submit answer of a plan that became a stage.
      final plain = SessionSnapshot.fromMap(_fixture('pending_plan'));
      expect(plain.pending!.isMission, isFalse);
      expect(plain.mission.building, isFalse);
      final old = SessionSnapshot.fromMap({..._fixture('connected_landed')}..remove('mission'));
      expect(old.mission.stages, isEmpty);
      final added = SubmitResult.fromMap({'addedToMission': true, 'stages': 2});
      expect((added as AddedToMission).stages, 2);
    });

    test('reads the speech section (T12)', () {
      final idle = SessionSnapshot.fromMap(_fixture('idle_disconnected')).speech;
      expect(idle.state, 'OFF');
      expect(idle.level, 'NO_VOICE');
      expect(idle.reason, 'NOT_STARTED');
      expect(idle.message, isNotEmpty);
      final listening = SessionSnapshot.fromMap(_fixture('ptt_listening')).speech;
      expect(listening.listening, isTrue);
      expect(listening.pttHeld, isTrue);
      expect(listening.partial, 'move forward');
      expect(listening.ok, isTrue);
      expect(listening.spotterArmed, isTrue);
      expect(listening.nativeVoiceStop, isTrue);
      expect(listening.engine, 'VOSK_GRAMMAR');
      expect(listening.utterance, 7);
      final none = SessionSnapshot.fromMap(_fixture('voice_unavailable')).speech;
      expect(none.language, 'ta');
      expect(none.reason, 'NEEDS_NEWER_PHONE');
      expect(none.message,
          'Voice for this language needs a newer phone — use the tap pad');
      expect(speechLanguages.keys, ['en', 'hi', 'te', 'ta', 'kn', 'ml']);
      // An older service without the section: voice off.
      final old = SessionSnapshot.fromMap(
          {..._fixture('connected_landed')}..remove('speech'));
      expect(old.speech.level, 'NO_VOICE');
    });

    for (final file in _fixtures) {
      final name = file.uri.pathSegments.last;
      test('parses $name without losing a field', () {
        final json = jsonDecode(file.readAsStringSync()) as Map<String, Object?>;
        final snapshot = SessionSnapshot.fromMap(json);
        // Every field survives the round trip, so nothing the UI reads is lost.
        expect(snapshot.toMap(), json);
        expect(snapshot.raw, json);
      });
    }

    test('reads the fields the flight screen shows', () {
      final idle = SessionSnapshot.fromMap(_fixture('idle_disconnected'));
      expect(idle.schemaVersion, sessionSchemaVersion);
      expect(idle.seq, 1);
      expect(idle.link.state, LinkState.down);
      expect(idle.link.transport, isNull);
      expect(idle.link.unsigned, isTrue);
      expect(idle.link.foregroundService, isFalse);
      expect(idle.executionEnabled, isFalse);
      expect(idle.vehicle.connected, isFalse);
      expect(idle.vehicle.modeOwner, isNull);
      expect(idle.vehicle.ages['heartbeat'], isNull);
      expect(idle.pending, isNull);
      expect(idle.run, isNull);
      expect(idle.stop, isNull);
      expect(idle.latency['stopToAck'], isNull);

      final landed = SessionSnapshot.fromMap(_fixture('connected_landed'));
      expect(landed.link.state, LinkState.up);
      expect(landed.link.transport, 'udp');
      expect(landed.link.profile, 'wifi');
      expect(landed.link.heartbeatAgeMs, 310);
      expect(landed.vehicle.modeName, 'STABILIZE');
      expect(landed.vehicle.mode, 0);
      expect(landed.vehicle.armed, isFalse);
      expect(landed.vehicle.landedStateName, 'ON_GROUND');
      expect(landed.vehicle.modeOwner, 'EXTERNAL');
      expect(landed.vehicle.modeOwnerLabel, 'Pilot mode');
      expect(landed.vehicle.altitudeRelM, 0.02);
      expect(landed.vehicle.batteryPercent, 100);
      expect(landed.vehicle.positionReady, isTrue);
      expect(landed.vehicle.homeDistanceM, 0.22);
      expect(landed.vehicle.lastStatusText!.text, 'EKF3 IMU0 is using GPS');
      expect(landed.vehicle.ages['globalPosition'], 60);

      final pending = SessionSnapshot.fromMap(_fixture('pending_plan')).pending!;
      expect(pending.token, '0b6f4d2a-6c4e-4c1e-9d0a-5e2f1c3b7a90');
      expect(pending.summary, '1. Move forward 2 m; 2. Move right 2 m');
      expect(pending.source, PlanSource.typed);
      expect(pending.expiresInMs, 12400);
      expect([for (final s in pending.steps) s.description],
          ['Move forward 2 m', 'Move right 2 m']);
      expect(pending.steps.first.name, 'move_relative');
      expect(pending.steps.first.arguments,
          {'direction': 'forward', 'distance_m': 2});
      expect(pending.steps.first.source, 'rules');

      final executing = SessionSnapshot.fromMap(_fixture('executing_move'));
      final run = executing.run!;
      expect(run.executing, isTrue);
      expect(run.source, PlanSource.voice);
      expect(run.stepIndex, 0);
      expect(run.stepCount, 2);
      expect([for (final o in run.outcomes) o.outcome], ['RUNNING', 'PENDING']);
      expect(run.outcomes.first.step, 'Move forward 2 m');
      expect(run.message, isNull);
      expect(executing.executionEnabled, isTrue);
      expect(executing.vehicle.modeOwner, 'APP');
      expect(executing.latency['moveTxToEcho']!.p95Ms, 233.0);
      expect(executing.latency['moveTxToEcho']!.n, 4);

      final alarm = SessionSnapshot.fromMap(_fixture('stop_not_confirmed'));
      final stop = alarm.stop!;
      expect(stop.id, 57);
      expect(stop.phase, 'NOT_CONFIRMED');
      expect(stop.trigger, 'BUTTON');
      expect(stop.action, 'BRAKE');
      expect(stop.alarm, isTrue);
      expect(stop.override, isFalse);
      expect(stop.needsHoldForExternal, isTrue);
      expect(stop.modeOwnerLabel, 'Autopilot failsafe');
      expect(stop.message,
          'STOP NOT CONFIRMED: flight mode changed to LAND outside this app, use RC');
      expect(alarm.run!.failed, isTrue);
      expect(alarm.run!.outcomes.single.outcome, 'UNKNOWN');
      expect(alarm.link.txErrors, 2);
    });

    test('refuses a schema version it does not know', () {
      expect(
          () => SessionSnapshot.fromMap(
              {..._fixture('idle_disconnected'), 'schemaVersion': 2}),
          throwsFormatException);
    });

    test('refuses a malformed snapshot', () {
      final broken = _fixture('connected_landed');
      (broken['link'] as Map)['state'] = 42;
      expect(() => SessionSnapshot.fromMap(broken), throwsFormatException);
      expect(() => SessionSnapshot.fromMap({'schemaVersion': 1}),
          throwsFormatException);
    });

    test('the placeholder before the first snapshot is a disconnected dry run',
        () {
      const none = SessionSnapshot.none;
      expect(none.link.state, LinkState.down);
      expect(none.executionEnabled, isFalse);
      expect(none.vehicle.connected, isFalse);
      expect(none.pending, isNull);
      expect(none.seq, -1);
    });
  });

  group('ChannelSessionApi contract with SessionChannel.kt', () {
    final channelSource = _source('session/SessionChannel.kt');
    final apiSource = _source('session/SessionApi.kt');
    final configSource = _source('session/SessionConfig.kt');
    final schemaSource = _source('session/SessionSchema.kt');
    final stateSource = _source('safety/SupervisorState.kt');
    final planSource = _source('command/model/FlightPlan.kt');

    String constant(String name) =>
        RegExp('const val $name = "([^"]+)"').firstMatch(channelSource)![1]!;

    // The `when (method)` branches of SessionApi.call, plus the reserved ones.
    final kotlinMethods = {
      for (final m in RegExp(r'^\s*"(\w+)" ->', multiLine: true)
          .allMatches(apiSource))
        m[1]!
    };

    late List<MethodCall> calls;
    late Object? Function(MethodCall call) reply;

    setUp(() {
      calls = [];
      reply = (_) => null;
      messenger.setMockMethodCallHandler(
          const MethodChannel(ChannelSessionApi.methodsName), (call) async {
        calls.add(call);
        return reply(call);
      });
    });
    tearDown(() {
      messenger.setMockMethodCallHandler(
          const MethodChannel(ChannelSessionApi.methodsName), null);
      messenger.setMockStreamHandler(
          const EventChannel(ChannelSessionApi.eventsName), null);
    });

    test('uses the Kotlin channel names', () {
      expect(ChannelSessionApi.methodsName, constant('METHODS'));
      expect(ChannelSessionApi.eventsName, constant('EVENTS'));
      expect(kotlinMethods, containsAll([
        'connect', 'disconnect', 'setExecution', 'submitPlan', 'confirm', //
        'cancel', 'stop', 'acknowledgeAlarm', 'requestTelemetry', 'getSnapshot',
      ]));
    });

    test('every call is a method SessionApi.kt answers, with its arguments',
        () async {
      final api = ChannelSessionApi();
      reply = (call) => switch (call.method) {
            'connect' || 'getSnapshot' => _fixture('connected_landed'),
            'setExecution' => {'enabled': true},
            'submitPlan' => {
                'token': 't-1',
                'expiresInMs': 15000,
                'summary': '1. Take off to 3 m'
              },
            'confirm' => {'accepted': true},
            'requestTelemetry' => true,
            _ => null,
          };
      final connected = await api.connect(const LinkConfig(
          transport: LinkTransport.udp,
          host: '192.168.0.5',
          remotePort: 14550,
          localPort: 14551,
          radioLink: true));
      expect(connected.link.state, LinkState.up);
      await api.disconnect();
      expect(await api.setExecution(true), isTrue);
      final submitted = await api.submitPlan(
          FlightPlan([
            PlanStep(_command('takeoff', {'altitude_m': 3}),
                source: StepSource.rules, clause: 'take off to 3 metres'),
            PlanStep(_command('land'), source: StepSource.fixture),
          ]),
          PlanSource.voice,
          utterance: 'take off to 3 metres and land');
      expect(submitted, isA<AwaitingConfirmation>());
      expect((await api.confirm('t-1')).accepted, isTrue);
      await api.cancel();
      await api.stop(heldOneSecond: false);
      await api.stop(heldOneSecond: true);
      await api.acknowledgeAlarm();
      expect(await api.requestTelemetry(), isTrue);
      expect((await api.getSnapshot()).seq, 42);

      expect([for (final c in calls) c.method], [
        'connect', 'disconnect', 'setExecution', 'submitPlan', 'confirm', //
        'cancel', 'stop', 'stop', 'acknowledgeAlarm', 'requestTelemetry',
        'getSnapshot',
      ]);
      for (final call in calls) {
        expect(kotlinMethods, contains(call.method), reason: call.method);
      }
      Object? args(String method, [int skip = 0]) =>
          calls.where((c) => c.method == method).skip(skip).first.arguments;

      // connect: exactly the keys SessionConfig.fromMap reads.
      expect(args('connect'), {
        'transport': 'udp',
        'host': '192.168.0.5',
        'remotePort': 14550,
        'localPort': 14551,
        'radioLink': true,
      });
      expect((args('connect') as Map).keys.toSet(),
          _readKeys(configSource, 'map', from: 'fun fromMap'));
      expect(RegExp(r'UDP\("(\w+)"').firstMatch(configSource)![1], 'udp');
      expect(RegExp(r'TCP\("(\w+)"').firstMatch(configSource)![1],
          LinkTransport.tcp.name);

      expect(args('disconnect'), isNull);
      expect(args('setExecution'), {'enabled': true});
      expect(apiSource, contains('argument(arguments, "enabled")'));
      expect(args('confirm'), {'token': 't-1'});
      expect(apiSource, contains('argument(arguments, "token")'));
      expect(args('cancel'), isNull);
      expect(args('stop'), {'heldOneSecond': false});
      expect(args('stop', 1), {'heldOneSecond': true});
      expect(apiSource, contains('argument(arguments, "heldOneSecond")'));
      expect(args('acknowledgeAlarm'), isNull);
      expect(args('requestTelemetry'), isNull);
      expect(args('getSnapshot'), isNull);

      // submitPlan: SessionSchema.parsePlan's request and step keys.
      expect(args('submitPlan'), {
        'steps': [
          {
            'name': 'takeoff',
            'arguments': {'altitude_m': 3},
            'source': 'rules',
            'clause': 'take off to 3 metres',
          },
          {'name': 'land', 'arguments': <String, Object?>{}, 'source': 'fixture'},
        ],
        'source': 'phone',
        'utterance': 'take off to 3 metres and land',
      });
      final parsePlan = schemaSource.substring(
          schemaSource.indexOf('fun parsePlan'),
          schemaSource.indexOf('fun submitted'));
      expect((args('submitPlan') as Map).keys.toSet(),
          _readKeys(parsePlan, 'map'));
      final stepKeys = {
        ..._readKeys(parsePlan, 'step'),
        for (final m in RegExp(r'"(\w+)" !in step').allMatches(parsePlan)) m[1]!
      };
      for (final step in (args('submitPlan') as Map)['steps'] as List) {
        expect(stepKeys, containsAll((step as Map).keys));
      }
      expect(stepKeys, {'name', 'arguments', 'source', 'clause'});
      // Step and plan sources are the Kotlin wire spellings.
      expect(planSource, contains('RULES, FIXTURE, AI;'));
      expect(planSource, contains('name.lowercase()'));
      final planSources = RegExp(r'enum class PlanSource\(val label: String\) \{([^}]*)\}')
          .firstMatch(stateSource)![1]!;
      expect({
        for (final m in RegExp(r'(\w+)\("').allMatches(planSources))
          m[1]!.toLowerCase()
      }, {for (final s in PlanSource.values) s.wire});
      // The source chip reads the service's own label (spec 4.10).
      expect({
        for (final m in RegExp(r'(\w+)\("([^"]*)"\)').allMatches(planSources))
          m[1]!.toLowerCase(): m[2]
      }, {for (final s in PlanSource.values) s.wire: s.label});
      expect(schemaSource, contains('val PlanSource.wire: String get() = name.lowercase()'));
    });

    test('runAgain and clearMemory are methods SessionApi.kt answers', () async {
      final api = ChannelSessionApi();
      reply = (call) => switch (call.method) {
            'runAgain' => {'error': 'That plan is no longer in the flight memory.'},
            _ => null,
          };
      final again = await api.runAgain(7);
      expect((again as RejectedResult).error, 'That plan is no longer in the flight memory.');
      await api.clearMemory();
      expect([for (final c in calls) c.method], ['runAgain', 'clearMemory']);
      expect(calls.first.arguments, {'planId': 7});
      expect(calls.last.arguments, isNull);
      for (final call in calls) {
        expect(kotlinMethods, contains(call.method), reason: call.method);
      }
      expect(apiSource, contains('argument(arguments, "planId")'));
    });

    test('the mission calls are methods SessionApi.kt answers, with its arguments', () async {
      final api = ChannelSessionApi();
      reply = (call) => switch (call.method) {
            'addStage' || 'startMission' || 'resumeMission' => {'addedToMission': true, 'stages': 1},
            'continueMission' => {'accepted': true},
            _ => null,
          };
      await api.setMissionBuilding(true);
      expect(await api.addStage('t-1'), isA<AddedToMission>());
      await api.removeStage(0);
      await api.clearMission();
      await api.setMissionCheckpoints(true);
      expect(await api.startMission(), isA<AddedToMission>());
      expect((await api.continueMission('c-1')).accepted, isTrue);
      expect(await api.resumeMission(skipInterrupted: true), isA<AddedToMission>());
      expect([for (final c in calls) c.method], [
        'missionBuilding', 'addStage', 'removeStage', 'clearMission', 'missionCheckpoints', //
        'startMission', 'continueMission', 'resumeMission',
      ]);
      expect([for (final c in calls) c.arguments], [
        {'enabled': true}, {'token': 't-1'}, {'index': 0}, null, {'enabled': true}, null, {'token': 'c-1'},
        {'skipInterrupted': true},
      ]);
      for (final call in calls) {
        expect(kotlinMethods, contains(call.method), reason: call.method);
      }
      for (final key in ['"enabled"', '"token"', '"index"', '"skipInterrupted"']) {
        expect(apiSource, contains('argument(arguments, $key)'));
      }
    });

    test('a step without a clause or a plan without an utterance omits them',
        () {
      final request = ChannelSessionApi.planRequest(
          FlightPlan.single(_command('hold'), source: StepSource.rules),
          PlanSource.tap);
      expect(request, {
        'steps': [
          {'name': 'hold', 'arguments': <String, Object?>{}, 'source': 'rules'}
        ],
        'source': 'tap',
      });
    });

    test('submitPlan answers are typed', () async {
      final api = ChannelSessionApi();
      final plan =
          FlightPlan.single(_command('get_status'), source: StepSource.rules);
      reply = (_) => {
            'immediate': true,
            'outcome': 'NOT_SENT',
            'message': 'NOT SENT — dry run: get_status({})'
          };
      final immediate = await api.submitPlan(plan, PlanSource.typed);
      expect(immediate, isA<ImmediateResult>());
      expect((immediate as ImmediateResult).outcome, 'NOT_SENT');
      expect(immediate.message, 'NOT SENT — dry run: get_status({})');
      reply = (_) => {'error': 'Takeoff can only be the first step.'};
      final rejected = await api.submitPlan(plan, PlanSource.typed);
      expect((rejected as RejectedResult).error,
          'Takeoff can only be the first step.');
      reply = (_) =>
          {'token': 'abc', 'expiresInMs': 14990, 'summary': '1. Report status'};
      final waiting =
          await api.submitPlan(plan, PlanSource.typed) as AwaitingConfirmation;
      expect(waiting.token, 'abc');
      expect(waiting.expiresInMs, 14990);
      expect(waiting.summary, '1. Report status');
      reply = (_) => {'unexpected': 1};
      expect(() => api.submitPlan(plan, PlanSource.typed), throwsFormatException);
    });

    test('a refused confirm carries its reason', () async {
      reply = (_) => {
            'accepted': false,
            'error': 'The confirmation expired. Say the command again.'
          };
      final result = await ChannelSessionApi().confirm('old');
      expect(result.accepted, isFalse);
      expect(result.error, 'The confirmation expired. Say the command again.');
    });

    test('service errors reach the caller as PlatformExceptions', () async {
      messenger.setMockMethodCallHandler(
          const MethodChannel(ChannelSessionApi.methodsName), (call) async {
        throw PlatformException(
            code: 'CONNECT_FAILED',
            message: 'Cannot open the UDP link to 10.0.0.1:14550: busy');
      });
      await expectLater(
          ChannelSessionApi().connect(const LinkConfig(host: '10.0.0.1')),
          throwsA(isA<PlatformException>()
              .having((e) => e.code, 'code', 'CONNECT_FAILED')
              .having((e) => e.message, 'message',
                  'Cannot open the UDP link to 10.0.0.1:14550: busy')));
    });

    test('snapshots stream from the state EventChannel', () async {
      messenger.setMockStreamHandler(
          const EventChannel(ChannelSessionApi.eventsName),
          MockStreamHandler.inline(onListen: (arguments, events) {
        events.success(_fixture('pending_plan'));
        events.success(_fixture('executing_move'));
      }));
      final api = ChannelSessionApi();
      final received = await api.snapshots.take(2).toList();
      expect([for (final s in received) s.seq], [97, 131]);
      expect(received.first.pending!.source, PlanSource.typed);
    });

    test('each snapshot is parsed once and shared by every listener',
        () async {
      var listens = 0, cancels = 0;
      MockStreamHandlerEventSink? sink;
      messenger.setMockStreamHandler(
          const EventChannel(ChannelSessionApi.eventsName),
          MockStreamHandler.inline(onListen: (arguments, events) {
        listens++;
        sink = events;
      }, onCancel: (arguments) => cancels++));
      Future<void> settle() async {
        for (var i = 0; i < 5; i++) {
          await Future<void>.delayed(Duration.zero);
        }
      }

      final api = ChannelSessionApi();
      final stopLane = <SessionSnapshot>[], flight = <SessionSnapshot>[];
      final a = api.snapshots.listen(stopLane.add);
      final b = api.snapshots.listen(flight.add);
      await settle();
      sink!.success(_fixture('pending_plan'));
      sink!.success(_fixture('executing_move'));
      await settle();
      expect(listens, 1, reason: 'one platform stream for every listener');
      expect([for (final s in stopLane) s.seq], [97, 131]);
      expect([for (final s in flight) s.seq], [97, 131]);
      for (var i = 0; i < 2; i++) {
        expect(identical(stopLane[i], flight[i]), isTrue,
            reason: 'parsed once, not once per listener');
      }
      await a.cancel();
      await settle();
      expect(cancels, 0, reason: 'still listened to');
      await b.cancel();
      await settle();
      expect(cancels, 1, reason: 'the service stops pushing when nobody listens');
      final again = api.snapshots.first;
      await settle();
      expect(listens, 2);
      sink!.success(_fixture('idle_disconnected'));
      expect((await again).link.state, LinkState.down);
    });

    test('push-to-talk, typed text, the language and the flight screen flag '
        'are SessionApi.kt methods with its arguments (T12)', () async {
      final api = ChannelSessionApi();
      reply = (call) => switch (call.method) {
            'submitText' => {'error': 'Say a command first.'},
            'setLanguage' => {'language': 'hi'},
            'microphonePermission' => 'granted',
            _ => null,
          };
      final refused = await api.submitText('', 'en', PlanSource.typed);
      expect((refused as RejectedResult).error, 'Say a command first.');
      await api.pttDown();
      await api.pttUp();
      expect(await api.setLanguage('hi-IN'), 'hi');
      await api.setFlightScreenVisible(true);
      expect(await api.microphonePermission(ask: true),
          NotificationPermission.granted);
      expect([for (final c in calls) c.method], [
        'submitText', 'pttDown', 'pttUp', 'setLanguage', 'flightScreen', //
        'microphonePermission',
      ]);
      expect(calls[0].arguments,
          {'text': '', 'language': 'en', 'source': 'typed'});
      expect(calls[3].arguments, {'language': 'hi-IN'});
      expect(calls[4].arguments, {'visible': true});
      expect(calls[5].arguments, {'ask': true});
      for (final method in ['submitText', 'pttDown', 'pttUp', 'setLanguage',
          'flightScreen']) {
        expect(kotlinMethods, contains(method), reason: method);
      }
      expect(constant('MICROPHONE_PERMISSION'), 'microphonePermission');
      expect(apiSource, contains('argument(arguments, "language")'));
      expect(apiSource, contains('argument(arguments, "visible")'));
      expect(apiSource, contains('map["text"]'));
      expect(apiSource, contains('map["source"]'));
    });

    test('notificationPermission asks only when told to; SessionChannel.kt '
        'answers it with the Kotlin state names', () async {
      final api = ChannelSessionApi();
      reply = (call) => 'askable';
      expect(await api.notificationPermission(), NotificationPermission.askable);
      reply = (call) => 'denied';
      expect(await api.notificationPermission(ask: true),
          NotificationPermission.denied);
      expect([for (final c in calls) c.method],
          ['notificationPermission', 'notificationPermission']);
      expect([for (final c in calls) c.arguments], [
        {'ask': false},
        {'ask': true}
      ]);
      expect(constant('NOTIFICATION_PERMISSION'), 'notificationPermission');
      final states = RegExp(r'enum class State\(val wire: String\) \{([^}]*)\}')
          .firstMatch(_source('session/NotificationPermission.kt'))![1]!;
      expect({for (final m in RegExp(r'\("(\w+)"\)').allMatches(states)) m[1]!},
          {for (final p in NotificationPermission.values) p.name});
      reply = (call) => 'maybe';
      await expectLater(api.notificationPermission(), throwsFormatException);
    });

    test('the AI methods (0.4.0) are SessionApi.kt methods with its arguments; '
        'a thinking answer and the models status are read', () async {
      final api = ChannelSessionApi();
      reply = (call) => switch (call.method) {
            'submitText' => {'thinking': true, 'request': 4},
            'aiModels' => {
                'available': false,
                'reason': 'Needs a 64-bit ARM phone',
                'models': [
                  {
                    'id': 'lfm-q4',
                    'file': 'LFM2.5-VL-450M-Q4_K_M.gguf',
                    'bytes': 229313568,
                    'state': 'missing',
                    'importPath': '/sdcard/Android/data/x/files/import/lfm-q4/LFM2.5-VL-450M-Q4_K_M.gguf',
                  },
                  {'id': 'needle3', 'file': 'needle3.cact', 'bytes': 35335380, 'state': 'ready', 'importPath': null},
                ],
                'transfer': {'id': 'lfm-q4', 'source': 'download', 'receivedBytes': 1000, 'totalBytes': 4000},
                'error': {'id': 'needle3', 'message': 'Cancelled.'},
              },
            'downloadAiModel' || 'importAiModel' => {'started': true},
            'removeAiModel' => {'removed': true},
            _ => null,
          };
      final thinking = await api.submitText('circle the tree', 'en', PlanSource.typed);
      expect((thinking as ThinkingResult).request, 4);
      await api.cancelAi();
      final models = await api.aiModels();
      expect(models.available, isFalse);
      expect(models.reason, 'Needs a 64-bit ARM phone');
      expect([for (final m in models.models) m.id], ['lfm-q4', 'needle3']);
      expect(models.models.last.ready, isTrue);
      expect(models.transfer!.fraction, 0.25);
      expect(models.errorId, 'needle3');
      await api.downloadAiModel('lfm-q4');
      await api.importAiModel('needle3');
      await api.cancelAiModel();
      expect(await api.removeAiModel('lfm-q4'), isTrue);
      expect([for (final c in calls) c.method], [
        'submitText', 'cancelAi', 'aiModels', 'downloadAiModel', 'importAiModel', //
        'cancelAiModel', 'removeAiModel',
      ]);
      expect(calls[3].arguments, {'id': 'lfm-q4'});
      expect(calls[6].arguments, {'id': 'lfm-q4'});
      for (final method in ['cancelAi', 'aiModels', 'downloadAiModel', 'importAiModel', 'cancelAiModel',
          'removeAiModel']) {
        expect(kotlinMethods, contains(method), reason: method);
      }
    });

    test('an unreadable snapshot is a stream error, not a crash', () async {
      messenger.setMockStreamHandler(
          const EventChannel(ChannelSessionApi.eventsName),
          MockStreamHandler.inline(onListen: (arguments, events) {
        events.success({..._fixture('idle_disconnected'), 'schemaVersion': 9});
      }));
      await expectLater(
          ChannelSessionApi().snapshots.first, throwsFormatException);
    });
  });
}
