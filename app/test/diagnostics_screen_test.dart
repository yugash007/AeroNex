import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/diagnostics/presentation/diagnostics_screen.dart';
import 'package:aeronex/features/flight/presentation/widgets/stop_button.dart';
import 'package:aeronex/features/session/session_api.dart';

import 'support/fake_session_api.dart';

/// [FakeSessionApi] whose telemetry request can go unqueued or fail.
class _Session extends FakeSessionApi {
  _Session([super.initial]);

  bool telemetryQueued = true;
  Object? telemetryError;

  @override
  Future<bool> requestTelemetry() async {
    final queued = await super.requestTelemetry();
    if (telemetryError != null) throw telemetryError!;
    return queued && telemetryQueued;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<_Session> open(WidgetTester tester,
      {SessionSnapshot? initial,
      _Session? session,
      Size size = const Size(480, 2400),
      double textScale = 1.0}) async {
    final fake = session ?? _Session(initial);
    addTearDown(fake.close);
    await tester.binding.setSurfaceSize(size);
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
        builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(textScale)),
            child: StopLane(session: fake, child: child!)),
        home: DiagnosticsScreen(session: fake)));
    await tester.pumpAndSettle();
    return fake;
  }

  /// A value row by its key (`section.field`).
  Finder value(String key, String text) => find.descendant(
      of: find.byKey(ValueKey(key)), matching: find.text(text));
  Finder latency(String stage, String text) => find.descendant(
      of: find.byKey(ValueKey('latency.$stage')), matching: find.text(text));

  /// Scrolls the screen's list until [finder] is built and on screen.
  Future<void> reveal(WidgetTester tester, Finder finder) async {
    await tester.scrollUntilVisible(finder, 200,
        scrollable: find.byType(Scrollable).last);
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  group('latency', () {
    testWidgets('shows p50, p95 and n per stage from the snapshot',
        (tester) async {
      await open(tester, initial: fixture('stop_not_confirmed'));
      expect(latency('stopToTx', 'STOP → sent'), findsOneWidget);
      expect(latency('stopToTx', '0.41'), findsOneWidget);
      expect(latency('stopToTx', '0.88'), findsOneWidget);
      expect(latency('stopToTx', '3'), findsOneWidget);
      expect(latency('stopToAck', '4.9'), findsOneWidget);
      expect(latency('stopToAck', '7.7'), findsOneWidget);
      expect(latency('stopToAck', '3'), findsOneWidget);
      // A stage with no spans yet.
      expect(latency('confirmToAck', '—'), findsNWidgets(3));
      for (final header in ['p50 ms', 'p95 ms', 'n']) {
        expect(find.text(header), findsOneWidget);
      }
    });

    testWidgets('every stage the service reports has a row, in its order',
        (tester) async {
      await open(tester, initial: fixture('executing_move'));
      expect(latency('moveTxToEcho', '121.5'), findsOneWidget);
      expect(latency('moveTxToEcho', '233.0'), findsOneWidget);
      expect(latency('moveTxToEcho', '4'), findsOneWidget);
      expect(latency('confirmToMoveTx', '3.1'), findsOneWidget);
      const stages = [
        'confirmToTx',
        'confirmToAck',
        'confirmToMoveTx',
        'moveTxToEcho',
        'confirmToInAir',
        'stopToTx',
        'stopToAck',
      ];
      final tops = [
        for (final stage in stages)
          tester.getTopLeft(find.byKey(ValueKey('latency.$stage'))).dy
      ];
      expect(tops, orderedEquals([...tops]..sort()));
    });

    testWidgets('a stage this screen does not know is still shown',
        (tester) async {
      await open(tester,
          initial: fixture('connected_landed', {
            'latency': {
              'pttUpToPlan': {'p50Ms': 180.0, 'p95Ms': 410.0, 'maxMs': 410.0, 'n': 9}
            }
          }));
      expect(latency('pttUpToPlan', 'pttUpToPlan'), findsOneWidget);
      expect(latency('pttUpToPlan', '410.0'), findsOneWidget);
    });
  });

  group('link', () {
    testWidgets('statistics are shown as the service reports them',
        (tester) async {
      await open(tester,
          initial: fixture('connected_landed', {
            'link': {
              'profile': 'radio',
              'heartbeatAgeMs': 1250,
              'txErrors': 2,
              'droppedDatagrams': 5,
            }
          }));
      expect(value('link.state', 'up'), findsOneWidget);
      expect(value('link.transport', 'udp'), findsOneWidget);
      expect(value('link.description', 'UDP 192.168.0.5:14550 local *:14551'),
          findsOneWidget);
      expect(value('link.profile', 'radio'), findsOneWidget);
      expect(value('link.unsigned', 'yes'), findsOneWidget);
      expect(value('link.heartbeatAgeMs', '1250 ms'), findsOneWidget);
      expect(value('link.txErrors', '2'), findsOneWidget);
      expect(value('link.droppedDatagrams', '5'), findsOneWidget);
      expect(value('link.foregroundService', 'yes'), findsOneWidget);
      expect(value('execution.enabled', 'off (dry run)'), findsOneWidget);
    });

    testWidgets('a closed link shows what is unknown', (tester) async {
      await open(tester);
      expect(value('link.state', 'down'), findsOneWidget);
      expect(value('link.heartbeatAgeMs', '—'), findsOneWidget);
      expect(value('link.profile', '—'), findsOneWidget);
    });
  });

  group('vehicle', () {
    testWidgets('shows who owns the flight mode and the raw telemetry',
        (tester) async {
      await open(tester, initial: fixture('connected_landed'));
      expect(value('owner.modeOwner', 'EXTERNAL'), findsOneWidget);
      expect(value('owner.modeOwnerLabel', 'Pilot mode'), findsOneWidget);
      expect(value('owner.mode', 'STABILIZE (0)'), findsOneWidget);
      expect(value('vehicle.modeName', 'STABILIZE'), findsOneWidget);
      expect(value('vehicle.altitudeRelM', '0.02'), findsOneWidget);
      expect(value('vehicle.batteryPercent', '100'), findsOneWidget);
      expect(value('vehicle.homeDistanceM', '0.22'), findsOneWidget);
      expect(value('vehicle.lastStatusText.text', 'EKF3 IMU0 is using GPS'),
          findsOneWidget);
      expect(value('vehicle.ages.heartbeat', '310'), findsOneWidget);
      expect(value('vehicle.ages.positionTarget', '—'), findsOneWidget);
    });

    testWidgets('the app owning the mode reads APP', (tester) async {
      await open(tester, initial: fixture('executing_move'));
      expect(value('owner.modeOwner', 'APP'), findsOneWidget);
      expect(value('owner.mode', 'GUIDED (4)'), findsOneWidget);
    });

    testWidgets('the last STOP is shown raw', (tester) async {
      await open(tester, initial: fixture('stop_not_confirmed'));
      await reveal(tester, find.byKey(const ValueKey('stop.phase')));
      expect(value('stop.phase', 'NOT_CONFIRMED'), findsOneWidget);
      expect(value('stop.action', 'BRAKE'), findsOneWidget);
      expect(value('stop.trigger', 'BUTTON'), findsOneWidget);
    });

    testWidgets('new snapshots update it; an older one never replaces a newer '
        'one', (tester) async {
      final session = await open(tester, initial: fixture('connected_landed'));
      session.pushFixture('executing_move');
      await tester.pumpAndSettle();
      expect(value('vehicle.modeName', 'GUIDED'), findsOneWidget);
      session.push(fixture('connected_landed', {'seq': 5}));
      await tester.pumpAndSettle();
      expect(value('vehicle.modeName', 'GUIDED'), findsOneWidget);
    });

    testWidgets('an unreadable service says why', (tester) async {
      final session = await open(tester, initial: fixture('connected_landed'));
      session.addError(const FormatException(
          'The flight service uses data version 9; this app reads version 1. '
          'Install matching versions.'));
      await tester.pumpAndSettle();
      expect(find.textContaining('data version 9'), findsOneWidget);
    });
  });

  group('Request telemetry', () {
    testWidgets('asks the service and says it was asked', (tester) async {
      final session = await open(tester, initial: fixture('connected_landed'));
      await tap(tester, find.text('Request telemetry'));
      expect(session.methods.where((m) => m == 'requestTelemetry'), hasLength(1));
      expect(find.text(telemetryRequested), findsOneWidget);
    });

    testWidgets('says when it was not sent', (tester) async {
      final session = _Session(fixture('connected_landed'))
        ..telemetryQueued = false;
      await open(tester, session: session);
      await tap(tester, find.text('Request telemetry'));
      expect(find.text(telemetryNotSent), findsOneWidget);
      session.telemetryError =
          PlatformException(code: 'INTERNAL', message: 'The link closed.');
      await tap(tester, find.text('Request telemetry'));
      expect(find.text('The link closed.'), findsOneWidget);
    });

    testWidgets('needs an open link', (tester) async {
      final session = await open(tester);
      final button = find.widgetWithText(OutlinedButton, 'Request telemetry');
      expect(tester.widget<OutlinedButton>(button).onPressed, isNull);
      session.pushFixture('connected_landed');
      await tester.pumpAndSettle();
      expect(tester.widget<OutlinedButton>(button).onPressed, isNotNull);
    });
  });

  testWidgets('fits a 5-inch phone with large text; STOP stays above',
      (tester) async {
    final session = await open(tester,
        initial: fixture('stop_not_confirmed', {
          'vehicle': {'modeOwner': 'APP', 'modeOwnerLabel': null},
          'stop': {'needsHoldForExternal': false, 'modeOwnerLabel': null},
        }),
        size: const Size(360, 640),
        textScale: 1.3);
    expect(tester.takeException(), isNull);
    await reveal(tester, find.byKey(const ValueKey('stop.phase')));
    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const Key('stopButton')));
    await tester.pump();
    expect(session.methods.last, 'stop');
  });

  for (final (label, hint) in [
    ('Pilot mode', 'Pilot has control — hold 1 s to override'),
    ('Autopilot failsafe', 'Autopilot failsafe active — hold 1 s to override'),
  ]) {
    testWidgets('fits a 5-inch phone with large text while the mode is '
        'external ($label): STOP shows its hold hint', (tester) async {
      // The alarm banner is acknowledged: this checks STOP's hint.
      final session = await open(tester,
          initial: fixture('stop_not_confirmed', {
            'vehicle': {'modeOwnerLabel': label},
            'stop': {'modeOwnerLabel': label, 'alarm': false},
          }),
          size: const Size(360, 640),
          textScale: 1.3);
      expect(tester.takeException(), isNull);
      expect(find.text(hint), findsOneWidget);
      await reveal(tester, find.byKey(const ValueKey('stop.phase')));
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const Key('stopButton')));
      await tester.pump();
      expect(session.methods.last, 'stop');
    });
  }
}
