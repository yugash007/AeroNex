import 'dart:async';

import 'package:flutter/material.dart';

import '../../../../core/user_errors.dart';
import '../../../session/session_api.dart';
import 'ptt_bar.dart';

/// Sends a typed command for planning and confirmation.
typedef CommandHandler = Future<void> Function(
    String text, String language, PlanSource source);

/// Why the app asks for the microphone (shown before the system dialog).
const microphoneReason = 'AeroNex listens only while you hold Talk or '
    'Volume-Up, and for the word "stop" while the drone flies under the '
    "app's control. The audio stays on this phone and is never saved or sent.";

/// The system dialog covers STOP: never open it while the drone is armed.
const microphoneWhileArmed = 'To use voice, allow the microphone while the '
    'drone is landed and disarmed: the permission dialog covers STOP. Use '
    'the tap pad or typed commands now.';

const microphoneDenied = 'The microphone is not allowed. To use voice, allow '
    'it for AeroNex in Android Settings. The tap pad and typed commands work '
    'without it.';

const microphoneAllowed = 'Microphone allowed. Hold Talk to speak.';

/// The voice STOP spotter hears English "stop" only (KWS, no STOP word in
/// the selected language).
const englishVoiceStop = 'Voice STOP: say "stop" in English.';

/// Voice and typed commands. The session service does the listening and the
/// parsing (the Kotlin rules, and since 0.4.0 the on-phone AI for what they
/// do not understand): push-to-talk sends only the press and release,
/// and what it heard comes back in the snapshot (the live partial, the
/// transcript, or why it is not a plan); a plan shows as the waiting plan
/// with the Phone source. Typed text goes to [onCommand] in the selected
/// language. Either way the plan still needs CONFIRM in the service.
class CommandInput extends StatefulWidget {
  const CommandInput({
    super.key,
    required this.session,
    required this.snapshot,
    required this.onCommand,
    required this.onEdited,
    this.enabled = true,
    this.onOpenModels,
  });

  final SessionApi session;
  final SessionSnapshot snapshot;
  final CommandHandler onCommand;

  /// The operator changed a command they had not sent yet.
  final VoidCallback onEdited;

  /// False while the screen is sending a plan; the text field is locked too,
  /// so the plan shown always matches the text shown.
  final bool enabled;
  final VoidCallback? onOpenModels;

  @override
  State<CommandInput> createState() => _CommandInputState();
}

class _CommandInputState extends State<CommandInput> {
  final _typed = TextEditingController();

  /// A press that is push-to-talk in the service (not the permission flow).
  bool _talking = false;
  bool _asking = false;

  /// What this screen says about voice (permission, a failed call).
  String? _notice;

  SpeechStatus get _speech => widget.snapshot.speech;

  @override
  void dispose() {
    _typed.dispose();
    super.dispose();
  }

  Future<void> _call(Future<void> Function() call) async {
    try {
      await call();
    } catch (error) {
      if (mounted) setState(() => _notice = userErrorMessage(error));
    }
  }

  void _down() {
    setState(() => _notice = null);
    if (_speech.needsMicrophone) {
      unawaited(_microphone());
      return;
    }
    _talking = true;
    unawaited(_call(widget.session.pttDown));
  }

  void _up() {
    if (!_talking) return;
    _talking = false;
    unawaited(_call(widget.session.pttUp));
  }

  /// RECORD_AUDIO before the first push-to-talk: the reason first, and never
  /// while the drone is armed (the system dialog covers STOP).
  Future<void> _microphone() async {
    if (_asking) return;
    _asking = true;
    try {
      final state = await widget.session.microphonePermission();
      if (state == NotificationPermission.askable) {
        if (widget.snapshot.vehicle.armed == true) {
          _say(microphoneWhileArmed);
          return;
        }
        if (!mounted || !await _explain()) return;
        // Granted in the dialog: the service starts the microphone itself.
        _say(await widget.session.microphonePermission(ask: true) ==
                NotificationPermission.granted
            ? microphoneAllowed
            : microphoneDenied);
      } else if (state == NotificationPermission.granted) {
        // Allowed in Settings after the session started: a press with voice
        // off asks the service to start the microphone (nothing is heard).
        await widget.session.pttDown();
        await widget.session.pttUp();
        _say(microphoneAllowed);
      } else {
        _say(microphoneDenied);
      }
    } catch (error) {
      _say(userErrorMessage(error));
    } finally {
      _asking = false;
    }
  }

  void _say(String message) {
    if (mounted) setState(() => _notice = message);
  }

  Future<bool> _explain() async =>
      await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
                title: const Text('Allow the microphone?'),
                content: const Text(microphoneReason),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(context, false),
                      child: const Text('Not now')),
                  FilledButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('Continue')),
                ],
              )) ==
      true;

  void _edited() {
    if (_notice != null) setState(() => _notice = null);
    widget.onEdited();
  }

  void _sendTyped() {
    if (widget.enabled) {
      widget.onCommand(_typed.text, _speech.language, PlanSource.typed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final speech = _speech;
    final connected = widget.snapshot.link.state != LinkState.down;
    final partial = speech.partial;
    final notice = _notice;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      PttBar(
          speech: speech,
          enabled: connected,
          onDown: _down,
          onUp: _up),
      const SizedBox(height: 4),
      Row(children: [
        const Icon(Icons.translate, size: 18),
        const SizedBox(width: 8),
        Expanded(
          child: DropdownButton<String>(
          key: const Key('speechLanguage'),
          isExpanded: true,
          value: speechLanguages.containsKey(speech.language)
              ? speech.language
              : null,
          hint: const Text('Language'),
          onChanged: (code) {
            if (code != null) {
              unawaited(_call(() => widget.session.setLanguage(code)));
            }
          },
          items: [
            for (final MapEntry(:key, :value) in speechLanguages.entries)
              DropdownMenuItem(
                  value: key,
                  child: Text(value, overflow: TextOverflow.ellipsis)),
          ],
        )),
        if (widget.onOpenModels != null)
          TextButton(
              onPressed: widget.onOpenModels,
              child: const Text('Speech models')),
      ]),
      // The KWS spotter (e.g. Telugu on a mid-range phone) hears English
      // "stop" only: a STOP said in the selected language would not work.
      if (speech.spotter == 'KWS' && !speech.nativeVoiceStop)
        Text(englishVoiceStop,
            key: const Key('englishVoiceStop'),
            style: theme.textTheme.bodyMedium
                ?.copyWith(fontWeight: FontWeight.w600)),
      if (speech.listening || speech.pttHeld)
        Text(
            partial == null || partial.isEmpty
                ? 'Listening…'
                : 'Hearing: $partial',
            key: const Key('partialTranscript'),
            style: theme.textTheme.bodyLarge),
      if (!speech.listening && speech.heard != null)
        Text('Heard: ${speech.heard}',
            key: const Key('heardTranscript'), style: theme.textTheme.bodyLarge),
      if (connected && !speech.ok && speech.message != null)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Text(
              speech.degraded
                  ? 'Voice degraded: ${speech.message}'
                  : speech.message!,
              key: const Key('voiceLevel'),
              style: TextStyle(
                  color: speech.degraded
                      ? theme.colorScheme.tertiary
                      : theme.colorScheme.error)),
        ),
      for (final message in {
        // The level line already says why voice is off.
        if (!speech.listening && speech.error != speech.message) speech.error,
        notice,
      }.whereType<String>())
        Card(
          key: const Key('speechError'),
          color: theme.colorScheme.errorContainer,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Text(message,
                style: TextStyle(color: theme.colorScheme.onErrorContainer)),
          ),
        ),
      const SizedBox(height: 8),
      TextField(
          controller: _typed,
          // Locked while a command is sent: the plan must match this text.
          enabled: widget.enabled,
          textInputAction: TextInputAction.go,
          onChanged: (_) => _edited(),
          onSubmitted: (_) => _sendTyped(),
          decoration: InputDecoration(
              labelText: 'Type a command',
              hintText: 'take off to 3 metres, then forward 2 metres',
              suffixIcon: IconButton(
                  tooltip: 'Send',
                  icon: const Icon(Icons.send),
                  onPressed: widget.enabled ? _sendTyped : null))),
    ]);
  }
}
