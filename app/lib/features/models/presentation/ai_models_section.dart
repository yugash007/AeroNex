import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/user_errors.dart';
import '../../session/session_api.dart';
import '../data/speech_packs.dart' show megabytes;

/// The Models screen's AI rows (0.4.0): the LFM planner and the Needle tool
/// caller, with whether the AI can help on this phone and, if not, why in
/// plain words. Each row: its size and status, and Download (asked first,
/// with a Wi-Fi hint), Import (from the import folder), Remove, or Cancel
/// with the progress while one runs. The service verifies every file by
/// SHA-256 before it is used.
class AiModelsSection extends StatefulWidget {
  const AiModelsSection({super.key, required this.session});

  final SessionApi session;

  @override
  State<AiModelsSection> createState() => AiModelsSectionState();
}

const aiModelsCheckFailed = 'Could not check the AI models. Try again.';

/// The rows' names.
const _names = {
  'lfm-q4': 'AI planner (LFM2.5)',
  'needle3': 'AI tool calls (Cactus Needle)',
};

class AiModelsSectionState extends State<AiModelsSection> {
  AiModelsStatus? _status;
  bool _failed = false;
  Future<void>? _checking;

  /// Polls while a download or import runs.
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    refresh();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> refresh() => _checking ??= _check().whenComplete(() => _checking = null);

  Future<void> _check() async {
    try {
      final status = await widget.session.aiModels();
      if (mounted) {
        setState(() {
          _status = status;
          _failed = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    }
    if (!mounted) return;
    if (_status?.transfer != null) {
      _poll ??= Timer.periodic(const Duration(milliseconds: 500), (_) => refresh());
    } else {
      _poll?.cancel();
      _poll = null;
    }
  }

  void _say(Object error) {
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
        ?.showSnackBar(SnackBar(content: Text(userErrorMessage(error))));
  }

  Future<void> _run(Future<void> Function() action) async {
    try {
      await action();
    } on PlatformException catch (error) {
      _say(error);
    } catch (error) {
      _say(error);
    }
    await _checking;
    if (mounted) await refresh();
  }

  Future<bool> _ask(String title, String text, String action) async =>
      await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: Text(text),
          actions: [
            TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Not now')),
            FilledButton(
                key: Key('confirmAi$action'),
                onPressed: () => Navigator.of(context).pop(true),
                child: Text(action)),
          ],
        ),
      ) ==
      true;

  Future<void> _download(AiModel model) async {
    if (!await _ask('Download the ${_names[model.id] ?? model.id}?',
        'About ${megabytes(model.bytes)}. Use Wi-Fi: on mobile data this '
            'download can cost money. The phone checks the file before it uses it.',
        'Download')) {
      return;
    }
    await _run(() => widget.session.downloadAiModel(model.id));
  }

  Future<void> _remove(AiModel model) async {
    if (!await _ask('Remove the ${_names[model.id] ?? model.id}?',
        'The AI stops until you download it again. Commands in the supported '
            'words keep working.',
        'Remove')) {
      return;
    }
    await _run(() => widget.session.removeAiModel(model.id));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _status;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text('AI command help', style: theme.textTheme.titleMedium),
      const Text('For commands the supported words do not cover, an AI on '
          'this phone suggests a plan you confirm. It needs a 64-bit phone with '
          '4 GB of memory or more and these two downloads.'),
      const SizedBox(height: 4),
      if (status != null)
        Text(
            status.available
                ? 'AI is ready.'
                : 'AI is off: ${status.reason ?? 'not available on this phone'}.',
            key: const Key('aiAvailability'),
            style: theme.textTheme.bodyLarge?.copyWith(
                color: status.available ? theme.colorScheme.primary : null)),
      if (_failed)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(children: [
            Expanded(
                child: Text(aiModelsCheckFailed,
                    style: TextStyle(color: theme.colorScheme.error))),
            TextButton(onPressed: refresh, child: const Text('Try again')),
          ]),
        ),
      for (final model in status?.models ?? const <AiModel>[])
        _AiModelRow(
          key: ValueKey('ai-${model.id}'),
          model: model,
          transfer: status!.transfer?.id == model.id ? status.transfer : null,
          busy: status.transfer != null,
          error: status.errorId == model.id ? status.error : null,
          onDownload: () => _download(model),
          onImport: () => _run(() => widget.session.importAiModel(model.id)),
          onRemove: () => _remove(model),
          onCancel: () => _run(widget.session.cancelAiModel),
        ),
      if (status == null && !_failed)
        const Padding(padding: EdgeInsets.all(12), child: Text('Checking…')),
    ]);
  }
}

/// One AI model: its name, size and status in plain words, and its actions.
class _AiModelRow extends StatelessWidget {
  const _AiModelRow({
    super.key,
    required this.model,
    required this.transfer,
    required this.busy,
    required this.error,
    required this.onDownload,
    required this.onImport,
    required this.onRemove,
    required this.onCancel,
  });

  final AiModel model;
  final AiModelTransfer? transfer;

  /// Some model transfers now: one at a time.
  final bool busy;
  final String? error;
  final VoidCallback onDownload, onImport, onRemove, onCancel;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final transfer = this.transfer;
    final percent = transfer == null ? 0 : (transfer.fraction * 100).floor();
    final label = transfer != null
        ? (transfer.download ? 'Downloading… $percent%' : 'Importing… $percent%')
        : model.ready
            ? 'Installed'
            : model.state == 'checking'
                ? 'Checking the file…'
                : model.broken
                    ? 'Needs repair'
                    : 'Not installed';
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.only(top: 2, right: 12),
            child: Icon(
                model.ready
                    ? Icons.check_circle
                    : model.broken || error != null
                        ? Icons.error_outline
                        : Icons.download_for_offline_outlined,
                color: model.ready
                    ? theme.colorScheme.primary
                    : model.broken || error != null
                        ? theme.colorScheme.error
                        : theme.colorScheme.onSurfaceVariant),
          ),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(_names[model.id] ?? model.id, style: theme.textTheme.titleMedium),
              Text(label,
                  style: theme.textTheme.bodyLarge
                      ?.copyWith(color: model.broken ? theme.colorScheme.error : null)),
              if (transfer != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: LinearProgressIndicator(value: transfer.fraction),
                ),
              Text('${megabytes(model.bytes)} · ${model.file}', style: theme.textTheme.bodySmall),
              if (!model.ready && transfer == null && model.importPath != null)
                Text('To import, copy the file to ${model.importPath}',
                    style: theme.textTheme.bodySmall),
              if (error != null && transfer == null)
                Text(error!,
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error)),
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Wrap(spacing: 8, children: [
                  if (transfer != null)
                    OutlinedButton(onPressed: onCancel, child: const Text('Cancel'))
                  else if (model.ready)
                    OutlinedButton(onPressed: busy ? null : onRemove, child: const Text('Remove'))
                  else ...[
                    FilledButton.tonal(
                        onPressed: busy ? null : onDownload,
                        child: Text(model.broken ? 'Download again' : 'Download')),
                    OutlinedButton(onPressed: busy ? null : onImport, child: const Text('Import')),
                  ],
                ]),
              ),
            ]),
          ),
        ]),
      ),
    );
  }
}
