package com.aeronex.app.safety

import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ExternalCause
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.executor.StopAction

sealed interface StopDecision {
    data class Send(val action: StopAction, val override: Boolean) : StopDecision
    data class NothingSent(val message: String) : StopDecision
}

/** The spec 4.12 action table. Never a forced disarm or flight termination. */
object StopPolicy {
    const val NOT_CONNECTED = "Not connected, use RC"
    const val DISARMED = "Disarmed: nothing sent"
    const val DRY_RUN = "Dry run: nothing sent, use RC"
    const val NOT_CONFIRMED = "STOP NOT CONFIRMED, take RC control"

    /**
     * [appOwnsMode] and [armed] include a mode change or ARM the app just got accepted that no heartbeat shows yet
     * (ModeWatch).
     * - Not connected or disarmed: nothing.
     * - Another owner (autopilot failsafe, pilot): nothing, unless the on-screen button was held 1 s.
     * - Dry run: only a mode the app itself set (execution was on earlier in this connection) is stopped.
     * - Armed and ON_GROUND (fresh): non-forced DISARM; anything else (airborne, landing, unknown): BRAKE.
     */
    fun decide(
        state: VehicleState?,
        trigger: StopTrigger,
        heldOneSecond: Boolean,
        appOwnsMode: Boolean,
        execution: Boolean,
        armed: Boolean = state?.armed == true,
    ): StopDecision {
        if (state == null || !state.connected) return StopDecision.NothingSent(NOT_CONNECTED)
        if (!armed) return StopDecision.NothingSent(DISARMED)
        val override = !appOwnsMode && heldOneSecond && trigger.canOverride
        if (!appOwnsMode && !override) return StopDecision.NothingSent(externalBanner(state))
        if (override && !execution) return StopDecision.NothingSent(DRY_RUN)
        val onGround = state.fresh(ExtendedSysState.messageId) && state.landedState == VehicleState.LANDED_ON_GROUND
        return StopDecision.Send(if (onGround) StopAction.DISARM else StopAction.BRAKE, override)
    }

    /** "Autopilot failsafe active, use RC" or "Pilot mode MODE, use RC". */
    fun externalBanner(state: VehicleState): String =
        if (state.externalCause == ExternalCause.AUTOPILOT_FAILSAFE) "Autopilot failsafe active, use RC"
        else "Pilot mode ${state.mode?.let(CopterMode::name) ?: "unknown"}, use RC"

    fun sendingMessage(action: StopAction, previous: StopAction?): String = when {
        previous == StopAction.DISARM -> "Disarm refused, ${label(action)} sent"
        previous == StopAction.BRAKE -> "BRAKE refused, ${label(action)} sent"
        else -> "STOP: ${label(action)} sent"
    }

    fun confirmedMessage(action: StopAction): String = when (action) {
        StopAction.DISARM -> "STOP confirmed: disarmed"
        else -> "STOP confirmed: ${label(action)}"
    }

    /** A pilot, another ground station or a failsafe chose [mode] while the STOP was unconfirmed: nothing more is sent. */
    fun takenOverMessage(mode: Long) = "STOP NOT CONFIRMED: flight mode changed to ${CopterMode.name(mode)} outside this app, use RC"

    private fun label(action: StopAction) = if (action == StopAction.DISARM) "disarm" else action.name
}
