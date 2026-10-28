import 'package:flutter/services.dart';

/// A download or import of one voice pack that is running now.
class PackInstall {
  const PackInstall(
      {required this.source,
      required this.receivedBytes,
      required this.totalBytes});

  factory PackInstall.fromMap(Map map) => PackInstall(
      source: map['source'] as String? ?? 'download',
      receivedBytes: map['receivedBytes'] as int? ?? 0,
      totalBytes: map['totalBytes'] as int? ?? 0);

  /// `download` (the Models screen) or `import` (the import folder).
  final String source;
  final int receivedBytes, totalBytes;

  bool get download => source == 'download';

  double get fraction =>
      totalBytes <= 0 ? 0 : (receivedBytes / totalBytes).clamp(0.0, 1.0);
}

/// One voice pack the phone offers (Android `ModelSetupBridge` `packs`): an
/// IndicConformer language pack (`ctc`) or the voice STOP spotter (`kws`).
class SpeechPack {
  const SpeechPack({
    required this.id,
    required this.kind,
    required this.language,
    required this.bytes,
    required this.state,
    this.usable = true,
    this.reason,
    this.note,
    this.downloadable = false,
    this.install,
    this.error,
  });

  factory SpeechPack.fromMap(Map map) {
    final install = map['install'];
    return SpeechPack(
      id: map['id'] as String,
      kind: map['kind'] as String,
      language: map['language'] as String,
      bytes: map['bytes'] as int,
      state: map['state'] as String,
      usable: map['usable'] as bool? ?? false,
      reason: map['reason'] as String?,
      note: map['note'] as String?,
      downloadable: map['downloadable'] as bool? ?? false,
      install: install is Map ? PackInstall.fromMap(install) : null,
      error: map['error'] as String?,
    );
  }

  final String id, kind, language;
  final int bytes;

  /// ready | missing | partial | invalid | checking | unreadable.
  final String state;

  /// False: not for this phone, and [reason] says why.
  final bool usable;
  final String? reason;

  /// Which voice STOP this pack gives on this phone.
  final String? note;
  final bool downloadable;
  final PackInstall? install;

  /// Why the last download or import failed.
  final String? error;

  bool get ready => state == 'ready';
  bool get broken => state == 'invalid' || state == 'unreadable';
  bool get stopSpotter => kind == 'kws';
  String get sizeLabel => megabytes(bytes);
}

class SpeechPackStatus {
  const SpeechPackStatus(
      {required this.tier, required this.metered, required this.packs});

  factory SpeechPackStatus.fromMap(Map map) => SpeechPackStatus(
      tier: map['tier'] as String? ?? 'L',
      metered: map['metered'] as bool? ?? true,
      packs: [
        for (final pack in map['packs'] as List) SpeechPack.fromMap(pack as Map)
      ]);

  /// The phone's tier (L, M or H).
  final String tier;

  /// The phone is on mobile data (or the kind of network is unknown).
  final bool metered;
  final List<SpeechPack> packs;

  bool get installing => packs.any((pack) => pack.install != null);
}

/// Decimal megabytes, as download sizes are usually quoted: "198 MB".
String megabytes(int bytes) {
  final mb = bytes / 1e6;
  return mb >= 10 ? '${mb.round()} MB' : '${mb.toStringAsFixed(1)} MB';
}

/// The voice pack methods of the `aeronex/inference` channel (0.4.0): the
/// list with each pack's status, an HTTPS download verified by SHA-256 on the
/// phone before install (it answers when the download ends), and cancel.
class SpeechPacks {
  const SpeechPacks();

  static const channel = MethodChannel('aeronex/inference');

  Future<SpeechPackStatus> status() async {
    final map = await channel.invokeMapMethod<String, dynamic>('packs');
    if (map == null || map['packs'] is! List) {
      throw const FormatException('packs');
    }
    return SpeechPackStatus.fromMap(map);
  }

  Future<void> download(String id) =>
      channel.invokeMethod<void>('downloadPack', {'id': id});

  Future<void> cancel(String id) =>
      channel.invokeMethod<void>('cancelPack', {'id': id});
}
