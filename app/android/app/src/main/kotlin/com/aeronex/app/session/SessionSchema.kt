package com.aeronex.app.session

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.HomePosition
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.link.mavlink.StatusText
import com.aeronex.app.link.mavlink.SysStatus
import com.aeronex.app.memory.MemoryRoutes
import com.aeronex.app.memory.MemoryState
import com.aeronex.app.observability.LatencySummary
import com.aeronex.app.safety.ConfirmResult
import com.aeronex.app.safety.PendingPlan
import com.aeronex.app.safety.PlanSource
import com.aeronex.app.safety.PlanStage
import com.aeronex.app.safety.StepStatus
import com.aeronex.app.safety.StopStatus
import com.aeronex.app.safety.SubmitResult
import com.aeronex.app.safety.SupervisorState
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import kotlin.math.roundToLong

/** The `link.state` values. */
enum class LinkState(val wireName: String) {
    /** No session: nothing is connected or connecting. */
    DOWN("down"),

    /** A session is open but no vehicle heartbeat arrived within the last 3 s (never yet, or lost). */
    CONNECTING("connecting"),

    /** A vehicle heartbeat arrived within the last 3 s. */
    UP("up"),
}

/** What the snapshot's `link` section reports; see [SessionSchema]. */
data class LinkInfo(
    val state: LinkState,
    val transport: String?,
    val description: String?,
    val profile: String?,
    val unsigned: Boolean,
    val heartbeatAgeMs: Long?,
    val txErrors: Long,
    val droppedDatagrams: Long?,
    val foregroundService: Boolean,
) {
    companion object {
        fun down(foregroundService: Boolean) = LinkInfo(LinkState.DOWN, null, null, null, unsigned = true,
            heartbeatAgeMs = null, txErrors = 0, droppedDatagrams = null, foregroundService = foregroundService)
    }
}

/** A `submitPlan` request, parsed. */
data class PlanRequest(val plan: FlightPlan, val source: PlanSource, val utterance: String?)

/**
 * The `aeronex/session` wire schema, version [VERSION]: what the Dart client sends and what it receives. Maps go
 * over Flutter's StandardMessageCodec, so integers arrive as Dart `int`, decimals as `double`, maps in key order.
 * app/test/fixtures/session/\*.json are representative snapshots, checked by a JVM test (SessionSchemaFixtureTest)
 * and parsed by the Dart client's tests. Enum values are the Kotlin enum names (e.g. "COMPLETED", "APP"); link state,
 * transport, profile and sources are lower case. Times are monotonic milliseconds; "age" is milliseconds since.
 *
 * ## Snapshot (EventChannel `aeronex/session/state`, and `connect` / `getSnapshot`)
 * - `schemaVersion` (int): [VERSION]; a client must refuse a version it does not know.
 * - `seq` (int): increases with every snapshot built; a client drops one older than the last it showed.
 * - `atMs` (int): when it was built (monotonic, the service's clock; only differences mean anything).
 * - `link`: the connection.
 *   - `state` (string): `down` | `connecting` | `up` ([LinkState]).
 *   - `transport` (string?): `udp` | `tcp`; null while down.
 *   - `description` (string?): e.g. "UDP 192.168.4.1:14550 local *:14551"; null while down.
 *   - `profile` (string?): telemetry profile, `wifi` | `radio`; null while down.
 *   - `unsigned` (bool): MAVLink signing is off: show the UNSIGNED banner while connected (always true in this
 *     build; no key provisioning yet).
 *   - `heartbeatAgeMs` (int?): since the last vehicle heartbeat on this link; null before the first.
 *   - `txErrors` (int): frames the transport did not take (never retried).
 *   - `droppedDatagrams` (int?): UDP datagrams from another sender, dropped; null for TCP or while down.
 *   - `foregroundService` (bool): the foreground service runs, so the link, heartbeat and STOP survive the app
 *     going to the background (S7). False means a backgrounded app may be frozen and seen as link loss.
 * - `execution`: `enabled` (bool): live execution for this connection; false is dry run (S6), steps report NOT_SENT.
 * - `vehicle`: the last received state of sysid 1/1. Values are the last received ones: check `connected` and `ages`
 *   before showing one as current.
 *   - `connected` (bool): a vehicle heartbeat within the last 3 s.
 *   - `mode` (int?): HEARTBEAT custom_mode (ArduCopter mode number). `modeName` (string?): [CopterMode.name], e.g.
 *     "GUIDED", "MODE 13" for an unnamed one.
 *   - `armed` (bool?).
 *   - `landedState` (int?): MAV_LANDED_STATE. `landedStateName` (string?): UNDEFINED | ON_GROUND | IN_AIR |
 *     TAKEOFF | LANDING.
 *   - `modeOwner` (string?): `APP` | `EXTERNAL` (spec 4.12); null while not connected or no mode is known.
 *   - `modeOwnerLabel` (string?): while EXTERNAL after a mode change: "Autopilot failsafe" or "Pilot mode"; after a
 *     heartbeat gap with the vehicle still in the app's mode, "Link gap" (STOP works without the hold).
 *   - `altitudeRelM` (double?): metres above home (GLOBAL_POSITION_INT).
 *   - `speed` (double?): m/s, 3D, rounded to 0.01.
 *   - `batteryPercent` (int?): null when the autopilot reports it unknown.
 *   - `healthy` (bool?): every enabled onboard sensor is healthy (SYS_STATUS).
 *   - `positionReady` (bool): fresh EKF solution with absolute position (the takeoff/move precondition).
 *   - `homeKnown` (bool): HOME_POSITION received. `homeDistanceM` (double?): horizontal metres from home, rounded to
 *     0.01; null without home or position (the plan limit is 30 m, S13).
 *   - `lastStatusText` (map?): `{severity (int, MAV_SEVERITY, 0 = emergency), text (string), ageMs (int)}`.
 *   - `ages` (map): per message, ms since it was last received, null if never: `heartbeat`, `sysStatus`,
 *     `attitude`, `localPosition`, `globalPosition`, `extendedSysState`, `ekfStatus`, `home`, `positionTarget`,
 *     `statusText`.
 * - `pending` (map?): the plan waiting for CONFIRM; null when none.
 *   - `token` (string): pass it to `confirm`; works once.
 *   - `summary` (string): e.g. "1. Take off to 3 m; 2. Land".
 *   - `steps` (list): `{name (string), arguments (map, numbers as given), source (`rules` | `fixture` | `ai`),
 *     description (string, e.g. "Move forward 2 m")}`.
 *   - `source` (string): the source chip, `phone` | `typed` | `tap` | `ai` (0.4.0: a plan of the AI path).
 *   - `expiresInMs` (int): until the token expires (15 s after submit), at `atMs`; 0 when due.
 * - `run` (map?): the plan running or last run (including an immediate HOLD or status); null when none or while
 *   one only waits for confirmation.
 *   - `phase` (string): EXECUTING | FINISHED | FAILED.
 *   - `source` (string?), `summary` (string): as in `pending`.
 *   - `stepIndex` (int): 0-based index of the step in progress (the first RUNNING or PENDING); `stepCount` when none.
 *   - `stepCount` (int).
 *   - `outcomes` (list): per step `{step (string, its description), outcome (string: PENDING | RUNNING | COMPLETED |
 *     NOT_SENT | REJECTED | UNKNOWN | SKIPPED), message (string?, the v0.2.0 text, e.g. "COMPLETED — …")}`.
 *   - `message` (string?): the v0.2.0 result summary once the plan ended; null while it runs.
 * - `stop` (map?): the last STOP decision; null before the first.
 *   - `id` (int): new for every STOP, so a repeated identical STOP still shows as new.
 *   - `phase` (string): NOTHING_SENT | SENDING | CONFIRMED | NOT_CONFIRMED.
 *   - `trigger` (string): BUTTON | VOLUME_DOWN | NOTIFICATION | VOICE | PARTIAL_TOKEN | CANCEL | HOLD_COMMAND |
 *     EXECUTION_OFF.
 *   - `action` (string?): DISARM | BRAKE | LAND, the frame last sent; null when nothing was sent.
 *   - `message` (string): the banner, e.g. "STOP confirmed: BRAKE", "Pilot mode LOITER, use RC",
 *     "STOP NOT CONFIRMED, take RC control".
 *   - `alarm` (bool): sound the alarm until `acknowledgeAlarm`; it sounds again only if the STOP escalates (a new
 *     phase or action).
 *   - `override` (bool): sent over an EXTERNAL owner by the 1 s on-screen hold (logged).
 *   - `needsHoldForExternal` (bool): now an armed vehicle's mode is EXTERNAL: a STOP press sends nothing unless the
 *     on-screen STOP is held 1 s (spec 4.12). False after a heartbeat gap that left the app's own mode in place.
 *   - `modeOwnerLabel` (string?): as in `vehicle`, for the "use RC" banner.
 * - `notice` (string?): why the last pending plan went away (expired, link lost, app in background …).
 * - `latency` (map): per stage, `{p50Ms (double), p95Ms (double), maxMs (double), n (int)}` over the newest spans, or
 *   null before the first: `pttUpToDrained`, `drainedToFinal`, `pttUpToFinal`, `finalToPlan`, `pttUpToPlan`,
 *   `confirmToFrame`, `confirmToTx`, `confirmToAck`, `confirmToMoveTx`, `moveTxToEcho`, `confirmToInAir`, `stopToTx`,
 *   `stopToAck`, and (0.4.0) `aiGloss`, `aiPlan`, `aiTools`, `aiTotal` ([LatencyBook.Stage]).
 * - `speech` (map, T12; added to version 1, older clients ignore it): voice in the service ([VoiceStatus]).
 *   - `language` (string): the selected language code, `en` | `hi` | `te` | `ta` | `kn` | `ml`.
 *   - `state` (string): OFF | LOADING | READY | LISTENING | DECODING ([VoiceState]).
 *   - `level` (string): OK | DEGRADED | NO_VOICE (spec 4.11). `reason` (string?): [VoiceReason] name when not OK;
 *     `message` (string?): its operator text, e.g. "Voice for this language needs a newer phone — use the tap pad".
 *   - `engine` (string?): VOSK_GRAMMAR | SHERPA_CTC. `spotter` (string?): VOSK_STOP_GRAMMAR | KWS.
 *   - `nativeVoiceStop` (bool): voice STOP in this language; false means English "stop" only (KWS) or none.
 *   - `partialStop` (bool): a stop word said while PTT is held stops at once (streaming engines).
 *   - `spotterArmed` (bool): the voice stop spotter listens now (armed or airborne, the app owns the mode).
 *   - `pttHeld` (bool). `utterance` (int): PTT presses so far; a new one means new `heard` / `error`.
 *   - `partial` (string?): the live partial transcript while PTT is held. `heard` (string?): the last utterance's
 *     transcript. `error` (string?): why it did not become a plan (shown as is); null while the AI thinks about it.
 * - `ai` (map, 0.4.0; added to version 1, older clients ignore it): the AI path ([AiStatus], spec A1-A6).
 *   - `available` (bool); `reason` (string?): why not, in plain words, e.g. "Needs a 64-bit ARM phone",
 *     "Download the AI models in Models", "Checking the AI models".
 *   - `state` (string): IDLE | THINKING. `request` (int): AI requests so far; a new one means new fields below.
 *   - `heard` (string?): what the AI was asked. `gloss` (string?): its English gloss when that differs.
 *   - `understoodAs` (string?): the planner's steps ("take off to 3 meters, then move forward 2 meters").
 *   - `error` (string?): why the AI gave no plan or its plan was refused (shown as is).
 * - `memory` (map, 2026-10-28; added to version 1, older clients ignore it): the flight memory ([MemoryState]).
 *   Places are `{northM, eastM, upM}` (doubles, rounded to 0.01): metres north, east and up from home, or from the
 *   autopilot's local origin while `homeKnown` is false.
 *   - `homeKnown` (bool): the memory has the home its places belong to.
 *   - `vehicle` (place?): where the vehicle is now; null without a fresh position.
 *   - `trail` (list): the path flown in this flight, oldest first (the first is where the takeoff ended): places with
 *     `label` (string), e.g. "Takeoff", "Move forward 4 m", "Home", "Point 2", "Start".
 *   - `points` (list): the saved points by number: places with `point` (int, 1–9), `label` and `atWallMs` (int, epoch
 *     ms, when it was saved).
 *   - `goBackTo` (string?): the trail label "go back" would fly to now; null when there is nothing to go back to.
 *   - `canRetrace` (bool): "retrace" has a flown path to fly back along now.
 *   - `plans` (list): the newest finished plans (at most [PLANS]), newest first: `{planId (int), atWallMs (int),
 *     source (string), steps: [{name, arguments, description, outcome (StepStatus name)}]}`; `runAgain(planId)`
 *     shows one again.
 *   - `notice` (string?): why the trail and points were last forgotten (home moved).
 *
 * ## Requests and answers (MethodChannel `aeronex/session`)
 * - `connect({transport, host, remotePort, localPort, radioLink})` → snapshot; errors as PlatformException
 *   ([SessionException] codes). See [SessionConfig].
 * - `submitPlan({steps: [{name, arguments, source, clause?}], source, utterance?})` → `{token, expiresInMs,
 *   summary}`, or `{immediate: true, outcome, message}` for HOLD and status (no confirmation), or `{error}` with the
 *   operator message (v0.2.0 FlightCommand / FlightPlan / PlanValidator text, verbatim). A malformed request is a
 *   PlatformException BAD_ARGS. Steps with source `ai` are refused ([aiStep]) and the source `ai` is BAD_ARGS: AI
 *   plans come only from the service's own AI path (spec A3).
 * - `submitText({text, language, source?})` → as `submitPlan`, or `{thinking: true, request}` when the AI took it.
 * - `confirm(token)` → `{accepted: true}` or `{accepted: false, error}`.
 * - `runAgain({planId} | planId)` → as `submitPlan`: a plan from `memory.plans`, validated again (Tap source).
 * - `clearMemory` → null: forgets the trail, the points and the plans (home stays known).
 */
object SessionSchema {
    const val VERSION = 1
    val TOP_LEVEL_KEYS = listOf("schemaVersion", "seq", "atMs", "link", "execution", "vehicle", "pending", "run", "stop",
        "notice", "latency", "speech", "ai", "memory")

    /** At most this many finished plans in `memory.plans`. */
    const val PLANS = 20

    /** The refusal of a step the Dart rules did not match (its AI fallback proposed it). */
    fun aiStep(number: Int) = "Step $number was not matched by the command rules. Say it again with the supported words."

    private val AGES = linkedMapOf(
        "heartbeat" to Heartbeat.messageId, "sysStatus" to SysStatus.messageId, "attitude" to Attitude.messageId,
        "localPosition" to LocalPositionNed.messageId, "globalPosition" to GlobalPositionInt.messageId,
        "extendedSysState" to ExtendedSysState.messageId, "ekfStatus" to EkfStatusReport.messageId,
        "home" to HomePosition.messageId, "positionTarget" to PositionTargetLocalNed.messageId,
        "statusText" to StatusText.messageId,
    )
    private val LANDED = mapOf(
        VehicleState.LANDED_UNDEFINED to "UNDEFINED", VehicleState.LANDED_ON_GROUND to "ON_GROUND",
        VehicleState.LANDED_IN_AIR to "IN_AIR", VehicleState.LANDED_TAKEOFF to "TAKEOFF",
        VehicleState.LANDED_LANDING to "LANDING",
    )

    fun snapshot(
        seq: Long,
        atMs: Long,
        link: LinkInfo,
        supervisor: SupervisorState,
        vehicle: VehicleState,
        latency: Map<String, LatencySummary?>,
        speech: VoiceStatus,
        ai: AiStatus = AiStatus.OFF,
        memory: MemoryState = MemoryState(),
    ): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to VERSION,
        "seq" to seq,
        "atMs" to atMs,
        "link" to link(link),
        "execution" to linkedMapOf("enabled" to supervisor.execution),
        "vehicle" to vehicle(vehicle),
        "pending" to supervisor.pending?.let { pending(it, atMs) },
        "run" to run(supervisor),
        "stop" to supervisor.stop?.let { stop(it, vehicle) },
        "notice" to supervisor.notice,
        "latency" to latency.entries.associateTo(LinkedHashMap()) { (stage, summary) ->
            stage to summary?.let { linkedMapOf("p50Ms" to it.p50Ms, "p95Ms" to it.p95Ms, "maxMs" to it.maxMs, "n" to it.count) }
        },
        "speech" to speech(speech),
        "ai" to ai(ai),
        "memory" to memory(memory, vehicle),
    )

    /**
     * A `submitPlan` request. Throws [CommandFormatException] with the operator message for plan content (shown as
     * `{error}`), [SessionException] ([SessionException.BAD_ARGS]) for a malformed request.
     */
    fun parsePlan(arguments: Any?): PlanRequest {
        val map = arguments as? Map<*, *> ?: throw bad("A plan needs steps and a source.")
        val steps = map["steps"] as? List<*> ?: throw bad("A plan needs a list of steps.")
        val source = PlanSource.fromChannel.firstOrNull { it.wire == map["source"] }
            ?: throw bad("Unknown plan source: ${map["source"]}")
        val parsed = steps.mapIndexed { i, raw ->
            val number = i + 1
            val step = raw as? Map<*, *> ?: throw bad("Step $number is not a map.")
            if ("name" !in step || "arguments" !in step) throw bad("Step $number needs a name and arguments.")
            val stepSource = when (step["source"]) {
                StepSource.RULES.wireName -> StepSource.RULES
                StepSource.FIXTURE.wireName -> StepSource.FIXTURE
                AI_SOURCE -> throw CommandFormatException(aiStep(number))
                else -> throw bad("Step $number has an unknown source: ${step["source"]}")
            }
            val command = FlightCommand.fromToolCalls(listOf(mapOf("name" to step["name"], "arguments" to step["arguments"])))
            PlanStep(command, stepSource, step["clause"] as? String ?: "")
        }
        return PlanRequest(FlightPlan(parsed), source, map["utterance"] as? String)
    }

    fun submitted(result: SubmitResult, nowMs: Long): Map<String, Any?> = when (result) {
        is SubmitResult.AwaitingConfirmation -> linkedMapOf(
            "token" to result.pending.token,
            "expiresInMs" to maxOf(0L, result.pending.expiresAtMs - nowMs),
            "summary" to result.pending.plan.toString(),
        )
        is SubmitResult.Immediate -> linkedMapOf("immediate" to true, "outcome" to result.outcome.status.name,
            "message" to result.outcome.message)
        is SubmitResult.Rejected -> linkedMapOf("error" to result.message)
    }

    fun confirmed(result: ConfirmResult): Map<String, Any?> = when (result) {
        ConfirmResult.Started -> linkedMapOf("accepted" to true)
        is ConfirmResult.Rejected -> linkedMapOf("accepted" to false, "error" to result.message)
    }

    /** The plan source chip on the wire. */
    val PlanSource.wire: String get() = name.lowercase()

    private const val AI_SOURCE = "ai"

    private fun link(link: LinkInfo) = linkedMapOf(
        "state" to link.state.wireName,
        "transport" to link.transport,
        "description" to link.description,
        "profile" to link.profile,
        "unsigned" to link.unsigned,
        "heartbeatAgeMs" to link.heartbeatAgeMs,
        "txErrors" to link.txErrors,
        "droppedDatagrams" to link.droppedDatagrams,
        "foregroundService" to link.foregroundService,
    )

    private fun owner(vehicle: VehicleState): ModeOwner? = vehicle.modeOwner.takeIf { vehicle.connected && vehicle.mode != null }

    private fun vehicle(vehicle: VehicleState): Map<String, Any?> {
        val owner = owner(vehicle)
        val home = vehicle.home
        val position = vehicle.position
        return linkedMapOf(
            "connected" to vehicle.connected,
            "mode" to vehicle.mode,
            "modeName" to vehicle.mode?.let(CopterMode::name),
            "armed" to vehicle.armed,
            "landedState" to vehicle.landedState,
            "landedStateName" to vehicle.landedState?.let { LANDED[it] ?: "STATE_$it" },
            "modeOwner" to owner?.name,
            "modeOwnerLabel" to vehicle.modeOwnerLabel.takeIf { owner != null },
            "altitudeRelM" to vehicle.altitudeRelM,
            "speed" to vehicle.speed?.let(::centimetres),
            "batteryPercent" to vehicle.batteryPercent,
            "healthy" to vehicle.healthy,
            "positionReady" to vehicle.positionReady,
            "homeKnown" to (home != null),
            "homeDistanceM" to if (home != null && position != null) centimetres(position.horizontalDistanceTo(home)) else null,
            "lastStatusText" to vehicle.lastStatusText?.let {
                linkedMapOf("severity" to it.message.severity, "text" to it.message.text, "ageMs" to vehicle.atMs - it.atMs)
            },
            "ages" to AGES.entries.associateTo(LinkedHashMap()) { (name, id) -> name to vehicle.ageMs[id] },
        )
    }

    private fun pending(pending: PendingPlan, atMs: Long) = linkedMapOf(
        "token" to pending.token,
        "summary" to pending.plan.toString(),
        "steps" to pending.plan.steps.map { step ->
            linkedMapOf("name" to step.command.name, "arguments" to LinkedHashMap(step.command.arguments),
                "source" to step.source.wireName, "description" to step.command.description)
        },
        "source" to pending.source.wire,
        "expiresInMs" to maxOf(0L, pending.expiresAtMs - atMs),
    )

    private fun run(state: SupervisorState): Map<String, Any?>? {
        if (state.stage == PlanStage.IDLE || state.stage == PlanStage.AWAITING_CONFIRMATION) return null
        val plan = state.plan ?: return null
        val inProgress = state.outcomes.indexOfFirst { it.status == StepStatus.RUNNING || it.status == StepStatus.PENDING }
        return linkedMapOf(
            "phase" to state.stage.name,
            "source" to state.source?.wire,
            "summary" to plan.toString(),
            "stepIndex" to if (inProgress < 0) plan.steps.size else inProgress,
            "stepCount" to plan.steps.size,
            "outcomes" to state.outcomes.map {
                linkedMapOf("step" to it.step.command.description, "outcome" to it.status.name, "message" to it.message)
            },
            "message" to state.result,
        )
    }

    private fun stop(stop: StopStatus, vehicle: VehicleState): Map<String, Any?> {
        val owner = owner(vehicle)
        return linkedMapOf(
            "id" to stop.id,
            "phase" to stop.phase.name,
            "trigger" to stop.trigger.name,
            "action" to stop.action?.name,
            "message" to stop.message,
            "alarm" to stop.alarm,
            "override" to stop.override,
            "needsHoldForExternal" to (owner == ModeOwner.EXTERNAL && vehicle.armed == true && !vehicle.appModeAfterGap),
            "modeOwnerLabel" to vehicle.modeOwnerLabel.takeIf { owner != null },
        )
    }

    private fun speech(speech: VoiceStatus) = linkedMapOf(
        "language" to speech.language,
        "state" to speech.state.name,
        "level" to speech.level.name,
        "reason" to speech.reason?.name,
        "message" to speech.reason?.message,
        "engine" to speech.engine?.name,
        "spotter" to speech.spotter?.name,
        "nativeVoiceStop" to speech.nativeVoiceStop,
        "partialStop" to speech.partialStop,
        "spotterArmed" to speech.spotterArmed,
        "pttHeld" to speech.pttHeld,
        "utterance" to speech.utterance,
        "partial" to speech.partial,
        "heard" to speech.heard,
        "error" to speech.error,
    )

    private fun ai(ai: AiStatus) = linkedMapOf(
        "available" to ai.available,
        "reason" to ai.reason,
        "state" to ai.state.name,
        "request" to ai.request,
        "heard" to ai.heard,
        "gloss" to ai.gloss,
        "understoodAs" to ai.understoodAs,
        "error" to ai.error,
    )

    private fun memory(memory: MemoryState, vehicle: VehicleState): Map<String, Any?> {
        val home = memory.home
        fun place(p: Ned) = linkedMapOf<String, Any?>(
            "northM" to centimetres(p.x - (home?.x ?: 0.0)),
            "eastM" to centimetres(p.y - (home?.y ?: 0.0)),
            "upM" to centimetres((home?.z ?: 0.0) - p.z),
        )
        val position = vehicle.position?.takeIf { vehicle.connected && vehicle.fresh(LocalPositionNed.messageId) }
        // As the supervisor would resolve them now ([com.aeronex.app.safety.SafetySupervisor]).
        fun route(name: String) = position?.let {
            try {
                MemoryRoutes.resolve(FlightCommand.of(name), MemoryRoutes.departing(memory, it), it)
            } catch (_: CommandFormatException) {
                null
            }
        }
        return linkedMapOf(
            "homeKnown" to (home != null),
            "vehicle" to position?.let(::place),
            "trail" to memory.trail.map { waypoint ->
                linkedMapOf<String, Any?>("label" to waypoint.label).apply { putAll(place(waypoint.position)) }
            },
            "points" to memory.points.toSortedMap().map { (number, saved) ->
                linkedMapOf<String, Any?>("point" to number, "label" to saved.label).apply {
                    putAll(place(saved.position))
                    put("atWallMs", saved.atWallMs)
                }
            },
            "goBackTo" to route(FlightCommand.GO_BACK)?.label,
            "canRetrace" to (route(FlightCommand.RETRACE) != null),
            "plans" to memory.history.groupBy { it.planId }.values.reversed().take(PLANS).map { records ->
                val first = records.first()
                linkedMapOf(
                    "planId" to first.planId,
                    "atWallMs" to first.atWallMs,
                    "source" to first.source,
                    "steps" to records.map {
                        linkedMapOf("name" to it.name, "arguments" to LinkedHashMap(it.arguments),
                            "description" to it.description, "outcome" to it.outcome)
                    },
                )
            },
            "notice" to memory.notice,
        )
    }

    private fun centimetres(value: Double): Double? = if (value.isFinite()) (value * 100).roundToLong() / 100.0 else null

    private fun bad(message: String) = SessionException(SessionException.BAD_ARGS, message)
}
