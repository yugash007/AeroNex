import 'package:flutter/material.dart';

import '../../../session/session_api.dart';

/// Hold-to-talk (spec 4.4): the service listens from 500 ms before the press
/// until the adaptive tail after the release, then parses what it heard.
/// Volume-Up does the same natively while this screen is in front. Raw
/// pointer events, not taps: a hold must never be taken over by scrolling
/// or the gesture arena, and the release always reaches [onUp].
class PttBar extends StatefulWidget {
  const PttBar({
    super.key,
    required this.speech,
    required this.onDown,
    required this.onUp,
    this.enabled = true,
  });

  final SpeechStatus speech;
  final VoidCallback onDown, onUp;
  final bool enabled;

  @override
  State<PttBar> createState() => _PttBarState();
}

class _PttBarState extends State<PttBar> {
  /// The pointer holding the bar, if any (one press at a time).
  int? _pointer;

  void _down(PointerDownEvent event) {
    if (!widget.enabled || _pointer != null) return;
    setState(() => _pointer = event.pointer);
    widget.onDown();
  }

  void _up(PointerEvent event) {
    if (event.pointer != _pointer) return;
    setState(() => _pointer = null);
    widget.onUp();
  }

  @override
  void didUpdateWidget(PttBar old) {
    super.didUpdateWidget(old);
    // A STOP (or the app leaving) ended push-to-talk in the service.
    if (_pointer != null && old.speech.pttHeld && !widget.speech.pttHeld) {
      _pointer = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final held = _pointer != null;
    final speech = widget.speech;
    final label = held
        ? 'Listening… release to send'
        : speech.decoding
            ? 'Working…'
            : 'Hold to talk';
    final background = !widget.enabled
        ? scheme.surfaceContainerHighest
        : held
            ? scheme.primary
            : scheme.primaryContainer;
    final foreground = !widget.enabled
        ? scheme.onSurface.withValues(alpha: 0.38)
        : held
            ? scheme.onPrimary
            : scheme.onPrimaryContainer;
    return Semantics(
      button: true,
      enabled: widget.enabled,
      label: 'Hold to talk',
      child: Listener(
        key: const Key('pttBar'),
        behavior: HitTestBehavior.opaque,
        onPointerDown: _down,
        onPointerUp: _up,
        onPointerCancel: _up,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 80),
          height: 72,
          decoration: BoxDecoration(
              color: background, borderRadius: BorderRadius.circular(16)),
          alignment: Alignment.center,
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(held ? Icons.mic : Icons.mic_none, color: foreground, size: 28),
            const SizedBox(width: 12),
            Flexible(
              child: Text(label,
                  style: TextStyle(
                      color: foreground,
                      fontSize: 20,
                      fontWeight: FontWeight.w600)),
            ),
          ]),
        ),
      ),
    );
  }
}
