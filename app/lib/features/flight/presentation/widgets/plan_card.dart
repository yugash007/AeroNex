import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../session/session_api.dart';

const _icons = {
  'PENDING': Icons.schedule,
  'RUNNING': Icons.sync,
  'COMPLETED': Icons.check_circle_outline,
  'NOT_SENT': Icons.cancel_schedule_send_outlined,
  'REJECTED': Icons.block,
  'UNKNOWN': Icons.warning_amber,
  'SKIPPED': Icons.skip_next,
};

/// The service's step text without the tool call it ends with ("NOT SENT —
/// dry run: land({})" reads "NOT SENT — dry run"): the row already names the
/// step. REJECTED and UNKNOWN keep their whole reason.
String plainOutcome(StepResult result) {
  final message = result.message;
  if (message == null) {
    return result.outcome == 'RUNNING' ? 'Running…' : 'Waiting for the previous step.';
  }
  if (result.outcome != 'NOT_SENT' && result.outcome != 'COMPLETED') return message;
  return message.replaceFirst(RegExp(r': [a-z_]+(\(\{.*\}\))?$'), '');
}

Widget _sourceChip(PlanSource? source) => source == null
    ? const SizedBox.shrink()
    : Chip(
        visualDensity: VisualDensity.compact,
        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        label: Text(source.label));

/// "Stage n" above the first step of each stage of a mission (2026-10-28).
Widget? _stageHeader(List<int>? stageOfStep, int step, ThemeData theme) {
  if (stageOfStep == null || step >= stageOfStep.length) return null;
  if (step > 0 && stageOfStep[step] == stageOfStep[step - 1]) return null;
  return Padding(
    key: Key('stageHeader${stageOfStep[step]}'),
    padding: const EdgeInsets.only(top: 8),
    child: Text('Stage ${stageOfStep[step] + 1}',
        style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.primary)),
  );
}

/// What the operator said and what the AI understood (0.4.0, an AI plan):
/// shown with the plan so the one CONFIRM covers both.
class AiUnderstanding extends StatelessWidget {
  const AiUnderstanding({super.key, required this.ai, this.showHeard = true});

  final AiStatus ai;

  /// False when the voice line already shows the same transcript.
  final bool showHeard;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final heard = showHeard ? ai.heard : null, understood = ai.understoodAs;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (heard != null)
        Text('Heard: $heard', key: const Key('aiHeard'), style: theme.textTheme.bodyLarge),
      if (understood != null)
        Text('Understood as: $understood',
            key: const Key('aiUnderstoodAs'), style: theme.textTheme.bodyLarge),
    ]);
  }
}

/// The plan waiting for CONFIRM: numbered steps, where it came from, the
/// time left, one large CONFIRM and Cancel. A plan of the AI path also says
/// what was heard and what the AI understood ([ai]). A mission (2026-10-28)
/// shows its stages; a plan can instead become a mission's stage
/// ([onAddToMission]).
class PendingPlanCard extends StatelessWidget {
  const PendingPlanCard({
    super.key,
    required this.pending,
    required this.executionEnabled,
    required this.secondsLeft,
    required this.canConfirm,
    required this.onConfirm,
    required this.onCancel,
    this.confirmRowKey,
    this.ai,
    this.showAiHeard = true,
    this.onAddToMission,
  });

  /// Makes the plan the next stage of a mission instead of running it.
  final VoidCallback? onAddToMission;

  final PendingPlan pending;

  /// The snapshot's AI section, for a plan with the AI source.
  final AiStatus? ai;

  /// False when the voice line already shows what the AI heard.
  final bool showAiHeard;
  final bool executionEnabled;
  final int secondsLeft;
  final bool canConfirm;
  final VoidCallback onConfirm, onCancel;
  final Key? confirmRowKey;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(
                child: Text(pending.isMission ? 'Confirm this mission' : 'Confirm this plan',
                    style: theme.textTheme.titleMedium)),
            _sourceChip(pending.source),
          ]),
          if (pending.source == PlanSource.ai && ai != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: AiUnderstanding(ai: ai!, showHeard: showAiHeard),
            ),
          for (final (i, step) in pending.steps.indexed) ...[
            if (_stageHeader(pending.stageOfStep, i, theme) case final header?) header,
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(children: [
                Expanded(
                    child: Text('${i + 1}. ${step.description}',
                        style: theme.textTheme.titleMedium)),
                if (step.source == 'fixture') const Text('Test'),
              ]),
            ),
          ],
          if (pending.steps.any((s) => s.name == 'takeoff'))
            const Text('Takeoff arms the motors.'),
          const SizedBox(height: 4),
          Text(
              secondsLeft > 0
                  ? 'Expires in $secondsLeft s'
                  : 'Expired. Say or tap the command again.',
              style: const TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          Column(key: confirmRowKey, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            SizedBox(
              key: const Key('confirmButton'),
              height: 64,
              child: FilledButton(
                  onPressed: canConfirm && secondsLeft > 0 ? onConfirm : null,
                  child: Text(executionEnabled ? 'CONFIRM' : 'CONFIRM (dry run)',
                      style: const TextStyle(
                          fontSize: 22, fontWeight: FontWeight.bold))),
            ),
            TextButton(onPressed: onCancel, child: const Text('Cancel')),
            if (onAddToMission != null && !pending.isMission)
              TextButton.icon(
                  key: const Key('addToMission'),
                  onPressed: onAddToMission,
                  icon: const Icon(Icons.playlist_add),
                  label: const Text('Add to mission instead')),
          ]),
        ]),
      ),
    );
  }
}

/// The plan running or last run: one row per step with its outcome, and
/// while it runs, its progress and Cancel (which is STOP). A mission
/// (2026-10-28) shows its stages, and at a checkpoint the Continue button.
class RunCard extends StatelessWidget {
  const RunCard(
      {super.key,
      required this.run,
      required this.onCancel,
      this.cancelRowKey,
      this.checkpoint,
      this.onContinue});

  final PlanRun run;
  final VoidCallback onCancel;
  final Key? cancelRowKey;

  /// The mission holds between stages until [onContinue].
  final MissionCheckpoint? checkpoint;
  final VoidCallback? onContinue;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final count = math.max(run.stepCount, 1);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(
                child: Text(run.executing ? 'Running' : (run.isMission ? 'Last mission' : 'Last plan'),
                    style: theme.textTheme.titleMedium)),
            _sourceChip(run.source),
          ]),
          if (run.executing && checkpoint != null) ...[
            Text('Stage ${checkpoint!.stage} of ${checkpoint!.stageCount} done. The drone holds here; STOP still works.',
                key: const Key('checkpointText')),
            const SizedBox(height: 8),
            SizedBox(
              height: 64,
              child: FilledButton(
                  key: const Key('continueMission'),
                  onPressed: onContinue,
                  child: Text('Continue to stage ${checkpoint!.stage + 1}',
                      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold))),
            ),
            const SizedBox(height: 8),
          ],
          if (run.executing) ...[
            Text(run.isMission && run.stageOfStep!.isNotEmpty
                ? 'Stage ${run.stageOfStep![math.min(run.stepIndex, run.stageOfStep!.length - 1)] + 1} of '
                    '${run.stageOfStep!.last + 1} · step ${math.min(run.stepIndex + 1, count)} of ${run.stepCount}'
                : 'Step ${math.min(run.stepIndex + 1, count)} of ${run.stepCount}'),
            const SizedBox(height: 4),
            LinearProgressIndicator(value: run.stepIndex / count),
            const SizedBox(height: 8),
            SizedBox(
              key: cancelRowKey,
              height: 56,
              child: FilledButton.icon(
                  key: const Key('cancelRun'),
                  style: FilledButton.styleFrom(
                      backgroundColor: theme.colorScheme.error,
                      foregroundColor: theme.colorScheme.onError),
                  onPressed: onCancel,
                  icon: const Icon(Icons.stop),
                  label: const Text('Cancel')),
            ),
          ],
          for (final (i, result) in run.outcomes.indexed) ...[
            if (_stageHeader(run.stageOfStep, i, theme) case final header?) header,
            _StepRow(index: i, result: result),
          ],
        ]),
      ),
    );
  }
}

class _StepRow extends StatelessWidget {
  const _StepRow({required this.index, required this.result});
  final int index;
  final StepResult result;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final bad = result.outcome == 'REJECTED' || result.outcome == 'UNKNOWN';
    final color = bad ? theme.colorScheme.error : null;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(_icons[result.outcome] ?? Icons.help_outline, color: color),
        const SizedBox(width: 8),
        Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${index + 1}. ${result.step}', style: theme.textTheme.titleSmall),
          Text(plainOutcome(result), style: TextStyle(color: color)),
        ])),
      ]),
    );
  }
}
