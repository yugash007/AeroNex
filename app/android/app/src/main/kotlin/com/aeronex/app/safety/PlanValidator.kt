package com.aeronex.app.safety

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.vehicle.VehicleState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Validates a plan against the vehicle (spec 4.10, S2, S13). In order:
 * 1. the v0.2.0 FlightPlan structure rules (in [FlightPlan]'s constructor);
 * 2. the v0.2.0 vehicle check, only while the vehicle is connected and reports a fresh landed state (an
 *    unreported state is left to the executor, which re-checks every step, as in v0.2.0);
 * 3. the home distance: each move's horizontal end point, projected in the body frame from the current position
 *    and yaw, must stay within [maxHomeDistanceM] of home. On the [realHardwareProfile], any move is rejected
 *    while home is unknown.
 *
 * Every message is operator-facing. The projection assumes the heading does not change between steps; ArduCopter
 * may yaw toward its target (WP_YAW_BEHAVIOR), so re-validate each step against a fresh state before sending it.
 */
class PlanValidator(val maxHomeDistanceM: Double = 30.0, val realHardwareProfile: Boolean = false) {

    /** Builds the plan from [steps] and validates it: null when it may be shown, else the rejection message. */
    fun validate(steps: List<PlanStep>, vehicle: VehicleState?): String? = try {
        validate(FlightPlan(steps), vehicle)
    } catch (e: CommandFormatException) {
        e.message
    }

    /** Null when [plan] may be shown for confirmation, else the rejection message. */
    fun validate(plan: FlightPlan, vehicle: VehicleState?): String? {
        if (vehicle != null && vehicle.connected && vehicle.fresh(ExtendedSysState.messageId) &&
            vehicle.landedState in LANDED_STATES
        ) {
            val altitude = vehicle.altitudeRelM?.takeIf { vehicle.fresh(GlobalPositionInt.messageId) }
            try {
                plan.checkVehicle(landed = vehicle.landedState == VehicleState.LANDED_ON_GROUND, altitude = altitude)
            } catch (e: CommandFormatException) {
                return e.message
            }
        }
        return homeDistance(plan, vehicle)
    }

    private fun homeDistance(plan: FlightPlan, vehicle: VehicleState?): String? {
        if (plan.steps.none { it.command.name == FlightCommand.MOVE }) return null
        val home = vehicle?.home ?: return if (realHardwareProfile) HOME_UNKNOWN else null
        val position = vehicle.position?.takeIf { vehicle.fresh(LocalPositionNed.messageId) }
        val yaw = vehicle.yaw?.takeIf { vehicle.fresh(Attitude.messageId) }
        if (position == null || yaw == null) return POSITION_UNKNOWN
        var north = position.x
        var east = position.y
        plan.steps.forEachIndexed { i, step ->
            if (step.command.name != FlightCommand.MOVE) return@forEachIndexed
            val d = step.command.distanceM!!
            when (step.command.direction) {
                "forward" -> { north += cos(yaw) * d; east += sin(yaw) * d }
                "backward" -> { north -= cos(yaw) * d; east -= sin(yaw) * d }
                "right" -> { north -= sin(yaw) * d; east += cos(yaw) * d }
                "left" -> { north += sin(yaw) * d; east -= cos(yaw) * d }
            }
            // Tolerate float rounding, as the altitude range does.
            if (hypot(north - home.x, east - home.y) > maxHomeDistanceM + 1e-9) {
                return "Step ${i + 1} would go more than ${FlightCommand.metres(maxHomeDistanceM)} m from home."
            }
        }
        return null
    }

    companion object {
        const val HOME_UNKNOWN = "Home position is unknown. Wait for GPS home before moving."
        const val POSITION_UNKNOWN = "Position or heading is unavailable. Wait for fresh telemetry before moving."
        private val LANDED_STATES = setOf(
            VehicleState.LANDED_ON_GROUND, VehicleState.LANDED_IN_AIR,
            VehicleState.LANDED_TAKEOFF, VehicleState.LANDED_LANDING,
        )
    }
}
