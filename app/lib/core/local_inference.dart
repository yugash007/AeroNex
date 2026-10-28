import 'package:flutter/services.dart';

/// SITL endpoint remembered across launches. Simulator execution is a
/// per-connection opt-in and is deliberately never part of it.
class SimSettings {
  final bool enabled;
  final String host;
  final int remotePort, localPort;
  const SimSettings(
      {this.enabled = false,
      this.host = '192.168.1.100',
      this.remotePort = 14550,
      this.localPort = 14551});
}

/// The v0.2.0 `aeronex/inference` channel, now only for the Vosk speech packs
/// (status, download, import) and the remembered SITL endpoint (Android's
/// ModelSetupBridge). Speech recognition and command parsing run in the
/// flight session service; LFM, Whisper, Whistle and Needle were removed in
/// 0.3.0.
class LocalInference {
  static const channel = MethodChannel('aeronex/inference');

  Future<Map<String, dynamic>> status() async =>
      await channel.invokeMapMethod<String, dynamic>('status') ?? {};
  Future<void> importModel(String profile) =>
      channel.invokeMethod<void>('importModel', {'profile': profile});
  Future<void> downloadModel(String profile) =>
      channel.invokeMethod<void>('downloadModel', {'profile': profile});

  /// Missing, mistyped or unreadable values fall back to the defaults.
  Future<SimSettings> simSettings() async {
    Object? stored;
    try {
      stored = await channel.invokeMethod<Object?>('simSettings');
    } on PlatformException {
      stored = null;
    } on MissingPluginException {
      stored = null;
    }
    const defaults = SimSettings();
    final values = stored is Map ? stored : const {};
    T pick<T>(String key, T fallback) =>
        values[key] is T ? values[key] as T : fallback;
    return SimSettings(
        enabled: pick('enabled', defaults.enabled),
        host: pick('host', defaults.host),
        remotePort: pick('remotePort', defaults.remotePort),
        localPort: pick('localPort', defaults.localPort));
  }

  /// The native side validates and throws INVALID_SIM_SETTINGS.
  Future<void> saveSimSettings(SimSettings settings) =>
      channel.invokeMethod<void>('saveSimSettings', {
        'enabled': settings.enabled,
        'host': settings.host,
        'remotePort': settings.remotePort,
        'localPort': settings.localPort,
      });

  Future<void> cancel() => channel.invokeMethod<void>('cancel');
}
