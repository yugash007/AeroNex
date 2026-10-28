import 'package:flutter/material.dart';

import 'features/diagnostics/presentation/diagnostics_screen.dart';
import 'features/flight/presentation/flight_screen.dart';
import 'features/link/data/link_settings.dart';
import 'features/link/presentation/link_setup_screen.dart';
import 'features/models/presentation/models_screen.dart';
import 'features/session/session_api.dart';

/// Navigation: Flight is home; its menu opens Link setup, Models and
/// Diagnostics. Every screen gets the one [session]. STOP is not a screen's:
/// `AeroNexApp` puts a `StopLane` above the navigator, over every route.
class AppShell extends StatelessWidget {
  const AppShell({
    super.key,
    required this.session,
    this.settings = const DeviceLinkSettingsStore(),
  });

  final SessionApi session;
  final LinkSettingsStore settings;

  Widget _screen(FlightDestination destination) => switch (destination) {
        FlightDestination.linkSetup =>
          LinkSetupScreen(session: session, settings: settings),
        FlightDestination.models => ModelsScreen(session: session),
        FlightDestination.diagnostics => DiagnosticsScreen(session: session),
      };

  @override
  Widget build(BuildContext context) => FlightScreen(
        session: session,
        settings: settings,
        open: (destination) => Navigator.of(context).push<void>(
            MaterialPageRoute(builder: (_) => _screen(destination))),
      );
}
