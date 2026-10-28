package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.Rig
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec A3 in the supervisor: AI plans come only from the in-process AI pipeline and always wait for one CONFIRM. */
class SupervisorAiSourceTest {
    private val rig = Rig()
    private val supervisor = SafetySupervisor(rig.store, PlanValidator(), tickMs = 20)

    @After fun tearDown() {
        supervisor.close()
        rig.close()
    }

    private val takeoff = FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to 3))
    private val land = FlightCommand.of(FlightCommand.LAND)
    private fun plan(source: StepSource, vararg commands: FlightCommand) = FlightPlan(commands.map { PlanStep(it, source) })

    @Test fun theSourceChipAndTheStepSourceAreAi() {
        assertEquals("AI", PlanSource.AI.label)
        assertEquals("ai", StepSource.AI.wireName)
    }

    @Test fun anAiPlanWaitsForConfirmationWithTheAiSource() {
        val result = supervisor.submitAi(plan(StepSource.AI, takeoff, land)).get(2, TimeUnit.SECONDS)
        val pending = (result as SubmitResult.AwaitingConfirmation).pending
        assertEquals(PlanSource.AI, pending.source)
        assertEquals(PlanSource.AI, supervisor.state.source)
        assertEquals(ConfirmResult.Started, supervisor.confirm(pending.token).get(2, TimeUnit.SECONDS))
    }

    @Test fun theOrdinaryEntryRefusesTheAiSourceAndAiSteps() {
        for ((plan, source) in listOf(plan(StepSource.RULES, takeoff) to PlanSource.AI,
            plan(StepSource.AI, takeoff) to PlanSource.TYPED, plan(StepSource.AI, land) to PlanSource.PHONE)) {
            assertEquals(SubmitResult.Rejected(SafetySupervisor.AI_ONLY_IN_PROCESS),
                supervisor.submit(plan, source).get(2, TimeUnit.SECONDS))
        }
        assertNull(supervisor.state.pending)
    }

    @Test fun theAiEntryTakesOnlyAiSteps() {
        assertEquals(SubmitResult.Rejected(SafetySupervisor.AI_ONLY_IN_PROCESS),
            supervisor.submitAi(plan(StepSource.RULES, takeoff)).get(2, TimeUnit.SECONDS))
    }

    @Test fun holdAndStatusNeverRunAtOnceFromTheAi() {
        // A1: HOLD and status run at once only from the rules; an AI single step of either is refused, never run.
        for (command in listOf(FlightCommand.of(FlightCommand.HOLD), FlightCommand.of(FlightCommand.STATUS))) {
            assertEquals(SubmitResult.Rejected(SafetySupervisor.AI_NO_IMMEDIATE),
                supervisor.submitAi(plan(StepSource.AI, command)).get(2, TimeUnit.SECONDS))
        }
        assertEquals(PlanStage.IDLE, supervisor.state.stage)
        assertNull(supervisor.state.stop)
    }

    @Test fun anAiPlanStillPassesThePlanValidator() {
        rig.linkListener = { supervisor.onLinkState(it) }
        rig.connect()
        supervisor.attach(rig.link, FAST_TIMING).get(2, TimeUnit.SECONDS)
        val move = FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to "forward", FlightCommand.DISTANCE to 2))
        val rejected = supervisor.submitAi(plan(StepSource.AI, move)).get(2, TimeUnit.SECONDS) as SubmitResult.Rejected
        assertEquals("The drone is on the ground. Start with a takeoff height, for example: take off to 3 m and fly forward 3 m.",
            rejected.message)
        assertNull(supervisor.state.pending)
    }
}
