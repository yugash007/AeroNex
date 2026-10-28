import 'package:flutter/material.dart';

import '../../../session/session_api.dart';

/// Missions (2026-10-28): several plans flown as one after a single CONFIRM.
/// While "Build a mission" is on, every new command (said, typed or tapped)
/// becomes the next stage here instead of waiting for CONFIRM; STOP, Hold and
/// status still act at once. Start shows the whole mission, checked from the
/// drone's state now, for one CONFIRM. With checkpoints every mission (built,
/// run again from the flight memory, or resumed) holds after each stage until
/// Continue. A mission that ended early can be resumed.
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
    this.enabled = true,
    this.running = false,
  });

  final MissionStatus mission;
  final ValueChanged<bool> onBuilding, onCheckpoints;
  final ValueChanged<int> onRemoveStage;
  final VoidCallback onStart, onClear;

  /// Resume from the step the mission ended at, or ([skip]) the one after.
  final void Function({required bool skip}) onResume;

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
        ]),
      ),
    );
  }
}
