# Multi-step command plans and screen cleanup — design

Date: 2026-10-11. Supersedes the "exactly one action per proposal" rule of [mobile-local control](2026-10-08-mobile-local-control-design.md) for the simulator profile only. All other safety rules there still apply.

## Problem

An operator says "hey drone fly go 3 meters forward and move 5 meters right". Today this always fails: the LFM system prompt asks for exactly one tool with parallel tool calls disabled, `FlightCommand.fromToolCalls` rejects anything but one call, and the spec required one action per proposal. Operators also report clumsiness: SITL setup repeats every launch, too many taps between speaking and flying, raw technical text, and one long cluttered page.

## Decisions (operator-approved 2026-10-11)

1. **One confirmation per plan.** A plan is an ordered list of 1–5 validated steps, previewed as a numbered list and confirmed once. Steps run in order; each waits for telemetry confirmation (the existing executor) before the next. The plan stops at the first REJECTED or UNKNOWN step; remaining steps are reported as skipped and never sent. A Stop control aborts the run with the existing interruption semantics (drop the link, report UNKNOWN).
2. **Split + rules first, AI fallback.** The transcript is split into clauses on connectives. Each clause goes to a deterministic step parser. Only a clause in which the parser finds no command at all goes to the selected local AI (one tool per clause, unchanged prompt). The preview labels every step with its source (rules or AI). A clause the parser recognises but rejects (negation, non-metre unit, missing height/distance, unsupported number, two commands in one clause) rejects the whole plan and is never sent to AI.
3. **No invented takeoff.** If the vehicle is known to be on the ground and the first step is not a takeoff, the plan is rejected with a hint: "The drone is on the ground. Start with a takeoff height, for example: take off to 3 m and fly forward 3 m." If the vehicle is airborne, a takeoff step is rejected.
4. **Clumsiness fixes:** persist SITL settings, fewer taps, readable text, decluttered layout (details below).

## Plan rules

- 1 ≤ steps ≤ `FlightPlan.maxSteps` (5). More clauses → "Say at most 5 steps at a time."
- Every step is a `FlightCommand` validated by the existing allowlist and simulator policy (takeoff 1–10 m, distance >0–10 m).
- `get_status` only as a single-step plan. `takeoff` only as step 1. `land` only as the last step.
- When the plan starts with takeoff(h), or the current altitude is known, the projected altitude after every up/down move must stay within 1–10 m; otherwise the plan is rejected before preview. The executor still re-checks each step.
- Confirmation: bound to the immutable plan, expires after 15 s, one use. New input, cancel, backgrounding, disconnect, changed vehicle readiness or toggling execution mode invalidates it (unchanged rules, now per plan). The remaining seconds are shown on the confirm button.
- Results per step: COMPLETED, NOT SENT (dry run), REJECTED — nothing sent, UNKNOWN, or SKIPPED (not sent because an earlier step did not complete). No retries, no replay after reconnect.

## Splitting and parsing

- Connectives (case-insensitive, whole words; applied for every language because speech mixes languages): `,` `;` `and then` `then` `after that` `next` `and`; Hindi `और फिर` `फिर` `उसके बाद` `इसके बाद` `और`; Telugu `ఆ తర్వాత` `తర్వాత` `తరువాత` `మరియు`. Tamil/Kannada/Malayalam connectives are provisional (`பிறகு` `மற்றும்`, `ನಂತರ` `ಮತ್ತು`, `ശേഷം` `പിന്നെ`). Wake words (`hey drone`, `drone`) are stripped first; empty clauses are dropped.
- Negation is still rejected for the whole transcript by `CommandTranscript.inspect` before any parsing.
- Step parser (deterministic): takeoff phrases with an explicit height → `takeoff(altitude_m)`; a takeoff phrase without a height → reject with the takeoff-height hint. Moves, hold, land and status reuse the hardened `RuleBasedIntentEngine` vocabularies, numerals, unit and negation checks; "fly"/"go" are motion verbs, so "fly go 3 meters forward" is a forward move of 3 m. Only the engine's "no command recognised" outcome is eligible for AI fallback (typed `IntentNotRecognized`).

## Screen and flow

- **Fewer taps:** after Finish, a transcript with no review problem and no recognition suggestion is planned immediately (rules are instant). Typed commands submit from the keyboard. Flow: Speak → Finish → Confirm.
- **Readable text:** steps read "Take off to 3 m", "Move forward 3 m", "Move right 5 m", "Hold position", "Land", "Report status". Telemetry becomes a status line (link, armed, mode name, altitude, battery, position ready); the raw map moves to Diagnostics.
- **Layout:** top status card (DRY RUN or SIMULATOR EXECUTION ON, vehicle status); command card (Speak, type, heard/review text); plan card (numbered steps, source chips, countdown confirm, cancel, per-step progress/results); simulator card (connection, execution switch); speech & models settings and diagnostics below, collapsed when models are ready.
- **Setup once:** SITL mode, host and ports persist across launches (native SharedPreferences). Connect also requests the telemetry streams. With SITL mode remembered, the app reconnects on launch. Simulator **execution** stays a per-connection opt-in and is never persisted.

## Out of scope

Real aircraft, arbitrary missions, waypoints, loops, conditional steps, changing the LFM prompt, and plans longer than 5 steps.
