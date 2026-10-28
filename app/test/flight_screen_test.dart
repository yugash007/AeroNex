import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show SemanticsAction;
import 'package:flutter/semantics.dart' show CustomSemanticsAction;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/app_shell.dart';
import 'package:aeronex/features/diagnostics/presentation/diagnostics_screen.dart';
import 'package:aeronex/features/flight/domain/flight_command.dart';
import 'package:aeronex/features/flight/domain/flight_plan.dart';
import 'package:aeronex/features/flight/presentation/flight_screen.dart';
import 'package:aeronex/features/flight/presentation/widgets/command_input.dart';
import 'package:aeronex/features/flight/presentation/widgets/status_line.dart';
import 'package:aeronex/features/flight/presentation/widgets/stop_button.dart';
import 'package:aeronex/features/link/data/link_settings.dart';
import 'package:aeronex/features/link/presentation/link_setup_screen.dart';
import 'package:aeronex/features/models/presentation/models_screen.dart';
import 'package:aeronex/features/session/session_api.dart';
import 'package:aeronex/main.dart' show AeroNexApp;

import 'support/fake_session_api.dart';

const _saved = LinkSettings(
    link: LinkConfig(host: '192.168.0.5', remotePort: 14550, localPort: 14551));

/// The v0.2.0 operator message for a move while landed.
const _onGround = 'The drone is on the ground. Start with a takeoff height, '
    'for example: take off to 3 m and fly forward 3 m.';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  // aeronex/inference: model management for the Models screen (speech runs in
  // the session service since T12).
  late List<FlightDestination> opened;

  setUp(() {
    opened = [];
    messenger.setMockMethodCallHandler(const MethodChannel('aeronex/inference'),
        (call) async => call.method == 'status'
            ? {
                'models': {'vosk-te': true, 'vosk-en-in': true},
                'modelStates': {'vosk-te': 'ready', 'vosk-en-in': 'ready'},
                'speechRuntimeErrors': {},
              }
            : null);
  });
  tearDown(() => messenger.setMockMethodCallHandler(
      const MethodChannel('aeronex/inference'), null));

  Future<FakeSessionApi> launch(WidgetTester tester,
      {SessionSnapshot? initial,
      FakeSessionApi? session,
      LinkSettingsStore? store,
      Size size = const Size(480, 1800),
      double textScale = 1.0}) async {
    final fake = session ?? FakeSessionApi(initial);
    addTearDown(fake.close);
    await tester.binding.setSurfaceSize(size);
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
        // As AeroNexApp: STOP sits above the navigator, so above every route.
        builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(textScale)),
            child: StopLane(session: fake, child: child!)),
        home: FlightScreen(
            session: fake,
            settings: store ?? MemoryLinkSettingsStore(_saved),
            open: (destination) async => opened.add(destination))));
    await tester.pumpAndSettle();
    return fake;
  }

  Finder stopButton() => find.byKey(const Key('stopButton'));
  Finder planCard() => find.byKey(const Key('planCard'));
  Finder inPlan(Finder finder) => find.descendant(of: planCard(), matching: finder);
  Finder chip(String label) =>
      find.descendant(of: find.byKey(const Key('statusChips')), matching: find.text(label));
  String statusLine(WidgetTester tester) =>
      tester.widget<Text>(find.byKey(const Key('statusLine'))).data!;

  Future<void> type(WidgetTester tester, String text) async {
    await tester.enterText(find.widgetWithText(TextField, 'Type a command'), text);
    await tester.testTextInput.receiveAction(TextInputAction.go);
    await tester.pumpAndSettle();
  }

  /// A plan as the service's parser would build it from [calls].
  FlightPlan plan(List<Map<String, Object?>> calls) => FlightPlan([
        for (final call in calls)
          PlanStep(FlightCommand.fromToolCalls([call]), source: StepSource.rules),
      ]);

  Finder pttBar() => find.byKey(const Key('pttBar'));

  /// Holds the PTT bar; the returned gesture releases it.
  Future<TestGesture> hold(WidgetTester tester) async {
    await tester.ensureVisible(pttBar());
    await tester.pump();
    final gesture = await tester.startGesture(tester.getCenter(pttBar()));
    await tester.pump();
    return gesture;
  }

  Future<void> talk(WidgetTester tester) async {
    final gesture = await hold(tester);
    await gesture.up();
    await tester.pumpAndSettle();
  }

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.ensureVisible(find.text(text).last);
    await tester.pump();
    await tester.tap(find.text(text).last);
    await tester.pumpAndSettle();
  }

  group('STOP', () {
    testWidgets('is pinned above the page, enabled while disconnected, and a '
        'press calls the service at once', (tester) async {
      final session = await launch(tester);
      expect(stopButton().hitTestable(), findsOneWidget);
      expect(find.descendant(of: stopButton(), matching: find.text('STOP')),
          findsOneWidget);
      // Scrolling the page never moves STOP away.
      await tester.drag(find.byType(Scrollable).first, const Offset(0, -2000));
      await tester.pumpAndSettle();
      expect(stopButton().hitTestable(), findsOneWidget);
      // Sent on the press itself, before the finger lifts; no confirmation.
      final press = await tester.startGesture(tester.getCenter(stopButton()));
      await tester.pump();
      expect(session.methods.last, 'stop');
      expect(session.calls.last.$2, {'heldOneSecond': false});
      await press.up();
      await tester.pumpAndSettle();
      expect(session.argumentsOf('stop'), [
        {'heldOneSecond': false}
      ]);
      expect(session.methods, isNot(contains('confirm')));
    });

    testWidgets('stays enabled while a plan runs and needs no confirmation',
        (tester) async {
      final session = await launch(tester, initial: fixture('executing_move'));
      expect(find.text('Pilot has control — hold 1 s to override'), findsNothing);
      // An app-owned flight: holding never turns into an override.
      final press = await tester.startGesture(tester.getCenter(stopButton()));
      await tester.pump(const Duration(milliseconds: 1500));
      await press.up();
      await tester.pumpAndSettle();
      expect(session.argumentsOf('stop'), [
        {'heldOneSecond': false}
      ]);
    });

    testWidgets('a pilot-owned mode needs a 1 s hold with visible progress to '
        'override', (tester) async {
      final session = await launch(tester,
          initial: fixture('connected_landed', {
            'vehicle': {'armed': true, 'landedState': 2, 'landedStateName': 'IN_AIR'}
          }));
      expect(find.text('Pilot has control — hold 1 s to override'),
          findsOneWidget);
      // A tap is a plain STOP: the service sends nothing and says use RC.
      await tester.tap(stopButton());
      await tester.pumpAndSettle();
      expect(session.argumentsOf('stop'), [
        {'heldOneSecond': false}
      ]);
      double fill() => tester
          .widget<FractionallySizedBox>(find.byKey(const Key('stopHoldFill')))
          .widthFactor!;
      // Released early: no override.
      var press = await tester.startGesture(tester.getCenter(stopButton()));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      expect(fill(), closeTo(0.5, 0.1));
      await press.up();
      await tester.pumpAndSettle();
      expect(fill(), 0);
      expect(session.argumentsOf('stop'), [
        {'heldOneSecond': false},
        {'heldOneSecond': false}
      ]);
      // Held 1 s: the override goes out once, while still held.
      press = await tester.startGesture(tester.getCenter(stopButton()));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 1100));
      expect(session.methods.last, 'stop');
      expect(session.calls.last.$2, {'heldOneSecond': true});
      await tester.pump(const Duration(milliseconds: 1000));
      await press.up();
      await tester.pumpAndSettle();
      expect(
          session.argumentsOf('stop').where((a) => (a as Map)['heldOneSecond'] == true),
          hasLength(1));
    });

    testWidgets('the service asking for the hold shows it too', (tester) async {
      await launch(tester,
          initial: fixture('stop_not_confirmed', {
            'vehicle': {'modeOwner': 'APP', 'modeOwnerLabel': null},
            'stop': {'modeOwnerLabel': 'Pilot mode'},
          }));
      expect(find.text('Pilot has control — hold 1 s to override'),
          findsOneWidget);
    });

    testWidgets('the hint says when an autopilot failsafe has control',
        (tester) async {
      final session = await launch(tester,
          initial: fixture('stop_not_confirmed', {
            'stop': {'alarm': false}
          }));
      expect(find.text('Autopilot failsafe active — hold 1 s to override'),
          findsOneWidget);
      expect(find.text('Pilot has control — hold 1 s to override'), findsNothing);
      // The vehicle's owner now wins over the label of the last STOP.
      session.pushFixture('stop_not_confirmed', {
        'vehicle': {'modeOwnerLabel': 'Pilot mode'},
        'stop': {'alarm': false},
      });
      await tester.pumpAndSettle();
      expect(find.text('Pilot has control — hold 1 s to override'),
          findsOneWidget);
    });

    testWidgets('a disarmed vehicle in a pilot mode shows no hold: STOP sends '
        'nothing there; unknown arming keeps the hold', (tester) async {
      // Freshly connected: the pilot's mode, landed and disarmed (Kotlin sets
      // needsHoldForExternal only when armed).
      final session = await launch(tester, initial: fixture('connected_landed'));
      expect(session.current.vehicle.external, isTrue);
      expect(session.current.vehicle.armed, isFalse);
      expect(find.textContaining('hold 1 s to override'), findsNothing);
      final press = await tester.startGesture(tester.getCenter(stopButton()));
      await tester.pump(const Duration(milliseconds: 1500));
      await press.up();
      await tester.pumpAndSettle();
      expect(session.argumentsOf('stop'), [
        {'heldOneSecond': false}
      ]);
      // Arming not known yet: keep the hold, as the safe side.
      session.pushFixture('connected_landed', {
        'vehicle': {'armed': null}
      });
      await tester.pumpAndSettle();
      expect(find.text('Pilot has control — hold 1 s to override'),
          findsOneWidget);
    });

    for (final (label, hint) in [
      ('Pilot mode', 'Pilot has control — hold 1 s to override'),
      ('Autopilot failsafe', 'Autopilot failsafe active — hold 1 s to override'),
    ]) {
      testWidgets('the hold hint fits a 5-inch phone with large text ($label)',
          (tester) async {
        final session = await launch(tester,
            initial: fixture('connected_landed', {
              'vehicle': {
                'armed': true,
                'landedState': 2,
                'landedStateName': 'IN_AIR',
                'modeOwnerLabel': label,
              }
            }),
            size: const Size(360, 640),
            textScale: 1.3);
        expect(tester.takeException(), isNull);
        expect(find.text(hint), findsOneWidget);
        // The whole hint is inside the button.
        final button = tester.getRect(stopButton()).inflate(0.5);
        final text = tester.getRect(find.text(hint));
        expect(button.contains(text.topLeft) && button.contains(text.bottomRight),
            isTrue);
        expect(stopButton().hitTestable(), findsOneWidget);
        // The page under STOP still reaches the tap pad.
        await tester.scrollUntilVisible(find.text('Land'), 200,
            scrollable: find.byType(Scrollable).first);
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.tap(stopButton());
        await tester.pump();
        expect(session.methods.last, 'stop');
      });
    }

    testWidgets('stays on top of dialogs, menus and sheets', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      Future<void> pressStop() async {
        expect(stopButton().hitTestable(), findsOneWidget);
        final before = session.argumentsOf('stop').length;
        await tester.tap(stopButton());
        await tester.pump();
        expect(session.argumentsOf('stop'), hasLength(before + 1));
      }

      await tapText(tester, 'Take off');
      expect(find.byType(AlertDialog), findsOneWidget);
      await pressStop();
      await tester.tap(find.descendant(
          of: find.byType(AlertDialog), matching: find.text('Cancel')));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      expect(find.text('Diagnostics'), findsOneWidget);
      await pressStop();
      expect(session.methods, isNot(contains('submitPlan')));
    });

    testWidgets('is a button in the accessibility tree, over dialogs too',
        (tester) async {
      final semantics = tester.ensureSemantics();
      final session = await launch(tester, initial: fixture('connected_landed'));
      Future<void> tapBySemantics() async {
        final node = tester.getSemantics(find.bySemanticsLabel(RegExp('^STOP')));
        expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
        // Its own node, the size of the button: not merged into the screen.
        expect(node.rect.size, tester.getSize(stopButton()));
        // As TalkBack's double tap: the semantics action, not a pointer.
        tester.semantics.tap(find.semantics.byLabel(RegExp('^STOP')));
        await tester.pump();
        expect(session.methods.last, 'stop');
      }

      await tapBySemantics();
      await tapText(tester, 'Take off');
      expect(find.byType(AlertDialog), findsOneWidget);
      await tapBySemantics();
      semantics.dispose();
    });

    testWidgets('STOP NOT CONFIRMED also vibrates until Got it, and again for '
        'a new alarm', (tester) async {
      var buzzes = 0;
      messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'HapticFeedback.vibrate') buzzes++;
        return null;
      });
      addTearDown(
          () => messenger.setMockMethodCallHandler(SystemChannels.platform, null));
      final session = await launch(tester, initial: fixture('stop_not_confirmed'));
      expect(buzzes, greaterThanOrEqualTo(1));
      var seen = buzzes;
      await tester.pump(const Duration(seconds: 3));
      expect(buzzes, greaterThanOrEqualTo(seen + 2));
      await tester.tap(find.text('Got it'));
      await tester.pump();
      seen = buzzes;
      await tester.pump(const Duration(seconds: 5));
      expect(buzzes, seen);
      session.pushFixture('stop_not_confirmed', {
        'stop': {'id': 58}
      });
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      expect(buzzes, greaterThan(seen));
      // The service cleared it: quiet without Got it.
      session.pushFixture('stop_not_confirmed', {
        'stop': {'id': 58, 'alarm': false}
      });
      await tester.pump();
      seen = buzzes;
      await tester.pump(const Duration(seconds: 5));
      expect(buzzes, seen);
    });

    testWidgets('STOP NOT CONFIRMED is a red alarm with the owner label until '
        'Got it', (tester) async {
      final session = await launch(tester, initial: fixture('stop_not_confirmed'));
      final alarm = find.byKey(const Key('stopAlarm'));
      expect(alarm, findsOneWidget);
      expect(
          find.descendant(
              of: alarm,
              matching: find.text('STOP NOT CONFIRMED: flight mode changed to '
                  'LAND outside this app, use RC')),
          findsOneWidget);
      expect(find.descendant(of: alarm, matching: find.text('Autopilot failsafe')),
          findsOneWidget);
      expect(tester.widget<Material>(find.descendant(
              of: alarm, matching: find.byType(Material)).first).color,
          Theme.of(tester.element(alarm)).colorScheme.error);
      await tester.tap(find.descendant(of: alarm, matching: find.text('Got it')));
      await tester.pumpAndSettle();
      expect(session.methods, contains('acknowledgeAlarm'));
      // The banner goes when the service clears the alarm.
      session.pushFixture('stop_not_confirmed', {
        'stop': {'alarm': false}
      });
      await tester.pumpAndSettle();
      expect(alarm, findsNothing);
    });

    testWidgets('the STOP result is shown beside STOP', (tester) async {
      final session = await launch(tester, initial: fixture('executing_move'));
      await tester.tap(stopButton());
      session.pushFixture('executing_move', {
        'run': null,
        'stop': {
          'id': 3,
          'phase': 'CONFIRMED',
          'trigger': 'BUTTON',
          'action': 'BRAKE',
          'message': 'STOP confirmed: BRAKE',
          'alarm': false,
          'override': false,
          'needsHoldForExternal': false,
          'modeOwnerLabel': null,
        }
      });
      await tester.pump();
      expect(find.byKey(const Key('stopMessage')), findsOneWidget);
      expect(find.text('STOP confirmed: BRAKE'), findsOneWidget);
      await tester.pump(const Duration(seconds: 10));
      expect(find.text('STOP confirmed: BRAKE'), findsNothing);
    });

    testWidgets('a STOP that cannot reach the service says to use RC',
        (tester) async {
      final session = await launch(tester);
      session.stopError = MissingPluginException('no service');
      await tester.tap(stopButton());
      await tester.pumpAndSettle();
      expect(find.text('STOP did not reach the flight service. Use RC.'),
          findsOneWidget);
    });
  });

  group('status line', () {
    testWidgets('disconnected: Not connected and Dry run', (tester) async {
      await launch(tester);
      expect(statusLine(tester), 'Not connected');
      expect(chip('Not connected'), findsOneWidget);
      expect(chip('Dry run'), findsOneWidget);
      expect(chip('Unsigned link'), findsNothing);
      expect(find.text('Connect'), findsOneWidget);
    });

    testWidgets('connected: connection, mode with its owner, altitude, battery',
        (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      expect(statusLine(tester),
          'Connected · Stabilize (Pilot mode) · Alt 0.0 m · Battery 100%');
      expect(chip('Dry run'), findsOneWidget);
      expect(chip('Unsigned link'), findsOneWidget);
      expect(chip('Not connected'), findsNothing);
      expect(chip('Radio profile'), findsNothing);
      expect(find.text('Disconnect'), findsOneWidget);
      session.pushFixture('executing_move');
      await tester.pumpAndSettle();
      expect(statusLine(tester),
          'Connected · Guided (this app) · Alt 3.0 m · Battery 87%');
      expect(chip('Dry run'), findsNothing);
    });

    testWidgets('radio profile, stale telemetry and a missing service chip',
        (tester) async {
      await launch(tester,
          initial: fixture('connected_landed', {
            'link': {'profile': 'radio', 'foregroundService': false},
            'vehicle': {
              'ages': {'globalPosition': 5500, 'sysStatus': 400}
            },
          }));
      expect(chip('Radio profile'), findsOneWidget);
      expect(chip('Telemetry stale'), findsOneWidget);
      expect(chip('Keep this app open'), findsOneWidget);
      // A stale value is never shown as current.
      expect(statusLine(tester),
          'Connected · Stabilize (Pilot mode) · Battery 100%');
    });

    test('one lost frame on the radio profile is not stale; 3 s on Wi-Fi is',
        () {
      SessionSnapshot aged(String profile, int ms) =>
          fixture('connected_landed', {
            'link': {'profile': profile},
            'vehicle': {
              'ages': {'globalPosition': ms, 'sysStatus': ms, 'ekfStatus': ms}
            },
          });
      const all = 'Connected · Stabilize (Pilot mode) · Alt 0.0 m · Battery 100%';
      const none = 'Connected · Stabilize (Pilot mode)';
      // RADIO streams these at 0.5 Hz: after one lost frame the value is ~4 s old.
      expect(statusChips(aged('radio', 4200)), isNot(contains('Telemetry stale')));
      expect(statusText(aged('radio', 4200)), all);
      expect(statusChips(aged('radio', 5000)), contains('Telemetry stale'));
      expect(statusText(aged('radio', 5000)), none);
      expect(statusChips(aged('wifi', 2900)), isNot(contains('Telemetry stale')));
      expect(statusChips(aged('wifi', 3000)), contains('Telemetry stale'));
      expect(statusText(aged('wifi', 3000)), none);
      // The 1 Hz landed state keeps the 3 s window on the radio too.
      expect(
          statusChips(fixture('connected_landed', {
            'link': {'profile': 'radio'},
            'vehicle': {
              'ages': {'extendedSysState': 3000}
            },
          })),
          contains('Telemetry stale'));
    });

    testWidgets('a session without a heartbeat waits for the drone',
        (tester) async {
      await launch(tester,
          initial: fixture('connected_landed', {
            'link': {'state': 'connecting', 'heartbeatAgeMs': null},
            'vehicle': {'connected': false, 'modeOwner': null, 'modeOwnerLabel': null},
          }));
      expect(statusLine(tester), 'Waiting for the drone…');
      expect(chip('Not connected'), findsOneWidget);
      expect(find.text('Disconnect'), findsOneWidget);
    });

    test('a hair below the ground reads 0.0; unreported values are left out; '
        'unhealthy sensors are a chip', () {
      expect(
          statusText(fixture('connected_landed', {
            'vehicle': {'altitudeRelM': -0.03}
          })),
          'Connected · Stabilize (Pilot mode) · Alt 0.0 m · Battery 100%');
      // A heartbeat alone, before any telemetry stream.
      final bare = fixture('connected_landed', {
        'vehicle': {
          'modeOwner': null,
          'modeOwnerLabel': null,
          'altitudeRelM': null,
          'batteryPercent': null,
          'ages': {'globalPosition': null, 'sysStatus': null},
        }
      });
      expect(statusText(bare), 'Connected · Stabilize');
      expect(statusChips(bare), isNot(contains('Telemetry stale')));
      expect(
          statusChips(fixture('connected_landed', {
            'vehicle': {'healthy': false}
          })),
          contains('Sensors not healthy'));
      expect(statusChips(fixture('connected_landed')),
          isNot(contains('Sensors not healthy')));
    });

    test('modes read as words; unknown ones as given', () {
      expect(modeLabel('ALT_HOLD'), 'Altitude hold');
      expect(modeLabel('RTL'), 'Return home');
      expect(modeLabel('BRAKE'), 'Brake');
      expect(modeLabel('MODE 13'), 'MODE 13');
    });
  });

  group('plan', () {
    testWidgets('a typed command goes to the service parser in the selected '
        'language; the card shows its steps, source, countdown and one large '
        'CONFIRM', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      const text = 'hey drone fly go 3 meters forward and move 5 meters right';
      session.textPlans[text] = plan([
        {'name': 'move_relative', 'arguments': {'direction': 'forward', 'distance_m': 3}},
        {'name': 'move_relative', 'arguments': {'direction': 'right', 'distance_m': 5}},
      ]);
      await type(tester, text);
      expect(session.argumentsOf('submitText').single,
          {'text': text, 'language': 'en', 'source': 'typed'});
      expect(session.methods, isNot(contains('submitPlan')));
      expect(inPlan(find.text('Confirm this plan')), findsOneWidget);
      expect(inPlan(find.text('1. Move forward 3 m')), findsOneWidget);
      expect(inPlan(find.text('2. Move right 5 m')), findsOneWidget);
      expect(inPlan(find.text('Typed')), findsOneWidget);
      expect(inPlan(find.text('Expires in 15 s')), findsOneWidget);
      final confirm = find.byKey(const Key('confirmButton'));
      expect(find.descendant(of: confirm, matching: find.text('CONFIRM (dry run)')),
          findsOneWidget);
      expect(tester.getSize(confirm).height, greaterThanOrEqualTo(64));
      // Nothing was confirmed on the operator's behalf.
      expect(session.methods, isNot(contains('confirm')));
    });

    testWidgets('the command field is locked while it is being sent, so the '
        'waiting plan always matches the text shown', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      TextField field() =>
          tester.widget<TextField>(find.widgetWithText(TextField, 'Type a command'));
      final gate = session.submitGate = Completer<void>();
      await tester.enterText(
          find.widgetWithText(TextField, 'Type a command'), 'take off to 3 metres');
      await tester.testTextInput.receiveAction(TextInputAction.go);
      await tester.pump();
      expect(session.methods, contains('submitText'));
      expect(field().enabled, isFalse);
      gate.complete();
      await tester.pumpAndSettle();
      expect(field().enabled, isTrue);
      expect(field().controller!.text, 'take off to 3 metres');
      expect(planCard(), findsOneWidget);
      expect(session.methods, isNot(contains('cancel')));
      // The tap pad's plans lock it too.
      session.submitGate = Completer<void>();
      await tapText(tester, 'Land');
      await tester.pump();
      expect(field().enabled, isFalse);
      session.submitGate!.complete();
      await tester.pumpAndSettle();
      expect(field().enabled, isTrue);
    });

    testWidgets('CONFIRM sends the token once', (tester) async {
      final session = await launch(tester,
          initial: fixture('connected_landed', {
            'execution': {'enabled': true}
          }));
      await type(tester, 'take off to 3 metres');
      expect(find.text('CONFIRM'), findsOneWidget);
      await tester.tap(find.byKey(const Key('confirmButton')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirmButton')), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(session.argumentsOf('confirm'), [
        {'token': 'token-1'}
      ]);
    });

    testWidgets('confirm after expiry is refused: CONFIRM goes and the reason '
        'is shown', (tester) async {
      final session = await launch(tester);
      await type(tester, 'land');
      await tester.pump(const Duration(seconds: 5));
      expect(inPlan(find.text('Expires in 10 s')), findsOneWidget);
      await tester.pump(const Duration(seconds: 10));
      expect(inPlan(find.text('Expired. Say or tap the command again.')),
          findsOneWidget);
      expect(
          tester
              .widget<FilledButton>(find.descendant(
                  of: find.byKey(const Key('confirmButton')),
                  matching: find.byType(FilledButton)))
              .onPressed,
          isNull);
      expect(session.methods, isNot(contains('confirm')));
      // The service is the judge: its refusal is shown verbatim.
      session.pushFixture('pending_plan');
      session.confirmResult = const ConfirmResult(
          false, 'The confirmation expired. Say the command again.');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirmButton')));
      await tester.pumpAndSettle();
      expect(find.text('The confirmation expired. Say the command again.'),
          findsOneWidget);
    });

    testWidgets('a refused plan shows the service reason verbatim, no card',
        (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      session.submitResult = const RejectedResult(_onGround);
      await type(tester, 'move forward 3 metres');
      expect(find.byKey(const Key('planError')), findsOneWidget);
      expect(find.text(_onGround), findsOneWidget);
      expect(planCard(), findsNothing);
    });

    testWidgets('a clause the service parser does not read is refused with '
        'its message: there is no AI fallback', (tester) async {
      final session = await launch(tester);
      const refusal = 'Step 1 ("climb to 3 meters"): not understood; say it again '
          'with the supported words, for example: move forward 3 m.';
      session.submitResult = const RejectedResult(refusal);
      await type(tester, 'climb to 3 meters then move forward 2 meters');
      expect(find.text(refusal), findsOneWidget);
      expect(planCard(), findsNothing);
      expect(session.methods, isNot(contains('submitPlan')));
    });

    testWidgets('Cancel on a waiting plan drops it through the service',
        (tester) async {
      final session = await launch(tester);
      await type(tester, 'land');
      await tester.tap(inPlan(find.text('Cancel')));
      await tester.pumpAndSettle();
      expect(session.methods.last, 'cancel');
    });

    testWidgets('editing the command drops a plan that was not confirmed',
        (tester) async {
      final session = await launch(tester);
      await type(tester, 'take off to 3m');
      await tester.enterText(find.widgetWithText(TextField, 'Type a command'), 'land');
      await tester.pump();
      expect(session.methods.last, 'cancel');
      // One cancel per plan, however many keystrokes follow.
      await tester.enterText(find.widgetWithText(TextField, 'Type a command'), 'lan');
      await tester.pump();
      expect(session.methods.where((m) => m == 'cancel'), hasLength(1));
      // Once confirmed, an edit never cancels (that would be a STOP).
      await type(tester, 'take off to 3m');
      await tester.tap(find.byKey(const Key('confirmButton')));
      await tester.pumpAndSettle();
      final count = session.methods.where((m) => m == 'cancel').length;
      await tester.enterText(find.widgetWithText(TextField, 'Type a command'), 'x');
      await tester.pump();
      expect(session.methods.where((m) => m == 'cancel'), hasLength(count));
    });

    group('new input drops a plan that was not confirmed (S2)', () {
      int cancels(FakeSessionApi session) =>
          session.methods.where((m) => m == 'cancel').length;

      Future<FakeSessionApi> waitingLand(WidgetTester tester) async {
        final session = await launch(tester, initial: fixture('connected_landed'));
        await tapText(tester, 'Land');
        expect(find.byKey(const Key('confirmButton')), findsOneWidget);
        return session;
      }

      testWidgets('Talk is new input: the service drops the waiting plan, the '
          'screen sends no cancel of its own', (tester) async {
        final session = await waitingLand(tester);
        final gesture = await hold(tester);
        expect(session.methods.last, 'pttDown');
        // The service invalidated the plan (Kotlin pttDown) and says so.
        session.pushFixture('connected_landed', {
          'notice': 'Plan cancelled.',
          'speech': {'state': 'LISTENING', 'pttHeld': true}
        });
        await tester.pump();
        expect(find.byKey(const Key('confirmButton')), findsNothing);
        await gesture.up();
        await tester.pumpAndSettle();
        expect(session.methods.last, 'pttUp');
        expect(cancels(session), 0);
      });

      testWidgets('a typed command refused by the service still drops it',
          (tester) async {
        final session = await launch(tester, initial: fixture('connected_landed'));
        const refusal = 'Step 1 ("climb to 3 meters"): not understood.';
        session.submitResult = const RejectedResult(refusal);
        await type(tester, 'climb to 3 meters');
        expect(find.text(refusal), findsOneWidget);
        session.submitResult = null;
        await tapText(tester, 'Land');
        expect(find.byKey(const Key('confirmButton')), findsOneWidget);
        // Sent again unchanged: no keystroke, only the send is new input.
        session.submitResult = const RejectedResult(refusal);
        await tester.ensureVisible(find.byTooltip('Send'));
        await tester.tap(find.byTooltip('Send'));
        await tester.pumpAndSettle();
        expect(cancels(session), 1);
        expect(find.byKey(const Key('confirmButton')), findsNothing);
        expect(find.text(refusal), findsOneWidget);
        expect(session.argumentsOf('submitText'), hasLength(2));
      });

      testWidgets('after CONFIRM, new input never cancels: during a run that '
          'would be a STOP', (tester) async {
        final session = await waitingLand(tester);
        await tester.tap(find.byKey(const Key('confirmButton')));
        await tester.pumpAndSettle();
        // The confirmed plan still shows (its next snapshot is late) …
        await talk(tester);
        await type(tester, 'climb to 5 meters');
        expect(session.methods, isNot(contains('cancel')));
        // … and once it runs.
        session.pushFixture('executing_move');
        await tester.pumpAndSettle();
        await talk(tester);
        await type(tester, 'climb to 4 meters');
        expect(session.methods, isNot(contains('cancel')));
        expect(session.methods.where((m) => m == 'pttDown'), hasLength(2));
      });
    });

    testWidgets('a running plan shows each step and Cancel, which is STOP',
        (tester) async {
      final session = await launch(tester, initial: fixture('executing_move'));
      expect(inPlan(find.text('Running')), findsOneWidget);
      expect(inPlan(find.text('Step 1 of 2')), findsOneWidget);
      expect(inPlan(find.text('Phone')), findsOneWidget);
      expect(inPlan(find.text('1. Move forward 2 m')), findsOneWidget);
      expect(inPlan(find.text('2. Land')), findsOneWidget);
      expect(find.byKey(const Key('confirmButton')), findsNothing);
      await tester.tap(find.byKey(const Key('cancelRun')));
      await tester.pumpAndSettle();
      expect(session.methods.last, 'cancel');
      expect(session.methods, isNot(contains('disconnect')));
    });

    testWidgets('a finished plan keeps each outcome without tool-call text',
        (tester) async {
      await launch(tester,
          initial: fixture('executing_move', {
            'run': {
              'phase': 'FAILED',
              'summary': '1. Take off to 3 m; 2. Move forward 2 m; 3. Land',
              'stepIndex': 3,
              'stepCount': 3,
              'outcomes': [
                {
                  'step': 'Take off to 3 m',
                  'outcome': 'COMPLETED',
                  'message': 'COMPLETED — confirmed by simulator telemetry: takeoff'
                },
                {
                  'step': 'Move forward 2 m',
                  'outcome': 'UNKNOWN',
                  'message': 'UNKNOWN — interrupted by STOP; check the '
                      'simulator before sending another command.'
                },
                {'step': 'Land', 'outcome': 'SKIPPED', 'message': 'SKIPPED — not sent'},
              ],
              'message': 'done',
            }
          }));
      expect(inPlan(find.text('Last plan')), findsOneWidget);
      expect(inPlan(find.text('COMPLETED — confirmed by simulator telemetry')),
          findsOneWidget);
      expect(inPlan(find.textContaining('UNKNOWN — interrupted by STOP')),
          findsOneWidget);
      expect(inPlan(find.text('SKIPPED — not sent')), findsOneWidget);
      expect(inPlan(find.byIcon(Icons.warning_amber)), findsOneWidget);
      expect(find.byKey(const Key('cancelRun')), findsNothing);
    });

    testWidgets('a dry run reads NOT SENT per step', (tester) async {
      await launch(tester,
          initial: fixture('idle_disconnected', {
            'run': {
              'phase': 'FINISHED',
              'source': 'tap',
              'summary': '1. Land',
              'stepIndex': 1,
              'stepCount': 1,
              'outcomes': [
                {
                  'step': 'Land',
                  'outcome': 'NOT_SENT',
                  'message': 'NOT SENT — dry run: land({})'
                }
              ],
              'message': 'NOT SENT — dry run: land({})',
            }
          }));
      expect(inPlan(find.text('NOT SENT — dry run')), findsOneWidget);
      expect(find.textContaining('land({})'), findsNothing);
    });

    testWidgets('why a waiting plan went away is shown', (tester) async {
      await launch(tester,
          initial: fixture('connected_landed', {
            'notice': 'The app went to the background. Say the command again.'
          }));
      expect(find.text('The app went to the background. Say the command again.'),
          findsOneWidget);
    });

    testWidgets('an older snapshot never replaces a newer one', (tester) async {
      final session = await launch(tester);
      session.push(fixture('executing_move', {'seq': 500}));
      await tester.pumpAndSettle();
      expect(inPlan(find.text('Running')), findsOneWidget);
      session.push(fixture('idle_disconnected', {'seq': 400}));
      await tester.pumpAndSettle();
      expect(inPlan(find.text('Running')), findsOneWidget);
    });

    testWidgets('a five-step plan fits a small phone with large text, STOP '
        'stays on screen', (tester) async {
      final session = await launch(tester,
          size: const Size(360, 780), textScale: 1.3);
      const text = 'take off to 3 metres, move forward 2 metres, move right 5 '
          'metres, move up 1 metre and land';
      session.textPlans[text] = plan([
        {'name': 'takeoff', 'arguments': {'altitude_m': 3}},
        {'name': 'move_relative', 'arguments': {'direction': 'forward', 'distance_m': 2}},
        {'name': 'move_relative', 'arguments': {'direction': 'right', 'distance_m': 5}},
        {'name': 'move_relative', 'arguments': {'direction': 'up', 'distance_m': 1}},
        {'name': 'land', 'arguments': <String, Object?>{}},
      ]);
      await type(tester, text);
      expect(session.methods, contains('submitText'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      final confirm = find.byKey(const Key('confirmButton'));
      // The new plan scrolled its CONFIRM into view.
      expect(confirm.hitTestable(), findsOneWidget);
      expect(inPlan(find.text('5. Land')), findsOneWidget);
      expect(confirm.hitTestable(), findsOneWidget);
      expect(stopButton().hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  testWidgets('a refused plan brings its whole reason on screen on a small phone',
      (tester) async {
    final session = await launch(tester,
        initial: fixture('connected_landed'), size: const Size(320, 560));
    session.submitResult = const RejectedResult(_onGround);
    await tester.enterText(
        find.widgetWithText(TextField, 'Type a command'), 'move forward 3 metres');
    await tester.pumpAndSettle();
    await tester.testTextInput.receiveAction(TextInputAction.go);
    await tester.pumpAndSettle();
    expect(find.text(_onGround).hitTestable(), findsOneWidget);
    final view = tester.getRect(find.byType(Scrollable).first);
    final card = tester.getRect(find.byKey(const Key('planError')));
    expect(card.top >= view.top && card.bottom <= view.bottom, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('test-fixture steps are labelled, never passed off as rules',
      (tester) async {
    await launch(tester,
        initial: fixture('pending_plan', {
          'pending': {
            'steps': [
              {
                'name': 'land',
                'arguments': <String, Object?>{},
                'source': 'fixture',
                'description': 'Land'
              }
            ]
          }
        }));
    expect(inPlan(find.text('1. Land')), findsOneWidget);
    expect(inPlan(find.text('Test')), findsOneWidget);
  });

  testWidgets('the screen reads STOP, status, plan, command, then the pad',
      (tester) async {
    await launch(tester, initial: fixture('executing_move'));
    final tops = [
      stopButton(),
      find.byKey(const Key('statusLine')),
      planCard(),
      pttBar(),
      find.widgetWithText(TextField, 'Type a command'),
      find.byKey(const Key('tapPad')),
    ].map((f) => tester.getTopLeft(f).dy).toList();
    expect(tops, [...tops]..sort());
    expect(tops.toSet(), hasLength(tops.length));
  });

  testWidgets('leaving the app neither stops nor cancels: the service keeps '
      'the run and its record (S7)', (tester) async {
    final session = await launch(tester, initial: fixture('executing_move'));
    final before = session.calls.length;
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      tester.binding.handleAppLifecycleStateChanged(state);
      await tester.pump();
    }
    await tester.pumpAndSettle();
    final after = session.methods.skip(before);
    expect(after, isNot(contains('cancel')));
    expect(after, isNot(contains('stop')));
    expect(after, isNot(contains('disconnect')));
    // Back in front, it reads the service again.
    expect(after, contains('getSnapshot'));
    expect(inPlan(find.text('Running')), findsOneWidget);
  });

  group('tap pad', () {
    testWidgets('builds the parser\'s tool call with source tap; a move waits '
        'for CONFIRM', (tester) async {
      final session = await launch(tester);
      expect(find.text('2 m'), findsOneWidget);
      await tester.ensureVisible(find.byTooltip('Longer'));
      await tester.tap(find.byTooltip('Longer'));
      await tester.pump();
      expect(find.text('3 m'), findsOneWidget);
      await tapText(tester, 'Forward');
      final request = session.argumentsOf('submitPlan').single as Map;
      expect(request['source'], 'tap');
      // The tool call the service parser makes of "move forward 3 meters".
      expect(request['steps'], [
        {
          'name': 'move_relative',
          'arguments': {'direction': 'forward', 'distance_m': 3},
          'source': 'rules'
        }
      ]);
      expect(inPlan(find.text('Tap')), findsOneWidget);
      expect(find.byKey(const Key('confirmButton')), findsOneWidget);
      expect(session.methods, isNot(contains('confirm')));
      for (final (label, direction) in [
        ('Back', 'backward'),
        ('Left', 'left'),
        ('Right', 'right'),
        ('Up', 'up'),
        ('Down', 'down'),
      ]) {
        await tapText(tester, label);
        final steps = (session.argumentsOf('submitPlan').last as Map)['steps'] as List;
        expect((steps.single as Map)['arguments'],
            {'direction': direction, 'distance_m': 3});
      }
      await tapText(tester, 'Land');
      expect((session.argumentsOf('submitPlan').last as Map)['steps'], [
        {'name': 'land', 'arguments': <String, Object?>{}, 'source': 'rules'}
      ]);
    });

    testWidgets('the distance stays within 1–10 m', (tester) async {
      await launch(tester);
      for (var i = 0; i < 12; i++) {
        await tester.tap(find.byTooltip('Longer'));
      }
      await tester.pump();
      expect(find.text('10 m'), findsOneWidget);
      for (var i = 0; i < 12; i++) {
        await tester.tap(find.byTooltip('Shorter'));
      }
      await tester.pump();
      expect(find.text('1 m'), findsOneWidget);
    });

    testWidgets('Take off asks for the height first', (tester) async {
      final session = await launch(tester);
      await tapText(tester, 'Take off');
      final dialog = find.byType(AlertDialog);
      expect(find.descendant(of: dialog, matching: find.text('Take off to what height?')),
          findsOneWidget);
      expect(find.descendant(of: dialog, matching: find.text('3 m')), findsOneWidget);
      await tester.tap(find.descendant(of: dialog, matching: find.text('Cancel')));
      await tester.pumpAndSettle();
      expect(session.methods, isNot(contains('submitPlan')));
      await tapText(tester, 'Take off');
      await tester.tap(find.byTooltip('Higher'));
      await tester.pump();
      await tester.tap(find.descendant(of: dialog, matching: find.text('Take off')));
      await tester.pumpAndSettle();
      expect(session.argumentsOf('submitPlan').single, {
        'steps': [
          {
            'name': 'takeoff',
            'arguments': {'altitude_m': 4},
            'source': 'rules'
          }
        ],
        'source': 'tap',
      });
    });

    testWidgets('HOLD is immediate: no CONFIRM', (tester) async {
      final session = await launch(tester, initial: fixture('executing_move'));
      session.submitResult = const ImmediateResult(
          'COMPLETED', 'COMPLETED — confirmed by ACK: hold');
      await tapText(tester, 'Hold');
      expect((session.argumentsOf('submitPlan').single as Map)['steps'], [
        {'name': 'hold', 'arguments': <String, Object?>{}, 'source': 'rules'}
      ]);
      expect(find.byKey(const Key('confirmButton')), findsNothing);
      expect(session.methods, isNot(contains('confirm')));
    });
  });

  group('link', () {
    testWidgets('the first launch without saved settings opens Link setup once',
        (tester) async {
      final session = await launch(tester, store: MemoryLinkSettingsStore());
      expect(opened, [FlightDestination.linkSetup]);
      session.pushFixture('idle_disconnected');
      await tester.pumpAndSettle();
      expect(opened, [FlightDestination.linkSetup]);
      expect(session.methods, isNot(contains('connect')));
    });

    testWidgets('saved settings that were connected reconnect at launch, in dry '
        'run', (tester) async {
      final store = MemoryLinkSettingsStore(_saved.copyWith(autoConnect: true));
      final session = await launch(tester, store: store);
      expect(opened, isEmpty);
      expect(session.argumentsOf('connect').single, _saved.link.toMap());
      expect(session.methods, isNot(contains('setExecution')));
      // The permission dialog would cover STOP: never at (auto-)connect.
      expect(session.methods, isNot(contains('notificationPermission')));
      expect(chip('Dry run'), findsOneWidget);
      expect(statusLine(tester), startsWith('Connected'));
    });

    testWidgets('a session still running in the service is not reconnected',
        (tester) async {
      final store = MemoryLinkSettingsStore(_saved.copyWith(autoConnect: true));
      final session = await launch(tester,
          initial: fixture('executing_move'), store: store);
      expect(session.methods.first, 'getSnapshot');
      expect(session.methods, isNot(contains('connect')));
      expect(inPlan(find.text('Running')), findsOneWidget);
    });

    testWidgets('Connect uses the saved settings and remembers to reconnect; '
        'Disconnect forgets that', (tester) async {
      final store = MemoryLinkSettingsStore(_saved);
      final session = await launch(tester, store: store);
      session.connectError = PlatformException(
          code: 'CONNECT_FAILED',
          message: 'Cannot open the UDP link to 192.168.0.5:14550: busy');
      await tapText(tester, 'Connect');
      expect(find.text('Cannot open the UDP link to 192.168.0.5:14550: busy'),
          findsOneWidget);
      expect(store.saved, isEmpty);
      session.connectError = null;
      await tapText(tester, 'Connect');
      expect(session.argumentsOf('connect').last, _saved.link.toMap());
      expect(find.text('Cannot open the UDP link to 192.168.0.5:14550: busy'),
          findsNothing);
      expect(store.saved.single.autoConnect, isTrue);
      expect(find.text('Disconnect'), findsOneWidget);
      await tapText(tester, 'Disconnect');
      expect(session.methods, contains('disconnect'));
      expect(store.saved.last.autoConnect, isFalse);
      expect(statusLine(tester), 'Not connected');
    });

    testWidgets('a link that cannot be saved still connects, and says so',
        (tester) async {
      final store = MemoryLinkSettingsStore(_saved)
        ..saveError = StateError('no place');
      final session = await launch(tester, store: store);
      await tapText(tester, 'Connect');
      expect(session.methods, contains('connect'));
      expect(find.text('Disconnect'), findsOneWidget);
      expect(find.text(linkNotSaved), findsOneWidget);
    });

    testWidgets('Connect without saved settings opens Link setup',
        (tester) async {
      final session = await launch(tester, store: MemoryLinkSettingsStore());
      opened.clear();
      await tapText(tester, 'Connect');
      expect(opened, [FlightDestination.linkSetup]);
      expect(session.methods, isNot(contains('connect')));
    });

    testWidgets('an unreadable service shows why; STOP still calls it',
        (tester) async {
      final session = await launch(tester);
      session.addError(const FormatException(
          'The flight service uses data version 9; this app reads version 1. '
          'Install matching versions.'));
      await tester.pumpAndSettle();
      expect(find.textContaining('data version 9'), findsOneWidget);
      await tester.tap(stopButton());
      await tester.pump();
      expect(session.methods.last, 'stop');
    });

    testWidgets('the menu opens Link setup, Models and Diagnostics',
        (tester) async {
      await launch(tester);
      for (final (label, destination) in [
        ('Link setup', FlightDestination.linkSetup),
        ('Models', FlightDestination.models),
        ('Diagnostics', FlightDestination.diagnostics),
      ]) {
        await tester.tap(find.byTooltip('Menu'));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
        expect(opened.last, destination);
      }
    });
  });

  group('voice', () {
    testWidgets('holding Talk sends the press and the release; the service\'s '
        'live partial shows while held', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      final gesture = await hold(tester);
      expect(session.methods.last, 'pttDown');
      expect(find.text('Listening… release to send'), findsOneWidget);
      session.pushSpeech({'state': 'LISTENING', 'pttHeld': true, 'partial': 'take off'});
      await tester.pump();
      expect(find.text('Hearing: take off'), findsOneWidget);
      await gesture.up();
      await tester.pump();
      expect(session.methods.last, 'pttUp');
      session.pushSpeech({'state': 'DECODING', 'pttHeld': false, 'partial': null});
      await tester.pump();
      expect(find.text('Working…'), findsOneWidget);
      // The screen never parses or submits speech itself.
      expect(session.methods, isNot(contains('submitPlan')));
      expect(session.methods, isNot(contains('submitText')));
    });

    testWidgets('a spoken plan arrives from the service as Phone and still '
        'needs CONFIRM', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      await talk(tester);
      session.pushFixture('pending_plan', {
        'pending': {'source': 'phone'},
        'speech': {'heard': 'move forward 2 metres then right 2 metres'},
      });
      await tester.pumpAndSettle();
      expect(inPlan(find.text('Phone')), findsOneWidget);
      expect(find.text('Heard: move forward 2 metres then right 2 metres'),
          findsOneWidget);
      expect(find.byKey(const Key('confirmButton')), findsOneWidget);
      expect(session.methods, isNot(contains('confirm')));
    });

    testWidgets('why an utterance did not become a plan is shown as the '
        'service says it', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      session.pushSpeech({
        'heard': 'move forward 2',
        'error': 'The command was not clear. Say it again.',
        'utterance': 3
      });
      await tester.pumpAndSettle();
      expect(find.text('Heard: move forward 2'), findsOneWidget);
      expect(
          find.descendant(
              of: find.byKey(const Key('speechError')),
              matching: find.text('The command was not clear. Say it again.')),
          findsOneWidget);
      expect(planCard(), findsNothing);
    });

    testWidgets('no voice for this language on this phone says so; the tap '
        'pad and typing still work', (tester) async {
      final session = await launch(tester, initial: fixture('voice_unavailable'));
      expect(find.text('Voice for this language needs a newer phone — use the tap pad'),
          findsOneWidget);
      await tapText(tester, 'Land');
      expect(session.methods, contains('submitPlan'));
      await type(tester, 'land');
      expect((session.argumentsOf('submitText').single as Map)['language'], 'ta');
    });

    testWidgets('a KWS spotter without a native STOP says to say "stop" in '
        'English', (tester) async {
      const english = 'Voice STOP: say "stop" in English.';
      final session = await launch(tester, initial: fixture('connected_landed'));
      // The Vosk STOP grammar in the selected language.
      expect(find.text(english), findsNothing);
      // Telugu on Tier M: the GigaSpeech KWS spotter hears English "stop" only.
      session.pushSpeech({
        'language': 'te',
        'engine': 'SHERPA_CTC',
        'spotter': 'KWS',
        'nativeVoiceStop': false
      });
      await tester.pumpAndSettle();
      expect(find.text(english), findsOneWidget);
      session.pushSpeech({'spotter': 'KWS', 'nativeVoiceStop': true});
      await tester.pumpAndSettle();
      expect(find.text(english), findsNothing);
      session.pushSpeech({'spotter': 'VOSK_STOP_GRAMMAR', 'nativeVoiceStop': false});
      await tester.pumpAndSettle();
      expect(find.text(english), findsNothing);
    });

    testWidgets('voice degraded is a warning, not a block', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      session.pushSpeech({
        'level': 'DEGRADED',
        'reason': 'REPEATED_UNSURE',
        'message': 'Voice commands were unclear several times.'
      });
      await tester.pumpAndSettle();
      expect(find.text('Voice degraded: Voice commands were unclear several times.'),
          findsOneWidget);
      await talk(tester);
      expect(session.methods, containsAllInOrder(['pttDown', 'pttUp']));
    });

    testWidgets('Talk is off while no link is open', (tester) async {
      final session = await launch(tester);
      await talk(tester);
      expect(session.methods, isNot(contains('pttDown')));
    });

    testWidgets('a STOP that ended push-to-talk in the service: the release '
        'sends nothing more', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      final gesture = await hold(tester);
      session.pushSpeech({'state': 'LISTENING', 'pttHeld': true});
      await tester.pump();
      session.pushSpeech({'state': 'READY', 'pttHeld': false});
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();
      expect(session.methods, isNot(contains('pttUp')));
    });

    group('the microphone permission', () {
      SessionSnapshot noMicrophone({bool armed = false}) =>
          fixture('connected_landed', {
            'vehicle': {'armed': armed},
            'speech': {
              'state': 'OFF',
              'level': 'NO_VOICE',
              'reason': 'NO_MICROPHONE',
              'message': 'Allow the microphone to use voice commands.'
            }
          });

      testWidgets('is asked with its reason before the first Talk',
          (tester) async {
        final session = FakeSessionApi(noMicrophone())
          ..microphone = NotificationPermission.askable
          ..microphoneDialog = NotificationPermission.granted;
        await launch(tester, session: session);
        expect(find.text('Allow the microphone to use voice commands.'),
            findsOneWidget);
        await talk(tester);
        expect(find.text('Allow the microphone?'), findsOneWidget);
        expect(find.text(microphoneReason), findsOneWidget);
        expect(stopButton().hitTestable(), findsOneWidget);
        await tester.tap(find.text('Continue'));
        await tester.pumpAndSettle();
        expect(session.argumentsOf('microphonePermission'), [
          {'ask': false},
          {'ask': true}
        ]);
        expect(find.text(microphoneAllowed), findsOneWidget);
        expect(session.methods, isNot(contains('pttDown')));
      });

      testWidgets('is never asked while the drone is armed (the dialog covers '
          'STOP)', (tester) async {
        final session = FakeSessionApi(noMicrophone(armed: true))
          ..microphone = NotificationPermission.askable;
        await launch(tester, session: session);
        await talk(tester);
        expect(find.byType(AlertDialog), findsNothing);
        expect(find.text(microphoneWhileArmed), findsOneWidget);
        expect(session.argumentsOf('microphonePermission'), [
          {'ask': false}
        ]);
      });

      testWidgets('denied, or not now, says how to get voice', (tester) async {
        final session = FakeSessionApi(noMicrophone())
          ..microphone = NotificationPermission.denied;
        await launch(tester, session: session);
        await talk(tester);
        expect(find.text(microphoneDenied), findsOneWidget);
        session.microphone = NotificationPermission.askable;
        await talk(tester);
        await tester.tap(find.text('Not now'));
        await tester.pumpAndSettle();
        expect(session.argumentsOf('microphonePermission').where(
            (a) => (a as Map)['ask'] == true), isEmpty);
      });

      testWidgets('allowed in Settings meanwhile: the press starts the '
          'microphone', (tester) async {
        final session = FakeSessionApi(noMicrophone());
        await launch(tester, session: session);
        await talk(tester);
        expect(session.methods, containsAllInOrder(['pttDown', 'pttUp']));
        expect(find.text(microphoneAllowed), findsOneWidget);
      });
    });

    testWidgets('the language picker sets the service language, which typed '
        'commands use', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      await tester.ensureVisible(find.byKey(const Key('speechLanguage')));
      await tester.tap(find.byKey(const Key('speechLanguage')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Hindi').last);
      await tester.pumpAndSettle();
      expect(session.argumentsOf('setLanguage').single, {'language': 'hi'});
      await type(tester, '2 मीटर आगे जाओ');
      expect(session.argumentsOf('submitText').single,
          {'text': '2 मीटर आगे जाओ', 'language': 'hi', 'source': 'typed'});
    });

    testWidgets('the service knows when this screen is in front (Volume-Up is '
        'push-to-talk only then)', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      expect(session.argumentsOf('flightScreen'), [
        {'visible': true}
      ]);
      await tapText(tester, 'Take off');
      expect(find.byType(AlertDialog), findsOneWidget);
      expect(session.argumentsOf('flightScreen').last, {'visible': false});
      await tester.tap(find.descendant(
          of: find.byType(AlertDialog), matching: find.text('Cancel')));
      await tester.pumpAndSettle();
      expect(session.argumentsOf('flightScreen').last, {'visible': true});
    });

    testWidgets('a failed "in front" call is sent again until the service has '
        'it (F15)', (tester) async {
      final session = FakeSessionApi(fixture('connected_landed'))
        ..flightScreenFailures = 2;
      await launch(tester, session: session);
      expect(session.argumentsOf('flightScreen'), [
        {'visible': true}
      ]);
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(seconds: 1));
      expect(session.argumentsOf('flightScreen'), [
        for (var i = 0; i < 3; i++) {'visible': true}
      ]);
      await tester.pump(const Duration(seconds: 5));
      expect(session.argumentsOf('flightScreen'), hasLength(3),
          reason: 'the third call went through: nothing more to send');
    });
  });

  group('app shell', () {
    testWidgets('Flight is home; each screen gets the same session',
        (tester) async {
      final session = FakeSessionApi();
      addTearDown(session.close);
      await tester.binding.setSurfaceSize(const Size(480, 1800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(MaterialApp(
          home: AppShell(
              session: session, settings: MemoryLinkSettingsStore(_saved))));
      await tester.pumpAndSettle();
      expect(find.byType(FlightScreen), findsOneWidget);
      for (final (label, type) in [
        ('Link setup', LinkSetupScreen),
        ('Models', ModelsScreen),
        ('Diagnostics', DiagnosticsScreen),
      ]) {
        await tester.tap(find.byTooltip('Menu'));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
        final screen = tester.widget(find.byType(type));
        expect((screen as dynamic).session, same(session));
        await tester.pageBack();
        await tester.pumpAndSettle();
        expect(find.byType(FlightScreen), findsOneWidget);
      }
    });

    testWidgets("Link setup saves into the app's store, which the Flight "
        'screen then connects with', (tester) async {
      final session = FakeSessionApi();
      addTearDown(session.close);
      final store = MemoryLinkSettingsStore(_saved);
      await tester.binding.setSurfaceSize(const Size(480, 1800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(AeroNexApp(session: session, settings: store));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Menu'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Link setup'));
      await tester.pumpAndSettle();
      expect(tester.widget<LinkSetupScreen>(find.byType(LinkSetupScreen)).settings,
          same(store));
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Drone address'), '192.168.0.9');
      await tester.tap(find.byKey(const Key('saveLink')));
      await tester.pumpAndSettle();
      expect(find.text(linkNotSaved), findsNothing);
      expect(find.byType(FlightScreen), findsOneWidget);
      expect((await store.load())!.link.host, '192.168.0.9');
      // The Flight screen reads the same store at its next Connect.
      session.pushFixture('idle_disconnected');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Connect'));
      await tester.pumpAndSettle();
      expect((session.argumentsOf('connect').last as Map)['host'], '192.168.0.9');
    });

    testWidgets('the app keeps STOP above every screen', (tester) async {
      final session = FakeSessionApi(fixture('connected_landed'));
      addTearDown(session.close);
      await tester.binding.setSurfaceSize(const Size(480, 1800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(AeroNexApp(
          session: session, settings: MemoryLinkSettingsStore(_saved)));
      await tester.pumpAndSettle();
      expect(stopButton().hitTestable(), findsOneWidget);
      for (final (label, type) in [
        ('Link setup', LinkSetupScreen),
        ('Models', ModelsScreen),
        ('Diagnostics', DiagnosticsScreen),
      ]) {
        await tester.tap(find.byTooltip('Menu'));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
        expect(find.byType(type), findsOneWidget);
        expect(stopButton().hitTestable(), findsOneWidget);
        final before = session.argumentsOf('stop').length;
        await tester.tap(stopButton());
        await tester.pump();
        expect(session.argumentsOf('stop'), hasLength(before + 1));
        // The pushed screen's own bar sits below STOP, not under the clock.
        expect(tester.getTopLeft(find.byType(AppBar).last).dy,
            greaterThanOrEqualTo(tester.getBottomLeft(stopButton()).dy));
        await tester.pageBack();
        await tester.pumpAndSettle();
      }
      expect(find.byType(FlightScreen), findsOneWidget);
    });
  });

  group('AI (0.4.0)', () {
    testWidgets('typed text the rules do not understand shows Thinking with '
        'Cancel, which ends it in the service; STOP stays on top', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      session.submitResult = const ThinkingResult(1);
      await type(tester, 'circle the tree');
      session.pushAi({'state': 'THINKING', 'request': 1, 'heard': 'circle the tree', 'available': true});
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.byKey(const Key('aiThinking')), findsOneWidget);
      expect(find.text('Thinking…'), findsOneWidget);
      expect(find.text('Heard: circle the tree'), findsOneWidget);
      expect(find.byKey(const Key('planError')), findsNothing);
      expect(stopButton().hitTestable(), findsOneWidget);
      await tester.tap(find.byKey(const Key('aiCancel')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(session.methods, contains('cancelAi'));
      expect(find.byKey(const Key('aiThinking')), findsNothing);
    });

    testWidgets('an AI plan shows the AI chip, what was heard and what it was '
        'understood as, and still needs CONFIRM', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      session.pushFixture('ai_plan', {
        'speech': {'heard': null}
      });
      await tester.pumpAndSettle();
      expect(inPlan(find.text('AI')), findsOneWidget);
      expect(inPlan(find.text('Heard: ముందుకు మూడు మీటర్లు తర్వాత దిగు')), findsOneWidget);
      expect(inPlan(find.text('Understood as: move forward 3 meters, then land')),
          findsOneWidget);
      expect(inPlan(find.text('1. Move forward 3 m')), findsOneWidget);
      expect(find.byKey(const Key('confirmButton')), findsOneWidget);
      expect(session.methods, isNot(contains('confirm')));
      // A spoken command: the voice line already says what was heard.
      session.pushFixture('ai_plan');
      await tester.pumpAndSettle();
      expect(find.text('Heard: ముందుకు మూడు మీటర్లు తర్వాత దిగు'), findsOneWidget);
    });

    testWidgets('why the AI gave no plan is shown until new input',
        (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      session.pushAi({
        'request': 2,
        'heard': 'forward 3 meters',
        'understoodAs': 'move forward 3 meters',
        'error': _onGround,
      });
      await tester.pumpAndSettle();
      final card = find.byKey(const Key('aiError'));
      expect(find.descendant(of: card, matching: find.text(_onGround)), findsOneWidget);
      expect(
          find.descendant(
              of: card, matching: find.text('Understood as: move forward 3 meters')),
          findsOneWidget);
      await tester.enterText(find.widgetWithText(TextField, 'Type a command'), 'land');
      await tester.pumpAndSettle();
      expect(card, findsNothing);
    });
  });

  test('a tap command is the parser\'s command', () {
    FlightCommand tap(String name, Map<String, Object?> arguments) =>
        FlightCommand.fromToolCalls([
          {'name': name, 'arguments': arguments}
        ]);
    expect(tap('move_relative', {'direction': 'backward', 'distance_m': 2}).description,
        'Move backward 2 m');
    expect(FlightPlan.single(tap('hold', {}), source: StepSource.rules).steps.single.source,
        StepSource.rules);
  });

  group('audit fixes (2026-10-28)', () {
    testWidgets('Disconnect while flying asks first; Stay connected keeps the link (F1)',
        (tester) async {
      final session = await launch(tester, initial: fixture('executing_move'));
      await tapText(tester, 'Disconnect');
      expect(find.byKey(const Key('disconnectConfirm')), findsOneWidget);
      expect(stopButton(), findsOneWidget, reason: 'STOP stays reachable above the dialog');
      await tester.tap(find.byKey(const Key('stayConnected')));
      await tester.pumpAndSettle();
      expect(session.methods, isNot(contains('disconnect')));
      await tapText(tester, 'Disconnect');
      await tester.tap(find.byKey(const Key('disconnectAnyway')));
      await tester.pumpAndSettle();
      expect(session.methods, contains('disconnect'));
    });

    testWidgets('on the ground, Disconnect is one tap', (tester) async {
      final session = await launch(tester,
          initial: fixture('connected_landed', {
            'vehicle': {...fixture('connected_landed').raw['vehicle'] as Map<String, Object?>, 'armed': false},
          }));
      await tapText(tester, 'Disconnect');
      expect(find.byKey(const Key('disconnectConfirm')), findsNothing);
      expect(session.methods, contains('disconnect'));
    });

    testWidgets('a double tap on Connect connects once (F12)', (tester) async {
      final session = await launch(tester);
      session.connectGate = Completer<void>();
      final connect = find.text('Connect');
      await tester.tap(connect);
      await tester.tap(connect);
      await tester.pump();
      session.connectGate!.complete();
      await tester.pumpAndSettle();
      expect(session.methods.where((m) => m == 'connect'), hasLength(1));
    });

    testWidgets('Hold on the tap pad acts while a typed command is being sent (F9)',
        (tester) async {
      final session = await launch(tester, initial: fixture('executing_move'));
      session.submitGate = Completer<void>();
      await tester.enterText(find.widgetWithText(TextField, 'Type a command'), 'land');
      await tester.testTextInput.receiveAction(TextInputAction.go);
      await tester.pump();
      final hold = find.widgetWithText(OutlinedButton, 'Hold');
      await tester.ensureVisible(hold);
      await tester.tap(hold);
      await tester.pump();
      expect(session.methods, contains('submitPlan'));
      session.submitGate!.complete();
      await tester.pumpAndSettle();
    });

    testWidgets('a speech error and a failed press show as two cards (F7: one key broke the screen)',
        (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      session.pushSpeech({'heard': 'move forward 2', 'error': 'The command was not clear. Say it again.', 'utterance': 3});
      await tester.pumpAndSettle();
      session.pttError = PlatformException(code: 'INTERNAL', message: 'The flight supervisor did not answer.');
      final press = await hold(tester);
      await press.up();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('speechError')), findsOneWidget);
      expect(find.byKey(const Key('speechNotice')), findsOneWidget);
    });

    testWidgets('STOP over a pilot mode can be chosen from the accessibility actions (F3)',
        (tester) async {
      final handle = tester.ensureSemantics();
      final calls = <bool>[];
      await tester.pumpWidget(MaterialApp(
          home: Scaffold(
              body: StopButton(
                  onStop: ({required bool heldOneSecond}) => calls.add(heldOneSecond),
                  holdToOverride: true,
                  ownerLabel: 'Pilot mode'))));
      final node = tester.getSemantics(find.byType(StopButton));
      expect(node.label, contains(overrideActionLabel));
      final action = CustomSemanticsAction.getIdentifier(const CustomSemanticsAction(label: overrideActionLabel));
      tester.renderObject(find.byType(StopButton)).owner!.semanticsOwner!
          .performAction(node.id, SemanticsAction.customAction, action);
      await tester.pump();
      expect(calls, [true]);
      handle.dispose();
    });

    testWidgets('snapshots that stop coming are shown as such until the next '
        'one, and only while the app is on screen (F14)', (tester) async {
      final session = await launch(tester, initial: fixture('executing_move'));
      final stale = find.byKey(const Key('serviceStale'));
      await tester.pump(const Duration(seconds: 2));
      session.pushFixture('executing_move');
      await tester.pump(const Duration(seconds: 2));
      expect(stale, findsNothing, reason: 'a snapshot 2 s ago is fresh');
      await tester.pump(const Duration(milliseconds: 1100));
      expect(stale, findsOneWidget);
      expect(find.text('No update from the flight service for 3 s: what this '
          'screen shows may be old. Use RC if the drone is flying.'), findsOneWidget);
      session.pushFixture('executing_move');
      await tester.pump();
      expect(stale, findsNothing);

      // In the background nobody is warned; back on screen the count restarts.
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump(const Duration(seconds: 10));
      expect(stale, findsNothing);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump(const Duration(seconds: 2));
      expect(stale, findsNothing);
      await tester.pump(const Duration(milliseconds: 1100));
      expect(stale, findsOneWidget);
    });
  });


  group('flight memory (2026-10-28)', () {
    Finder memoryButton(String key) => find.byKey(Key(key));

    Future<void> tapKey(WidgetTester tester, String key) async {
      await tester.ensureVisible(memoryButton(key));
      await tester.pumpAndSettle();
      await tester.tap(memoryButton(key));
      await tester.pumpAndSettle();
    }

    bool enabled(WidgetTester tester, String key) =>
        tester.widget<ButtonStyleButton>(memoryButton(key)).onPressed != null;

    testWidgets('shows the path, points and recent commands, and the buttons send '
        'the voice commands\' tool calls with the Tap source', (tester) async {
      final session = await launch(tester, initial: fixture('flight_memory'));
      expect(find.byKey(const Key('flightMemory')), findsOneWidget);
      expect(find.byKey(const Key('memoryMap')), findsOneWidget);
      expect(find.text('Go back to Move forward 4 m'), findsOneWidget);
      expect(find.text('Mark point 2'), findsOneWidget);
      expect(find.text('Go to point 1 (4 m from home)'), findsOneWidget);
      expect(find.text('Move right 3 m'), findsWidgets);
      for (final (key, name, arguments) in [
        ('memoryReturnHome', 'return_home', <String, Object?>{}),
        ('memoryGoBack', 'go_back', <String, Object?>{}),
        ('memoryRetrace', 'retrace', <String, Object?>{}),
        ('memoryMark', 'mark_point', <String, Object?>{'point': 2}),
        ('memoryPoint1', 'goto_point', <String, Object?>{'point': 1}),
      ]) {
        session.calls.clear();
        await tapKey(tester, key);
        final request = session.argumentsOf('submitPlan').single as Map;
        expect(request['source'], 'tap', reason: key);
        expect(request['steps'], [
          {'name': name, 'arguments': arguments, 'source': 'rules'}
        ], reason: key);
        // Shown for CONFIRM like any tap-pad plan; a new one replaces it.
        expect(planCard(), findsOneWidget);
        session.pushFixture('flight_memory');
        await tester.pumpAndSettle();
      }
    });

    testWidgets('Run again asks the service to show the recorded plan again', (tester) async {
      final session = await launch(tester, initial: fixture('flight_memory'));
      await tapKey(tester, 'memoryRunAgain2');
      expect(session.argumentsOf('runAgain'), [
        {'planId': 2}
      ]);
      expect(planCard(), findsOneWidget);
      // A refusal is shown as is.
      session.runAgainResult = const RejectedResult('The drone is already flying. Remove the takeoff step.');
      session.pushFixture('flight_memory');
      await tester.pumpAndSettle();
      await tapKey(tester, 'memoryRunAgain1');
      expect(find.text('The drone is already flying. Remove the takeoff step.'), findsOneWidget);
    });

    testWidgets('Clear asks first and keeps the memory on Keep', (tester) async {
      final session = await launch(tester, initial: fixture('flight_memory'));
      await tapKey(tester, 'memoryClear');
      expect(find.byKey(const Key('clearMemoryConfirm')), findsOneWidget);
      await tester.tap(find.byKey(const Key('keepMemory')));
      await tester.pumpAndSettle();
      expect(session.methods, isNot(contains('clearMemory')));
      await tapKey(tester, 'memoryClear');
      await tester.tap(find.byKey(const Key('clearMemory')));
      await tester.pumpAndSettle();
      expect(session.methods, contains('clearMemory'));
      expect(find.byKey(const Key('memoryMap')), findsNothing);
      expect(find.text('Go back'), findsOneWidget);
    });

    testWidgets('with nothing remembered only what can fly is enabled', (tester) async {
      await launch(tester);
      expect(find.byKey(const Key('memoryMap')), findsNothing);
      for (final key in ['memoryReturnHome', 'memoryGoBack', 'memoryRetrace', 'memoryMark']) {
        expect(enabled(tester, key), isFalse, reason: key);
      }
      expect(tester.widget<IconButton>(find.byKey(const Key('memoryClear'))).onPressed, isNull);
    });

    testWidgets('on the ground the launch point can be saved', (tester) async {
      await launch(tester, initial: fixture('connected_landed'));
      // The vehicle is on the map; nothing has been flown yet.
      expect(find.byKey(const Key('memoryMap')), findsOneWidget);
      expect(enabled(tester, 'memoryMark'), isTrue);
      expect(enabled(tester, 'memoryGoBack'), isFalse);
      expect(enabled(tester, 'memoryRetrace'), isFalse);
    });

    testWidgets('fits a 5-inch phone with large text', (tester) async {
      await launch(tester, initial: fixture('flight_memory'), size: const Size(360, 640), textScale: 1.3);
      await tester.ensureVisible(find.byKey(const Key('flightMemory')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(stopButton().hitTestable(), findsOneWidget);
    });
  });

  group('missions (2026-10-28)', () {
    Future<void> tapKey(WidgetTester tester, String key) async {
      await tester.ensureVisible(find.byKey(Key(key)));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(Key(key)));
      await tester.pumpAndSettle();
    }

    testWidgets('Build a mission is the service\'s switch', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      expect(find.byKey(const Key('missionCard')), findsOneWidget);
      expect(find.byKey(const Key('startMission')), findsNothing, reason: 'nothing built yet');
      await tapKey(tester, 'missionBuilding');
      expect(session.argumentsOf('missionBuilding'), [
        {'enabled': true}
      ]);
      expect(find.textContaining('it is added here instead of running'), findsOneWidget);
    });

    testWidgets('the stages are listed, one can go, and Start shows the whole mission with its stages',
        (tester) async {
      final session = await launch(tester, initial: fixture('mission_building'));
      expect(find.text('Take off to 3 m, then Move forward 4 m'), findsOneWidget);
      expect(find.byKey(const Key('missionStage1')), findsOneWidget);
      await tapKey(tester, 'removeStage1');
      expect(session.argumentsOf('removeStage'), [
        {'index': 1}
      ]);
      expect(find.byKey(const Key('missionStage1')), findsNothing);
      await tapKey(tester, 'missionCheckpoints');
      expect(session.argumentsOf('missionCheckpoints'), [
        {'enabled': true}
      ]);
      await tapKey(tester, 'startMission');
      expect(session.methods, contains('startMission'));
      expect(inPlan(find.text('Confirm this mission')), findsOneWidget);
      expect(inPlan(find.byKey(const Key('stageHeader0'))), findsOneWidget);
      expect(inPlan(find.byKey(const Key('addToMission'))), findsNothing, reason: 'a mission is no stage');
      await tester.tap(inPlan(find.textContaining('CONFIRM')));
      await tester.pumpAndSettle();
      expect(session.argumentsOf('confirm').single, {'token': session.current.pending!.token});
    });

    testWidgets('a shown plan can become a stage instead of running', (tester) async {
      final session = await launch(tester, initial: fixture('connected_landed'));
      await type(tester, 'land');
      expect(planCard(), findsOneWidget);
      final token = session.current.pending!.token;
      await tapKey(tester, 'addToMission');
      expect(session.argumentsOf('addStage'), [
        {'token': token}
      ]);
      expect(planCard(), findsNothing);
      expect(find.byKey(const Key('missionStage0')), findsOneWidget);
      expect(find.text('Added as stage 1 of the mission.'), findsOneWidget);
    });

    testWidgets('at a checkpoint Continue starts the next stage', (tester) async {
      final session = await launch(tester, initial: fixture('mission_checkpoint'));
      expect(inPlan(find.byKey(const Key('checkpointText'))), findsOneWidget);
      expect(inPlan(find.byKey(const Key('stageHeader1'))), findsOneWidget);
      expect(inPlan(find.text('Continue to stage 2')), findsOneWidget);
      await tester.tap(inPlan(find.byKey(const Key('continueMission'))));
      await tester.pumpAndSettle();
      expect(session.argumentsOf('continueMission'), [
        {'token': '5d3c9a1e-2b7f-4e6a-9c8d-1f2e3a4b5c6d'}
      ]);
      // STOP stays pinned above everything while the mission holds.
      expect(stopButton().hitTestable(), findsOneWidget);
    });

    testWidgets('a mission that ended early resumes from its step or the next', (tester) async {
      final session = await launch(tester, initial: fixture('mission_building'));
      expect(find.text('The last mission ended at step 4; 2 steps are left.'), findsOneWidget);
      await tapKey(tester, 'resumeMission');
      await tapKey(tester, 'resumeMissionSkip');
      expect(session.argumentsOf('resumeMission'), [
        {'skipInterrupted': false},
        {'skipInterrupted': true},
      ]);
      // The service's refusal is shown as is.
      expect(find.text('There is no mission to resume.'), findsOneWidget);
    });

    testWidgets('fits a 5-inch phone with large text', (tester) async {
      await launch(tester, initial: fixture('mission_building'), size: const Size(360, 640), textScale: 1.3);
      await tester.ensureVisible(find.byKey(const Key('startMission')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(stopButton().hitTestable(), findsOneWidget);
    });
  });

  group('battery reserve (2026-10-28)', () {
    testWidgets('below the reserve the screen says so and offers Return home and Land', (tester) async {
      final session = await launch(tester,
          initial: fixture('flight_memory', {
            'vehicle': {'batteryPercent': 22, 'lowBattery': true}
          }));
      expect(find.text('Battery 22 %: plans may only head back toward home.'), findsOneWidget);
      await tester.tap(find.byKey(const Key('lowBatteryReturnHome')));
      await tester.pumpAndSettle();
      final request = session.argumentsOf('submitPlan').single as Map;
      expect(request['source'], 'tap');
      expect(request['steps'], [
        {'name': 'return_home', 'arguments': <String, Object?>{}, 'source': 'rules'}
      ]);
      expect(planCard(), findsOneWidget);
    });

    testWidgets('on the ground, or with a healthy battery, nothing is shown', (tester) async {
      await launch(tester, initial: fixture('flight_memory'));
      expect(find.byKey(const Key('lowBattery')), findsNothing);
    });
  });
}
