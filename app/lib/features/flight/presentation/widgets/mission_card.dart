import 'package:flutter/material.dart';

import '../../../session/session_api.dart';

/// Missions (2026-10-28): several plans flown as one after a single CONFIRM.
/// While "Build a mission" is on, every new command (said, typed or tapped)
/// becomes the next stage here instead of waiting for CONFIRM; STOP, Hold and
/// status still act at once. Start shows the whole mission, checked from the
/// drone's state now, for one CONFIRM. With checkpoints every mission (built,
/// run again from the flight memory, or resumed) holds after each stage until
/// Continue. A mission that ended early can be resumed. A built mission can
/// be saved by name, and a saved one loaded again later, even after a restart.
class MissionCard extends StatelessWidget {
  const MissionCard({
    super.key,
    required this.mission,
    required this.onBuilding,
    required this.onCheckpoints,
    required this.onRemoveStage,
    required this.onStart,
    required this.onClear,
    required this.onResume,
    required this.onSave,
    required this.onLoad,
    required this.onDelete,
    this.enabled = true,
    this.running = false,
  });

  final MissionStatus mission;
  final ValueChanged<bool> onBuilding, onCheckpoints;
  final ValueChanged<int> onRemoveStage;
  final VoidCallback onStart, onClear;

  /// Resume from the step the mission ended at, or ([skip]) the one after.
  final void Function({required bool skip}) onResume;

  /// Save the stages built under a name; load or delete a saved mission.
  final ValueChanged<String> onSave, onLoad, onDelete;

  /// False while another command is being sent.
  final bool enabled;

  /// A plan or mission is running: nothing new may start.
  final bool running;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final stages = mission.stages;
    final resume = mission.resume;
    final full = stages.length >= mission.maxStages;
    return Card(
      key: const Key('missionCard'),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            const Icon(Icons.layers_outlined),
            const SizedBox(width: 8),
            Expanded(child: Text('Mission', style: theme.textTheme.titleMedium)),
            if (stages.isNotEmpty)
              Text('${stages.length} of ${mission.maxStages} stages', style: theme.textTheme.bodySmall),
          ]),
          SwitchListTile(
            key: const Key('missionBuilding'),
            contentPadding: EdgeInsets.zero,
            title: const Text('Build a mission'),
            subtitle: Text(mission.building
                ? 'Say, type or tap each stage: it is added here instead of running. STOP, Hold and Status still act at once.'
                : 'Turn on to add several commands as stages, then fly them all after one CONFIRM.'),
            value: mission.building,
            onChanged: enabled ? onBuilding : null,
          ),
          for (final (i, stage) in stages.indexed)
            Padding(
              key: Key('missionStage$i'),
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                CircleAvatar(radius: 12, child: Text('${i + 1}', style: const TextStyle(fontSize: 12))),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text([for (final step in stage.steps) step.description].join(', then ')),
                    if (stage.source != null)
                      Text(stage.source!.label, style: theme.textTheme.bodySmall),
                  ]),
                ),
                IconButton(
                    key: Key('removeStage$i'),
                    tooltip: 'Remove stage ${i + 1}',
                    onPressed: enabled ? () => onRemoveStage(i) : null,
                    icon: const Icon(Icons.close)),
              ]),
            ),
          if (full)
            Text('A mission holds at most ${mission.maxStages} stages.', key: const Key('missionFull')),
          SwitchListTile(
            key: const Key('missionCheckpoints'),
            contentPadding: EdgeInsets.zero,
            title: const Text('Hold after each stage'),
            subtitle: const Text('The drone waits in place until you tap Continue. For every mission: '
                'built here, run again or resumed.'),
            value: mission.checkpoints,
            onChanged: enabled ? onCheckpoints : null,
          ),
          if (stages.isNotEmpty) ...[
            Row(children: [
              Expanded(
                child: FilledButton.icon(
                    key: const Key('startMission'),
                    onPressed: enabled && !running ? onStart : null,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('Start mission')),
              ),
              const SizedBox(width: 8),
              TextButton(
                  key: const Key('saveMission'),
                  onPressed: enabled ? () => _save(context) : null,
                  child: const Text('Save')),
              TextButton(key: const Key('clearMission'), onPressed: enabled ? onClear : null, child: const Text('Clear')),
            ]),
          ],
          if (resume != null && !running) ...[
            const Divider(),
            Text('The last mission ended at step ${resume.fromStep + 1}; ${resume.stepCount} '
                '${resume.stepCount == 1 ? 'step is' : 'steps are'} left.', key: const Key('missionResumeText')),
            Wrap(spacing: 8, children: [
              OutlinedButton(
                  key: const Key('resumeMission'),
                  onPressed: enabled ? () => onResume(skip: false) : null,
                  child: Text('Resume from step ${resume.fromStep + 1}')),
              // At a checkpoint nothing was interrupted: no step to skip.
              if (resume.stepCount > 1 && !resume.atCheckpoint)
                OutlinedButton(
                    key: const Key('resumeMissionSkip'),
                    onPressed: enabled ? () => onResume(skip: true) : null,
                    child: Text('Skip step ${resume.fromStep + 1} and resume')),
            ]),
            if (!resume.atCheckpoint)
              Text('A move that was interrupted runs again in full from where the drone is now.',
                  style: theme.textTheme.bodySmall),
          ],
          if (mission.saved.isNotEmpty) ...[
            const Divider(),
            Text('Saved missions', style: theme.textTheme.titleSmall),
            for (final saved in mission.saved)
              Padding(
                key: Key('savedMission${saved.name}'),
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Row(children: [
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(saved.name),
                      Text(
                          '${saved.stageCount == 1 ? '1 stage' : '${saved.stageCount} stages'} · '
                          '${saved.stepCount == 1 ? '1 step' : '${saved.stepCount} steps'}: ${saved.summary}',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall),
                    ]),
                  ),
                  TextButton(
                      key: Key('loadMission${saved.name}'),
                      onPressed: enabled && !running ? () => onLoad(saved.name) : null,
                      child: const Text('Load')),
                  IconButton(
                      key: Key('deleteMission${saved.name}'),
                      tooltip: 'Delete ${saved.name}',
                      onPressed: enabled ? () => onDelete(saved.name) : null,
                      icon: const Icon(Icons.delete_outline)),
                ]),
              ),
          ],
        ]),
      ),
    );
  }

  Future<void> _save(BuildContext context) async {
    final name = await showDialog<String>(context: context, builder: (_) => const _MissionNameDialog());
    if (name != null && name.trim().isNotEmpty) onSave(name.trim());
  }
}

/// Asks for a saved mission's name; pops it, or null for Cancel.
class _MissionNameDialog extends StatefulWidget {
  const _MissionNameDialog();

  @override
  State<_MissionNameDialog> createState() => _MissionNameDialogState();
}

class _MissionNameDialogState extends State<_MissionNameDialog> {
  final _name = TextEditingController();

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        key: const Key('saveMissionDialog'),
        title: const Text('Save this mission'),
        content: TextField(
          key: const Key('saveMissionName'),
          controller: _name,
          autofocus: true,
          maxLength: 32,
          decoration: const InputDecoration(labelText: 'Name', hintText: 'e.g. Perimeter check'),
          onSubmitted: (name) => Navigator.pop(context, name),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          TextButton(
              key: const Key('saveMissionConfirm'),
              onPressed: () => Navigator.pop(context, _name.text),
              child: const Text('Save')),
        ],
      );
}
