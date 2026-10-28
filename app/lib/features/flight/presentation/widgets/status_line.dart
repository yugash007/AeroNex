import 'package:flutter/material.dart';

import '../../../session/session_api.dart';

/// `VehicleState.FRESH_MS`: a value older than this is not shown as current.
const freshMs = 3000;

/// The radio profile (`TelemetryProfile.RADIO`) sends these at 0.5 Hz, so one
/// lost frame leaves a value about 4 s old. They stay current for two
/// intervals plus 1 s; a second lost frame makes them stale.
const _radioSlowStreams = {'globalPosition', 'sysStatus', 'ekfStatus'};
const radioSlowFreshMs = 5000;

/// How old [stream] may be on telemetry [profile] and still read as current.
int freshWindowMs(String? profile, String stream) =>
    profile == 'radio' && _radioSlowStreams.contains(stream)
        ? radioSlowFreshMs
        : freshMs;

/// The streams behind the values the line shows (altitude, battery) and the
/// flight state (landed, position).
const _watchedStreams = [
  'globalPosition',
  'sysStatus',
  'extendedSysState',
  'ekfStatus'
];

const _modeWords = {
  'STABILIZE': 'Stabilize',
  'ALT_HOLD': 'Altitude hold',
  'AUTO': 'Auto',
  'GUIDED': 'Guided',
  'LOITER': 'Loiter',
  'RTL': 'Return home',
  'LAND': 'Land',
  'POSHOLD': 'Position hold',
  'BRAKE': 'Brake',
  'SMART_RTL': 'Smart return',
};

/// An ArduCopter mode name in plain words; unknown ones as given.
String modeLabel(String name) => _modeWords[name] ?? name;

bool _fresh(SessionSnapshot snapshot, String stream) {
  final age = snapshot.vehicle.ages[stream];
  return age != null &&
      age >= 0 &&
      age < freshWindowMs(snapshot.link.profile, stream);
}

/// One line: connection · mode (who set it) · altitude · battery. Values
/// whose stream went quiet are left out.
String statusText(SessionSnapshot snapshot) {
  if (snapshot.link.state == LinkState.down) return 'Not connected';
  final vehicle = snapshot.vehicle;
  if (!vehicle.connected) return 'Waiting for the drone…';
  final name = vehicle.modeName;
  final owner = switch (vehicle.modeOwner) {
    'APP' => 'this app',
    'EXTERNAL' => vehicle.modeOwnerLabel ?? 'set outside this app',
    _ => null,
  };
  final altitude = vehicle.altitudeRelM;
  final alt = altitude != null && _fresh(snapshot, 'globalPosition')
      ? altitude.toStringAsFixed(1)
      : null;
  final battery = vehicle.batteryPercent;
  return [
    'Connected',
    if (name != null) owner == null ? modeLabel(name) : '${modeLabel(name)} ($owner)',
    if (alt != null) 'Alt ${alt == '-0.0' ? '0.0' : alt} m',
    if (battery != null && battery >= 0 && _fresh(snapshot, 'sysStatus'))
      'Battery $battery%',
  ].join(' · ');
}

/// What is degraded right now, in the order shown.
List<String> statusChips(SessionSnapshot snapshot) {
  final link = snapshot.link;
  final vehicle = snapshot.vehicle;
  final open = link.state != LinkState.down;
  return [
    if (!vehicle.connected) 'Not connected',
    if (!snapshot.executionEnabled) 'Dry run',
    if (open && link.unsigned) 'Unsigned link',
    if (link.profile == 'radio') 'Radio profile',
    if (vehicle.connected &&
        _watchedStreams.any((s) => vehicle.ages[s] != null && !_fresh(snapshot, s)))
      'Telemetry stale',
    if (vehicle.connected && vehicle.healthy == false && _fresh(snapshot, 'sysStatus'))
      'Sensors not healthy',
    if (open && !link.foregroundService) 'Keep this app open',
  ];
}

/// The vehicle line and the degradation chips: compact, so the pinned
/// header leaves room for the plan on a small phone.
class StatusLine extends StatelessWidget {
  const StatusLine({super.key, required this.snapshot});

  final SessionSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final chips = statusChips(snapshot);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(statusText(snapshot),
          key: const Key('statusLine'),
          style: theme.textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.w600)),
      if (chips.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Wrap(key: const Key('statusChips'), spacing: 4, runSpacing: 4, children: [
            for (final label in chips)
              Chip(
                  visualDensity: const VisualDensity(horizontal: -4, vertical: -4),
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  padding: EdgeInsets.zero,
                  labelPadding: const EdgeInsets.symmetric(horizontal: 6),
                  backgroundColor: label == 'Dry run'
                      ? theme.colorScheme.secondaryContainer
                      : theme.colorScheme.errorContainer,
                  side: BorderSide.none,
                  label: Text(label, style: theme.textTheme.labelMedium)),
          ]),
        ),
    ]);
  }
}
