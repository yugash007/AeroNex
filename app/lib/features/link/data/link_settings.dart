/// The vehicle link the operator set up, remembered across launches so setup
/// is not repeated (ported from the v0.2.0 saved SITL endpoint).
library;

import 'dart:convert';
import 'dart:io';

import '../../../core/local_inference.dart';
import '../../session/session_api.dart';

/// A failed save never blocks the link; the operator learns why setup may
/// be asked for again.
const linkNotSaved = 'The link settings could not be saved on this phone, so '
    'Link setup may open again next time.';

/// The saved link. Live execution is a per-connection opt-in in the service
/// and is never part of it (S6).
class LinkSettings {
  const LinkSettings({required this.link, this.autoConnect = false});

  /// The v0.2.0 SITL defaults, for a first setup form.
  static const defaults = LinkSettings(
      link: LinkConfig(host: '192.168.1.100', remotePort: 14550, localPort: 14551));

  final LinkConfig link;

  /// Connect at launch: the last session was left connected (v0.2.0
  /// "remembered SITL mode"). Every connection still starts in dry run.
  final bool autoConnect;

  LinkSettings copyWith({LinkConfig? link, bool? autoConnect}) => LinkSettings(
      link: link ?? this.link, autoConnect: autoConnect ?? this.autoConnect);

  Map<String, Object> toJson() => {...link.toMap(), 'autoConnect': autoConnect};

  /// Null unless every field has the shape `connect` accepts.
  static LinkSettings? fromJson(Object? json) {
    if (json is! Map) return null;
    final transport = LinkTransport.values
        .where((t) => t.name == json['transport'])
        .firstOrNull;
    final host = json['host'];
    final remotePort = json['remotePort'];
    final localPort = json['localPort'];
    final radioLink = json['radioLink'];
    final autoConnect = json['autoConnect'];
    if (transport == null ||
        host is! String ||
        host.trim().isEmpty ||
        remotePort is! int ||
        remotePort < 1 ||
        remotePort > 65535 ||
        localPort is! int ||
        localPort < 0 ||
        localPort > 65535 ||
        radioLink is! bool ||
        autoConnect is! bool) return null;
    return LinkSettings(
        link: LinkConfig(
            transport: transport,
            host: host.trim(),
            remotePort: remotePort,
            localPort: localPort,
            radioLink: radioLink),
        autoConnect: autoConnect);
  }

  @override
  bool operator ==(Object other) =>
      other is LinkSettings &&
      other.link == link &&
      other.autoConnect == autoConnect;

  @override
  int get hashCode => Object.hash(link, autoConnect);
}

/// Where [LinkSettings] live.
abstract class LinkSettingsStore {
  /// Null when nothing was saved yet: the first launch opens Link setup.
  Future<LinkSettings?> load();

  Future<void> save(LinkSettings settings);
}

/// The app's private Flutter data directory (path_provider's documents
/// directory, `<data>/app_flutter`). The engine's temporary directory on
/// Android is `<data>/code_cache`, so its parent is the app's data directory.
/// Null on other platforms or any other layout.
Future<Directory?> flutterDataDirectory() async {
  if (!Platform.isAndroid) return null;
  final temp = Directory.systemTemp;
  final name = temp.uri.pathSegments.where((s) => s.isNotEmpty).lastOrNull;
  if (name != 'code_cache' && name != 'cache') return null;
  final data = Directory('${temp.parent.path}/app_flutter');
  return data.create();
}

/// The v0.2.0 store (`aeronex/inference` `simSettings`): its endpoint carries
/// over whether SITL mode was on or off, as v0.2.0 filled its fields either
/// way. The native side answers its defaults when nothing was ever saved, so
/// SITL mode off with exactly those defaults counts as nothing saved.
Future<LinkSettings?> legacySimSettings() async {
  final sim = await LocalInference().simSettings();
  const untouched = SimSettings();
  if (!sim.enabled &&
      sim.host == untouched.host &&
      sim.remotePort == untouched.remotePort &&
      sim.localPort == untouched.localPort) {
    return null;
  }
  return LinkSettings(
      link: LinkConfig(
          host: sim.host, remotePort: sim.remotePort, localPort: sim.localPort));
}

/// [LinkSettings] as one JSON file in the app's private data directory, with
/// the v0.2.0 SITL endpoint as the starting point. A carried-over endpoint
/// never connects by itself: v0.2.0 had no radio setting, so the operator
/// connects once with it shown.
class DeviceLinkSettingsStore implements LinkSettingsStore {
  const DeviceLinkSettingsStore(
      {this.directory = flutterDataDirectory, this.legacy = legacySimSettings});

  static const fileName = 'link_settings.json';

  final Future<Directory?> Function() directory;
  final Future<LinkSettings?> Function() legacy;

  Future<File?> _file() async {
    try {
      final dir = await directory();
      return dir == null ? null : File('${dir.path}/$fileName');
    } on FileSystemException {
      return null;
    }
  }

  @override
  Future<LinkSettings?> load() async {
    final file = await _file();
    if (file != null && await file.exists()) {
      try {
        return LinkSettings.fromJson(jsonDecode(await file.readAsString()));
      } on FormatException {
        return null;
      } on FileSystemException {
        return null;
      }
    }
    final carried = await legacy();
    return carried?.copyWith(autoConnect: false);
  }

  /// Writes a temporary file, then renames it over the settings, so a crash
  /// mid-write never leaves half a file.
  @override
  Future<void> save(LinkSettings settings) async {
    final file = await _file();
    if (file == null) {
      throw StateError('This phone gave no place to save the link settings.');
    }
    final temporary = File('${file.path}.tmp');
    await temporary.writeAsString(jsonEncode(settings.toJson()), flush: true);
    await temporary.rename(file.path);
  }
}
