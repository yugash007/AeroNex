package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore

/** The state-dependent STOP frame (spec 4.12). Never a forced disarm or flight termination. */
enum class StopAction { DISARM, BRAKE, LAND }

sealed interface StopProgress {
    val action: StopAction

    data class Sending(override val action: StopAction) : StopProgress
    data class Confirmed(override val action: StopAction) : StopProgress
    /**
     * No ACK within the window, every fallback refused, or no heartbeat after the ACK showed the STOP's effect:
     * "STOP NOT CONFIRMED, take RC control".
     */
    data class NotConfirmed(override val action: StopAction) : StopProgress

    /**
     * A heartbeat showed a [mode] that neither the app nor this STOP chose: a pilot, another ground station or an
     * autopilot failsafe took over (spec 8.1). Nothing more is sent and a late ACK is ignored; also not confirmed.
     */
    data class TakenOver(override val action: StopAction, val mode: Long) : StopProgress
}

/**
 * Sends one STOP on the link's priority lane (which also drops queued vehicle commands) and drives it to an ACK:
 * the frame is re-sent with `confirmation` + 1 every [ExecutorTiming.commandRetryMs] until acknowledged, for at
 * most [ExecutorTiming.stopWindowMs] per frame (an IN_PROGRESS ACK stops the re-sends, not the window). Refusals
 * fall back: DISARM → BRAKE with [alarm] (ArduCopter refuses a non-forced GCS disarm unless landed), BRAKE → LAND
 * (BRAKE needs a position estimate), LAND → not confirmed.
 *
 * A COMMAND_ACK names only the command, so an ACK of an earlier DO_SET_MODE or ARM can pass for the STOP's own.
 * After the ACK the lane therefore keeps checking: a heartbeat received after the STOP was sent must show its
 * effect (the mode, or disarmed) within [ExecutorTiming.stopWindowMs] of the ACK, else the STOP turns
 * [StopProgress.NotConfirmed] with the alarm. Only that heartbeat makes the BRAKE or LAND the app's mode (or the
 * disarm final) in the [ModeWatch]; until then [ModeWatch.ownsMode] counts it as requested.
 *
 * The STOP never overrides a mode a pilot or the autopilot chose meanwhile (spec 8.1, S4): once a heartbeat newer
 * than the decision shows a mode outside [ModeWatch.modesInPlay] at the decision and this STOP's own modes, it is
 * [StopProgress.TakenOver]: no re-send, no fallback, no ACK taken. Once a heartbeat has shown this STOP's mode, the
 * decision's modes no longer count either. Supervisor thread only.
 *
 * The frames are built once here; the link re-encodes each send (sequence, CRC, signature), which is the codec's
 * replacement for spec 4.12's pre-encoded payloads.
 */
class StopLane(
    private val link: LinkSession,
    private val store: VehicleStateStore,
    private val timing: ExecutorTiming,
    private val trace: Trace,
    private val traceId: Long,
    private val first: StopAction,
    /** Shared with the plan executor of the same connection. */
    private val watch: ModeWatch = ModeWatch(store),
) {
    private val frames = mapOf(
        StopAction.DISARM to CommandLong(link.targetSystem, link.targetComponent, MAV_CMD_COMPONENT_ARM_DISARM,
            param1 = 0f, param2 = 0f),
        StopAction.BRAKE to setMode(CopterMode.BRAKE),
        StopAction.LAND to setMode(CopterMode.LAND),
    )
    private var exchange: CommandExchange? = null
    private var progress: StopProgress = StopProgress.Sending(first)
    private var heartbeatsAtDecision = 0L
    private var heartbeatsAtSend = 0L
    private var verifyUntilMs = 0L
    /** Modes the vehicle may show from before the decision ([ModeWatch.modesInPlay]). */
    private val decisionModes = HashSet<Long>()
    /** Modes this STOP asked for so far. */
    private val stopModes = HashSet<Long>()
    private var stopModeShown = false

    /** The actions tried, in order. */
    val actions = mutableListOf<StopAction>()

    /** A disarm was refused (now braking), or STOP was not confirmed. */
    var alarm = false
        private set

    /** A heartbeat after the ACK showed the STOP's effect. */
    var verified = false
        private set

    /** Still sending: no new STOP or plan may start. */
    val sending: Boolean get() = progress is StopProgress.Sending

    /** Nothing more to do: verified, not confirmed or taken over. */
    val settled: Boolean
        get() = verified || progress is StopProgress.NotConfirmed || progress is StopProgress.TakenOver

    /**
     * [decision]: the snapshot the STOP was decided on; [modesInPlay]: [ModeWatch.modesInPlay] then, taken before a
     * running plan was cancelled (its own mode change may still show).
     */
    fun start(
        nowMs: Long,
        decision: VehicleState = store.snapshot(),
        modesInPlay: Set<Long> = watch.modesInPlay(decision),
    ): StopProgress {
        heartbeatsAtDecision = ModeWatch.heartbeats(decision)
        decisionModes += modesInPlay
        begin(first, nowMs)
        return progress
    }

    /**
     * [acks]: the COMMAND_ACKs since the last call; [state]: a snapshot taken after they were drained, so it is at
     * least as new as each of them.
     */
    fun update(acks: List<CommandAck>, state: VehicleState, nowMs: Long): StopProgress {
        if (settled) return progress
        takenOverBy(state)?.let {
            takeOver(it)
            return progress
        }
        val current = exchange
        if (current == null) {
            if (progress is StopProgress.Confirmed && !verified) verify(state, nowMs)
            return progress
        }
        val action = progress.action
        when (val result = current.update(acks, nowMs)) {
            CommandExchange.Result.Waiting -> {}
            is CommandExchange.Result.Accepted -> {
                exchange = null
                traceAck(action, result.ack)
                progress = StopProgress.Confirmed(action)
                verifyUntilMs = nowMs + timing.stopWindowMs
                verify(state, nowMs)
            }
            is CommandExchange.Result.Refused -> {
                exchange = null
                watch.withdraw()
                traceAck(action, result.ack)
                when (action) {
                    StopAction.DISARM -> {
                        alarm = true
                        begin(StopAction.BRAKE, nowMs)
                    }
                    StopAction.BRAKE -> begin(StopAction.LAND, nowMs)
                    StopAction.LAND -> notConfirmed("refused")
                }
            }
            CommandExchange.Result.TimedOut -> notConfirmed("no ACK")
        }
        return progress
    }

    /** The link is gone: stop re-sending; the STOP is not confirmed. */
    fun cancel() {
        if (exchange != null) notConfirmed("cancelled")
    }

    /** A new STOP replaces this one: nothing more is sent or checked, and nothing of it becomes the app's mode. */
    fun abandon() {
        if (settled) return
        exchange = null
        watch.withdraw()
    }

    private fun begin(action: StopAction, nowMs: Long) {
        actions += action
        modeOf(action)?.let(stopModes::add)
        progress = StopProgress.Sending(action)
        heartbeatsAtSend = ModeWatch.heartbeats(store.snapshot())
        exchange = CommandExchange(link, frames.getValue(action), timing.commandRetryMs, maxSends = Int.MAX_VALUE,
            windowMs = timing.stopWindowMs, priority = true) {
            modeOf(action)?.let(watch::requested)
            trace.mark(TracePoint.STOP_TX, traceId, mapOf("action" to action, "confirmation" to it.confirmation))
        }.also { it.start(nowMs) }
    }

    private fun verify(state: VehicleState, nowMs: Long) {
        val action = progress.action
        val shown = when (action) {
            StopAction.DISARM -> state.armed == false
            else -> state.mode == modeOf(action)
        }
        if (shown && ModeWatch.heartbeats(state) > heartbeatsAtSend) {
            val mode = modeOf(action)
            if (mode != null) watch.accepted(mode) else watch.disarmAccepted()
            verified = true
            trace.mark(TracePoint.STOP_VERIFIED, traceId, mapOf("action" to action))
        } else if (nowMs >= verifyUntilMs) {
            notConfirmed("no heartbeat showed it")
        }
    }

    /** The mode a heartbeat newer than the decision shows when neither the app nor this STOP chose it, else null. */
    private fun takenOverBy(state: VehicleState): Long? {
        val mode = state.mode ?: return null
        if (!state.connected || ModeWatch.heartbeats(state) <= heartbeatsAtDecision) return null
        if (mode in stopModes) {
            stopModeShown = true
            return null
        }
        return if (!stopModeShown && mode in decisionModes) null else mode
    }

    private fun takeOver(mode: Long) {
        exchange = null
        watch.withdraw()
        alarm = true
        progress = StopProgress.TakenOver(progress.action, mode)
        trace.mark(TracePoint.STOP_NOT_CONFIRMED, traceId, mapOf("action" to progress.action,
            "why" to "mode changed outside this app", "mode" to CopterMode.name(mode)))
    }

    private fun notConfirmed(why: String) {
        exchange = null
        watch.withdraw()
        alarm = true
        progress = StopProgress.NotConfirmed(progress.action)
        trace.mark(TracePoint.STOP_NOT_CONFIRMED, traceId, mapOf("action" to progress.action, "why" to why))
    }

    private fun traceAck(action: StopAction, ack: CommandAck) =
        trace.mark(TracePoint.STOP_ACK, traceId, mapOf("action" to action, "result" to ack.result))

    private fun setMode(mode: Long) = CommandLong(link.targetSystem, link.targetComponent,
        ArduPilotExecutor.MAV_CMD_DO_SET_MODE, param1 = ArduPilotExecutor.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
        param2 = mode.toFloat())

    private companion object {
        const val MAV_CMD_COMPONENT_ARM_DISARM = 400

        fun modeOf(action: StopAction) = when (action) {
            StopAction.BRAKE -> CopterMode.BRAKE
            StopAction.LAND -> CopterMode.LAND
            StopAction.DISARM -> null
        }
    }
}
