package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.memory.MemoryRoutes
import com.aeronex.app.memory.MemoryState
import com.aeronex.app.memory.Waypoint
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import org.junit.Assert.*
import org.junit.Test

/** Spec 2026-10-28 M3: flight-memory steps are validated as the supervisor will fly them. */
class PlanValidatorMemoryTest {
    private val sitl = PlanValidator()
    private val hardware = PlanValidator(realHardwareProfile = true)

    private fun tool(name: String, arguments: Map<String, Any?> = emptyMap()) =
        PlanStep(FlightCommand.of(name, arguments), StepSource.RULES)

    private fun move(direction: String, distance: Number) =
        tool(FlightCommand.MOVE, mapOf("direction" to direction, "distance_m" to distance))

    private fun numbered(name: String, n: Int) = tool(name, mapOf(FlightCommand.POINT to n))
    private val returnHome = tool(FlightCommand.RETURN_HOME)
    private val goBack = tool(FlightCommand.GO_BACK)
    private val retrace = tool(FlightCommand.RETRACE)

    private fun plan(vararg steps: PlanStep) = FlightPlan(steps.toList())

    /** A connected, airborne vehicle with every message fresh unless listed in [stale]. */
    private fun vehicle(
        landed: Boolean = false, altitude: Double? = 3.0, position: Ned? = Ned(0.0, 0.0, -3.0),
        yaw: Double? = 0.0, home: Ned? = Ned(0.0, 0.0, 0.0), stale: Set<Int> = emptySet(),
    ) = VehicleState(
        landedState = if (landed) VehicleState.LANDED_ON_GROUND else VehicleState.LANDED_IN_AIR,
        altitudeRelM = altitude, position = position, yaw = yaw, home = home,
        ageMs = listOf(0, 30, 32, 33, 242, 245).associateWith { if (it in stale) 3000L else 0L },
    )

    private fun waypoint(x: Double, y: Double, z: Double = -3.0, label: String = "w") = Waypoint(Ned(x, y, z), label, 0)

    private val home = Ned(0.0, 0.0, 0.0)
    private val flown = MemoryState(home = home, trail = listOf(waypoint(0.0, 0.0, label = "Takeoff"),
        waypoint(10.0, 0.0), waypoint(10.0, 10.0)), points = mapOf(1 to waypoint(-5.0, 0.0, -6.0, "Point 1")))

    @Test fun flightMemoryStepsResolveFromTheMemory() {
        val there = vehicle(position = Ned(10.0, 10.0, -3.0))
        assertNull(sitl.validate(plan(returnHome), there, flown))
        assertNull(sitl.validate(plan(goBack, goBack), there, flown))
        assertNull(sitl.validate(plan(retrace), there, flown))
        assertNull(sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1)), there, flown))
        // Go back walks the trail: a third go back from the takeoff position has nowhere to go.
        assertEquals("Step 3 — ${MemoryRoutes.NOTHING_TO_GO_BACK}", sitl.validate(plan(goBack, goBack, goBack), there, flown))
        assertEquals("Step 1 — ${MemoryRoutes.noPoint(4)}", sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 4)), there, flown))
    }

    @Test fun aPointMarkedEarlierInThePlanIsThereForALaterStep() {
        val empty = MemoryState(home = home)
        val gotoTwo = numbered(FlightCommand.GOTO_POINT, 2)
        assertEquals("Step 2 — ${MemoryRoutes.noPoint(2)}", sitl.validate(plan(move("forward", 3), gotoTwo), vehicle(), empty))
        assertNull(sitl.validate(plan(numbered(FlightCommand.MARK_POINT, 2), move("forward", 3), gotoTwo), vehicle(), empty))
        // A move earlier in the plan is a position to go back to.
        assertNull(sitl.validate(plan(move("forward", 3), goBack), vehicle(), empty))
        assertEquals("Step 1 — ${MemoryRoutes.NOTHING_TO_GO_BACK}", sitl.validate(plan(goBack), vehicle(), empty))
    }

    @Test fun everyLegStaysWithinTheHomeDistance() {
        val far = MemoryState(home = home, points = mapOf(3 to waypoint(35.0, 0.0)))
        assertEquals("Step 2 would go more than 30 m from home.",
            sitl.validate(plan(move("forward", 1), numbered(FlightCommand.GOTO_POINT, 3)), vehicle(), far))
        // Return home ends at home whatever the distance: it is the way back in.
        assertNull(sitl.validate(plan(returnHome), vehicle(position = Ned(0.0, 31.0, -3.0)), flown))
    }

    @Test fun movesAfterAMemoryFlightKeepTheAltitudeRange() {
        // Point 1 is at 6 m; up 5 from there would be 11 m.
        assertEquals("Step 2 would leave the 1–10 m altitude range.",
            sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1), move("up", 5)), vehicle(), flown))
        assertNull(sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1), move("up", 4)), vehicle(), flown))
        assertNull(sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1), move("down", 5)), vehicle(), flown))
        assertEquals("Step 2 would leave the 1–10 m altitude range.",
            sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1), move("down", 5.5)), vehicle(), flown))
        // Unknown altitude: left to the executor.
        assertNull(sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1), move("up", 5)), vehicle(altitude = null), flown))
    }

    @Test fun aRememberedPositionJustOutsideTheRangeIsFlownInsideIt() {
        // The takeoff ended 0.4 m short of 1 m: retrace flies its last leg at 1 m instead of refusing it.
        val low = MemoryState(home = home, trail = listOf(waypoint(0.0, 0.0, -0.6, "Takeoff"), waypoint(5.0, 0.0, -1.0)))
        assertNull(sitl.validate(plan(retrace), vehicle(altitude = 1.0, position = Ned(5.0, 0.0, -1.0)), low))
    }

    @Test fun markingAloneIsAllowedOnTheGround() {
        assertNull(sitl.validate(plan(numbered(FlightCommand.MARK_POINT, 1)), vehicle(landed = true, altitude = 0.0)))
        assertEquals("The drone is on the ground. Start with a takeoff height, for example: take off to 3 m and fly forward 3 m.",
            sitl.validate(plan(returnHome), vehicle(landed = true, altitude = 0.0), flown))
        assertEquals("The drone is on the ground. Start with a takeoff height, for example: take off to 3 m and fly forward 3 m.",
            sitl.validate(plan(numbered(FlightCommand.MARK_POINT, 1), move("up", 1)), vehicle(landed = true)))
    }

    @Test fun aTakeoffStartsANewTrailButKeepsThePoints() {
        val ground = vehicle(landed = true, altitude = 0.0, position = Ned(0.0, 0.0, 0.0))
        val takeoff = tool(FlightCommand.TAKEOFF, mapOf("altitude_m" to 3))
        // The old trail is gone after the takeoff: nothing to go back to but the takeoff position itself.
        assertEquals("Step 2 — ${MemoryRoutes.NOTHING_TO_GO_BACK}", sitl.validate(plan(takeoff, goBack), ground, flown))
        assertNull(sitl.validate(plan(takeoff, numbered(FlightCommand.GOTO_POINT, 1), returnHome), ground, flown))
    }

    @Test fun withoutHomeOnlyTheMemoryAloneIsChecked() {
        val noHome = MemoryState(trail = flown.trail)
        assertNull(sitl.validate(plan(goBack), vehicle(home = null), noHome))
        assertNull(sitl.validate(plan(goBack), null, noHome))
        assertEquals("Step 1 — ${MemoryRoutes.NOTHING_TO_RETRACE}", sitl.validate(plan(retrace), null, MemoryState()))
        assertEquals("Step 1 — ${MemoryRoutes.noPoint(1)}", sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1)), null))
        assertNull(sitl.validate(plan(numbered(FlightCommand.MARK_POINT, 1), numbered(FlightCommand.GOTO_POINT, 1)), null))
        // The real hardware profile flies nothing without home.
        assertEquals(PlanValidator.HOME_UNKNOWN, hardware.validate(plan(goBack), vehicle(home = null), noHome))
    }

    @Test fun aMemoryFlightNeedsAFreshPositionLikeAMove() {
        assertEquals(PlanValidator.POSITION_UNKNOWN, sitl.validate(plan(returnHome), vehicle(stale = setOf(32)), flown))
    }

    @Test fun theVehiclesHomeIsUsedWhenTheMemoryHasNone() {
        val noHome = MemoryState()
        assertNull(sitl.validate(plan(returnHome), vehicle(position = Ned(5.0, 5.0, -3.0)), noHome))
    }

    /** As [vehicle], with a fresh SYS_STATUS battery level. */
    private fun withBattery(percent: Int, position: Ned = Ned(10.0, 0.0, -3.0), sysStatusAge: Long = 0) = vehicle(position = position)
        .let { it.copy(batteryPercent = percent, ageMs = it.ageMs + (1 to sysStatusAge)) }

    @Test fun belowTheBatteryReserveOnlyFlightsBackTowardHomeAreAllowed() {
        val low = withBattery(22)
        val away = "Step 1 flies away from home with the battery at 22 %. Below 30 % only flights back toward home " +
            "are allowed: say \"return home\" or \"land\"."
        assertEquals(away, sitl.validate(plan(move("forward", 2)), low, flown))
        assertEquals(away.replace("Step 1", "Step 2"), sitl.validate(plan(move("backward", 4), move("right", 9)), low, flown))
        // Toward home, sideways within the arrival slack, home itself, holding and landing: allowed.
        assertNull(sitl.validate(plan(move("backward", 4)), low, flown))
        assertNull(sitl.validate(plan(returnHome, tool(FlightCommand.LAND)), low, flown))
        assertNull(sitl.validate(plan(move("up", 1), tool(FlightCommand.HOLD)), low, flown))
        // A remembered point farther out is refused too; one closer in is fine.
        assertEquals(away, sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 7)), low,
            flown.copy(points = mapOf(7 to waypoint(20.0, 0.0)))))
        assertNull(sitl.validate(plan(numbered(FlightCommand.GOTO_POINT, 1)), low, flown))
        // At the reserve, or with a stale or unknown level, there is no restriction.
        assertNull(sitl.validate(plan(move("forward", 2)), withBattery(30), flown))
        assertNull(sitl.validate(plan(move("forward", 2)), withBattery(5, sysStatusAge = 9_000), flown))
        assertNull(sitl.validate(plan(move("forward", 2)), vehicle(position = Ned(10.0, 0.0, -3.0)), flown))
        // The reserve is configurable.
        assertNull(PlanValidator(lowBatteryPercent = 20).validate(plan(move("forward", 2)), low, flown))
    }
}
