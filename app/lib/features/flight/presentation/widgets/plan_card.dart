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
/// what was heard and what the AI understood ([ai]).
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
  });

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
            Expanded(child: Text('Confirm this plan', style: theme.textTheme.titleMedium)),
            _sourceChip(pending.source),
          ]),
          if (pending.source == PlanSource.ai && ai != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: AiUnderstanding(ai: ai!, showHeard: showAiHeard),
            ),
          for (final (i, step) in pending.steps.indexed)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(children: [
                Expanded(
                    child: Text('${i + 1}. ${step.description}',
                        style: theme.textTheme.titleMedium)),
                if (step.source == 'fixture') const Text('Test'),
              ]),
            ),
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
          ]),
        ]),
      ),
    );
  }
}

/// The plan running or last run: one row per step with its outcome, and
/// while it runs, its progress and Cancel (which is STOP).
class RunCard extends StatelessWidget {
  const RunCard(
      {super.key, required this.run, required this.onCancel, this.cancelRowKey});

  final PlanRun run;
  final VoidCallback onCancel;
  final Key? cancelRowKey;

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
                child: Text(run.executing ? 'Running' : 'Last plan',
                    style: theme.textTheme.titleMedium)),
            _sourceChip(run.source),
          ]),
          if (run.executing) ...[
            Text('Step ${math.min(run.stepIndex + 1, count)} of ${run.stepCount}'),
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
          for (final (i, result) in run.outcomes.indexed)
            _StepRow(index: i, result: result),
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
