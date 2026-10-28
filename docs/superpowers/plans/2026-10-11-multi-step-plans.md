# Multi-step plans and screen cleanup — implementation plan

Spec: [2026-10-11-multi-step-plans-design.md](../specs/2026-10-11-multi-step-plans-design.md). App root: `app/`. Every task is TDD: write the failing test first, then the code, then run the task's checks. Flutter runs go through the shared lock wrapper given to each task. Keep the surrounding code style. No commits.

Order: T1 → (T2 ∥ T3 ∥ T4) → T5 → T6 → T7. Parallel tasks own disjoint files.

## T1 — Plan domain (owns `lib/features/flight/domain/flight_plan.dart` (new), `lib/features/flight/domain/flight_command.dart`, `test/flight_plan_test.dart` (new))

```dart
// flight_command.dart — add to FlightCommand (toString unchanged):
String get description; // 'Take off to 3 m', 'Move forward 3 m', 'Move up 2.5 m', 'Hold position', 'Land', 'Report status'

// flight_plan.dart
enum StepSource { rules, ai, fixture }
class PlanStep {
  final FlightCommand command; final StepSource source; final String clause;
  const PlanStep(this.command, {required this.source, this.clause = ''});
}
class FlightPlan {
  static const maxSteps = 5;
  final List<PlanStep> steps;                    // unmodifiable
  FlightPlan(List<PlanStep> steps);              // structural validation, throws FormatException
  factory FlightPlan.single(FlightCommand command, {StepSource source = StepSource.ai, String clause = ''});
  void checkVehicle({required bool landed, num? altitude}); // live-state validation, throws FormatException
  @override String toString();                   // '1. Take off to 3 m; 2. Move forward 3 m'
}
```

Exact FormatException messages:
- no steps: `Say a command first.`
- more than 5: `Say at most 5 steps at a time.`
- get_status with other steps: `Ask for status on its own.`
- takeoff not first: `Takeoff can only be the first step.`
- land not last: `Land can only be the last step.`
- projected altitude outside 1–10 m (simulate from takeoff height, or from `altitude` when given and airborne; up subtracts nothing — up adds, down subtracts): `Step N would leave the 1–10 m altitude range.` (N is 1-based)
- landed, first step not takeoff and not get_status: `The drone is on the ground. Start with a takeoff height, for example: take off to 3 m and fly forward 3 m.`
- airborne (landed false) and plan contains takeoff: `The drone is already flying. Remove the takeoff step.`

Numbers print without a trailing `.0` (3 → `3`, 2.5 → `2.5`).

## T2 — Splitting and parsing (owns `lib/features/flight/application/plan_interpreter.dart` (new), `lib/features/intent/data/rule_intent_engine.dart`, `test/plan_interpreter_test.dart` (new))

```dart
class CommandSplitter { static List<String> split(String transcript); }
class StepParser {
  StepParser({RuleBasedIntentEngine? engine});
  /// null = no command recognised (AI-eligible). FormatException = recognised but invalid (never AI).
  Future<FlightCommand?> parse(String clause, String language);
}
class PlanInterpreter {
  PlanInterpreter({required Future<FlightCommand> Function(String clause, String language) ai, StepParser? parser});
  Future<FlightPlan> interpret(String transcript, String language); // throws FormatException
}
```
- Connectives, wake words and provisional languages exactly as the spec. Language arguments are app codes such as `en-IN`, `te-IN`, `hi-IN`, `auto`; map to the engine's codes.
- In `rule_intent_engine.dart` add `class IntentNotRecognized extends IntentException` and throw it only from the "no motion verb found" path (message unchanged; the Python mirror's behaviour is unchanged). Optionally add a public helper that reuses the private numeral/unit logic for takeoff heights. Do not change any existing parse outcome or message.
- Takeoff phrase + explicit metre height → `takeoff`; takeoff phrase without height, or a bare `fly`, → FormatException `Step N ("<clause>"): say a takeoff height, for example: take off to 3 m.` Other engine rejections → `Step N ("<clause>"): <short readable reason>`.
- AI is called only for `IntentNotRecognized` clauses; its FlightCommand is tagged `StepSource.ai`; an AI FormatException becomes `Step N ("<clause>"): <its message>`.
- Required tests include the operator's sentence: `hey drone fly go 3 meters forward and move 5 meters right` → [Move forward 3 m, Move right 5 m] from rules, AI never called; `take off to 3 metres then move forward 2 metres and land` → 3 steps; Hindi and Telugu connective examples; `move forward 50 cm and land` rejected without AI; 6 clauses rejected; an unrecognised clause goes to AI and is tagged.

## T3 — Plan execution (owns `lib/features/flight/application/command_controller.dart`, `test/command_controller_test.dart`)

```dart
enum StepStatus { pending, running, completed, notSent, rejected, unknown, skipped }
class StepOutcome { final PlanStep step; final StepStatus status; final String? message; const StepOutcome(this.step, this.status, [this.message]); }
// CommandController keeps its constructor and `execute` (per step).
FlightPlan? proposal;            // was FlightCommand?
List<StepOutcome> outcomes;      // one per step after confirm, updated as steps run
int? get secondsLeft;            // whole seconds until expiry while awaiting confirmation, else null
Future<void> prepare(Future<FlightPlan> Function() interpret);
```
- A 1 s periodic tick notifies listeners while awaiting confirmation; 15 s expiry unchanged.
- `confirm()` revalidates every step (`FlightCommand.fromToolCalls`) and the plan (`FlightPlan(...)`), then runs steps in order. A string result whose text starts with `NOT SENT` is `notSent`, otherwise `completed`. `NotSentError` → `rejected`; any other error → `unknown`. The first non-completed/non-notSent step stops the plan; later steps become `skipped` and are never executed. `failure` = that error; `stage` = failed, else finished.
- `result` keeps the existing single-step strings exactly for one-step plans (`NOT SENT — dry run: …`, executor text, `REJECTED — nothing sent: …`, `UNKNOWN — no confirmation in time…`, `UNKNOWN — execution failed: …`). For multi-step plans `result` is one line per step: `1. <description> — <that step's text or SKIPPED — not sent>`.
- Cancel and generation semantics unchanged: a cancelled run executes no further steps and its results are discarded.

## T4 — Saved simulator settings (owns `android/app/src/main/kotlin/com/aeronex/app/LocalInferenceBridge.kt`, `lib/core/local_inference.dart`, `test/local_inference_test.dart`, optional new Kotlin host test file)

- Channel `aeronex/inference`: `simSettings` → `{enabled: bool, host: String, remotePort: int, localPort: int}` (defaults false, `192.168.1.100`, 14550, 14551); `saveSimSettings(args)` validates (host is an IPv4 literal ≤ 15 chars, ports 1–65535, enabled bool) and stores in the existing `models` SharedPreferences under `sim-enabled`, `sim-host`, `sim-remote-port`, `sim-local-port`; invalid → `error("INVALID_SIM_SETTINGS", …)`.
- Dart: `class SimSettings { final bool enabled; final String host; final int remotePort, localPort; }` and `Future<SimSettings> simSettings()` (tolerant: missing/wrong types → defaults) and `Future<void> saveSimSettings(SimSettings)`.

## T5 — Screen: plan flow (owns `lib/features/flight/presentation/local_control_screen.dart`, `test/local_control_screen_test.dart`)

- Typed and reviewed commands: `CommandTranscript.inspect` first (unchanged), then `PlanInterpreter(ai: _local.interpret).interpret(...)`; when SITL is connected, call `plan.checkVehicle(landed: telemetry landed == 1, altitude: telemetry altitude)` before showing it. Diagnostics fixtures become `FlightPlan.single(..., source: StepSource.fixture)`.
- After Finish, a transcript with no review problem and no recognition suggestion is planned automatically. The typed field submits from the keyboard.
- Plan card: numbered `step.command.description` rows with a source chip (Rules/AI/Test), a separate `Expires in N s` text (the confirm button labels stay exactly `Confirm dry run` / `Confirm and send to simulator`), Cancel, and during/after the run one row per step with its status and message; each result text appears once on screen.
- The interpret buttons read `Interpret command` (Needle keeps its own label if selected). Keep every existing safety behaviour and update tests to the new labels and flow.

## T6 — Screen: setup once, readable status, decluttered layout (owns the same two files as T5 plus `lib/features/flight/presentation/vehicle_status.dart` (new) and `test/vehicle_status_test.dart` (new))

- `String vehicleStatus(Map<String, dynamic> values, {required bool connected})` → e.g. `Connected · Disarmed · On ground · Stabilize · Alt 0.0 m · Battery 100% · Position ready`; ArduCopter mode names for 0,2,3,4,5,6,9,16,17 (`Stabilize`,`AltHold`,`Auto`,`Guided`,`Loiter`,`RTL`,`Land`,`PosHold`,`Brake`), `Mode N` otherwise; `Not connected` when not connected.
- Layout per spec: status card (mode banner + vehicle status) → command card → plan card → simulator card → speech & models (collapsed when models are ready) → diagnostics (raw telemetry map moves here).
- Load `simSettings()` at start: fill fields; when enabled, enter SITL mode without the dialog and connect automatically. Save on SITL toggle and after a successful Connect. Connect also requests telemetry. Execution opt-in stays per connection and is never saved.

## T7 — Docs and version (owns `app/pubspec.yaml`, `README.md`, `docs/simulation/RUN_SIMULATION.md`)

- Version `0.2.0+11`. README current-behaviour section and RUN_SIMULATION "Connect the Android app" describe multi-step plans, the rules/AI labels, saved settings and the execution switch. Do not claim real-flight readiness.

## Verification

Full `flutter analyze` and `flutter test`; Android host tests (`gradlew --offline testDebugUnitTest`); Python edge tests unchanged; release APK with `tools/check_android_package.py`; adversarial review of the whole diff; end-to-end run of a multi-step plan against the live SITL from the emulator.
