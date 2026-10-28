import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/voice/presentation/model_setup_sheet.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() => messenger.setMockMethodCallHandler(
      const MethodChannel('aeronex/inference'),
      (call) async => call.method == 'status'
          ? {
              'models': {'vosk-hi': false},
              'modelStates': {'vosk-hi': 'missing'},
            }
          : null));
  tearDown(() => messenger.setMockMethodCallHandler(
      const MethodChannel('aeronex/inference'), null));

  testWidgets('the closing line fits wherever the sheet was opened from',
      (tester) async {
    // Models opens it as well as Speak: no "tap Speak again".
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(body: ModelSetupSheet(profiles: ['vosk-hi']))));
    await tester.pumpAndSettle();
    expect(
        find.text('Close this panel when the model is installed. Typed '
            'commands, the buttons and STOP need no model.'),
        findsOneWidget);
    expect(find.textContaining('Speak again'), findsNothing);
  });
}
