import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aeronex/features/link/data/link_settings.dart';
import 'package:aeronex/features/session/session_api.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late Directory dir;
  Map<String, Object?>? legacy;

  setUp(() {
    dir = Directory.systemTemp.createTempSync('aeronex-link-settings');
    legacy = null;
    // The v0.2.0 store: `simSettings` on aeronex/inference.
    messenger.setMockMethodCallHandler(const MethodChannel('aeronex/inference'),
        (call) async => call.method == 'simSettings' ? legacy : null);
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(
        const MethodChannel('aeronex/inference'), null);
    dir.deleteSync(recursive: true);
  });

  DeviceLinkSettingsStore store() =>
      DeviceLinkSettingsStore(directory: () async => dir);

  const radio = LinkSettings(
      link: LinkConfig(
          transport: LinkTransport.tcp,
          host: '10.0.0.1',
          remotePort: 5760,
          localPort: 0,
          radioLink: true),
      autoConnect: true);

  test('nothing saved, and the untouched v0.2.0 defaults, mean first launch',
      () async {
    expect(await store().load(), isNull);
    legacy = {
      'enabled': false,
      'host': '192.168.1.100',
      'remotePort': 14550,
      'localPort': 14551
    };
    expect(await store().load(), isNull);
  });

  test('saves every setting, the radio link included, across launches',
      () async {
    await store().save(radio);
    final loaded = await store().load();
    expect(loaded, radio);
    expect(loaded!.link.radioLink, isTrue);
    expect(loaded.link.transport, LinkTransport.tcp);
    expect(loaded.autoConnect, isTrue);
    await store().save(radio.copyWith(autoConnect: false));
    expect((await store().load())!.autoConnect, isFalse);
    // No temporary file is left beside the settings.
    expect(dir.listSync().map((e) => e.uri.pathSegments.last),
        [DeviceLinkSettingsStore.fileName]);
  });

  test('a remembered v0.2.0 SITL endpoint carries over without auto-connect',
      () async {
    legacy = {
      'enabled': true,
      'host': '192.168.0.9',
      'remotePort': 14560,
      'localPort': 14561
    };
    final loaded = await store().load();
    expect(
        loaded,
        const LinkSettings(
            link: LinkConfig(
                host: '192.168.0.9', remotePort: 14560, localPort: 14561),
            autoConnect: false));
    expect(loaded!.link.radioLink, isFalse);
  });

  test('a v0.2.0 endpoint saved with SITL mode off carries over too',
      () async {
    legacy = {
      'enabled': false,
      'host': '192.168.0.9',
      'remotePort': 14550,
      'localPort': 14551
    };
    expect(
        await store().load(),
        const LinkSettings(
            link: LinkConfig(
                host: '192.168.0.9', remotePort: 14550, localPort: 14551)));
  });

  test('the saved file wins over the v0.2.0 endpoint', () async {
    legacy = {
      'enabled': true,
      'host': '192.168.0.9',
      'remotePort': 14560,
      'localPort': 14561
    };
    await store().save(radio);
    expect(await store().load(), radio);
  });

  test('an unreadable or invalid file is not trusted', () async {
    final file = File('${dir.path}/${DeviceLinkSettingsStore.fileName}');
    for (final content in [
      '{not json',
      jsonEncode({...radio.toJson(), 'remotePort': 70000}),
      jsonEncode({...radio.toJson(), 'host': ' '}),
      jsonEncode({...radio.toJson(), 'transport': 'usb'}),
      jsonEncode({...radio.toJson(), 'radioLink': 'yes'}),
      jsonEncode([1, 2]),
    ]) {
      file.writeAsStringSync(content);
      expect(await store().load(), isNull, reason: content);
    }
  });

  test('without a settings directory nothing is saved and loading still works',
      () async {
    final none = DeviceLinkSettingsStore(directory: () async => null);
    expect(await none.load(), isNull);
    await expectLater(none.save(radio), throwsStateError);
  });

  test('defaults are the v0.2.0 SITL endpoint over UDP, no radio', () {
    expect(LinkSettings.defaults.link,
        const LinkConfig(host: '192.168.1.100', remotePort: 14550, localPort: 14551));
    expect(LinkSettings.defaults.link.transport, LinkTransport.udp);
    expect(LinkSettings.defaults.autoConnect, isFalse);
  });
}
