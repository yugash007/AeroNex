# Golden plan vectors

`plan_vectors.json` records what the v0.2.0 Dart pipeline does with each
transcript. The Kotlin port (architecture v2) is gated on it. It is a JSON
array with one vector per line.

## Vector

```
{input, language, vehicle, plan?, expect, spec_only?, note?}
```

- `input`: the transcript, exactly as typed or reviewed.
- `language`: the selected language: `en-IN`, `hi-IN`, `te-IN`, `ta-IN`,
  `kn-IN` or `ml-IN`.
- `vehicle`: `null` when there is no live state, so no vehicle check runs.
  Otherwise `{landed, altitude}`, where `altitude` (m) is `null` when it is
  not reported. When the vehicle is landed, its altitude is ignored.
- `plan` (optional): the rule steps `[{name, arguments}]`, in order, before
  the FlightPlan rules and the vehicle check. It is present only when the
  review passed and the rules read every clause. It is absent when the review
  rejected the input, a clause was rejected, or a clause needed the AI. `[]`
  means the transcript had no clauses.
- `expect`: the outcome of the whole pipeline. It is one of these:
  - `{steps: [{name, arguments, source}]}`: the plan is accepted. `source` is
    always `"rules"`.
  - `{error: "<message>"}`: rejected with exactly this v0.2.0 message (UTF-8;
    the ranges use an en dash, as in `1–10 m`).
  - `{error: "ai_required", clause}`: a sentinel. The v0.2.0 rules did not
    read `clause` (the first such clause), and v0.2.0 would have asked the
    local AI. v2 has **no AI fallback** (spec 4.9), so the port must
    **reject**, and any message passes. The vehicle does not matter.
  - `{error: "rejected"}`: a sentinel used only with `spec_only`. Any
    rejection passes.
- `spec_only: true`: the spec's outcome (the 4.9 `[unk]` policy), not
  v0.2.0's, with a `note`. The Dart guard only checks these are recorded
  unchanged. The Kotlin port must meet them.

Tool names and arguments: `takeoff {altitude_m}`,
`move_relative {direction, distance_m}`, `hold {}`, `land {}`,
`get_status {}`. Compare numbers numerically: `3` and `3.0` are equal. The
altitude rules allow a 1e-9 tolerance, so a takeoff to 1.4 m followed by a
0.4 m descent is accepted.

## Gates

- **Parser (T10):** for every vector, the transcript inspector, then the plan
  parser, then the validator must give `expect`, with the sentinels above.
- **PlanValidator (T5):** for every vector that has `plan`, validating
  `plan` (as `FlightCommand`s) against `vehicle` must accept when `expect` has
  `steps`, and otherwise reject with exactly `expect.error`.
  - With `vehicle: null`, only the FlightPlan rules run, in order: empty;
    more than 5 steps; status on its own; takeoff first; land last; the
    1–10 m range from the takeoff height.
  - With a vehicle, `checkVehicle` then runs: landed, then airborne with a
    takeoff step, then the 1–10 m range from the reported altitude.
  - `validatePlan` in `export_golden_test.dart` is the reference.
  - These vectors carry no home or position. Run the gate with the v2
    home-distance rule neutral (home known, at the start point). No accepted
    plan here goes more than 30 m from its start.

## Frozen since T12

The v0.2.0 Dart parser that recorded these vectors (`PlanInterpreter`,
`CommandTranscript`, and the source tests the inputs were mined from) was
removed in T12: speech recognition and parsing now run in the flight session
service, in Kotlin. `plan_vectors.json` and this file stay as the frozen
contract, and there is no regeneration.

- **Kotlin gates (the real ones):** `PlanParserGoldenTest` (T10: inspector,
  parser and validator against every vector, sentinels as above) and
  `PlanValidatorGoldenTest` (T5) read this file in place.
- **Dart guard:** `export_golden_test.dart` is a fixture-integrity test. It
  checks the vector count, every language, the standard vehicles, the five
  `spec_only` `[unk]` vectors (verbatim), the shape of every vector, that no
  input/language/vehicle triple repeats, that every FlightPlan and
  checkVehicle message has a plan vector, and that every recorded `plan`
  alone still gives its `expect` under the Dart FlightPlan rules (still used
  by the tap pad).

To change a vector deliberately, edit the JSON by hand, update
`vectorCount` in `export_golden_test.dart`, and run both Kotlin gates and
`flutter test test/golden`.

## 0.4.0 delta

`plan_vectors_delta_v04.json` holds the vectors whose expectation changes in
0.4.0 (spec `2026-10-15-natural-voice-ai-planner.md`, plan task N1: spoken
decimals and halves, direction verbs). `plan_vectors.json` stays frozen;
`PlanParserGoldenTest` applies the delta over it.

```
{version, note, changes: [{input, language, vehicle, was, plan, expect, phrasing}]}
```

- `input`, `language` and `vehicle` name exactly one frozen vector, and `was`
  repeats its frozen `expect`.
- `was` is a parser rejection (an exact error or `ai_required`; the frozen
  vector has no `plan`). `plan` is what the 0.4.0 parser reads, and `expect` is
  that plan's outcome under `PlanValidator` for the recorded vehicle: the
  steps, or the validator's message ("The drone is on the ground ...").
- So "error -> accepted" means the parser now reads a plan. For some vehicles
  the validator then refuses that plan, and `expect` stays an error: 7 of the
  21 changes are a vehicle-state message ("The drone is on the ground ..." 6
  times, "The drone is already flying. Remove the takeoff step." once).
- `phrasing` names the 0.4.0 table that reads the input.

The gate fails on any other change: a delta entry that is not such a
rejection-to-plan change, an input without a 0.4.0 word, or a frozen vector
the parser now reads that the delta does not list. 21 changes (7 inputs, 3
vehicles each): "move left one point five meters", "move forward two point
five meters", "move forward two and a half meters", "आधा मीटर आगे जाओ",
"సగం మీటర్ ముందుకు వెళ్ళు" (provisional), "climb by three meters" and "take off
to 2 m, then climb by three meters, then move down 1.5 m". The Dart guard
(`export_golden_test.dart`) reads only `plan_vectors.json`, so it is
unchanged.
