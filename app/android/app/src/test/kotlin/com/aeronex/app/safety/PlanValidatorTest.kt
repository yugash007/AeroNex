package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class PlanValidatorTest {
    private val sitl = PlanValidator()
    private val hardware = PlanValidator(realHardwareProfile = true)

    private fun tool(name: String, arguments: Map<String, Any?> = emptyMap()) =
        PlanStep(FlightCommand.of(name, arguments), StepSource.RULES)

    private fun takeoff(altitude: Number) = tool("takeoff", mapOf("altitude_m" to altitude))
    private fun move(direction: String, distance: Number) =
        tool("move_relative", mapOf("direction" to direction, "distance_m" to distance))

    private fun plan(vararg steps: PlanStep) = FlightPlan(steps.toList())

    /** A connected vehicle with every message fresh unless listed in [stale]. */
    private fun vehicle(
        landed: Boolean = false, altitude: Double? = 3.0, position: Ned? = Ned(0.0, 0.0, -3.0),
        yaw: Double? = 0.0, home: Ned? = Ned(0.0, 0.0, 0.0), stale: Set<Int> = emptySet(),
        landedState: Int = if (landed) VehicleState.LANDED_ON_GROUND else VehicleState.LANDED_IN_AIR,
    ) = VehicleState(
        landedState = landedState, altitudeRelM = altitude, position = position, yaw = yaw, home = home,
        ageMs = listOf(0, 30, 32, 33, 242, 245).associateWith { if (it in stale) 3000L else 0L },
    )

    private val onGround = "The drone is on the ground. Start with a takeoff height, " +
        "for example: take off to 3 m and fly forward 3 m."
    private val homeUnknown = "Home position is unknown. Wait for GPS home before moving."

    @Test fun structuralRulesComeFirstAndKeepTheDartMessages() {
        assertEquals("Say a command first.", sitl.validate(emptyList(), vehicle()))
        assertEquals("Say at most 5 steps at a time.", sitl.validate(List(6) { move("left", 1) }, null))
        assertEquals("Takeoff can only be the first step.",
            sitl.validate(listOf(move("forward", 3), takeoff(3)), vehicle(landed = true)))
        assertNull(sitl.validate(listOf(takeoff(3), move("forward", 3), tool("land")), vehicle(landed = true)))
    }

    @Test fun checksTheVehicleLikeV020() {
        assertEquals(onGround, sitl.validate(plan(move("forward", 3)), vehicle(landed = true)))
        assertEquals("The drone is already flying. Remove the takeoff step.",
            sitl.validate(plan(takeoff(3)), vehicle(landed = false)))
        assertEquals("Step 2 would leave the 1–10 m altitude range.",
            sitl.validate(plan(move("forward", 3), move("up", 2)), vehicle(altitude = 9.0)))
        // A stale altitude is not projected.
        assertNull(sitl.validate(plan(move("forward", 3), move("up", 2)), vehicle(altitude = 9.0, stale = setOf(33))))
        assertNull(sitl.validate(plan(move("up", 2)), vehicle(altitude = null)))
        // On the ground the altitude is ignored.
        assertNull(sitl.validate(plan(takeoff(3), move("down", 1)), vehicle(landed = true, altitude = 0.0)))
    }

    @Test fun anUnreportedLandedStateIsLeftToTheExecutor() {
        val ground = plan(move("forward", 3))
        assertEquals(onGround, sitl.validate(ground, vehicle(landed = true)))
        assertNull(sitl.validate(ground, vehicle(landed = true, stale = setOf(245))))
        assertNull(sitl.validate(ground, vehicle(landed = true, stale = setOf(0))))
        assertNull(sitl.validate(ground, vehicle(landedState = VehicleState.LANDED_UNDEFINED)))
        assertEquals("The drone is already flying. Remove the takeoff step.",
            sitl.validate(plan(takeoff(3)), vehicle(landedState = VehicleState.LANDED_TAKEOFF)))
        assertEquals("The drone is already flying. Remove the takeoff step.",
            sitl.validate(plan(takeoff(3)), vehicle(landedState = VehicleState.LANDED_LANDING)))
        assertNull(sitl.validate(ground, null))
    }

    @Test fun theVehicleCheckRunsBeforeTheHomeDistance() {
        assertEquals(onGround, sitl.validate(plan(move("forward", 10)),
            vehicle(landed = true, position = Ned(29.0, 0.0, 0.0))))
    }

    @Test fun rejectsTheFirstStepThatGoesBeyondTheHomeDistance() {
        val at25 = vehicle(position = Ned(25.0, 0.0, -3.0))
        assertNull(sitl.validate(plan(move("forward", 5)), at25))
        assertEquals("Step 2 would go more than 30 m from home.",
            sitl.validate(plan(move("forward", 5), move("forward", 1), tool("land")), at25))
        assertEquals("Step 1 would go more than 30 m from home.",
            sitl.validate(plan(move("forward", 10), move("backward", 10)), at25))
        assertNull(sitl.validate(plan(move("backward", 10), move("left", 10)), at25))
    }

    @Test fun projectsMovesInTheBodyFrameRotatedByTheCurrentYaw() {
        val east = vehicle(position = Ned(0.0, 25.0, -3.0), yaw = PI / 2)
        assertEquals("Step 1 would go more than 30 m from home.", sitl.validate(plan(move("forward", 10)), east))
        assertNull(sitl.validate(plan(move("left", 10)), east))
        assertNull(sitl.validate(plan(move("right", 10)), east))
        assertNull(sitl.validate(plan(move("backward", 10)), east))
        val north = vehicle(position = Ned(0.0, 25.0, -3.0), yaw = 0.0)
        assertNull(sitl.validate(plan(move("forward", 10)), north))
        assertEquals("Step 1 would go more than 30 m from home.", sitl.validate(plan(move("right", 10)), north))
        assertNull(sitl.validate(plan(move("left", 10)), north))
        // Facing south-west from (20, 20): forward heads back toward home.
        val southWest = vehicle(position = Ned(20.0, 20.0, -3.0), yaw = -3 * PI / 4)
        assertNull(sitl.validate(plan(move("forward", 10)), southWest))
        assertEquals("Step 1 would go more than 30 m from home.", sitl.validate(plan(move("backward", 3)), southWest))
    }

    @Test fun measuresFromHomeNotFromTheLocalOrigin() {
        val away = vehicle(position = Ned(10.0, 0.0, -3.0), home = Ned(-15.0, 0.0, 0.0))
        assertNull(sitl.validate(plan(move("forward", 5)), away))
        assertEquals("Step 1 would go more than 30 m from home.", sitl.validate(plan(move("forward", 5.5)), away))
    }

    @Test fun repeatedForwardTenStopsAtTheLimit() {
        var position = Ned(0.0, 0.0, -3.0)
        var accepted = 0
        while (accepted < 10 && sitl.validate(plan(move("forward", 10)), vehicle(position = position)) == null) {
            accepted++
            position = position.copy(x = position.x + 10)
        }
        assertEquals(3, accepted)
        assertEquals("Step 1 would go more than 30 m from home.",
            sitl.validate(plan(move("forward", 10)), vehicle(position = position)))
    }

    @Test fun exactlyTheLimitIsAllowedDespiteRounding() {
        val diagonal = vehicle(position = Ned(0.0, 0.0, -3.0), yaw = PI / 4)
        assertNull(sitl.validate(plan(move("forward", 10), move("forward", 10), move("forward", 10)), diagonal))
        assertEquals("Step 4 would go more than 30 m from home.", sitl.validate(
            plan(move("forward", 10), move("forward", 10), move("forward", 10), move("forward", 0.001)), diagonal))
    }

    @Test fun verticalMovesKeepTheHorizontalDistance() {
        val at29 = vehicle(position = Ned(0.0, -29.0, -3.0))
        assertNull(sitl.validate(plan(move("up", 2), move("down", 1)), at29))
        // Beyond the limit only a move that ends back inside it is allowed; landing and holding always are.
        val at31 = vehicle(position = Ned(0.0, -31.0, -3.0))
        assertEquals("Step 1 would go more than 30 m from home.", sitl.validate(plan(move("down", 1)), at31))
        assertEquals("Step 1 would go more than 30 m from home.", sitl.validate(plan(move("forward", 1)), at31))
        assertEquals("Step 1 would go more than 30 m from home.", sitl.validate(plan(move("left", 5)), at31))
        assertNull(sitl.validate(plan(move("right", 5)), at31))
        assertNull(sitl.validate(plan(tool("hold"), tool("land")), at31))
        assertNull(sitl.validate(plan(tool("get_status")), at31))
    }

    @Test fun theLimitIsConfigurable() {
        val wide = PlanValidator(maxHomeDistanceM = 50.0)
        val at45 = vehicle(position = Ned(45.0, 0.0, -3.0))
        assertNull(wide.validate(plan(move("forward", 5)), at45))
        assertEquals("Step 1 would go more than 50 m from home.", wide.validate(plan(move("forward", 6)), at45))
        assertEquals("Step 1 would go more than 12.5 m from home.",
            PlanValidator(maxHomeDistanceM = 12.5)
                .validate(plan(move("forward", 10)), vehicle(position = Ned(5.0, 0.0, 0.0))))
    }

    @Test fun unknownHomeRejectsMovesOnlyOnTheRealHardwareProfile() {
        val noHome = vehicle(home = null)
        assertNull(sitl.validate(plan(move("forward", 10)), noHome))
        assertNull(sitl.validate(plan(move("forward", 10)), null))
        assertEquals(homeUnknown, hardware.validate(plan(move("forward", 10)), noHome))
        assertEquals(homeUnknown, hardware.validate(plan(move("up", 1)), noHome))
        assertEquals(homeUnknown, hardware.validate(plan(move("left", 1)), null))
        assertEquals(homeUnknown,
            hardware.validate(plan(takeoff(3), move("forward", 1)), vehicle(landed = true, home = null)))
        assertNull(hardware.validate(plan(takeoff(3), tool("land")), vehicle(landed = true, home = null)))
        assertNull(hardware.validate(plan(tool("hold")), noHome))
        assertNull(hardware.validate(plan(tool("get_status")), null))
        assertNull(hardware.validate(plan(move("forward", 10)), vehicle()))
    }

    @Test fun aKnownHomeNeedsAFreshPositionAndHeadingToProjectMoves() {
        val positionUnknown = "Position or heading is unavailable. Wait for fresh telemetry before moving."
        for (profile in listOf(sitl, hardware)) {
            assertEquals(positionUnknown, profile.validate(plan(move("forward", 1)), vehicle(stale = setOf(32))))
            assertEquals(positionUnknown, profile.validate(plan(move("up", 1)), vehicle(stale = setOf(30))))
            assertEquals(positionUnknown, profile.validate(plan(move("left", 1)), vehicle(position = null)))
            assertEquals(positionUnknown, profile.validate(plan(move("left", 1)), vehicle(yaw = null)))
            assertNull(profile.validate(plan(tool("land")), vehicle(position = null, yaw = null)))
        }
    }
}
