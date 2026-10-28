import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/core/local_inference.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('aeronex/inference');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('saved simulator settings round-trip through the channel', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return call.method == 'simSettings'
          ? {
              'enabled': true,
              'host': '10.0.2.2',
              'remotePort': 14560,
              'localPort': 14561
            }
          : null;
    });
    final settings = await LocalInference().simSettings();
    expect([
      settings.enabled,
      settings.host,
      settings.remotePort,
      settings.localPort
    ], [
      true,
      '10.0.2.2',
      14560,
      14561
    ]);
    await LocalInference().saveSimSettings(const SimSettings(
        enabled: true, host: '10.0.2.3', remotePort: 1, localPort: 65535));
    expect(calls.last.method, 'saveSimSettings');
    expect(calls.last.arguments, {
      'enabled': true,
      'host': '10.0.2.3',
      'remotePort': 1,
      'localPort': 65535
    });
  });

  test('missing or mistyped simulator settings fall back to defaults',
      () async {
    for (final stored in <Object?>[
      null,
      'not a map',
      <String, Object?>{},
      {'enabled': 'yes', 'host': 7, 'remotePort': '14560', 'localPort': 1.5},
      {'enabled': true}
    ]) {
      messenger.setMockMethodCallHandler(channel, (call) async => stored);
      final settings = await LocalInference().simSettings();
      expect(settings.enabled, stored is Map && stored['enabled'] == true,
          reason: '$stored');
      expect('${settings.host}:${settings.remotePort}:${settings.localPort}',
          '192.168.1.100:14550:14551',
          reason: '$stored');
    }
    // A failed read must not block startup; defaults keep SITL off.
    messenger.setMockMethodCallHandler(
        channel, (call) async => throw PlatformException(code: 'ERROR'));
    expect((await LocalInference().simSettings()).enabled, isFalse);
    messenger.setMockMethodCallHandler(channel, null);
    expect((await LocalInference().simSettings()).host, '192.168.1.100');
  });

  test('invalid simulator settings are refused by the native side', () async {
    messenger.setMockMethodCallHandler(
        channel,
        (call) async => throw PlatformException(
            code: 'INVALID_SIM_SETTINGS', message: 'Enter an IPv4 address.'));
    await expectLater(
        LocalInference().saveSimSettings(const SimSettings(host: 'sitl.local')),
        throwsA(isA<PlatformException>()
            .having((e) => e.code, 'code', 'INVALID_SIM_SETTINGS')));
  });
}
