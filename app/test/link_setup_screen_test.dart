import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/flight/presentation/flight_screen.dart'
    show FlightDestination, FlightScreen;
import 'package:aeronex/features/flight/presentation/widgets/stop_button.dart';
import 'package:aeronex/features/link/data/link_settings.dart';
import 'package:aeronex/features/link/presentation/link_setup_screen.dart';
import 'package:aeronex/features/session/session_api.dart';

import 'support/fake_session_api.dart';

const _saved = LinkSettings(
    link: LinkConfig(host: '192.168.0.5', remotePort: 14550, localPort: 14551));

/// [FakeSessionApi] whose telemetry request can wait, fail or go unqueued,
/// and whose service can keep execution off.
class _Session extends FakeSessionApi {
  _Session([super.initial]);

  Completer<void>? telemetryGate, executionGate;
  bool telemetryQueued = true;
  Object? telemetryError, disableError;
  bool? executionReply;

  @override
  Future<bool> requestTelemetry() async {
    final queued = await super.requestTelemetry();
    if (telemetryGate != null) await telemetryGate!.future;
    if (telemetryError != null) throw telemetryError!;
    return queued && telemetryQueued;
  }

  @override
  Future<bool> setExecution(bool enabled) async {
    final reply = await super.setExecution(enabled);
    if (enabled && executionGate != null) await executionGate!.future;
    if (!enabled && disableError != null) throw disableError!;
    return executionReply ?? reply;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Session session;
  late MemoryLinkSettingsStore store;

  /// Link setup pushed over a stand-in Flight page, under the app's STOP.
  Future<void> open(WidgetTester tester,
      {SessionSnapshot? initial,
      LinkSettings? saved,
      NotificationPermission notifications = NotificationPermission.granted,
      Size size = const Size(480, 1600),
      double textScale = 1.0}) async {
    session = _Session(initial)..notifications = notifications;
    addTearDown(session.close);
    store = MemoryLinkSettingsStore(saved);
    await tester.binding.setSurfaceSize(size);
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(MaterialApp(
        navigatorKey: navigator,
        builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(textScale)),
            child: StopLane(session: session, child: child!)),
        home: const Scaffold(body: Text('Flight page'))));
    navigator.currentState!.push(MaterialPageRoute<void>(
        builder: (_) => LinkSetupScreen(session: session, settings: store)));
    await tester.pumpAndSettle();
  }

  Finder field(String label) => find.widgetWithText(TextFormField, label);
  String fieldText(WidgetTester tester, String label) =>
      tester.widget<TextFormField>(field(label)).controller!.text;
  Finder executionSwitch() => find.byKey(const Key('executionSwitch'));
  Finder radioSwitch() => find.byKey(const Key('radioLinkSwitch'));
  Finder saveButton() => find.byKey(const Key('saveLink'));
  Finder stopButton() => find.byKey(const Key('stopButton'));
  Finder inDialog(String text) =>
      find.descendant(of: find.byType(AlertDialog), matching: find.text(text));
  SwitchListTile execution(WidgetTester tester) =>
      tester.widget<SwitchListTile>(executionSwitch());
  Iterable<String> executionCalls() => session.methods
      .where((m) => m == 'requestTelemetry' || m == 'setExecution');

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  /// Switch on and agree to the notice.
  Future<void> enable(WidgetTester tester) async {
    await tap(tester, executionSwitch());
    await tap(tester, inDialog('Fly the drone'));
  }

  group('validation', () {
    test('an address is four numbers from 0 to 255, as the service binds it',
        () {
      for (final good in ['192.168.0.5', '10.0.2.2', '0.0.0.0', '255.255.255.255']) {
        expect(addressProblem(good), isNull, reason: good);
        expect(addressProblem(' $good '), isNull, reason: 'trimmed $good');
      }
      for (final bad in [
        '',
        '192.168.1',
        '192.168.1.256',
        '192.168.001.5',
        'drone.local',
        '1.2.3.4.5',
        '::1'
      ]) {
        expect(addressProblem(bad), badAddress, reason: bad);
      }
    });

    test('the drone port is 1–65535; the phone port may be 0 (any free port)',
        () {
      expect(dronePortProblem('14550'), isNull);
      expect(dronePortProblem('65535'), isNull);
      for (final bad in ['', '0', '65536', '-1', '14 550', 'abc', '1.5']) {
        expect(dronePortProblem(bad), badDronePort, reason: bad);
      }
      expect(phonePortProblem('0'), isNull);
      expect(phonePortProblem('14551'), isNull);
      for (final bad in ['', '65536', '-1', 'x']) {
        expect(phonePortProblem(bad), badPhonePort, reason: bad);
      }
    });
  });

  group('link settings', () {
    testWidgets('a first setup offers the usual values with plain help; Save & '
        'Connect saves, connects in practice mode and returns', (tester) async {
      await open(tester);
      expect(fieldText(tester, 'Drone address'), '192.168.1.100');
      expect(fieldText(tester, 'Drone port'), '14550');
      expect(fieldText(tester, 'Phone port'), '14551');
      expect(find.textContaining('PC running the simulator: 192.168.x.x'),
          findsOneWidget);
      expect(find.textContaining('10.0.2.2'), findsOneWidget);
      await tester.enterText(field('Drone address'), '192.168.0.5');
      await tap(tester, saveButton());
      expect(session.argumentsOf('connect').single, _saved.link.toMap());
      expect(store.saved.single, _saved.copyWith(autoConnect: true));
      expect(session.methods, isNot(contains('setExecution')));
      expect(find.byType(LinkSetupScreen), findsNothing);
      expect(find.text('Flight page'), findsOneWidget);
    });

    testWidgets('saved settings fill the form, TCP and the radio included',
        (tester) async {
      await open(tester,
          saved: const LinkSettings(
              link: LinkConfig(
                  transport: LinkTransport.tcp,
                  host: '10.0.0.1',
                  remotePort: 5760,
                  localPort: 0,
                  radioLink: true),
              autoConnect: true));
      expect(fieldText(tester, 'Drone address'), '10.0.0.1');
      expect(fieldText(tester, 'Drone port'), '5760');
      // TCP dials out: the phone needs no port of its own.
      expect(field('Phone port'), findsNothing);
      expect(
          tester
              .widget<SegmentedButton<LinkTransport>>(
                  find.byType(SegmentedButton<LinkTransport>))
              .selected,
          {LinkTransport.tcp});
      expect(tester.widget<SwitchListTile>(radioSwitch()).value, isTrue);
    });

    testWidgets('the telemetry radio switch picks the radio profile',
        (tester) async {
      await open(tester, saved: _saved);
      expect(tester.widget<SwitchListTile>(radioSwitch()).value, isFalse);
      expect(find.text('This link goes through a telemetry radio'),
          findsOneWidget);
      expect(find.textContaining('ELRS'), findsOneWidget);
      await tap(tester, radioSwitch());
      await tap(tester, saveButton());
      expect((session.argumentsOf('connect').single as Map)['radioLink'], isTrue);
      expect(store.saved.single.link.radioLink, isTrue);
    });

    testWidgets('TCP dials the drone port and sends no phone port',
        (tester) async {
      await open(tester, saved: _saved);
      await tap(tester, find.text('TCP'));
      expect(field('Phone port'), findsNothing);
      await tester.enterText(field('Drone port'), '5760');
      await tap(tester, saveButton());
      expect(session.argumentsOf('connect').single, {
        'transport': 'tcp',
        'host': '192.168.0.5',
        'remotePort': 5760,
        'localPort': 0,
        'radioLink': false,
      });
    });

    testWidgets('a wrong address or port says how to fix it and sends nothing',
        (tester) async {
      await open(tester, saved: _saved);
      await tester.enterText(field('Drone address'), 'drone.local');
      await tester.enterText(field('Drone port'), '70000');
      await tester.enterText(field('Phone port'), '-1');
      await tap(tester, saveButton());
      expect(find.text(badAddress), findsOneWidget);
      expect(find.text(badDronePort), findsOneWidget);
      expect(find.text(badPhonePort), findsOneWidget);
      expect(session.methods, isNot(contains('connect')));
      expect(store.saved, isEmpty);
      expect(find.byType(LinkSetupScreen), findsOneWidget);
    });

    testWidgets('a failed connect shows why and keeps the settings, so setup '
        'is not asked for again', (tester) async {
      await open(tester);
      session.connectError = PlatformException(
          code: 'CONNECT_FAILED',
          message: 'Cannot open the UDP link to 192.168.1.100:14550: busy');
      await tap(tester, saveButton());
      expect(find.text('Cannot open the UDP link to 192.168.1.100:14550: busy'),
          findsOneWidget);
      expect(find.byType(LinkSetupScreen), findsOneWidget);
      // Remembered, but not connected: nothing reconnects at launch.
      expect(store.saved.single, LinkSettings.defaults);
    });

    testWidgets('a link the Flight screen opened meanwhile is kept, and still '
        'reconnects at launch', (tester) async {
      // Save tapped before this screen learned of the open link (its first
      // snapshot, or the Flight screen's own connect still under way).
      await open(tester, saved: _saved.copyWith(autoConnect: true));
      session.connectError = PlatformException(
          code: 'ALREADY_CONNECTED',
          message: 'Already connected. Disconnect first.');
      session.current = fixture('connected_landed', {'seq': 5000});
      await tap(tester, saveButton());
      expect(session.methods, isNot(contains('disconnect')));
      expect(store.saved.single, _saved.copyWith(autoConnect: true));
      expect(find.text('Already connected. Disconnect first.'), findsNothing);
      expect(find.text('Saved.'), findsOneWidget);
      expect(find.descendant(of: saveButton(), matching: find.text('Save')),
          findsOneWidget);
      expect(find.byType(LinkSetupScreen), findsOneWidget);
    });

    testWidgets('settings that cannot be saved still connect, and it says so',
        (tester) async {
      await open(tester, saved: _saved);
      store.saveError = StateError('no place');
      await tap(tester, saveButton());
      expect(session.methods, contains('connect'));
      expect(find.text(linkNotSaved), findsOneWidget);
      expect(find.byType(LinkSetupScreen), findsOneWidget);
    });

    testWidgets('while connected, Save keeps the link and applies at the next '
        'connect', (tester) async {
      await open(tester,
          initial: fixture('connected_landed'),
          saved: _saved.copyWith(autoConnect: true));
      expect(find.text('Save & Connect'), findsNothing);
      expect(find.descendant(of: saveButton(), matching: find.text('Save')),
          findsOneWidget);
      // Unchanged: still reconnects at launch.
      await tap(tester, saveButton());
      expect(store.saved.last, _saved.copyWith(autoConnect: true));
      await tester.enterText(field('Drone address'), '192.168.0.9');
      await tap(tester, saveButton());
      expect(session.methods, isNot(contains('connect')));
      expect(session.methods, isNot(contains('disconnect')));
      // A new, untried link never connects by itself.
      expect(
          store.saved.last,
          const LinkSettings(
              link: LinkConfig(
                  host: '192.168.0.9', remotePort: 14550, localPort: 14551)));
      expect(find.textContaining('next time you connect'), findsOneWidget);
      expect(find.byType(LinkSetupScreen), findsOneWidget);
    });

    testWidgets('setup is asked for once: once saved, a launch never opens it '
        'again', (tester) async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      // The Flight screen's speech input reads its models here.
      messenger.setMockMethodCallHandler(
          const MethodChannel('aeronex/inference'),
          (call) async => call.method == 'status'
              ? {'models': <String, bool>{}, 'modelStates': <String, String>{}}
              : null);
      addTearDown(() => messenger.setMockMethodCallHandler(
          const MethodChannel('aeronex/inference'), null));
      session = _Session();
      addTearDown(session.close);
      store = MemoryLinkSettingsStore();
      await tester.binding.setSurfaceSize(const Size(480, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final opened = <FlightDestination>[];
      Widget app() => MaterialApp(
          home: Builder(
              builder: (context) => FlightScreen(
                  session: session,
                  settings: store,
                  open: (destination) {
                    opened.add(destination);
                    return Navigator.of(context).push<void>(MaterialPageRoute(
                        builder: (_) =>
                            LinkSetupScreen(session: session, settings: store)));
                  })));
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      expect(opened, [FlightDestination.linkSetup]);
      await tester.enterText(field('Drone address'), '192.168.0.5');
      await tap(tester, saveButton());
      expect(find.byType(LinkSetupScreen), findsNothing);
      expect(find.byType(FlightScreen), findsOneWidget);
      // Relaunch.
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      expect(opened, [FlightDestination.linkSetup]);
      expect(find.byType(LinkSetupScreen), findsNothing);
    });

    testWidgets('fits a 5-inch phone with large text', (tester) async {
      await open(tester,
          saved: _saved, size: const Size(360, 640), textScale: 1.3);
      expect(tester.takeException(), isNull);
      expect(stopButton().hitTestable(), findsOneWidget);
      await tap(tester, saveButton());
      expect(tester.takeException(), isNull);
      expect(session.methods, contains('connect'));
    });

    testWidgets('fits a 5-inch phone with large text while the pilot has the '
        'mode (STOP shows its hold hint)', (tester) async {
      await open(tester,
          initial: fixture('connected_landed', {
            'vehicle': {'armed': true, 'landedState': 2, 'landedStateName': 'IN_AIR'}
          }),
          saved: _saved,
          size: const Size(360, 640),
          textScale: 1.3);
      expect(tester.takeException(), isNull);
      expect(find.text('Pilot has control — hold 1 s to override'),
          findsOneWidget);
      expect(stopButton().hitTestable(), findsOneWidget);
      await tap(tester, executionSwitch());
      expect(tester.takeException(), isNull);
      expect(inDialog('Fly the drone'), findsOneWidget);
      expect(stopButton().hitTestable(), findsOneWidget);
      await tap(tester, inDialog('Cancel'));
      await tap(tester, saveButton());
      expect(tester.takeException(), isNull);
      expect(find.text('Saved.'), findsOneWidget);
    });
  });

  group('notifications', () {
    testWidgets('Save & Connect asks once, with its reason, before connecting; '
        'a denial still connects', (tester) async {
      await open(tester,
          saved: _saved, notifications: NotificationPermission.askable);
      expect(find.text(notificationsReason), findsOneWidget);
      final dialog = session.notificationDialog =
          Completer<NotificationPermission>();
      session.connectError = PlatformException(
          code: 'CONNECT_FAILED', message: 'Cannot open the UDP link: busy');
      await tester.ensureVisible(saveButton());
      await tester.pumpAndSettle();
      await tester.tap(saveButton());
      await tester.pump();
      expect(session.argumentsOf('notificationPermission').last, {'ask': true});
      // The system dialog is answered before any link opens.
      expect(session.methods, isNot(contains('connect')));
      dialog.complete(NotificationPermission.denied);
      await tester.pumpAndSettle();
      expect(session.methods, contains('connect'));
      expect(find.text('Cannot open the UDP link: busy'), findsOneWidget);
      expect(find.text(notificationsReason), findsNothing);
      // Asked once: the next Save connects without asking again.
      session.connectError = null;
      await tap(tester, saveButton());
      expect(session.argumentsOf('connect'), hasLength(2));
      expect(
          session
              .argumentsOf('notificationPermission')
              .where((a) => (a as Map)['ask'] == true),
          hasLength(1));
      expect(store.saved.last, _saved.copyWith(autoConnect: true));
      expect(find.byType(LinkSetupScreen), findsNothing);
    });

    testWidgets('a permission call that fails never blocks connecting',
        (tester) async {
      await open(tester,
          saved: _saved, notifications: NotificationPermission.askable);
      final dialog = session.notificationDialog =
          Completer<NotificationPermission>();
      await tester.ensureVisible(saveButton());
      await tester.pumpAndSettle();
      await tester.tap(saveButton());
      await tester.pump();
      dialog.completeError(MissingPluginException());
      await tester.pumpAndSettle();
      expect(session.methods, contains('connect'));
      expect(find.byType(LinkSetupScreen), findsNothing);
    });

    testWidgets('while connected, Save never opens the dialog over STOP',
        (tester) async {
      await open(tester,
          initial: fixture('connected_landed'),
          saved: _saved,
          notifications: NotificationPermission.askable);
      expect(find.text(notificationsReason), findsNothing);
      await tap(tester, saveButton());
      expect(find.text('Saved.'), findsOneWidget);
      expect(
          session
              .argumentsOf('notificationPermission')
              .where((a) => (a as Map)['ask'] == true),
          isEmpty);
    });

    for (final state in [
      NotificationPermission.granted,
      NotificationPermission.denied
    ]) {
      testWidgets('${state.name}: no reason line and no dialog', (tester) async {
        await open(tester, saved: _saved, notifications: state);
        expect(find.text(notificationsReason), findsNothing);
        await tap(tester, saveButton());
        expect(session.methods, contains('connect'));
        expect(
            session
                .argumentsOf('notificationPermission')
                .where((a) => (a as Map)['ask'] == true),
            isEmpty);
      });
    }
  });

  group('execution', () {
    testWidgets('is Practice (dry run) by default and needs a drone that '
        'answers', (tester) async {
      await open(tester, saved: _saved);
      expect(execution(tester).value, isFalse);
      expect(execution(tester).onChanged, isNull);
      expect(find.text('Fly the drone'), findsOneWidget);
      expect(find.textContaining('Practice (dry run)'), findsWidgets);
      expect(find.textContaining('Connect first'), findsOneWidget);
      // A link without a heartbeat is not enough.
      session.pushFixture('connected_landed', {
        'link': {'state': 'connecting', 'heartbeatAgeMs': null},
        'vehicle': {'connected': false},
      });
      await tester.pumpAndSettle();
      expect(execution(tester).onChanged, isNull);
      session.pushFixture('connected_landed');
      await tester.pumpAndSettle();
      expect(execution(tester).value, isFalse);
      expect(execution(tester).onChanged, isNotNull);
      expect(find.textContaining('Connect first'), findsNothing);
      expect(executionCalls(), isEmpty);
    });

    testWidgets('turning it on shows the notice first; declining keeps '
        'Practice and asks the service nothing', (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      await tap(tester, executionSwitch());
      // Until the spec's 8.4 real-hardware prerequisites pass, the notice is
      // about the simulator from its title on (v0.2.0's framing).
      expect(find.text('Send plans to the simulator?'), findsOneWidget);
      expect(find.text('Fly the drone?'), findsNothing);
      Finder notice(String text) => find.descendant(
          of: find.byType(AlertDialog), matching: find.textContaining(text));
      expect(
          notice('Confirmed plans will be sent over MAVLink to '
              'UDP 192.168.0.5:14550 local *:14551 and flown.'),
          findsOneWidget);
      expect(notice('Confirmed plans will be sent to the drone'), findsNothing);
      expect(notice('Takeoff starts the motors'), findsOneWidget);
      // v0.2.0's meaning, kept until the real-hardware profile is unlocked
      // (spec S6, 8.4): a simulator only, which the app cannot verify.
      expect(notice('Use only an isolated ArduPilot simulator'), findsOneWidget);
      expect(notice('this app cannot verify that the link leads to a simulator'),
          findsOneWidget);
      expect(notice('Never use this with a real aircraft.'), findsOneWidget);
      for (final loosened in ['tethered', 'caged', 'RC pilot', 'real-flight']) {
        expect(notice(loosened), findsNothing, reason: loosened);
      }
      // STOP stays above the notice.
      expect(stopButton().hitTestable(), findsOneWidget);
      await tap(tester, inDialog('Cancel'));
      expect(find.byType(AlertDialog), findsNothing);
      expect(execution(tester).value, isFalse);
      expect(executionCalls(), isEmpty);
    });

    testWidgets('agreeing asks for telemetry, then turns it on for this '
        'connection only, never saved', (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      await enable(tester);
      expect(executionCalls(), ['requestTelemetry', 'setExecution']);
      expect(session.argumentsOf('setExecution').single, {'enabled': true});
      expect(execution(tester).value, isTrue);
      expect(find.textContaining('On:'), findsOneWidget);
      expect(store.saved, isEmpty);
    });

    testWidgets('cancelling while it is being turned on keeps Practice',
        (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      final gate = session.telemetryGate = Completer<void>();
      await tap(tester, executionSwitch());
      await tester.tap(inDialog('Fly the drone'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      final cancel = find.byKey(const Key('cancelExecution'));
      expect(cancel, findsOneWidget);
      await tester.ensureVisible(cancel);
      await tester.tap(cancel);
      await tester.pump();
      gate.complete();
      await tester.pumpAndSettle();
      expect(execution(tester).value, isFalse);
      expect(session.methods, isNot(contains('setExecution')));
      expect(cancel, findsNothing);
    });

    for (final (how, abandon) in [
      (
        'Cancel',
        (WidgetTester tester) async {
          await tester.tap(find.byKey(const Key('cancelExecution')));
        }
      ),
      (
        'leaving the screen',
        (WidgetTester tester) async {
          await tester.pageBack();
        }
      ),
    ]) {
      testWidgets('$how while the service is turning it on turns it back off',
          (tester) async {
        await open(tester, initial: fixture('connected_landed'), saved: _saved);
        final gate = session.executionGate = Completer<void>();
        await tap(tester, executionSwitch());
        await tester.tap(inDialog('Fly the drone'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
        expect(session.argumentsOf('setExecution'), [
          {'enabled': true}
        ]);
        await abandon(tester);
        await tester.pump();
        gate.complete();
        await tester.pumpAndSettle();
        expect(session.argumentsOf('setExecution'), [
          {'enabled': true},
          {'enabled': false}
        ]);
        if (how == 'Cancel') expect(execution(tester).value, isFalse);
      });
    }

    testWidgets('after Cancel, the switch waits until the abandoned turn-on '
        'and its undo have answered', (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      final gate = session.executionGate = Completer<void>();
      await tap(tester, executionSwitch());
      await tester.tap(inDialog('Fly the drone'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.byKey(const Key('cancelExecution')));
      await tester.pump();
      // A new turn-on now could land before the old call's undo: not offered.
      expect(execution(tester).value, isFalse);
      expect(execution(tester).onChanged, isNull);
      gate.complete();
      await tester.pumpAndSettle();
      expect(session.argumentsOf('setExecution'), [
        {'enabled': true},
        {'enabled': false}
      ]);
      expect(execution(tester).value, isFalse);
      expect(execution(tester).onChanged, isNotNull);
    });

    for (final (what, patch, message) in <(String, Map<String, Object?>, String)>[
      ('the same link', {}, executionRefused),
      (
        'another link',
        {
          'link': {'description': 'UDP 10.0.0.1:14550 local *:14551'}
        },
        linkChangedWhileEnabling
      ),
    ]) {
      testWidgets("a later snapshot that contradicts the service's answer "
          'says so ($what)', (tester) async {
        await open(tester, initial: fixture('connected_landed'), saved: _saved);
        await enable(tester);
        expect(execution(tester).value, isTrue);
        session.pushFixture('connected_landed', patch);
        await tester.pumpAndSettle();
        expect(session.methods.last, 'getSnapshot',
            reason: 'asked again: the snapshot may predate the answer');
        expect(execution(tester).value, isFalse);
        expect(find.text(message), findsOneWidget);
      });
    }

    testWidgets('a snapshot built before the answer does not contradict it',
        (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      await enable(tester);
      // Built before setExecution ran, delivered after its answer.
      session.pushFixture('connected_landed');
      session.current = fixture('connected_landed', {
        'seq': 99999,
        'execution': {'enabled': true}
      });
      await tester.pumpAndSettle();
      expect(execution(tester).value, isTrue);
      expect(find.text(executionRefused), findsNothing);
      expect(find.text(linkChangedWhileEnabling), findsNothing);
    });

    testWidgets('a service that cannot turn it back off after Cancel shows it '
        'is still on', (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      final gate = session.executionGate = Completer<void>();
      session.disableError =
          PlatformException(code: 'INTERNAL', message: 'The service stopped.');
      await tap(tester, executionSwitch());
      await tester.tap(inDialog('Fly the drone'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.byKey(const Key('cancelExecution')));
      await tester.pump();
      gate.complete();
      await tester.pumpAndSettle();
      expect(session.argumentsOf('setExecution'), [
        {'enabled': true},
        {'enabled': false}
      ]);
      // The service said on and never said off: the switch must not claim
      // Practice.
      expect(execution(tester).value, isTrue);
      expect(find.text(executionStillOn), findsOneWidget);
      expect(execution(tester).onChanged, isNotNull);
    });

    for (final (what, name, patch) in <(String, String, Map<String, Object?>)>[
      (
        'another link',
        'connected_landed',
        {
          'link': {'description': 'UDP 10.0.0.1:14550 local *:14551'}
        }
      ),
      (
        'a drone that stopped answering',
        'connected_landed',
        {
          'vehicle': {'connected': false}
        }
      ),
      ('a closed link', 'idle_disconnected', {}),
    ]) {
      testWidgets('$what while the notice is open keeps Practice and asks the '
          'service nothing', (tester) async {
        await open(tester, initial: fixture('connected_landed'), saved: _saved);
        await tap(tester, executionSwitch());
        expect(find.text('Send plans to the simulator?'), findsOneWidget);
        session.pushFixture(name, patch);
        await tester.pumpAndSettle();
        await tap(tester, inDialog('Fly the drone'));
        expect(find.byType(AlertDialog), findsNothing);
        expect(executionCalls(), isEmpty);
        expect(find.text(linkChangedWhileEnabling), findsOneWidget);
        expect(execution(tester).value, isFalse);
      });
    }

    testWidgets('a failed telemetry request keeps Practice and says why',
        (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      session.telemetryQueued = false;
      await enable(tester);
      expect(execution(tester).value, isFalse);
      expect(find.text(telemetryNotRequested), findsOneWidget);
      session.telemetryQueued = true;
      session.telemetryError =
          PlatformException(code: 'INTERNAL', message: 'The link closed.');
      await enable(tester);
      expect(execution(tester).value, isFalse);
      expect(find.text('The link closed.'), findsOneWidget);
      expect(session.methods, isNot(contains('setExecution')));
    });

    testWidgets('a link that changes while it is being turned on keeps '
        'Practice', (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      final gate = session.telemetryGate = Completer<void>();
      await tap(tester, executionSwitch());
      await tester.tap(inDialog('Fly the drone'));
      await tester.pump();
      session.pushFixture('connected_landed', {
        'link': {'description': 'UDP 10.0.0.1:14550 local *:14551'}
      });
      await tester.pump();
      gate.complete();
      await tester.pumpAndSettle();
      expect(execution(tester).value, isFalse);
      expect(session.methods, isNot(contains('setExecution')));
      expect(find.text(linkChangedWhileEnabling), findsOneWidget);
    });

    testWidgets('the service can keep Practice', (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      session.executionReply = false;
      await enable(tester);
      expect(execution(tester).value, isFalse);
      expect(find.text(executionRefused), findsOneWidget);
    });

    testWidgets('turning it off needs no notice and says it stops a running '
        'plan', (tester) async {
      await open(tester, initial: fixture('executing_move'), saved: _saved);
      expect(execution(tester).value, isTrue);
      expect(find.textContaining('stops the drone'), findsOneWidget);
      await tap(tester, executionSwitch());
      expect(find.byType(AlertDialog), findsNothing);
      expect(session.argumentsOf('setExecution').single, {'enabled': false});
      expect(session.methods, isNot(contains('requestTelemetry')));
      expect(execution(tester).value, isFalse);
    });

    testWidgets('the switch follows the service', (tester) async {
      await open(tester, initial: fixture('connected_landed'), saved: _saved);
      session.pushFixture('executing_move');
      await tester.pumpAndSettle();
      expect(execution(tester).value, isTrue);
      // Disconnecting ends execution in the service.
      session.pushFixture('idle_disconnected');
      await tester.pumpAndSettle();
      expect(execution(tester).value, isFalse);
      expect(execution(tester).onChanged, isNull);
    });
  });
}
