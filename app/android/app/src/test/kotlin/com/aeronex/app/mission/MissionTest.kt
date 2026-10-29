package com.aeronex.app.mission

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 2026-10-28 "Missions": the stages, the plan rules across them, and what resume continues. */
class MissionTest {
    private fun step(name: String, arguments: Map<String, Any?> = emptyMap()) =
        PlanStep(FlightCommand.of(name, arguments), StepSource.RULES)

    private fun takeoff(m: Int = 3) = step(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to m))
    private fun move(direction: String, m: Number) =
        step(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to m))
    private val land = step(FlightCommand.LAND)

    private fun refused(message: String, vararg stages: List<PlanStep>) {
        val error = assertThrows(CommandFormatException::class.java) { Mission(stages.toList()) }
        assertEquals(message, error.message)
    }

    @Test fun stagesFlyAsOnePlanAndKnowTheirSteps() {
        val mission = Mission(listOf(listOf(takeoff(), move("forward", 4)), listOf(move("right", 3)),
            listOf(step(FlightCommand.RETURN_HOME), land)), checkpoints = true)
        assertEquals(5, mission.plan.steps.size)
        assertEquals(listOf(0, 0, 1, 2, 2), mission.stageOfStep)
        assertEquals(listOf(false, false, true, true, false), mission.plan.steps.indices.map(mission::startsStage))
        assertTrue(mission.checkpoints)
        assertEquals("1. Take off to 3 m; 2. Move forward 4 m; 3. Move right 3 m; 4. Return home; 5. Land",
            mission.plan.toString())
    }

    @Test fun theMissionKeepsThePlanRulesAcrossItsStages() {
        refused("Takeoff can only be the first step.", listOf(move("forward", 2)), listOf(takeoff()))
        refused("Land can only be the last step.", listOf(takeoff(), land), listOf(move("forward", 2)))
        refused(Mission.NO_STATUS, listOf(takeoff()), listOf(step(FlightCommand.STATUS)))
        // Heights count from the takeoff across stages: 3 m up 5, then up 3 is 11 m.
        refused("Step 3 would leave the 1–10 m altitude range.", listOf(takeoff(), move("up", 5)), listOf(move("up", 3)))
    }

    @Test fun theSizeLimits() {
        refused(Mission.EMPTY)
        refused(Mission.badStage(1), listOf(takeoff()), emptyList())
        val five = List(5) { move("left", 1) }
        assertEquals(25, Mission(List(5) { five }).plan.steps.size)
        refused(Mission.TOO_MANY_STAGES, *Array(6) { listOf(move("left", 1)) })
        refused(Mission.badStage(0), List(6) { move("left", 1) })
        assertEquals(Mission.MAX_STAGES * 5, Mission.MAX_STEPS)
    }

    @Test fun resumeContinuesFromAStepInTheSameStages() {
        val mission = Mission(listOf(listOf(takeoff(), move("forward", 4)), listOf(move("right", 3), move("left", 1)),
            listOf(land)), checkpoints = true)
        val rest = mission.from(3)
        assertEquals(listOf(listOf("Move left 1 m"), listOf("Land")), rest.stages.map { stage -> stage.map { it.command.description } })
        assertEquals(listOf(0, 1), rest.stageOfStep)
        assertTrue(rest.checkpoints)
        assertEquals(mission.plan.steps, mission.from(0).plan.steps)
        assertFalse(mission.from(2).startsStage(0))
        assertThrows(IllegalArgumentException::class.java) { mission.from(5) }
    }
}
