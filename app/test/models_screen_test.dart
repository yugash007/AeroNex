import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/core/local_models.dart';
import 'package:aeronex/features/flight/presentation/widgets/stop_button.dart';
import 'package:aeronex/features/models/data/speech_packs.dart';
import 'package:aeronex/features/models/presentation/models_screen.dart';
import 'package:aeronex/features/session/session_api.dart';
import 'package:aeronex/features/voice/presentation/model_setup_sheet.dart';

import 'support/fake_session_api.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  // aeronex/inference: the offline speech pack inventory (ModelSetupBridge).
  late Map<String, String> states;
  late List<String> downloads;
  late int statusCalls;
  PlatformException? statusError;
  Completer<void>? statusGate;

  // The voice packs (0.4.0): the bridge's `packs` rows, keyed by id.
  late Map<String, Map<String, Object?>> packs;
  late bool metered;
  late List<String> packDownloads, packCancels;
  late Map<String, Completer<void>> downloadReplies;
  Map<String, Object?> pack(String id, String kind, String language, int bytes,
          {String state = 'missing',
          bool usable = true,
          String? reason,
          String? note}) =>
      {
        'id': id,
        'kind': kind,
        'language': language,
        'bytes': bytes,
        'state': state,
        'usable': usable,
        'reason': reason,
        'note': note,
        'downloadable': true,
        'install': null,
        'error': null,
      };

  setUp(() {
    states = {
      'vosk-te': 'ready',
      'vosk-hi': 'missing',
      'vosk-en-in': 'invalid',
    };
    downloads = [];
    statusCalls = 0;
    statusError = null;
    statusGate = null;
    metered = true;
    packDownloads = [];
    packCancels = [];
    downloadReplies = {};
    packs = {
      for (final p in [
        pack('indicconformer-te-ctc-int8', 'ctc', 'te', 197663298,
            state: 'ready', note: 'Telugu voice STOP keeps using Vosk.'),
        pack('indicconformer-ta-ctc-int8', 'ctc', 'ta', 197663118,
            note: 'With this pack, voice STOP is the English word "stop".'),
        pack('indicconformer-kn-ctc-int8', 'ctc', 'kn', 197663333),
        pack('indicconformer-ml-ctc-int8', 'ctc', 'ml', 197663160),
        pack('kws-gigaspeech-3.3M-chunk-16', 'kws', 'en', 6283571),
      ])
        p['id'] as String: p
    };
    messenger.setMockMethodCallHandler(const MethodChannel('aeronex/inference'),
        (call) async {
      switch (call.method) {
        case 'status':
          statusCalls++;
          if (statusGate != null) await statusGate!.future;
          if (statusError != null) throw statusError!;
          return {
            'models': {for (final e in states.entries) e.key: e.value == 'ready'},
            'modelStates': {...states},
            'transfer': null,
          };
        case 'downloadModel':
          final profile = (call.arguments as Map)['profile'] as String;
          downloads.add(profile);
          states[profile] = 'ready';
          return null;
        case 'packs':
          return {
            'tier': 'M',
            'metered': metered,
            'packs': [for (final p in packs.values) {...p}],
          };
        case 'downloadPack':
          final id = (call.arguments as Map)['id'] as String;
          packDownloads.add(id);
          packs[id]!['install'] = {
            'source': 'download',
            'receivedBytes': 0,
            'totalBytes': packs[id]!['bytes'],
          };
          await (downloadReplies[id] = Completer<void>()).future;
          return null;
        case 'cancelPack':
          packCancels.add((call.arguments as Map)['id'] as String);
          return null;
        default:
          return null;
      }
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(
      const MethodChannel('aeronex/inference'), null));

  Future<FakeSessionApi> open(WidgetTester tester,
      {Size size = const Size(480, 2400),
      double textScale = 1.0,
      bool settle = true,
      FakeSessionApi? fake}) async {
    final session = fake ?? FakeSessionApi();
    addTearDown(session.close);
    await tester.binding.setSurfaceSize(size);
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
        builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(textScale)),
            child: StopLane(session: session, child: child!)),
        home: ModelsScreen(session: session)));
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pump(const Duration(milliseconds: 50));
    }
    return session;
  }

  Finder row(String language) => find.byKey(ValueKey('speech-$language'));
  Finder inRow(String language, Finder finder) =>
      find.descendant(of: row(language), matching: finder);
  Finder buttonsIn(String language) =>
      inRow(language, find.byWidgetPredicate((w) => w is ButtonStyleButton));

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  testWidgets('each language shows its offline speech status in plain words, '
      'with at most one action', (tester) async {
    await open(tester);
    expect(inRow('te', find.text('Telugu')), findsOneWidget);
    expect(inRow('te', find.text('Ready')), findsOneWidget);
    expect(buttonsIn('te'), findsNothing);
    expect(inRow('hi', find.text('Hindi')), findsOneWidget);
    expect(inRow('hi', find.text('Not installed')), findsOneWidget);
    expect(buttonsIn('hi'), findsOneWidget);
    expect(inRow('hi', find.text('Set up')), findsOneWidget);
    expect(inRow('en', find.text('Indian English')), findsOneWidget);
    expect(inRow('en', find.text('Needs repair')), findsOneWidget);
    expect(buttonsIn('en'), findsOneWidget);
    expect(inRow('en', find.text('Repair')), findsOneWidget);
    // Speech is optional: the buttons, typing and STOP never need a pack.
    expect(find.textContaining('typed commands'), findsOneWidget);
    // Whisper and Whistle are gone (0.3.0): nothing mentions them. LFM and
    // Needle are back in 0.4.0, as the AI rows at the end.
    for (final removed in ['Whisper', 'Whistle']) {
      expect(find.textContaining(removed), findsNothing);
    }
  });

  test('the only offline models are the Vosk speech packs', () {
    expect(localModels.keys.toSet(), {'vosk-en-in', 'vosk-te', 'vosk-hi'});
  });

  testWidgets('says Checking while the phone looks, with no action yet',
      (tester) async {
    final gate = statusGate = Completer<void>();
    await open(tester);
    for (final language in ['te', 'hi', 'en']) {
      expect(inRow(language, find.text('Checking…')), findsOneWidget);
      expect(buttonsIn(language), findsNothing);
    }
    statusGate = null;
    gate.complete();
    await tester.pumpAndSettle();
    expect(inRow('te', find.text('Ready')), findsOneWidget);
  });

  testWidgets('Set up opens setup for that language only; the row updates '
      'after', (tester) async {
    await open(tester);
    await tap(tester, inRow('hi', find.text('Set up')));
    expect(find.byType(ModelSetupSheet), findsOneWidget);
    expect(
        tester.widget<ModelSetupSheet>(find.byType(ModelSetupSheet)).profiles,
        ['vosk-hi']);
    expect(find.text('Download Vosk Hindi'), findsOneWidget);
    expect(find.textContaining('Vosk Telugu'), findsNothing);
    // The old sheet text asked for an AI model; v2 has none.
    expect(find.textContaining('Local AI'), findsNothing);
    await tap(tester, find.text('Download Vosk Hindi'));
    expect(downloads, ['vosk-hi']);
    await tap(tester, find.byTooltip('Close model setup'));
    expect(find.byType(ModelSetupSheet), findsNothing);
    expect(inRow('hi', find.text('Ready')), findsOneWidget);
    expect(buttonsIn('hi'), findsNothing);
  });

  testWidgets('Repair opens setup for a pack that failed its check',
      (tester) async {
    await open(tester);
    await tap(tester, inRow('en', find.text('Repair')));
    expect(
        tester.widget<ModelSetupSheet>(find.byType(ModelSetupSheet)).profiles,
        ['vosk-en-in']);
    expect(find.textContaining('File needs repair'), findsOneWidget);
  });

  // --- Voice packs (0.4.0): IndicConformer te/ta/kn/ml and the voice STOP pack ---

  Finder packRow(String id) => find.byKey(ValueKey('pack-$id'));
  Finder inPack(String id, Finder finder) =>
      find.descendant(of: packRow(id), matching: finder);
  Finder packButtons(String id) =>
      inPack(id, find.byWidgetPredicate((w) => w is ButtonStyleButton));
  const ta = 'indicconformer-ta-ctc-int8';
  const kws = 'kws-gigaspeech-3.3M-chunk-16';

  /// While a pack installs the screen polls every 500 ms: pump, never settle.
  Future<void> poll(WidgetTester tester) async {
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump();
  }

  void progress(String id, int received, {String source = 'download'}) =>
      packs[id]!['install'] = {
        'source': source,
        'receivedBytes': received,
        'totalBytes': packs[id]!['bytes'],
      };

  testWidgets('Telugu, Tamil, Kannada, Malayalam and the voice STOP pack each '
      'show their size and at most one action', (tester) async {
    await open(tester);
    for (final (id, name) in [
      ('indicconformer-ta-ctc-int8', 'Tamil'),
      ('indicconformer-kn-ctc-int8', 'Kannada'),
      ('indicconformer-ml-ctc-int8', 'Malayalam'),
    ]) {
      expect(inPack(id, find.text(name)), findsOneWidget);
      expect(inPack(id, find.text('Not installed')), findsOneWidget);
      expect(inPack(id, find.text('198 MB download')), findsOneWidget);
      expect(packButtons(id), findsOneWidget);
      expect(inPack(id, find.text('Download')), findsOneWidget);
    }
    const te = 'indicconformer-te-ctc-int8';
    expect(inPack(te, find.text('Telugu (IndicConformer)')), findsOneWidget);
    expect(inPack(te, find.text('Installed')), findsOneWidget);
    expect(packButtons(te), findsNothing);
    expect(inPack(kws, find.text('Voice STOP (English "stop")')),
        findsOneWidget);
    expect(inPack(kws, find.text('6.3 MB download')), findsOneWidget);
    expect(find.textContaining('Coming in the next update'), findsNothing);
  });

  testWidgets('each pack says which voice STOP it gives', (tester) async {
    await open(tester);
    expect(
        inPack(ta,
            find.text('With this pack, voice STOP is the English word "stop".')),
        findsOneWidget);
    expect(
        inPack('indicconformer-te-ctc-int8',
            find.text('Telugu voice STOP keeps using Vosk.')),
        findsOneWidget);
  });

  testWidgets('Download asks first with the size and a Wi-Fi hint; Not now '
      'downloads nothing', (tester) async {
    await open(tester);
    await tap(tester, inPack(ta, find.text('Download')));
    expect(find.text('Download the Tamil voice pack?'), findsOneWidget);
    expect(find.textContaining('About 198 MB'), findsOneWidget);
    final dialog = find.byType(AlertDialog);
    expect(find.descendant(of: dialog, matching: find.textContaining('Wi-Fi')),
        findsOneWidget);
    expect(find.textContaining('on mobile data now'), findsOneWidget);
    await tap(tester, find.text('Not now'));
    expect(packDownloads, isEmpty);
    expect(inPack(ta, find.text('Not installed')), findsOneWidget);
  });

  testWidgets('on Wi-Fi the dialog does not warn about mobile data',
      (tester) async {
    metered = false;
    await open(tester);
    await tap(tester, inPack(kws, find.text('Download')));
    expect(find.textContaining('About 6.3 MB'), findsOneWidget);
    expect(find.textContaining('on mobile data now'), findsNothing);
  });

  testWidgets('a download shows its progress with Cancel, then Installed',
      (tester) async {
    await open(tester);
    await tap(tester, inPack(ta, find.text('Download')));
    await tester.tap(find.byKey(const Key('confirmPackDownload')));
    await tester.pump();
    expect(packDownloads, [ta]);
    progress(ta, 98831559);
    await poll(tester);
    expect(inPack(ta, find.text('Downloading… 50%')), findsOneWidget);
    expect(inPack(ta, find.byType(LinearProgressIndicator)), findsOneWidget);
    expect(packButtons(ta), findsOneWidget);
    expect(inPack(ta, find.text('Cancel')), findsOneWidget);
    packs[ta]!['install'] = null;
    packs[ta]!['state'] = 'ready';
    downloadReplies[ta]!.complete();
    await poll(tester);
    await tester.pumpAndSettle();
    expect(inPack(ta, find.text('Installed')), findsOneWidget);
    expect(packButtons(ta), findsNothing);
  });

  testWidgets('Cancel stops the download and the row offers it again',
      (tester) async {
    await open(tester);
    await tap(tester, inPack(kws, find.text('Download')));
    await tester.tap(find.byKey(const Key('confirmPackDownload')));
    await tester.pump();
    progress(kws, 1000000);
    await poll(tester);
    await tester.tap(inPack(kws, find.text('Cancel')));
    await tester.pump();
    expect(packCancels, [kws]);
    packs[kws]!['install'] = null;
    downloadReplies[kws]!.completeError(
        PlatformException(code: 'CANCELLED', message: 'Voice pack download cancelled.'));
    await poll(tester);
    await tester.pumpAndSettle();
    expect(inPack(kws, find.text('Not installed')), findsOneWidget);
    expect(inPack(kws, find.text('Download')), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('a failed download keeps its reason on the row', (tester) async {
    packs[ta]!['error'] =
        'The model file is incomplete or does not match model.int8.onnx. Download it again.';
    await open(tester);
    expect(inPack(ta, find.textContaining('does not match')), findsOneWidget);
    expect(inPack(ta, find.text('Download')), findsOneWidget);
  });

  testWidgets('a pack that is copying in from the import folder says '
      'Installing voice pack', (tester) async {
    progress(ta, 49415780, source: 'import');
    await open(tester, settle: false);
    expect(inPack(ta, find.text('Installing voice pack… 25%')), findsOneWidget);
    expect(inPack(ta, find.text('Cancel')), findsOneWidget);
    packs[ta]!['install'] = null;
    packs[ta]!['state'] = 'ready';
    await poll(tester);
    await tester.pumpAndSettle();
    expect(inPack(ta, find.text('Installed')), findsOneWidget);
  });

  testWidgets('a pack that is not for this phone says why and offers nothing',
      (tester) async {
    packs[ta] = pack(ta, 'ctc', 'ta', 197663118,
        usable: false,
        reason: 'Needs a 64-bit phone with 4 GB of memory or more.');
    await open(tester);
    expect(inPack(ta, find.text('Not for this phone')), findsOneWidget);
    expect(
        inPack(ta,
            find.text('Needs a 64-bit phone with 4 GB of memory or more.')),
        findsOneWidget);
    expect(packButtons(ta), findsNothing);
  });

  testWidgets('a pack that failed its check offers Download again',
      (tester) async {
    packs[ta]!['state'] = 'invalid';
    await open(tester);
    expect(inPack(ta, find.text('Needs repair')), findsOneWidget);
    expect(inPack(ta, find.text('Download again')), findsOneWidget);
  });

  test('pack sizes read in decimal megabytes', () {
    expect(megabytes(197663118), '198 MB');
    expect(megabytes(6283571), '6.3 MB');
  });

  testWidgets('a failed check says so and can be tried again', (tester) async {
    statusError = PlatformException(code: 'BUSY', message: 'Busy.');
    await open(tester);
    expect(find.text(modelsCheckFailed), findsOneWidget);
    for (final language in ['te', 'hi', 'en']) {
      expect(inRow(language, find.text('Not checked')), findsOneWidget);
      expect(buttonsIn(language), findsNothing);
    }
    statusError = null;
    final before = statusCalls;
    await tap(tester, find.text('Try again'));
    expect(statusCalls, before + 1);
    expect(find.text(modelsCheckFailed), findsNothing);
    expect(inRow('te', find.text('Ready')), findsOneWidget);
  });

  testWidgets('checks again when the app comes back', (tester) async {
    await open(tester);
    final before = statusCalls;
    states['vosk-hi'] = 'ready';
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(statusCalls, greaterThan(before));
    expect(inRow('hi', find.text('Ready')), findsOneWidget);
  });

  testWidgets('fits a 5-inch phone with large text; STOP stays above',
      (tester) async {
    final session = await open(tester,
        size: const Size(360, 640), textScale: 1.3);
    expect(tester.takeException(), isNull);
    await tester.scrollUntilVisible(packRow(kws), 200,
        scrollable: find.byType(Scrollable).last);
    await tester.pumpAndSettle();
    expect(inPack(kws, find.text('Download')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const Key('stopButton')));
    await tester.pump();
    expect(session.methods.last, 'stop');
  });

  group('AI models (0.4.0)', () {
    AiModelsStatus aiStatus(
            {bool available = false,
            String? reason = 'Download the AI models in Models',
            String lfm = 'missing',
            String needle = 'ready',
            AiModelTransfer? transfer,
            String? errorId,
            String? error}) =>
        AiModelsStatus(
            available: available,
            reason: reason,
            models: [
              AiModel(
                  id: 'lfm-q4',
                  file: 'LFM2.5-VL-450M-Q4_K_M.gguf',
                  bytes: 229313568,
                  state: lfm,
                  importPath: '/storage/emulated/0/Android/data/app/files/import/lfm-q4/'
                      'LFM2.5-VL-450M-Q4_K_M.gguf'),
              AiModel(id: 'needle3', file: 'needle3.cact', bytes: 35335380, state: needle),
            ],
            transfer: transfer,
            errorId: errorId,
            error: error);
    Finder aiRow(String id) => find.byKey(ValueKey('ai-$id'));
    Finder inAi(String id, Finder finder) => find.descendant(of: aiRow(id), matching: finder);

    Future<FakeSessionApi> openWith(WidgetTester tester, AiModelsStatus status) async {
      final session = FakeSessionApi()..aiModelsStatus = status;
      await open(tester, fake: session);
      await tester.scrollUntilVisible(aiRow('needle3'), 200,
          scrollable: find.byType(Scrollable).last);
      await tester.pumpAndSettle();
      return session;
    }

    testWidgets('say whether the AI can help, and why not in plain words',
        (tester) async {
      await openWith(tester, aiStatus(reason: 'Needs a 64-bit ARM phone'));
      expect(find.text('AI is off: Needs a 64-bit ARM phone.'), findsOneWidget);
      expect(inAi('lfm-q4', find.text('AI planner (LFM2.5)')), findsOneWidget);
      expect(inAi('lfm-q4', find.text('Not installed')), findsOneWidget);
      expect(inAi('lfm-q4', find.textContaining('229 MB')), findsOneWidget);
      expect(inAi('lfm-q4', find.textContaining('To import, copy the file to')), findsOneWidget);
      expect(inAi('needle3', find.text('Installed')), findsOneWidget);
      expect(inAi('needle3', find.text('Remove')), findsOneWidget);
    });

    testWidgets('Download and Remove ask first, Import starts at once; the '
        'service does each', (tester) async {
      final session = await openWith(tester, aiStatus());
      await tap(tester, inAi('lfm-q4', find.text('Download')));
      expect(find.textContaining('Use Wi-Fi'), findsOneWidget);
      await tap(tester, find.byKey(const Key('confirmAiDownload')));
      expect(session.argumentsOf('downloadAiModel'), [
        {'id': 'lfm-q4'}
      ]);
      await tap(tester, inAi('lfm-q4', find.text('Import')));
      expect(session.argumentsOf('importAiModel'), [
        {'id': 'lfm-q4'}
      ]);
      await tap(tester, inAi('needle3', find.text('Remove')));
      await tap(tester, find.byKey(const Key('confirmAiRemove')));
      expect(session.argumentsOf('removeAiModel'), [
        {'id': 'needle3'}
      ]);
    });

    testWidgets('a running download shows its progress and Cancel; a failure '
        'shows why', (tester) async {
      final session = await openWith(
          tester,
          aiStatus(
              transfer: const AiModelTransfer('lfm-q4', 'download', 57328392, 229313568),
              errorId: 'needle3',
              error: 'Cancelled.'));
      expect(inAi('lfm-q4', find.text('Downloading… 25%')), findsOneWidget);
      expect(inAi('needle3', find.text('Cancelled.')), findsOneWidget);
      await tap(tester, inAi('lfm-q4', find.text('Cancel')));
      expect(session.methods, contains('cancelAiModel'));
      session.aiModelsStatus = aiStatus(available: true, reason: null, lfm: 'ready');
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      expect(find.text('AI is ready.'), findsOneWidget);
      expect(inAi('lfm-q4', find.text('Installed')), findsOneWidget);
    });
  });
}
