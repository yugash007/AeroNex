package com.aeronex.app.command.model

import org.junit.Assert.*
import org.junit.Test

/** Port of the v0.2.0 flight_plan_test.dart: the same structure rules, vehicle check and messages. */
class FlightPlanTest {
    private fun tool(name: String, arguments: Map<String, Any?> = emptyMap()) = FlightCommand.of(name, arguments)
    private fun takeoff(altitude: Number) = tool("takeoff", mapOf("altitude_m" to altitude))
    private fun move(direction: String, distance: Number) =
        tool("move_relative", mapOf("direction" to direction, "distance_m" to distance))

    private fun plan(vararg commands: FlightCommand) =
        FlightPlan(commands.map { PlanStep(it, StepSource.RULES) })

    private fun rejects(message: String, block: () -> Unit) {
        val error = assertThrows(CommandFormatException::class.java) { block() }
        assertEquals(message, error.message)
    }

    private val onGround = "The drone is on the ground. Start with a takeoff height, " +
        "for example: take off to 3 m and fly forward 3 m."

    @Test fun keepsAnImmutableOrderedPlanAndNumbersIt() {
        val steps = mutableListOf(
            PlanStep(takeoff(3), StepSource.RULES, "take off to 3 m"),
            PlanStep(move("forward", 3), StepSource.FIXTURE),
            PlanStep(tool("land"), StepSource.RULES),
        )
        val flightPlan = FlightPlan(steps)
        steps.clear()
        assertEquals(3, flightPlan.steps.size)
        assertEquals("take off to 3 m", flightPlan.steps.first().clause)
        assertEquals(StepSource.FIXTURE, flightPlan.steps[1].source)
        assertEquals("", flightPlan.steps[1].clause)
        assertEquals("1. Take off to 3 m; 2. Move forward 3 m; 3. Land", flightPlan.toString())
        @Suppress("UNCHECKED_CAST")
        val leaked = flightPlan.steps as MutableList<PlanStep>
        assertThrows(UnsupportedOperationException::class.java) { leaked.add(flightPlan.steps.first()) }
        assertEquals("rules", StepSource.RULES.wireName)
    }

    @Test fun wrapsASingleCommand() {
        val single = FlightPlan.single(tool("hold"))
        assertEquals("hold", single.steps.single().command.name)
        assertEquals(StepSource.RULES, single.steps.single().source)
        assertEquals("", single.steps.single().clause)
        val fixture = FlightPlan.single(tool("land"), StepSource.FIXTURE, "land")
        assertEquals(StepSource.FIXTURE, fixture.steps.single().source)
        assertEquals("land", fixture.steps.single().clause)
        assertEquals("1. Land", fixture.toString())
    }

    @Test fun rejectsEmptyAndOverLongPlans() {
        assertEquals(5, FlightPlan.MAX_STEPS)
        rejects("Say a command first.") { plan() }
        assertEquals(5, plan(*Array(5) { move("left", 1) }).steps.size)
        rejects("Say at most 5 steps at a time.") { plan(*Array(6) { move("left", 1) }) }
    }

    @Test fun enforcesStepPositions() {
        rejects("Ask for status on its own.") { plan(tool("get_status"), tool("land")) }
        rejects("Ask for status on its own.") { plan(move("up", 1), tool("get_status")) }
        rejects("Takeoff can only be the first step.") { plan(move("forward", 3), takeoff(3)) }
        rejects("Takeoff can only be the first step.") { plan(takeoff(3), takeoff(4)) }
        rejects("Land can only be the last step.") { plan(tool("land"), move("forward", 3)) }
        rejects("Land can only be the last step.") { plan(tool("land"), tool("land")) }
        assertEquals(1, plan(tool("get_status")).steps.size)
        assertEquals(3, plan(takeoff(3), tool("hold"), tool("land")).steps.size)
    }

    @Test fun rulesRunInTheDartOrder() {
        // More than 5 steps wins over status, takeoff and land placement.
        rejects("Say at most 5 steps at a time.") {
            plan(tool("land"), tool("get_status"), takeoff(3), move("up", 1), move("up", 1), move("up", 1))
        }
        // Status before takeoff placement, takeoff before land placement.
        rejects("Ask for status on its own.") { plan(tool("land"), takeoff(3), tool("get_status")) }
        rejects("Takeoff can only be the first step.") { plan(tool("land"), takeoff(3)) }
        // Placement before the takeoff altitude range.
        rejects("Land can only be the last step.") { plan(takeoff(3), move("up", 9), tool("land"), tool("hold")) }
    }

    @Test fun projectsAltitudeFromTheTakeoffHeight() {
        assertEquals(2, plan(takeoff(3), move("up", 7)).steps.size)
        assertEquals(3, plan(takeoff(3), move("forward", 5), move("down", 2)).steps.size)
        // 1.4 - 0.4 is 0.9999999999999999 in floating point.
        assertEquals(2, plan(takeoff(1.4), move("down", 0.4)).steps.size)
        rejects("Step 2 would leave the 1–10 m altitude range.") { plan(takeoff(3), move("up", 7.5)) }
        rejects("Step 3 would leave the 1–10 m altitude range.") {
            plan(takeoff(3), move("forward", 5), move("down", 2.5))
        }
        rejects("Step 3 would leave the 1–10 m altitude range.") {
            plan(takeoff(5), move("up", 4), move("up", 2), tool("land"))
        }
        // Without a takeoff the start altitude is unknown here.
        assertEquals(2, plan(move("up", 10), move("up", 10)).steps.size)
    }

    @Test fun requiresATakeoffFirstWhenTheVehicleIsOnTheGround() {
        rejects(onGround) { plan(move("forward", 3)).checkVehicle(landed = true) }
        rejects(onGround) { plan(tool("hold"), tool("land")).checkVehicle(landed = true) }
        plan(tool("get_status")).checkVehicle(landed = true)
        plan(takeoff(3), move("up", 2)).checkVehicle(landed = true, altitude = 0.0)
        // On the ground the reported altitude is ignored; takeoff sets the start.
        plan(takeoff(3), move("down", 1)).checkVehicle(landed = true, altitude = 0.0)
    }

    @Test fun rejectsTakeoffAndProjectsAltitudeWhenAirborne() {
        rejects("The drone is already flying. Remove the takeoff step.") {
            plan(takeoff(3)).checkVehicle(landed = false)
        }
        rejects("Step 2 would leave the 1–10 m altitude range.") {
            plan(move("forward", 3), move("up", 2)).checkVehicle(landed = false, altitude = 9.0)
        }
        rejects("Step 1 would leave the 1–10 m altitude range.") {
            plan(move("down", 1.5)).checkVehicle(landed = false, altitude = 2.0)
        }
        plan(move("down", 1), tool("land")).checkVehicle(landed = false, altitude = 2.0)
        plan(move("up", 10), move("up", 10)).checkVehicle(landed = false)
        plan(tool("get_status")).checkVehicle(landed = false, altitude = 0.2)
        // Only up/down moves change the projected altitude.
        plan(move("forward", 3)).checkVehicle(landed = false, altitude = 0.5)
    }
}
