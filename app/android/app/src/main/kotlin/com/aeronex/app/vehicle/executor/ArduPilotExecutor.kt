package com.aeronex.app.vehicle.executor

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.link.mavlink.SysStatus
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a step stands after [ArduPilotExecutor.start] or [ArduPilotExecutor.update]. */
sealed interface StepUpdate {
    data object Running : StepUpdate

    /** Confirmed by telemetry; [detail] is the get_status line ([ArduPilotExecutor.statusReport]). */
    data class Completed(val detail: String? = null) : StepUpdate

    /** Nothing of this step was sent. */
    data class Rejected(val reason: String) : StepUpdate

    /** Something was sent and the outcome is not confirmed: never retry blindly (S3). */
    data class Unknown(val reason: String) : StepUpdate
}

/**
 * ArduCopter adapter for plan steps (spec 4.14), one plan per instance, driven by the supervisor thread: [start] a
 * step with a fresh snapshot, then [update] it with every new snapshot and the COMMAND_ACKs since the last call.
 *
 * - Takeoff(h): DO_SET_MODE GUIDED → ARM → NAV_TAKEOFF(h), each confirmed by its ACK, then altitude ≥ h − 0.4 m and
 *   landed state IN_AIR.
 * - Move: one SET_POSITION_TARGET_LOCAL_NED in BODY_OFFSET_NED (9), type_mask 3576, **sent exactly once**; GUIDED is
 *   set first only if the app does not already own GUIDED. POSITION_TARGET_LOCAL_NED streams while the move is
 *   pending (for the whole plan when the supervisor streams it, see [planStreamsPositionTarget]). Confirmed by an
 *   echo of the target within [ExecutorTiming.echoTimeoutMs], then arrival within
 *   min(0.5 m, d/2) of the target computed from the position at send time, at < 0.3 m/s, on position samples newer
 *   than the echo.
 * - Flight memory (return home, go back, retrace, go to point; spec 2026-10-28 M2): the supervisor resolves the step
 *   to absolute legs; each is one SET_POSITION_TARGET_LOCAL_NED in LOCAL_NED (1), **sent exactly once**, with the
 *   heading of the step's start held, and confirmed like a move (echo, then arrival) before the next leg is sent.
 * - Land: DO_SET_MODE LAND by ACK, then ON_GROUND. Hold (a plan step): DO_SET_MODE BRAKE by ACK, then < 0.3 m/s.
 * - get_status: the v0.2.0 vehicle status line; nothing is sent.
 *
 * COMMAND_LONGs are re-sent with `confirmation` + 1 per [ExecutorTiming.commandRetryMs], [ExecutorTiming.commandTries]
 * sends at most. Any refusal or missing confirmation after a send is UNKNOWN. Preconditions keep the v0.2.0 rules
 * and messages. The plan-level rules (deadline, external mode, failsafe text, link loss, STOP) are the
 * supervisor's; [externalModeChange] and [ownsMode] answer the mode-ownership part. An ARM is reported to the
 * [ModeWatch] from its first send until it is refused, or, unanswered or accepted, until a later heartbeat shows the
 * vehicle's state, so a STOP in the ARM round trip still disarms.
 */
class ArduPilotExecutor(
    private val link: LinkSession,
    private val store: VehicleStateStore,
    private val timing: ExecutorTiming,
    private val trace: Trace = Trace(),
    private val traceId: Long = 0,
    /** Shared with the STOP lane of the same connection. */
    private val watch: ModeWatch = ModeWatch(store),
    /**
     * True while the supervisor streams POSITION_TARGET_LOCAL_NED for the whole plan: a move then neither requests
     * nor stops the stream. Otherwise each move streams it itself from its start to its end (the fallback).
     */
    private val planStreamsPositionTarget: () -> Boolean = { false },
    /** The vehicle heartbeat count when an ACK arrived ([VehicleEvents.heartbeatsAtArrival]); null if unknown. */
    private val heartbeatsAtArrival: (CommandAck) -> Long? = { null },
) {
    private var step: Step? = null
    /** The last ACCEPTED COMMAND_ACK [outcome] saw (its arrival orders a mode change against heartbeats). */
    private var lastAccepted: CommandAck? = null
    /** The plan's step number (1-based), for the trace. */
    private var stepNumber = 1

    /** Something of the current (or last) step was queued: a failure is then UNKNOWN, not REJECTED. */
    var sentInStep = false
        private set

    /** The app owned the flight mode at some point of this plan. */
    private var owned: Boolean? = null
    private var unobservedMode: Long? = null
    private var heartbeatsAtModeAck = 0L
    /** This executor's DO_SET_MODE is queued without an ACK. */
    private var modeInFlight = false

    /**
     * When the current step's second or later flight-memory leg was sent, else 0: the supervisor's step deadline
     * then counts from it, so a retrace of many legs is not cut off while each leg still arrives in time.
     */
    var legStartedAtMs = 0L
        private set

    /** [legs]: a flight-memory step's absolute targets ([com.aeronex.app.memory.Route.legs]); unused by other steps. */
    fun start(command: FlightCommand, state: VehicleState, nowMs: Long, stepNumber: Int = 1, legs: List<Ned>? = null): StepUpdate {
        cancel()
        if (owned == null) owned = watch.ownsMode(state)
        sentInStep = false
        legStartedAtMs = 0L
        this.stepNumber = stepNumber
        val next = when (command.name) {
            FlightCommand.TAKEOFF -> Takeoff(command.altitudeM!!)
            FlightCommand.MOVE -> Move(command.direction!!, command.distanceM!!)
            in FlightCommand.MEMORY_MOTION -> Goto(legs ?: return StepUpdate.Rejected(NO_ROUTE))
            FlightCommand.LAND -> ModeStep(CopterMode.LAND)
            FlightCommand.HOLD -> ModeStep(CopterMode.BRAKE)
            FlightCommand.STATUS -> return common(state, needsPosition = false)?.let { StepUpdate.Rejected(it) }
                ?: StepUpdate.Completed(statusReport(state))
            else -> return StepUpdate.Rejected("Tool is not allowed.")
        }
        step = next
        return settle(next.start(state, nowMs))
    }

    fun update(state: VehicleState, acks: List<CommandAck>, nowMs: Long): StepUpdate {
        val current = step ?: return StepUpdate.Unknown(INTERRUPTED)
        observeMode(state)
        if (sentInStep && !state.connected) return settle(StepUpdate.Unknown(LOST))
        return settle(current.update(state, acks, nowMs))
    }

    /** Ends the current step: nothing more is sent and POSITION_TARGET streaming is switched off. */
    fun cancel() {
        step?.end()
        step = null
        if (modeInFlight) watch.withdraw()
        modeInFlight = false
    }

    /** The app owns the flight mode, counting its own mode change in flight or just accepted ([ModeWatch]). */
    fun ownsMode(state: VehicleState) = watch.ownsMode(state)

    /** The app owned the mode in this plan and a later heartbeat shows a mode it did not command (spec 4.14). */
    fun externalModeChange(state: VehicleState) = owned == true && state.connected && !watch.ownsMode(state)

    private fun settle(update: StepUpdate): StepUpdate {
        if (update != StepUpdate.Running) cancel()
        return update
    }

    private fun modeAccepted(mode: Long, state: VehicleState) {
        watch.accepted(mode, lastAccepted?.let(heartbeatsAtArrival))
        modeInFlight = false
        owned = true
        unobservedMode = mode
        heartbeatsAtModeAck = ModeWatch.heartbeats(state)
    }

    /**
     * After a mode ACK, before the step sends what depends on that mode (ARM and NAV_TAKEOFF, a move frame): a
     * heartbeat that arrived after the ACK and shows another mode means a pilot or failsafe chose it, so nothing more
     * is sent (audit L7: ArduCopter would otherwise arm and take off in the pilot's LOITER). Null when the app owns it.
     */
    private fun takenOver(): StepUpdate? =
        if (watch.ownsMode(store.snapshot())) null else StepUpdate.Unknown(MODE_TAKEN_OVER)

    private fun observeMode(state: VehicleState) {
        val mode = unobservedMode ?: return
        if (ModeWatch.heartbeats(state) > heartbeatsAtModeAck && state.mode == mode) {
            unobservedMode = null
            trace.mark(TracePoint.MODE_OBSERVED, traceId, mapOf("mode" to CopterMode.name(mode)))
        }
    }

    /** The v0.2.0 checks before any step. */
    private fun common(state: VehicleState, needsPosition: Boolean): String? = when {
        !state.connected || !state.fresh(SysStatus.messageId) || !state.fresh(ExtendedSysState.messageId) -> STALE
        state.healthy != true -> "Vehicle sensors are not healthy."
        needsPosition && !state.positionReady -> POSITION_NOT_READY
        else -> null
    }

    private fun exchange(command: CommandLong) = CommandExchange(link, command, timing.commandRetryMs, timing.commandTries) {
        sentInStep = true
        if (it.command == MAV_CMD_DO_SET_MODE) {
            watch.requested(it.param2.toLong())
            modeInFlight = true
        }
        if (it.command == MAV_CMD_COMPONENT_ARM_DISARM && it.param1 == 1f) watch.armRequested()
        trace.mark(TracePoint.TX, traceId, mapOf("command" to it.command, "confirmation" to it.confirmation, "step" to stepNumber))
    }

    private fun setMode(mode: Long) = CommandLong(link.targetSystem, link.targetComponent, MAV_CMD_DO_SET_MODE,
        param1 = MAV_MODE_FLAG_CUSTOM_MODE_ENABLED, param2 = mode.toFloat())

    /** A queued first send is Running; an unqueued one fails like any other send. */
    private fun begin(exchange: CommandExchange, nowMs: Long): StepUpdate =
        if (exchange.start(nowMs)) StepUpdate.Running
        else if (sentInStep) StepUpdate.Unknown(NOT_QUEUED_AFTER_SEND) else StepUpdate.Rejected(NOT_QUEUED)

    /**
     * The result of a pending exchange: null when accepted, else the update to return. [failed] hears how it failed:
     * refused (true) or unanswered (false).
     */
    private fun outcome(
        exchange: CommandExchange,
        acks: List<CommandAck>,
        nowMs: Long,
        failed: (refused: Boolean) -> Unit = {},
    ): StepUpdate? = when (val result = exchange.update(acks, nowMs)) {
        CommandExchange.Result.Waiting -> StepUpdate.Running
        is CommandExchange.Result.Accepted -> {
            traceAck(result.ack)
            lastAccepted = result.ack
            null
        }
        is CommandExchange.Result.Refused -> {
            traceAck(result.ack)
            failed(true)
            StepUpdate.Unknown("execution failed: ArduPilot rejected command ${result.ack.command} (result ${result.ack.result}).")
        }
        CommandExchange.Result.TimedOut -> {
            failed(false)
            StepUpdate.Unknown(NO_CONFIRMATION)
        }
    }

    private fun traceAck(ack: CommandAck) =
        trace.mark(TracePoint.ACK, traceId, mapOf("command" to ack.command, "result" to ack.result, "step" to stepNumber))

    private abstract inner class Step {
        abstract fun start(state: VehicleState, nowMs: Long): StepUpdate
        abstract fun update(state: VehicleState, acks: List<CommandAck>, nowMs: Long): StepUpdate
        open fun end() {}
    }

    private inner class Takeoff(private val altitude: Double) : Step() {
        private var phase = Phase.GUIDED
        private var pending: CommandExchange? = null
        private var inAir = false

        override fun start(state: VehicleState, nowMs: Long): StepUpdate {
            common(state, needsPosition = true)?.let { return StepUpdate.Rejected(it) }
            if (!state.fresh(GlobalPositionInt.messageId)) return StepUpdate.Rejected(STALE)
            if (state.armed != false || state.landedState != VehicleState.LANDED_ON_GROUND ||
                (state.batteryPercent ?: -1) < MIN_TAKEOFF_BATTERY
            ) return StepUpdate.Rejected("Takeoff requires disarmed, landed vehicle and known battery >=20%.")
            return send(setMode(CopterMode.GUIDED), nowMs)
        }

        override fun update(state: VehicleState, acks: List<CommandAck>, nowMs: Long): StepUpdate {
            // The vehicle leaving the ground answers NAV_TAKEOFF: no re-send, and a late refusal of a re-sent copy
            // ("already flying") is moot (audit L11).
            if (phase == Phase.TAKEOFF && state.fresh(ExtendedSysState.messageId) &&
                (state.landedState == VehicleState.LANDED_TAKEOFF || state.landedState == VehicleState.LANDED_IN_AIR)
            ) {
                pending = null
                phase = Phase.CLIMB
            }
            pending?.let { exchange ->
                outcome(exchange, acks, nowMs) { refused ->
                    when {
                        phase != Phase.ARM -> {}
                        refused -> watch.armRefused()
                        else -> watch.armUnanswered()
                    }
                    pending = null // answered or given up: end() must not count the ARM again
                }?.let { return it }
                pending = null
                when (phase) {
                    Phase.GUIDED -> {
                        modeAccepted(CopterMode.GUIDED, state)
                        takenOver()?.let { return it }
                        phase = Phase.ARM
                        return send(CommandLong(link.targetSystem, link.targetComponent, MAV_CMD_COMPONENT_ARM_DISARM,
                            param1 = 1f), nowMs)
                    }
                    Phase.ARM -> {
                        watch.armAccepted()
                        phase = Phase.TAKEOFF
                        return send(CommandLong(link.targetSystem, link.targetComponent, MAV_CMD_NAV_TAKEOFF,
                            param7 = altitude.toFloat()), nowMs)
                    }
                    Phase.TAKEOFF -> phase = Phase.CLIMB
                    Phase.CLIMB -> {}
                }
            }
            if (!state.fresh(GlobalPositionInt.messageId) || !state.fresh(ExtendedSysState.messageId)) {
                return StepUpdate.Unknown(LOST)
            }
            if (state.landedState == VehicleState.LANDED_IN_AIR && !inAir) {
                inAir = true
                trace.mark(TracePoint.IN_AIR, traceId)
            }
            val reached = (state.altitudeRelM ?: 0.0) >= altitude - ALTITUDE_TOLERANCE_M
            return if (reached && state.landedState == VehicleState.LANDED_IN_AIR) StepUpdate.Completed() else StepUpdate.Running
        }

        private fun send(command: CommandLong, nowMs: Long) = exchange(command).let {
            pending = it
            begin(it, nowMs)
        }

        /**
         * Cancelled in the ARM round trip (a deadline, an external change, link loss): the ARM may still arm the
         * vehicle, so it counts until a later heartbeat shows the vehicle's state, not forever (audit L12).
         */
        override fun end() {
            if (phase == Phase.ARM && pending != null) watch.armUnanswered()
        }
    }

    /** Land (LAND, then on the ground) and Hold (BRAKE, then slow). */
    private inner class ModeStep(private val mode: Long) : Step() {
        private var pending: CommandExchange? = null

        override fun start(state: VehicleState, nowMs: Long): StepUpdate {
            if (mode == CopterMode.LAND) {
                // Landing is the way out of a degraded state: a sick sensor or a late SYS_STATUS must not block it
                // (audit L15). Its completion is read from EXTENDED_SYS_STATE, so that must be current.
                if (!state.connected || !state.fresh(ExtendedSysState.messageId)) return StepUpdate.Rejected(STALE)
                if (state.armed != true) return StepUpdate.Rejected("Vehicle is not armed.")
            } else {
                common(state, needsPosition = true)?.let { return StepUpdate.Rejected(it) }
                if (state.armed != true || state.landedState != VehicleState.LANDED_IN_AIR) {
                    return StepUpdate.Rejected("Hold requires an airborne vehicle.")
                }
                if (!state.fresh(LocalPositionNed.messageId)) return StepUpdate.Rejected(STALE)
            }
            return exchange(setMode(mode)).let {
                pending = it
                begin(it, nowMs)
            }
        }

        override fun update(state: VehicleState, acks: List<CommandAck>, nowMs: Long): StepUpdate {
            pending?.let { exchange ->
                outcome(exchange, acks, nowMs)?.let { return it }
                pending = null
                modeAccepted(mode, state)
            }
            return if (mode == CopterMode.LAND) {
                if (!state.fresh(ExtendedSysState.messageId)) StepUpdate.Unknown(LOST)
                else if (state.landedState == VehicleState.LANDED_ON_GROUND) StepUpdate.Completed()
                else StepUpdate.Running
            } else {
                if (!state.fresh(LocalPositionNed.messageId)) StepUpdate.Unknown(LOST)
                else if ((state.speed ?: Double.MAX_VALUE) < STILL_SPEED) StepUpdate.Completed()
                else StepUpdate.Running
            }
        }
    }

    private inner class Move(private val direction: String, private val distance: Double) : Step() {
        private var guided: CommandExchange? = null
        private var streaming = false
        private var target: Ned? = null
        private var sentAtMs = 0L
        private var echoesAtSend = 0L
        private var positionsAtEcho: Long? = null

        override fun start(state: VehicleState, nowMs: Long): StepUpdate {
            common(state, needsPosition = true)?.let { return StepUpdate.Rejected(it) }
            if (state.armed != true || state.landedState != VehicleState.LANDED_IN_AIR) {
                return StepUpdate.Rejected("Movement requires an airborne vehicle.")
            }
            if (!fresh(state, Attitude.messageId, LocalPositionNed.messageId, GlobalPositionInt.messageId)) {
                return StepUpdate.Rejected(STALE)
            }
            val projected = target(state)
            val altitude = state.altitudeRelM!! - (projected.z - state.position!!.z)
            if (altitude < 1 || altitude > 10) return StepUpdate.Rejected("Target exceeds simulator altitude limits.")
            streaming = !planStreamsPositionTarget() && link.setPositionTargetStreaming(true)
            if (watch.ownsMode(state) && watch.mode(state) == CopterMode.GUIDED) return sendFrame(state, nowMs)
            return exchange(setMode(CopterMode.GUIDED)).let {
                guided = it
                begin(it, nowMs)
            }
        }

        override fun update(state: VehicleState, acks: List<CommandAck>, nowMs: Long): StepUpdate {
            guided?.let { exchange ->
                outcome(exchange, acks, nowMs)?.let { return it }
                guided = null
                modeAccepted(CopterMode.GUIDED, state)
                takenOver()?.let { return it }
                return sendFrame(state, nowMs)
            }
            if (!fresh(state, Attitude.messageId, LocalPositionNed.messageId)) return StepUpdate.Unknown(LOST)
            val goal = target!!
            val echoedAt = positionsAtEcho
            if (echoedAt == null) {
                val echo = state.lastPositionTarget?.message
                if (state.positionTargetCount > echoesAtSend && echo != null && echo.matches(goal)) {
                    positionsAtEcho = state.samples[LocalPositionNed.messageId] ?: 0
                    trace.mark(TracePoint.ECHO, traceId, mapOf("step" to stepNumber))
                    return StepUpdate.Running
                }
                return if (nowMs - sentAtMs > timing.echoTimeoutMs) StepUpdate.Unknown(NO_ECHO) else StepUpdate.Running
            }
            val newer = (state.samples[LocalPositionNed.messageId] ?: 0) > echoedAt
            val position = state.position!!
            val arrived = distance(position, goal) < arrivalTolerance(distance) &&
                (state.speed ?: Double.MAX_VALUE) < STILL_SPEED
            if (newer && arrived) {
                trace.mark(TracePoint.ARRIVED, traceId)
                return StepUpdate.Completed()
            }
            return StepUpdate.Running
        }

        override fun end() {
            if (streaming) link.setPositionTargetStreaming(false)
            streaming = false
        }

        /**
         * The one and only move frame: never retried or duplicated (S9). The yaw is sent, as 0 relative to the
         * current heading (BODY_OFFSET_NED yaw is relative in ArduCopter): with yaw ignored, ArduCopter turns to face
         * its direction of travel (WP_YAW_BEHAVIOR), so the next body-frame step would fly on a new heading and a
         * confirmed "right 5 m, then forward 5 m" would fly a straight line instead of an L (audit L1).
         */
        private fun sendFrame(state: VehicleState, nowMs: Long): StepUpdate {
            if (!fresh(state, Attitude.messageId, LocalPositionNed.messageId)) {
                return if (sentInStep) StepUpdate.Unknown(LOST) else StepUpdate.Rejected(STALE)
            }
            target = target(state)
            echoesAtSend = state.positionTargetCount
            sentAtMs = nowMs
            val (x, y, z) = bodyOffset()
            val frame = SetPositionTargetLocalNed(0, link.targetSystem, link.targetComponent, MAV_FRAME_BODY_OFFSET_NED,
                TYPE_MASK_POSITION_AND_YAW, x, y, z, yaw = 0f)
            if (!link.send(frame)) return if (sentInStep) StepUpdate.Unknown(NOT_QUEUED_AFTER_SEND) else StepUpdate.Rejected(NOT_QUEUED)
            sentInStep = true
            trace.mark(TracePoint.MOVE_TX, traceId, mapOf("direction" to direction, "distance_m" to distance, "step" to stepNumber))
            return StepUpdate.Running
        }

        private fun bodyOffset(): Triple<Float, Float, Float> {
            val d = distance.toFloat()
            return when (direction) {
                "forward" -> Triple(d, 0f, 0f)
                "backward" -> Triple(-d, 0f, 0f)
                "right" -> Triple(0f, d, 0f)
                "left" -> Triple(0f, -d, 0f)
                "up" -> Triple(0f, 0f, -d)
                else -> Triple(0f, 0f, d) // down; FlightCommand allows nothing else
            }
        }

        /** The v0.2.0 projection: the body offset rotated by the current yaw, from the current position. */
        private fun target(state: VehicleState): Ned {
            val p = state.position!!
            val yaw = state.yaw!!
            val (x, y, z) = bodyOffset()
            return Ned(p.x + cos(yaw) * x - sin(yaw) * y, p.y + sin(yaw) * x + cos(yaw) * y, p.z + z)
        }

        /** The echo is this move's target, not the previous one (which is [distance] away). */
        private fun PositionTargetLocalNed.matches(goal: Ned) =
            distance(Ned(x.toDouble(), y.toDouble(), z.toDouble()), goal) < echoTolerance(distance)
    }

    /**
     * A flight-memory flight: [legs] in order, each like a move with an absolute target. The heading of the step's
     * start is sent with every leg (yaw is absolute in LOCAL_NED), so ArduCopter does not turn to face each leg and a
     * later body-frame move keeps the heading the operator saw. A leg starts where the last one was aimed (the first at
     * the position it was sent from), which sets its echo and arrival tolerances as a move of that length.
     */
    private inner class Goto(private val legs: List<Ned>) : Step() {
        private var guided: CommandExchange? = null
        private var streaming = false
        private var leg = 0
        private var from: Ned? = null
        private var heading = 0f
        private var sentAtMs = 0L
        private var echoesAtSend = 0L
        private var positionsAtEcho: Long? = null

        override fun start(state: VehicleState, nowMs: Long): StepUpdate {
            common(state, needsPosition = true)?.let { return StepUpdate.Rejected(it) }
            if (state.armed != true || state.landedState != VehicleState.LANDED_IN_AIR) {
                return StepUpdate.Rejected("Movement requires an airborne vehicle.")
            }
            if (!fresh(state, Attitude.messageId, LocalPositionNed.messageId, GlobalPositionInt.messageId)) {
                return StepUpdate.Rejected(STALE)
            }
            if (legs.isEmpty()) return StepUpdate.Completed(ALREADY_THERE)
            val position = state.position!!
            for (target in legs) {
                val altitude = state.altitudeRelM!! - (target.z - position.z)
                if (altitude < 1 - HEIGHT_EPSILON_M || altitude > 10 + HEIGHT_EPSILON_M) {
                    return StepUpdate.Rejected("Target exceeds simulator altitude limits.")
                }
            }
            heading = state.yaw!!.toFloat()
            streaming = !planStreamsPositionTarget() && link.setPositionTargetStreaming(true)
            if (watch.ownsMode(state) && watch.mode(state) == CopterMode.GUIDED) return sendLeg(state, nowMs)
            return exchange(setMode(CopterMode.GUIDED)).let {
                guided = it
                begin(it, nowMs)
            }
        }

        override fun update(state: VehicleState, acks: List<CommandAck>, nowMs: Long): StepUpdate {
            guided?.let { exchange ->
                outcome(exchange, acks, nowMs)?.let { return it }
                guided = null
                modeAccepted(CopterMode.GUIDED, state)
                takenOver()?.let { return it }
                return sendLeg(state, nowMs)
            }
            if (!fresh(state, Attitude.messageId, LocalPositionNed.messageId)) return StepUpdate.Unknown(LOST)
            val goal = legs[leg]
            val length = distance(from!!, goal)
            val echoedAt = positionsAtEcho
            if (echoedAt == null) {
                val echo = state.lastPositionTarget?.message
                if (state.positionTargetCount > echoesAtSend && echo != null &&
                    distance(Ned(echo.x.toDouble(), echo.y.toDouble(), echo.z.toDouble()), goal) < echoTolerance(length)
                ) {
                    positionsAtEcho = state.samples[LocalPositionNed.messageId] ?: 0
                    trace.mark(TracePoint.ECHO, traceId, mapOf("step" to stepNumber, "leg" to leg + 1))
                    return StepUpdate.Running
                }
                return if (nowMs - sentAtMs > timing.echoTimeoutMs) StepUpdate.Unknown(NO_ECHO) else StepUpdate.Running
            }
            val newer = (state.samples[LocalPositionNed.messageId] ?: 0) > echoedAt
            val arrived = distance(state.position!!, goal) < arrivalTolerance(length) &&
                (state.speed ?: Double.MAX_VALUE) < STILL_SPEED
            if (!newer || !arrived) return StepUpdate.Running
            trace.mark(TracePoint.ARRIVED, traceId, mapOf("step" to stepNumber, "leg" to leg + 1))
            if (leg == legs.size - 1) return StepUpdate.Completed()
            from = goal
            leg++
            legStartedAtMs = nowMs
            return sendLeg(state, nowMs)
        }

        override fun end() {
            if (streaming) link.setPositionTargetStreaming(false)
            streaming = false
        }

        /** The leg's one and only frame (S9), as a move's. */
        private fun sendLeg(state: VehicleState, nowMs: Long): StepUpdate {
            if (!fresh(state, Attitude.messageId, LocalPositionNed.messageId)) {
                return if (sentInStep) StepUpdate.Unknown(LOST) else StepUpdate.Rejected(STALE)
            }
            if (from == null) from = state.position!!
            val target = legs[leg]
            echoesAtSend = state.positionTargetCount
            positionsAtEcho = null
            sentAtMs = nowMs
            val frame = SetPositionTargetLocalNed(0, link.targetSystem, link.targetComponent, MAV_FRAME_LOCAL_NED,
                TYPE_MASK_POSITION_AND_YAW, target.x.toFloat(), target.y.toFloat(), target.z.toFloat(), yaw = heading)
            if (!link.send(frame)) return if (sentInStep) StepUpdate.Unknown(NOT_QUEUED_AFTER_SEND) else StepUpdate.Rejected(NOT_QUEUED)
            sentInStep = true
            trace.mark(TracePoint.MOVE_TX, traceId, mapOf("leg" to leg + 1, "legs" to legs.size, "step" to stepNumber))
            return StepUpdate.Running
        }
    }

    companion object {
        const val STALE = "Fresh Copter telemetry is required. Nothing sent."
        const val POSITION_NOT_READY = "Position estimator is not ready. Wait for a fresh EKF solution. Nothing sent."
        const val LOST = "Telemetry lost after commands were sent; vehicle outcome unknown."
        const val NO_CONFIRMATION = "no confirmation in time. Check the simulator before sending another command."
        const val NO_ECHO = "the vehicle did not echo the move target; vehicle outcome unknown. " +
            "Check the simulator before sending another command."
        const val INTERRUPTED = "interrupted; check the simulator before sending another command."
        const val NOT_QUEUED = "The link did not accept the command. Nothing sent."
        const val NOT_QUEUED_AFTER_SEND = "The link did not accept a command after others were sent; vehicle outcome unknown."
        const val NO_ROUTE = "The flight memory gave no route for this step. Nothing sent."
        /** A flight-memory step whose target is where the vehicle already is: confirmed with nothing sent. */
        const val ALREADY_THERE = "already there; nothing sent"

        const val MAV_CMD_DO_SET_MODE = 176
        const val MAV_CMD_COMPONENT_ARM_DISARM = 400
        const val MAV_CMD_NAV_TAKEOFF = 22
        const val MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1f
        const val MAV_FRAME_LOCAL_NED = 1
        const val MAV_FRAME_BODY_OFFSET_NED = 9
        /** Position and yaw: velocity, acceleration and yaw rate ignored (audit L1: the heading is held). */
        const val TYPE_MASK_POSITION_AND_YAW = 2552
        const val MODE_TAKEN_OVER = "the flight mode changed outside this app right after the app set it; " +
            "nothing more was sent. Check the simulator before sending another command."

        private const val MIN_TAKEOFF_BATTERY = 20
        private const val ALTITUDE_TOLERANCE_M = 0.4
        private const val ARRIVAL_TOLERANCE_M = 0.5
        private const val ECHO_TOLERANCE_M = 1.0
        private const val STILL_SPEED = 0.3
        /** A remembered leg is clamped to the 1–10 m range in floating point ([com.aeronex.app.memory.MemoryRoutes]). */
        private const val HEIGHT_EPSILON_M = 1e-6

        /** Short moves keep a usable tolerance (audit L14), always below [d] so the previous target never matches. */
        private const val SHORT_MOVE_FLOOR_M = 0.15

        internal fun echoTolerance(d: Double) = min(ECHO_TOLERANCE_M, max(d / 2, min(SHORT_MOVE_FLOOR_M, d * 0.75)))

        internal fun arrivalTolerance(d: Double) = min(ARRIVAL_TOLERANCE_M, max(d / 2, min(SHORT_MOVE_FLOOR_M, d * 0.75)))

        private fun fresh(state: VehicleState, vararg ids: Int) = ids.all(state::fresh)

        private fun distance(a: Ned, b: Ned): Double {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** v0.2.0 `vehicle_status.dart` mode names; others read "Mode n". */
        private val STATUS_MODES = mapOf(
            CopterMode.STABILIZE to "Stabilize", CopterMode.ALT_HOLD to "AltHold", CopterMode.AUTO to "Auto",
            CopterMode.GUIDED to "Guided", CopterMode.LOITER to "Loiter", CopterMode.RTL to "RTL",
            CopterMode.LAND to "Land", CopterMode.POSHOLD to "PosHold", CopterMode.BRAKE to "Brake",
        )
        private val STATUS_LANDED = mapOf(VehicleState.LANDED_ON_GROUND to "On ground",
            VehicleState.LANDED_IN_AIR to "In air", VehicleState.LANDED_TAKEOFF to "Taking off",
            VehicleState.LANDED_LANDING to "Landing")

        /**
         * The get_status line, as v0.2.0's `vehicleStatus`: for example `Connected · Disarmed · On ground ·
         * Stabilize · Alt 0.0 m · Battery 100% · Position ready`. A value whose stream went stale is left out and
         * the line ends `Telemetry stale`. The outcome reads `COMPLETED — <line>`.
         */
        fun statusReport(state: VehicleState): String {
            if (!state.connected) return "Not connected"
            var stale = false
            fun <T> current(value: T?, messageId: Int): T? = when {
                value == null -> null
                state.fresh(messageId) -> value
                else -> {
                    stale = true
                    null
                }
            }
            val landed = current(state.landedState, ExtendedSysState.messageId)
            val altitude = current(state.altitudeRelM, GlobalPositionInt.messageId)
            val battery = current(state.batteryPercent, SysStatus.messageId)
            val healthy = current(state.healthy, SysStatus.messageId)
            val flags = current(state.ekfFlags, EkfStatusReport.messageId)
            return listOfNotNull(
                "Connected",
                state.armed?.let { if (it) "Armed" else "Disarmed" },
                landed?.let(STATUS_LANDED::get),
                state.mode?.let { STATUS_MODES[it] ?: "Mode $it" },
                altitude?.let { "Alt " + String.format(Locale.ROOT, "%.1f", it).let { a -> if (a == "-0.0") "0.0" else a } + " m" },
                battery?.let { "Battery $it%" },
                if (healthy == false) "Sensors not healthy" else null,
                flags?.let { if (state.positionReady) "Position ready" else "Position not ready" },
                if (stale) "Telemetry stale" else null,
            ).joinToString(" · ")
        }
    }

    private enum class Phase { GUIDED, ARM, TAKEOFF, CLIMB }
}
