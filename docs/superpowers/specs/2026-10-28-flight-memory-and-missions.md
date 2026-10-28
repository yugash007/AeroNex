# Flight memory and missions (2026-10-28)

Two features on top of the 0.4.0 architecture. The flight memory remembers where the app flew the drone, so the
operator can say "return home", "go back", "retrace" or "go to point 2". It also keeps the commands given, so a plan
can be run again. Missions fly several plans as stages after one CONFIRM, with optional checkpoints and resume.

Both keep the v2 safety model. Nothing reaches the drone without the validator and one CONFIRM. STOP is never
delayed. The AI never invents a position.

## Flight memory

### M1: the journal (`memory/FlightJournal.kt`)

- The **trail** is the positions the app flew the vehicle to in this flight, oldest first. A position is added only
  when telemetry confirmed the step that reached it: where a takeoff completed, a move arrived, or a memory flight
  ended.
  - The first trail position is where the takeoff ended. A new takeoff starts a new trail.
  - A position within 0.5 m of the last one adds nothing.
  - The trail holds at most 64 positions, and always keeps the takeoff position.
- **Departures.** A move or memory flight that starts away from the newest trail position first records that start.
  This is the case after a STOP, after a pilot moved the vehicle, or on a flight this app did not take off. "Go back"
  can then return there.
- **Points** are positions saved by number (1–9) with "mark point n". Saving a number again replaces its point.
- **History** is the steps of every finished plan: name, arguments, description, source, final status and mission
  stage. At most 60 steps are kept; the UI's "Run again" uses it.
- **Home guard.** Positions are in the autopilot's local NED frame and only mean something while home stays put. When
  HOME_POSITION moves more than 1 m, the trail and points are forgotten and the notice says so. A memory restored
  without a home keeps its history only.
- **Persistence.** The memory is saved in `filesDir/flight_memory.json` (`FileJournalStore`). Each write goes to a
  temporary file that then replaces the old one, on a background thread, coalesced. A damaged file starts empty: the
  app never flies to a misread position.

### M2: routes (`memory/MemoryRoutes.kt`) and the executor

| Command | Route |
|---|---|
| return home | Home's north and east at the current height: a level flight. Land is its own step. This is not ArduPilot RTL. |
| go back | The newest trail position at least 0.75 m away. The positions after it are forgotten, so saying it again walks further back (undo). |
| retrace | Every trail position, newest first, back to where the takeoff ended: the path already flown. |
| go to point n | The saved point, at its saved height. |

- `MemoryRoutes.flyable` keeps every leg between 1 and 10 m above home, counted from the current altitude. A takeoff
  completes up to 0.4 m short, so a remembered position can sit just outside the range. It also drops legs shorter
  than 0.75 m, which could not be told from the last one.
- The executor's `Goto` step flies each leg as one SET_POSITION_TARGET_LOCAL_NED in LOCAL_NED, sent exactly once.
  - The heading of the step's start is held, so the next body-frame move keeps the heading the operator saw.
  - Each leg is confirmed like a move: its echo, then arrival at under 0.3 m/s. Only then is the next leg sent.
  - The step deadline counts from the latest leg.

### M3: validation (`safety/PlanValidator.kt`)

- A plan is projected with the same route rules the supervisor flies with. A point marked in step 2 is there for
  step 4, and a takeoff starts a new trail.
- Every leg must stay within 30 m of home. An up or down move after a memory flight must stay within 1–10 m.
- The executor and the supervisor check every step again when it starts, against the memory as it is then.

### M4: the supervisor and the session

- The supervisor writes the journal on its own thread when a step completes, and records every finished plan.
- "Mark point n" alone is saved at once, like status: it reads telemetry and sends nothing. Inside a plan it is saved
  when its turn comes. It needs a fresh, EKF-backed position and a known home.
- The snapshot's `memory` section has:
  - the trail, points and vehicle in metres from home;
  - where "go back" would fly now, and whether retrace has a path;
  - the newest 20 plans.
- `runAgain(planId)` shows a recorded plan again, validated against the vehicle now. `clearMemory` forgets
  everything except home.

### M5: language

- The AI path never proposes or submits a memory command. These commands are the operator's alone.
- The grammar's `memory` tables (en, hi and te; see `grammar/README.md` "Flight memory") feed the Vosk grammars, the
  CTC te templates and the rule parser (`command/MemoryCommands.kt`).
  - The rule parser reads a whole clause as a memory command only when it is exactly a template. So "go back 3 m"
    stays a move, and the 3,431 golden vectors are unchanged.
- The saved positions are called *points*, never "spot": "spot" is one sound from "stop".
- The hi and te phrases are provisional (native-speaker review). They have not yet been checked against the Vosk
  model vocabularies (`gen.py --require-vosk` with the models).

## Missions

### Model (`mission/Mission.kt`)

- A mission has up to 5 stages of up to 5 steps each, flown in order as one plan of up to 25 steps.
- It keeps the plan rules across stages: a takeoff only first, a land only last, and heights within 1–10 m from a
  takeoff.
- It never holds a status step: status runs at once.

### Building

- While **Build a mission** is on, every new plan becomes the next stage instead of waiting for CONFIRM. This covers
  plans that are said, typed, tapped or produced by the AI.
- STOP, HOLD and status still act at once. A lone "mark point n" is a stage, because it belongs to the mission.
- A stage is checked only for the mission rules when it is added, not against the vehicle. So a mission can be built
  on the ground with stages that need the drone in the air.
- A plan already shown for CONFIRM can be added as a stage instead of running.

### Start, run and checkpoints

- **Start** validates the whole mission from the vehicle now: geofence, heights and memory flights across stages.
  It then shows the mission for one CONFIRM, with 30 s instead of 15 s to read up to 25 steps.
- The mission runs like a plan. Each step is validated again with the rest of the mission when it starts.
- **Checkpoints.** With "Hold after each stage" on, the run stops before each later stage. The vehicle holds where the
  last stage left it, and nothing is sent until **Continue**. The next stage is then validated again from the
  vehicle's state.
- A STOP, a failsafe text or a mode change at a checkpoint ends the mission. The next stage's first step is REJECTED
  (nothing of it was sent), not UNKNOWN.

### Resume

- A mission that ended early (STOP, failure or deadline) can be resumed in two ways:
  - from the step it ended at: an interrupted relative move runs again in full from where the vehicle is now, and the
    plan card shows this before the CONFIRM;
  - from the step after it.
- Either way, the rest of the mission keeps its stages and is validated and confirmed again.
- The history records each step's stage, so "Run again" shows a recorded mission as a mission again.

## Tests

| Area | Tests |
|---|---|
| Journal, routes, codec | `memory/*Test.kt` |
| Validator projection | `safety/PlanValidatorMemoryTest.kt` |
| Executor legs | `vehicle/executor/ArduPilotExecutorMemoryTest.kt` |
| Supervisor, against the fake ArduCopter | `safety/SupervisorFlightMemoryTest.kt`, `safety/SupervisorMissionTest.kt` |
| Mission model | `mission/MissionTest.kt` |
| Session and channel | `session/SessionFlightMemoryTest.kt` |
| Parser and grammar | `command/MemoryCommandsTest.kt`, grammar fixtures |
| Snapshot schema | fixtures `flight_memory`, `mission_checkpoint`, `mission_building` |
| Flutter | the `flight memory` and `missions` groups of `flight_screen_test.dart`, and `session_api_test.dart` |

## Limits

- HOME_POSITION's x/y/z are taken as home in the LOCAL_POSITION_NED frame, as the geofence already does. Some
  ArduPilot versions report zeros there, which is the EKF origin; in SITL the two coincide.
- Memory flights are validated against the 30 m geofence and the 1–10 m height range only. There is no obstacle
  awareness. Retrace flies the path already flown, which was clear at the heights it was flown at.
- The ta, kn and ml lexicons have no memory phrases yet. Their speakers use the flight memory and mission cards.
