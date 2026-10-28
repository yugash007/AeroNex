import 'package:flutter/material.dart';

import '../../domain/flight_command.dart';

/// Commands without speech: the same tool calls the parser produces. Motion
/// still waits for CONFIRM in the service; HOLD runs at once and stays
/// enabled while another command is being sent.
class TapPad extends StatefulWidget {
  const TapPad({super.key, required this.onCommand, this.enabled = true});

  final void Function(FlightCommand command) onCommand;
  final bool enabled;

  @override
  State<TapPad> createState() => _TapPadState();
}

/// The plan limits for a move or a takeoff height.
const _minMetres = 1, _maxMetres = 10;

int _clamp(int metres) => metres.clamp(_minMetres, _maxMetres);

FlightCommand _call(String name, [Map<String, Object> arguments = const {}]) =>
    FlightCommand.fromToolCalls([
      {'name': name, 'arguments': arguments}
    ]);

class _TapPadState extends State<TapPad> {
  int _distance = 2;

  void _send(FlightCommand command) => widget.onCommand(command);

  void _move(String direction) =>
      _send(_call('move_relative', {'direction': direction, 'distance_m': _distance}));

  Future<void> _takeOff() async {
    final height = await showDialog<int>(
        context: context, builder: (_) => const _TakeoffHeightDialog());
    if (height != null && mounted) _send(_call('takeoff', {'altitude_m': height}));
  }

  Widget _button(String label, IconData icon, VoidCallback onPressed, {bool always = false}) => Padding(
        padding: const EdgeInsets.all(3),
        child: OutlinedButton(
          style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 4),
              minimumSize: const Size(0, 56)),
          onPressed: widget.enabled || always ? onPressed : null,
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Icon(icon),
              Text(label),
            ]),
          ),
        ),
      );

  Widget _move4(String label, IconData icon, String direction) =>
      _button(label, icon, () => _move(direction));

  @override
  Widget build(BuildContext context) {
    const gap = Expanded(child: SizedBox());
    return Card(
      key: const Key('tapPad'),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(child: _button('Take off', Icons.flight_takeoff, _takeOff)),
            Expanded(
                child: _button('Hold', Icons.pause_circle_outline, () => _send(_call('hold')),
                    always: true)),
            Expanded(child: _button('Land', Icons.flight_land, () => _send(_call('land')))),
          ]),
          _Stepper(
              label: 'Distance',
              value: _distance,
              less: 'Shorter',
              more: 'Longer',
              onStep: (delta) => setState(() => _distance = _clamp(_distance + delta))),
          Row(children: [
            gap,
            Expanded(child: _move4('Forward', Icons.arrow_upward, 'forward')),
            gap,
            Expanded(child: _move4('Up', Icons.keyboard_double_arrow_up, 'up')),
          ]),
          Row(children: [
            Expanded(child: _move4('Left', Icons.arrow_back, 'left')),
            gap,
            Expanded(child: _move4('Right', Icons.arrow_forward, 'right')),
            gap,
          ]),
          Row(children: [
            gap,
            Expanded(child: _move4('Back', Icons.arrow_downward, 'backward')),
            gap,
            Expanded(child: _move4('Down', Icons.keyboard_double_arrow_down, 'down')),
          ]),
        ]),
      ),
    );
  }
}

/// − value m + within 1–10 m.
class _Stepper extends StatelessWidget {
  const _Stepper({
    required this.label,
    required this.value,
    required this.less,
    required this.more,
    required this.onStep,
  });

  final String label, less, more;
  final int value;

  /// Called with -1 or +1; the owner clamps, so quick taps never overshoot.
  final ValueChanged<int> onStep;

  @override
  Widget build(BuildContext context) => Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(label),
            IconButton(
                tooltip: less,
                onPressed: value > _minMetres ? () => onStep(-1) : null,
                icon: const Icon(Icons.remove_circle_outline)),
            SizedBox(
                width: 56,
                child: Text('$value m',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.titleMedium)),
            IconButton(
                tooltip: more,
                onPressed: value < _maxMetres ? () => onStep(1) : null,
                icon: const Icon(Icons.add_circle_outline)),
          ]);
}

/// Takeoff needs a height (v0.2.0 asked "To what height?").
class _TakeoffHeightDialog extends StatefulWidget {
  const _TakeoffHeightDialog();

  @override
  State<_TakeoffHeightDialog> createState() => _TakeoffHeightDialogState();
}

class _TakeoffHeightDialogState extends State<_TakeoffHeightDialog> {
  int _height = 3;

  @override
  Widget build(BuildContext context) => AlertDialog(
        scrollable: true,
        title: const Text('Take off to what height?'),
        content: _Stepper(
            label: 'Height',
            value: _height,
            less: 'Lower',
            more: 'Higher',
            onStep: (delta) => setState(() => _height = _clamp(_height + delta))),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, _height),
              child: const Text('Take off')),
        ],
      );
}
