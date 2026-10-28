package com.aeronex.app.safety

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.memory.MemoryRoutes
import com.aeronex.app.memory.MemoryState
import com.aeronex.app.vehicle.Ned
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
 * 4. flight memory (spec 2026-10-28 M3): return home, go back, retrace and go to spot are resolved with
 *    [MemoryRoutes] from the memory as the earlier steps of the plan leave it (a spot marked in step 2 is there for
 *    step 4) and flown as [MemoryRoutes.flyable] flies them; every leg must stay within [maxHomeDistanceM] of home,
 *    and an up or down move after such a flight within 1–10 m while the altitude is known. Without home or a
 *    position (a dry run with no vehicle) only what the memory alone refuses is checked.
 *
 * Every message is operator-facing. The projection assumes the heading does not change between steps; ArduCopter
 * may yaw toward its target (WP_YAW_BEHAVIOR), so re-validate each step against a fresh state before sending it.
 */
class PlanValidator(val maxHomeDistanceM: Double = 30.0, val realHardwareProfile: Boolean = false) {

    /** Builds the plan from [steps] and validates it: null when it may be shown, else the rejection message. */
    fun validate(steps: List<PlanStep>, vehicle: VehicleState?, memory: MemoryState = MemoryState()): String? = try {
        validate(FlightPlan(steps), vehicle, memory)
    } catch (e: CommandFormatException) {
        e.message
    }

    /** Null when [plan] may be shown for confirmation, else the rejection message. */
    fun validate(plan: FlightPlan, vehicle: VehicleState?, memory: MemoryState = MemoryState()): String? {
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
        return homeDistance(plan, vehicle, memory)
    }

    private fun homeDistance(plan: FlightPlan, vehicle: VehicleState?, memory: MemoryState): String? {
        if (plan.steps.none { it.command.isMotion }) return null
        val home = vehicle?.home ?: return if (realHardwareProfile) HOME_UNKNOWN else memoryAlone(plan, memory)
        val position = vehicle.position?.takeIf { vehicle.fresh(LocalPositionNed.messageId) }
        val yaw = vehicle.yaw?.takeIf { vehicle.fresh(Attitude.messageId) }
        if (position == null || yaw == null) return POSITION_UNKNOWN
        var north = position.x
        var east = position.y
        var down = position.z
        // Above home, while known: FlightPlan checks up and down moves until a flight-memory flight, this the rest.
        var altitude = vehicle.altitudeRelM?.takeIf { vehicle.fresh(GlobalPositionInt.messageId) }
        var afterMemory = false
        var remembered = if (memory.home == null) memory.copy(home = home) else memory
        plan.steps.forEachIndexed { i, step ->
            val command = step.command
            when {
                command.name == FlightCommand.MOVE -> {
                    val d = command.distanceM!!
                    when (command.direction) {
                        "forward" -> { north += cos(yaw) * d; east += sin(yaw) * d }
                        "backward" -> { north -= cos(yaw) * d; east -= sin(yaw) * d }
                        "right" -> { north -= sin(yaw) * d; east += cos(yaw) * d }
                        "left" -> { north += sin(yaw) * d; east -= cos(yaw) * d }
                        "up" -> { down -= d; altitude = altitude?.plus(d) }
                        "down" -> { down += d; altitude = altitude?.minus(d) }
                    }
                    // Tolerate float rounding, as the altitude range does.
                    if (hypot(north - home.x, east - home.y) > maxHomeDistanceM + 1e-9) return tooFar(i)
                    val height = altitude
                    if (afterMemory && height != null && (height < 1 - 1e-9 || height > 10 + 1e-9)) {
                        return "Step ${i + 1} would leave the 1–10 m altitude range."
                    }
                    remembered = MemoryRoutes.after(remembered, command, null, Ned(north, east, down))
                }
                command.isMemoryMotion -> {
                    val from = Ned(north, east, down)
                    val route = try {
                        MemoryRoutes.flyable(MemoryRoutes.resolve(command, remembered, from), from, altitude)
                    } catch (e: CommandFormatException) {
                        return memoryStep(i, e.message)
                    }
                    for (leg in route.legs) {
                        if (hypot(leg.x - home.x, leg.y - home.y) > maxHomeDistanceM + 1e-9) return tooFar(i)
                    }
                    route.legs.lastOrNull()?.let {
                        altitude = altitude?.plus(down - it.z)
                        north = it.x
                        east = it.y
                        down = it.z
                    }
                    afterMemory = true
                    remembered = MemoryRoutes.after(remembered, command, route, Ned(north, east, down))
                }
                command.name == FlightCommand.TAKEOFF -> {
                    val height = command.altitudeM!!
                    down = position.z - (height - (altitude ?: 0.0))
                    altitude = height
                    remembered = MemoryRoutes.after(remembered, command, null, Ned(north, east, down))
                }
                else -> remembered = MemoryRoutes.after(remembered, command, null, Ned(north, east, down))
            }
        }
        return null
    }

    private fun tooFar(index: Int) = "Step ${index + 1} would go more than ${FlightCommand.metres(maxHomeDistanceM)} m from home."

    /** "Step n — why", which the supervisor renumbers like every step message. */
    private fun memoryStep(index: Int, message: String?) = "Step ${index + 1} — ${message ?: "the flight memory cannot answer."}"

    /**
     * No home or position to project from (a dry run with no vehicle): only what the memory alone refuses, counting
     * the spots and positions the plan's own earlier steps make.
     */
    private fun memoryAlone(plan: FlightPlan, memory: MemoryState): String? {
        plan.steps.forEachIndexed { i, step ->
            val command = step.command
            val earlier = plan.steps.subList(0, i).map { it.command }
            val flown = memory.trail.isNotEmpty() || earlier.any { it.isMotion || it.name == FlightCommand.TAKEOFF }
            val problem = when (command.name) {
                FlightCommand.GOTO_POINT -> {
                    val point = command.point!!
                    val marked = point in memory.points || earlier.any { it.name == FlightCommand.MARK_POINT && it.point == point }
                    if (marked) null else MemoryRoutes.noSpot(point)
                }
                FlightCommand.GO_BACK -> if (flown) null else MemoryRoutes.NOTHING_TO_GO_BACK
                FlightCommand.RETRACE -> if (flown) null else MemoryRoutes.NOTHING_TO_RETRACE
                else -> null
            }
            problem?.let { return memoryStep(i, it) }
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
