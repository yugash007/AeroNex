import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../core/local_inference.dart';
import '../../../core/local_models.dart';
import '../../../core/user_errors.dart';

class ModelSetupSheet extends StatefulWidget {
  final List<String> profiles;
  const ModelSetupSheet({super.key, required this.profiles});
  @override
  State<ModelSetupSheet> createState() => _ModelSetupSheetState();
}

class _ModelSetupSheetState extends State<ModelSetupSheet> {
  final _local = LocalInference();
  Map<String, dynamic> _status = {};
  bool _busy = false;
  bool _loading = true;
  Future<void>? _pendingRefresh;
  String? _active, _error, _notice;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() => _pendingRefresh ??=
      _loadStatus().whenComplete(() => _pendingRefresh = null);

  Future<void> _loadStatus() async {
    try {
      final status = await _local.status();
      if (status['models'] is! Map)
        throw StateError(
            'Cannot check installed models. Close this panel and try again.');
      if (mounted) setState(() => _status = status);
    } catch (error) {
      if (mounted) setState(() => _error = userErrorMessage(error));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _install(String id, {required bool download}) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _active = id;
      _error = null;
      _notice = null;
    });
    _poll =
        Timer.periodic(const Duration(milliseconds: 500), (_) => _refresh());
    try {
      if (download)
        await _local.downloadModel(id);
      else
        await _local.importModel(id);
      if (mounted)
        setState(() => _notice = '${localModels[id]!.name} is ready.');
    } catch (error) {
      if (mounted)
        setState(() {
          if (error is PlatformException && error.code == 'CANCELLED')
            _notice = 'Model setup cancelled.';
          else
            _error = userErrorMessage(error);
        });
    } finally {
      _poll?.cancel();
      // A poll started before installation completed may still contain the old
      // readiness map. Wait for it, then fetch a new snapshot before enabling UI.
      await _pendingRefresh;
      if (mounted) await _refresh();
      if (mounted) {
        setState(() {
          _busy = false;
          _active = null;
        });
      }
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final installed = _status['models'] as Map? ?? {};
    final modelStates = _status['modelStates'] as Map? ?? {};
    final allReady = widget.profiles.every((id) => installed[id] == true);
    final transfer = _status['transfer'] as Map?;
    final total = (transfer?['totalBytes'] as num?)?.toDouble();
    final received = (transfer?['receivedBytes'] as num?)?.toDouble();
    final progress = total != null && total > 0 && received != null
        ? (received / total).clamp(0.0, 1.0)
        : null;
    return PopScope(
      canPop: !_busy,
      child: SafeArea(
          child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.85,
        child:
            Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Padding(
              padding: const EdgeInsets.fromLTRB(20, 20, 8, 0),
              child: Row(children: [
                Expanded(
                    child: Text(
                        _loading || allReady
                            ? 'Offline models'
                            : 'Set up offline models',
                        style: Theme.of(context).textTheme.titleLarge)),
                IconButton(
                    tooltip: 'Close model setup',
                    onPressed: _busy ? null : () => Navigator.pop(context),
                    icon: const Icon(Icons.close)),
              ])),
          Expanded(
              child: ListView(padding: const EdgeInsets.all(20), children: [
            Text(allReady
                ? 'These models are installed and ready to use offline.'
                : 'Download once using the internet, then use the models offline. '
                    'Downloads come from the official model repositories. Your voice and commands stay on this phone.'),
            if (_loading) const LinearProgressIndicator(),
            const SizedBox(height: 12),
            for (final id in widget.profiles) ...[
              Text(localModels[id]!.name,
                  style: Theme.of(context).textTheme.titleMedium),
              Text(
                  '${localModels[id]!.sizeLabel} · ${_loading ? 'Checking installed file…' : installed[id] == true ? 'Installed and verified' : modelStates[id] == 'invalid' || modelStates[id] == 'unreadable' ? 'File needs repair' : 'Not installed'}'),
              SelectableText(localModels[id]!.file,
                  style: Theme.of(context).textTheme.bodySmall),
              if (id.startsWith('vosk-'))
                const Text(
                    'Import the official ZIP archive. The app verifies and installs all model files; keep the ZIP intact.'),
              const SizedBox(height: 8),
              if (!_loading &&
                  _status['models'] is Map &&
                  installed[id] != true)
                FilledButton(
                    onPressed:
                        _busy ? null : () => _install(id, download: true),
                    child: Text('Download ${localModels[id]!.name}')),
              TextButton(
                  onPressed: _busy || _loading
                      ? null
                      : () => _install(id, download: false),
                  child: Text(
                      '${installed[id] == true ? 'Replace' : 'Import'} ${localModels[id]!.name} from a file')),
              if (_active == id) ...[
                LinearProgressIndicator(value: progress),
                Text(progress == null
                    ? 'Preparing model…'
                    : '${(progress * 100).toStringAsFixed(0)}% · verifying before installation'),
                TextButton(
                    onPressed: () async {
                      try {
                        await _local.cancel();
                      } catch (error) {
                        if (mounted)
                          setState(() => _error = userErrorMessage(error));
                      }
                    },
                    child: const Text('Cancel model setup')),
              ],
              const Divider(height: 24),
            ],
            if (_error != null)
              Text(_error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            if (_notice != null) Text(_notice!),
            Text(allReady
                ? 'Close this panel to use your models.'
                : 'Close this panel when the model is installed. '
                    'Typed commands, the buttons and STOP need no model.'),
          ])),
        ]),
      )),
    );
  }
}
