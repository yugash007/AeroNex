import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/local_inference.dart';
import '../../../core/local_models.dart';
import '../../../core/user_errors.dart';
import '../../session/session_api.dart';
import '../../voice/presentation/model_setup_sheet.dart';
import '../data/speech_packs.dart';
import 'ai_models_section.dart';

const modelsCheckFailed =
    'Could not check the speech packs on this phone. Try again.';

/// The languages with an offline speech pack in this version (Vosk, set up
/// over `aeronex/inference`), in the app's default order.
const _languages = [
  ('te', 'Telugu'),
  ('hi', 'Hindi'),
  ('en', 'Indian English'),
];

/// The IndicConformer pack names; the voice STOP pack has its own.
const _packLanguages = {
  'te': 'Telugu (IndicConformer)',
  'ta': 'Tamil',
  'kn': 'Kannada',
  'ml': 'Malayalam',
};

String _packName(SpeechPack pack) => pack.stopSpotter
    ? 'Voice STOP (English "stop")'
    : _packLanguages[pack.language] ??
        speechLanguages[pack.language] ??
        pack.language;

/// The language in the download question ("the Tamil voice pack").
String _packSubject(SpeechPack pack) => pack.stopSpotter
    ? 'voice STOP pack'
    : '${speechLanguages[pack.language] ?? pack.language} voice pack';

/// What a row says and the one thing it offers.
enum _Readiness {
  checking('Checking…'),
  notChecked('Not checked'),
  ready('Ready'),
  missing('Not installed', action: 'Set up'),
  broken('Needs repair', action: 'Repair'),
  verifying('Checking the file…');

  const _Readiness(this.label, {this.action});
  final String label;
  final String? action;
}

/// Offline speech readiness per language: one row each, a plain status word
/// and at most one action, which opens the existing model setup for that
/// language's pack (download, or import from a file for field kits). Below
/// them the bigger IndicConformer voice packs and the voice STOP pack (0.4.0):
/// a size, Download (asked first, with a Wi-Fi hint) with progress and Cancel,
/// Installed, or "Not for this phone" with the reason. A pack copied into the
/// import folder installs at the next voice start and shows its progress here.
/// Last, the two AI models (0.4.0, [AiModelsSection]) and whether the AI can
/// help on this phone.
class ModelsScreen extends StatefulWidget {
  const ModelsScreen({super.key, required this.session});

  final SessionApi session;

  @override
  State<ModelsScreen> createState() => _ModelsScreenState();
}

class _ModelsScreenState extends State<ModelsScreen> with WidgetsBindingObserver {
  final _local = LocalInference();
  final _packs = const SpeechPacks();
  Map<String, dynamic>? _status;
  bool _failed = false;
  Future<void>? _checking;
  SpeechPackStatus? _packStatus;
  bool _packsFailed = false;
  Future<void>? _checkingPacks;

  /// Polls the pack list while a download or import runs.
  Timer? _poll;

  final _ai = GlobalKey<AiModelsSectionState>();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    _poll?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh();
      _ai.currentState?.refresh();
    }
  }

  Future<void> _refresh() =>
      _checking ??= _check().whenComplete(() => _checking = null);

  Future<void> _check() => Future.wait([_checkVosk(), _refreshPacks()]);

  Future<void> _refreshPacks() => _checkingPacks ??=
      _checkPacks().whenComplete(() => _checkingPacks = null);

  Future<void> _checkPacks() async {
    try {
      final status = await _packs.status();
      if (mounted) {
        setState(() {
          _packStatus = status;
          _packsFailed = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _packsFailed = true);
    }
    if (!mounted) return;
    final busy = _packStatus?.installing ?? false;
    if (busy) {
      _poll ??= Timer.periodic(
          const Duration(milliseconds: 500), (_) => _refreshPacks());
    } else {
      _poll?.cancel();
      _poll = null;
    }
  }

  Future<void> _checkVosk() async {
    try {
      final status = await _local.status();
      if (status['models'] is! Map) throw const FormatException('models');
      if (mounted) {
        setState(() {
          _status = status;
          _failed = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    }
  }

  void _retry() {
    setState(() {
      _failed = false;
      _status = null;
      _packsFailed = false;
      _packStatus = null;
    });
    _refresh();
  }

  /// A user-started HTTPS download, after a question with the size and a
  /// Wi-Fi hint. The phone verifies every file by SHA-256 before it installs
  /// it; the row shows the progress, and the outcome once it ends.
  Future<void> _download(SpeechPack pack) async {
    final metered = _packStatus?.metered ?? true;
    final go = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Download the ${_packSubject(pack)}?'),
        content: Text('About ${pack.sizeLabel}. Use Wi-Fi: on mobile data '
            'this download can cost money.'
            '${metered ? ' This phone is on mobile data now.' : ''}'
            ' The phone checks the files before it installs them.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Not now')),
          FilledButton(
              key: const Key('confirmPackDownload'),
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('Download')),
        ],
      ),
    );
    if (go != true || !mounted) return;
    final download = _packs.download(pack.id);
    unawaited(_refreshPacks());
    try {
      await download;
    } on PlatformException catch (error) {
      // A cancel is the operator's own choice; the row shows any other reason.
      if (error.code != 'CANCELLED' && mounted) {
        ScaffoldMessenger.maybeOf(context)
            ?.showSnackBar(SnackBar(content: Text(userErrorMessage(error))));
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.maybeOf(context)
            ?.showSnackBar(SnackBar(content: Text(userErrorMessage(error))));
      }
    }
    // A poll started before the download ended may hold the old row.
    await _checkingPacks;
    if (mounted) await _refreshPacks();
  }

  Future<void> _cancel(SpeechPack pack) async {
    try {
      await _packs.cancel(pack.id);
    } catch (_) {
      // The next poll shows whether it is still running.
    }
    if (mounted) await _refreshPacks();
  }

  _Readiness _readiness(String profile) {
    final status = _status;
    if (status == null) return _failed ? _Readiness.notChecked : _Readiness.checking;
    final state = (status['modelStates'] as Map? ?? const {})[profile];
    return switch (state) {
      'ready' => _Readiness.ready,
      'invalid' || 'unreadable' => _Readiness.broken,
      'checking' => _Readiness.verifying,
      'missing' => _Readiness.missing,
      _ => (status['models'] as Map)[profile] == true
          ? _Readiness.ready
          : _Readiness.missing,
    };
  }

  /// The v0.2.0 setup sheet for one pack; it cannot be dismissed while busy.
  Future<void> _setUp(String profile) async {
    await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        isDismissible: false,
        enableDrag: false,
        builder: (_) => ModelSetupSheet(profiles: [profile]));
    if (!mounted) return;
    // A check started before the sheet closed may hold the old states.
    await _checking;
    if (mounted) await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Models')),
      body: SafeArea(
        top: false,
        child: ListView(padding: const EdgeInsets.all(12), children: [
          const Text('Speech runs on this phone, offline. Each language needs '
              'its speech pack once: download it with internet, or import the '
              'file. STOP, the buttons and typed commands need no pack.'),
          const SizedBox(height: 8),
          if (_failed || _packsFailed)
            Card(
              color: theme.colorScheme.errorContainer,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
                child: Row(children: [
                  Expanded(
                      child: Text(modelsCheckFailed,
                          style: TextStyle(color: theme.colorScheme.onErrorContainer))),
                  TextButton(onPressed: _retry, child: const Text('Try again')),
                ]),
              ),
            ),
          for (final (code, name) in _languages)
            _LanguageRow(
              key: ValueKey('speech-$code'),
              name: name,
              readiness: _readiness(voskProfileForLanguage(code)!),
              detail: localModels[voskProfileForLanguage(code)]?.sizeLabel,
              onAction: () => _setUp(voskProfileForLanguage(code)!),
            ),
          const SizedBox(height: 12),
          Text('Bigger voice packs', style: theme.textTheme.titleMedium),
          const Text('IndicConformer understands Telugu, Tamil, Kannada and '
              'Malayalam better on phones with 4 GB of memory or more. Each '
              'pack is about 200 MB: download it on Wi-Fi.'),
          const SizedBox(height: 4),
          for (final pack in _packStatus?.packs ?? const <SpeechPack>[])
            _PackRow(
              key: ValueKey('pack-${pack.id}'),
              pack: pack,
              onDownload: () => _download(pack),
              onCancel: () => _cancel(pack),
            ),
          if (_packStatus == null)
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(_packsFailed ? 'Not checked' : 'Checking…'),
            ),
          const SizedBox(height: 12),
          AiModelsSection(key: _ai, session: widget.session),
        ]),
      ),
    );
  }
}

/// One language: its name, its status in plain words, and at most one
/// action.
class _LanguageRow extends StatelessWidget {
  const _LanguageRow({
    super.key,
    required this.name,
    this.readiness,
    this.detail,
    this.onAction,
  });

  final String name;
  final _Readiness? readiness;

  /// The pack's download size.
  final String? detail;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final readiness = this.readiness;
    final action = readiness?.action;
    final ready = readiness == _Readiness.ready;
    final broken = readiness == _Readiness.broken;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.only(top: 2, right: 12),
            child: Icon(
                ready
                    ? Icons.check_circle
                    : broken
                        ? Icons.error_outline
                        : Icons.download_for_offline_outlined,
                color: ready
                    ? theme.colorScheme.primary
                    : broken
                        ? theme.colorScheme.error
                        : theme.colorScheme.onSurfaceVariant),
          ),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(name, style: theme.textTheme.titleMedium),
              Text(readiness!.label,
                  style: theme.textTheme.bodyLarge?.copyWith(
                      color: broken ? theme.colorScheme.error : null)),
              if (action != null && detail != null)
                Text('$detail download', style: theme.textTheme.bodySmall),
              if (action != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: FilledButton.tonal(onPressed: onAction, child: Text(action)),
                ),
            ]),
          ),
        ]),
      ),
    );
  }
}

/// One voice pack: its name, its status in plain words (with the progress of
/// a download or import), why it is not for this phone, the voice STOP it
/// gives, and at most one action: Download or Cancel.
class _PackRow extends StatelessWidget {
  const _PackRow({
    super.key,
    required this.pack,
    required this.onDownload,
    required this.onCancel,
  });

  final SpeechPack pack;
  final VoidCallback onDownload, onCancel;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final install = pack.install;
    final percent = install == null ? 0 : (install.fraction * 100).floor();
    final (String label, String? action, VoidCallback? onAction) =
        install != null
            ? (
                install.download
                    ? 'Downloading… $percent%'
                    : 'Installing voice pack… $percent%',
                'Cancel',
                onCancel
              )
            : !pack.usable
                ? ('Not for this phone', null, null)
                : pack.ready
                    ? ('Installed', null, null)
                    : pack.state == 'checking'
                        ? ('Checking the file…', null, null)
                        : pack.broken
                            ? (
                                'Needs repair',
                                pack.downloadable ? 'Download again' : null,
                                onDownload
                              )
                            : (
                                'Not installed',
                                pack.downloadable ? 'Download' : null,
                                onDownload
                              );
    final error = install == null && !pack.ready ? pack.error : null;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.only(top: 2, right: 12),
            child: Icon(
                pack.ready
                    ? Icons.check_circle
                    : !pack.usable
                        ? Icons.block
                        : pack.broken || error != null
                            ? Icons.error_outline
                            : Icons.download_for_offline_outlined,
                color: pack.ready
                    ? theme.colorScheme.primary
                    : pack.broken || error != null
                        ? theme.colorScheme.error
                        : theme.colorScheme.onSurfaceVariant),
          ),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(_packName(pack), style: theme.textTheme.titleMedium),
              Text(label,
                  style: theme.textTheme.bodyLarge?.copyWith(
                      color: pack.broken ? theme.colorScheme.error : null)),
              if (install != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: LinearProgressIndicator(value: install.fraction),
                ),
              if (!pack.usable && pack.reason != null)
                Text(pack.reason!, style: theme.textTheme.bodySmall),
              if (install == null && action != null)
                Text('${pack.sizeLabel} download',
                    style: theme.textTheme.bodySmall),
              if (pack.usable && pack.note != null)
                Text(pack.note!, style: theme.textTheme.bodySmall),
              if (error != null)
                Text(error,
                    style: theme.textTheme.bodySmall
                        ?.copyWith(color: theme.colorScheme.error)),
              if (action != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: install != null
                      ? OutlinedButton(onPressed: onAction, child: Text(action))
                      : FilledButton.tonal(
                          onPressed: onAction, child: Text(action)),
                ),
            ]),
          ),
        ]),
      ),
    );
  }
}
