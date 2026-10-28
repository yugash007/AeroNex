import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../session/session_api.dart';
import '../../domain/flight_command.dart';

/// At most this many recorded plans are listed.
const _plansShown = 5;

FlightCommand _call(String name, [Map<String, Object> arguments = const {}]) =>
    FlightCommand.fromToolCalls([
      {'name': name, 'arguments': arguments}
    ]);

/// The flight memory (2026-10-28): the path flown in this flight on a small
/// map, the saved points and the recent plans. Every button sends the tool
/// call the matching voice command makes ("return home", "go back",
/// "retrace", "go to point n", "mark point n"); the service resolves it from
/// its own memory, validates it and shows it for CONFIRM (a point is saved at
/// once). "Run again" shows a recorded plan again, also for CONFIRM.
class FlightMemoryCard extends StatelessWidget {
  const FlightMemoryCard({
    super.key,
    required this.memory,
    required this.onCommand,
    required this.onRunAgain,
    required this.onClear,
    this.enabled = true,
  });

  final FlightMemory memory;
  final void Function(FlightCommand command) onCommand;
  final void Function(MemoryPlan plan) onRunAgain;
  final VoidCallback onClear;

  /// False while another command is being sent.
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final memory = this.memory;
    final goBackTo = memory.goBackTo;
    final mark = memory.nextFreePoint;
    final hasMap = memory.trail.isNotEmpty || memory.points.isNotEmpty || memory.vehicle != null;
    return Card(
      key: const Key('flightMemory'),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            const Icon(Icons.route),
            const SizedBox(width: 8),
            Expanded(child: Text('Flight memory', style: theme.textTheme.titleMedium)),
            IconButton(
                key: const Key('memoryClear'),
                tooltip: 'Clear flight memory',
                onPressed: memory.isEmpty ? null : onClear,
                icon: const Icon(Icons.delete_sweep_outlined)),
          ]),
          if (memory.notice != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(memory.notice!, key: const Key('memoryNotice')),
            ),
          if (hasMap)
            Semantics(
              label: 'Map of this flight: ${memory.trail.length} positions flown, '
                  '${memory.points.length} points saved',
              child: SizedBox(
                key: const Key('memoryMap'),
                height: 160,
                child: CustomPaint(
                  painter: MemoryMapPainter(memory, MemoryMapColors.of(theme)),
                  size: Size.infinite,
                ),
              ),
            ),
          const SizedBox(height: 8),
          Wrap(spacing: 8, runSpacing: 8, children: [
            FilledButton.tonalIcon(
                key: const Key('memoryReturnHome'),
                onPressed: enabled && memory.homeKnown ? () => onCommand(_call('return_home')) : null,
                icon: const Icon(Icons.home_outlined),
                label: const Text('Return home')),
            FilledButton.tonalIcon(
                key: const Key('memoryGoBack'),
                onPressed: enabled && goBackTo != null ? () => onCommand(_call('go_back')) : null,
                icon: const Icon(Icons.undo),
                label: Text(goBackTo == null ? 'Go back' : 'Go back to $goBackTo')),
            FilledButton.tonalIcon(
                key: const Key('memoryRetrace'),
                onPressed: enabled && memory.canRetrace ? () => onCommand(_call('retrace')) : null,
                icon: const Icon(Icons.timeline),
                label: const Text('Retrace')),
            OutlinedButton.icon(
                key: const Key('memoryMark'),
                onPressed: enabled && memory.vehicle != null
                    ? () => onCommand(_call('mark_point', {'point': mark}))
                    : null,
                icon: const Icon(Icons.add_location_alt_outlined),
                label: Text('Mark point $mark')),
          ]),
          if (memory.points.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 4, children: [
              for (final point in memory.points)
                ActionChip(
                  key: Key('memoryPoint${point.point}'),
                  avatar: CircleAvatar(child: Text('${point.point}')),
                  label: Text('Go to point ${point.point}${_distance(point.place)}'),
                  onPressed: enabled ? () => onCommand(_call('goto_point', {'point': point.point})) : null,
                ),
            ]),
          ],
          if (memory.plans.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text('Recent commands', style: theme.textTheme.titleSmall),
            for (final plan in memory.plans.take(_plansShown))
              _PlanRow(plan: plan, enabled: enabled, onRunAgain: () => onRunAgain(plan)),
          ],
          if (memory.isEmpty && memory.vehicle == null)
            const Text('Fly a command and the path, your saved points and the commands you gave appear '
                'here. Say "return home", "go back" or "retrace" to fly back.'),
        ]),
      ),
    );
  }

  /// ", 12 m away" from home, when home is known.
  String _distance(MemoryPlace place) {
    final north = place.northM, east = place.eastM;
    if (!memory.homeKnown || north == null || east == null) return '';
    final metres = math.sqrt(north * north + east * east);
    return ' (${metres.round()} m from home)';
  }
}

class _PlanRow extends StatelessWidget {
  const _PlanRow({required this.plan, required this.enabled, required this.onRunAgain});

  final MemoryPlan plan;
  final bool enabled;
  final VoidCallback onRunAgain;

  @override
  Widget build(BuildContext context) {
    final at = DateTime.fromMillisecondsSinceEpoch(plan.atWallMs);
    String two(int n) => n.toString().padLeft(2, '0');
    final outcome = plan.completed
        ? 'Completed'
        : plan.steps.map((s) => s.outcome).firstWhere((o) => o != 'COMPLETED', orElse: () => 'COMPLETED');
    final source = plan.source?.label ?? plan.sourceWire;
    return Padding(
      key: Key('memoryPlan${plan.planId}'),
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(plan.summary),
            Text('${two(at.hour)}:${two(at.minute)} · $source · ${_outcomeLabel(outcome)}',
                style: Theme.of(context).textTheme.bodySmall),
          ]),
        ),
        TextButton(
            key: Key('memoryRunAgain${plan.planId}'),
            onPressed: enabled ? onRunAgain : null,
            child: const Text('Run again')),
      ]),
    );
  }

  static String _outcomeLabel(String outcome) => switch (outcome) {
        'COMPLETED' => 'Completed',
        'NOT_SENT' => 'Dry run',
        'REJECTED' => 'Rejected',
        'UNKNOWN' => 'Outcome unknown',
        'SKIPPED' => 'Skipped',
        _ => outcome,
      };
}

/// The map's colours, from the theme.
class MemoryMapColors {
  const MemoryMapColors({
    required this.path,
    required this.home,
    required this.point,
    required this.vehicle,
    required this.grid,
    required this.label,
  });

  factory MemoryMapColors.of(ThemeData theme) {
    final scheme = theme.colorScheme;
    return MemoryMapColors(
      path: scheme.primary,
      home: scheme.secondary,
      point: scheme.tertiary,
      vehicle: scheme.error,
      grid: scheme.outlineVariant,
      label: scheme.onSurface,
    );
  }

  final Color path, home, point, vehicle, grid, label;
}

/// North up, east right, scaled to fit: home, the flown path (oldest to
/// newest), the saved points and the vehicle. At least [minSpanM] across, so a
/// short flight is not drawn as if it were large.
class MemoryMapPainter extends CustomPainter {
  MemoryMapPainter(this.memory, this.colors);

  final FlightMemory memory;
  final MemoryMapColors colors;

  static const minSpanM = 10.0;

  @override
  void paint(Canvas canvas, Size size) {
    final vehicle = memory.vehicle == null ? null : _offset(memory.vehicle!);
    final trail = [
      for (final p in memory.trail)
        if (_offset(p.place) case final offset?) offset
    ];
    final points = <Offset>[
      if (memory.homeKnown) Offset.zero,
      ...trail,
      for (final s in memory.points)
        if (_offset(s.place) case final offset?) offset,
      if (vehicle != null) vehicle,
    ];
    if (points.isEmpty || size.isEmpty) return;
    var minX = points.map((p) => p.dx).reduce(math.min), maxX = points.map((p) => p.dx).reduce(math.max);
    var minY = points.map((p) => p.dy).reduce(math.min), maxY = points.map((p) => p.dy).reduce(math.max);
    final spanX = math.max(maxX - minX, minSpanM), spanY = math.max(maxY - minY, minSpanM);
    final centre = Offset((minX + maxX) / 2, (minY + maxY) / 2);
    const margin = 14.0;
    final scale = math.min((size.width - 2 * margin) / spanX, (size.height - 2 * margin) / spanY);
    Offset at(Offset metres) => size.center(Offset.zero) + (metres - centre) * scale;

    final border = Paint()
      ..color = colors.grid
      ..style = PaintingStyle.stroke;
    canvas.drawRRect(RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(8)), border);

    if (trail.length > 1) {
      final path = Path()..moveTo(at(trail.first).dx, at(trail.first).dy);
      for (final p in trail.skip(1)) {
        path.lineTo(at(p).dx, at(p).dy);
      }
      canvas.drawPath(
          path,
          Paint()
            ..color = colors.path
            ..style = PaintingStyle.stroke
            ..strokeWidth = 2.5
            ..strokeJoin = StrokeJoin.round);
    }
    for (final p in trail) {
      canvas.drawCircle(at(p), 3, Paint()..color = colors.path);
    }
    if (memory.homeKnown) {
      final home = at(Offset.zero);
      canvas.drawRect(Rect.fromCenter(center: home, width: 12, height: 12), Paint()..color = colors.home);
      _text(canvas, 'H', home + const Offset(0, -14));
    }
    for (final point in memory.points) {
      final offset = _offset(point.place);
      if (offset == null) continue;
      canvas.drawCircle(at(offset), 8, Paint()..color = colors.point);
      _text(canvas, '${point.point}', at(offset), color: Colors.white);
    }
    if (vehicle != null) {
      final centre = at(vehicle);
      final arrow = Path()
        ..moveTo(centre.dx, centre.dy - 8)
        ..lineTo(centre.dx + 6, centre.dy + 6)
        ..lineTo(centre.dx - 6, centre.dy + 6)
        ..close();
      canvas.drawPath(arrow, Paint()..color = colors.vehicle);
    }
  }

  /// East right, north up; null for a place the service could not express.
  static Offset? _offset(MemoryPlace place) {
    final north = place.northM, east = place.eastM;
    if (north == null || east == null) return null;
    return Offset(east, -north);
  }

  void _text(Canvas canvas, String text, Offset centre, {Color? color}) {
    final painter = TextPainter(
      text: TextSpan(
          text: text,
          style: TextStyle(color: color ?? colors.label, fontSize: 11, fontWeight: FontWeight.bold)),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, centre - Offset(painter.width / 2, painter.height / 2));
  }

  @override
  bool shouldRepaint(MemoryMapPainter old) =>
      old.memory != memory || old.colors.path != colors.path || old.colors.vehicle != colors.vehicle;
}
