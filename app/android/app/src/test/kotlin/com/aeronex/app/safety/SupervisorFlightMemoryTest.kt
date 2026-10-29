package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.memory.FlightJournal
import com.aeronex.app.memory.MemoryRoutes
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.executor.ArduPilotExecutor
import com.aeronex.app.vehicle.executor.ExecutorTiming
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.Rig
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Spec 2026-10-28: the supervisor flies, records and refuses flight-memory steps against the fake ArduCopter. */
class SupervisorFlightMemoryTest {
    private val rig = Rig()
    private val vehicle get() = rig.vehicle
    private val journal = FlightJournal()
    private val supervisor = SafetySupervisor(rig.store, PlanValidator(), tickMs = 20, journal = journal)

    @After fun tearDown() {
        supervisor.close()
        rig.close()
    }

    private fun connect(execution: Boolean = true, timing: ExecutorTiming = FAST_TIMING) {
        rig.linkListener = { supervisor.onLinkState(it) }
        rig.connect()
        supervisor.attach(rig.link, timing).get(2, TimeUnit.SECONDS)
        if (execution) supervisor.setExecution(true).get(2, TimeUnit.SECONDS)
    }

    private fun plan(vararg commands: FlightCommand) = FlightPlan(commands.map { PlanStep(it, StepSource.RULES) })
    private fun move(direction: String, m: Number) =
        FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to m))
    private fun numbered(name: String, n: Int) = FlightCommand.of(name, mapOf(FlightCommand.POINT to n))
    private val takeoff = FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to 3))
    private val returnHome = FlightCommand.of(FlightCommand.RETURN_HOME)
    private val goBack = FlightCommand.of(FlightCommand.GO_BACK)
    private val retrace = FlightCommand.of(FlightCommand.RETRACE)

    private fun submit(plan: FlightPlan) = supervisor.submit(plan, PlanSource.TYPED).get(2, TimeUnit.SECONDS)

    private fun fly(plan: FlightPlan): SupervisorState {
        val pending = (submit(plan) as SubmitResult.AwaitingConfirmation).pending
        assertEquals(ConfirmResult.Started, supervisor.confirm(pending.token).get(2, TimeUnit.SECONDS))
        waitFor("plan end", 10_000) { supervisor.state.stage.let { it == PlanStage.FINISHED || it == PlanStage.FAILED } }
        return supervisor.state
    }

    private fun flies(plan: FlightPlan) {
        val state = fly(plan)
        assertEquals(state.result, PlanStage.FINISHED, state.stage)
        waitFor("still") { vehicle.speedNow() < 0.05 }
    }

    private fun airborne() {
        flies(plan(takeoff))
        waitFor("APP owns GUIDED") { rig.store.snapshot().modeOwner == ModeOwner.APP }
    }

    private fun assertAt(n: Double, e: Double, altitude: Double = 3.0) {
        val p = vehicle.position()
        assertTrue("at ($n, $e, $altitude): ${p.toList()}",
            abs(p[0] - n) < 0.5 && abs(p[1] - e) < 0.5 && abs(-p[2] - altitude) < 0.5)
    }

    private fun trail() = journal.state.trail.map { it.label }

    @Test fun returnHomeFliesBackLevelAndGoBackUndoesIt() {
        connect()
        airborne()
        flies(plan(move("forward", 4), move("right", 3)))
        assertEquals(listOf(FlightJournal.TAKEOFF_LABEL, "Move forward 4 m", "Move right 3 m"), trail())
        flies(plan(returnHome))
        assertAt(0.0, 0.0)
        val frame = vehicle.positionTargets().last()
        assertEquals(ArduPilotExecutor.MAV_FRAME_LOCAL_NED, frame.coordinateFrame)
        assertEquals("Home", trail().last())
        // "Go back" after "return home" returns to where it was.
        flies(plan(goBack))
        assertAt(4.0, 3.0)
        assertEquals(listOf(FlightJournal.TAKEOFF_LABEL, "Move forward 4 m", "Move right 3 m"), trail())
    }

    @Test fun goBackWalksTheTrailOnePositionAtATime() {
        connect()
        airborne()
        flies(plan(move("forward", 4), move("right", 3)))
        flies(plan(goBack))
        assertAt(4.0, 0.0)
        flies(plan(goBack))
        assertAt(0.0, 0.0)
        assertEquals(listOf(FlightJournal.TAKEOFF_LABEL), trail())
        val rejected = submit(plan(goBack)) as SubmitResult.Rejected
        assertEquals("Step 1 — ${MemoryRoutes.NOTHING_TO_GO_BACK}", rejected.message)
    }

    @Test fun retraceFliesTheTrailNewestFirstToTheTakeoffPosition() {
        connect()
        airborne()
        flies(plan(move("forward", 4), move("right", 3), move("up", 2)))
        val before = vehicle.positionTargets().size
        flies(plan(retrace))
        assertAt(0.0, 0.0)
        val legs = vehicle.positionTargets().drop(before).map { Triple(it.x.toDouble(), it.y.toDouble(), -it.z.toDouble()) }
        assertEquals(3, legs.size)
        val expected = listOf(Triple(4.0, 3.0, 3.0), Triple(4.0, 0.0, 3.0), Triple(0.0, 0.0, 3.0))
        for ((leg, want) in legs.zip(expected)) {
            assertTrue("$leg ≈ $want", abs(leg.first - want.first) < 0.5 && abs(leg.second - want.second) < 0.5 &&
                abs(leg.third - want.third) < 0.5)
        }
        assertEquals(listOf(FlightJournal.TAKEOFF_LABEL), trail())
    }

    @Test fun aPointMarkedAloneIsSavedAtOnceAndGoToPointFliesBack() {
        connect()
        airborne()
        flies(plan(move("forward", 4)))
        val marked = (submit(plan(numbered(FlightCommand.MARK_POINT, 1))) as SubmitResult.Immediate).outcome
        assertEquals(StepStatus.COMPLETED, marked.status)
        assertTrue(marked.message, marked.message!!.startsWith("COMPLETED — point 1 saved, 4.0 m from home, 3."))
        assertEquals(PlanStage.FINISHED, supervisor.state.stage)
        assertEquals(setOf(1), journal.state.points.keys)
        flies(plan(move("right", 3), move("up", 1)))
        flies(plan(numbered(FlightCommand.GOTO_POINT, 1)))
        assertAt(4.0, 0.0)
        assertEquals("Point 1", trail().last())
    }

    @Test fun aPointMarkedInAPlanIsThereForALaterStep() {
        connect()
        airborne()
        val state = fly(plan(numbered(FlightCommand.MARK_POINT, 2), move("forward", 3), numbered(FlightCommand.GOTO_POINT, 2)))
        assertEquals(state.result, listOf(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.COMPLETED),
            state.outcomes.map { it.status })
        waitFor("still") { vehicle.speedNow() < 0.05 }
        assertAt(0.0, 0.0)
    }

    @Test fun theLaunchPointCanBeMarkedOnTheGround() {
        connect()
        val marked = (submit(plan(numbered(FlightCommand.MARK_POINT, 9))) as SubmitResult.Immediate).outcome
        assertEquals(marked.message, StepStatus.COMPLETED, marked.status)
        assertEquals(Ned(0.0, 0.0, 0.0), journal.state.points.getValue(9).position)
    }

    @Test fun aSecondTakeoffFromElsewhereKeepsThePointsAndMovesHome() {
        // Review R2: ArduCopter sets home where it arms. Taken for a new frame, that forgot every point mid-plan, and
        // "go to point 1" after the takeoff failed with the vehicle in the air.
        connect()
        airborne()
        flies(plan(move("forward", 4)))
        val marked = (submit(plan(numbered(FlightCommand.MARK_POINT, 1))) as SubmitResult.Immediate).outcome
        assertEquals(marked.message, StepStatus.COMPLETED, marked.status)
        flies(plan(move("forward", 5), FlightCommand.of(FlightCommand.LAND)))
        waitFor("landed and disarmed", 10_000) {
            rig.store.snapshot().let { it.armed == false && it.landedState == VehicleState.LANDED_ON_GROUND }
        }
        flies(plan(takeoff, numbered(FlightCommand.GOTO_POINT, 1)))
        assertAt(4.0, 0.0)
        val home = journal.state.home!!
        assertTrue("home moved to the second takeoff: $home", abs(home.x - 9.0) < 0.5 && abs(home.y) < 0.5)
        assertEquals(setOf(1), journal.state.points.keys)
    }

    @Test fun aRebootElsewhereForgetsThePointsOfTheOldFrame() {
        connect()
        val marked = (submit(plan(numbered(FlightCommand.MARK_POINT, 9))) as SubmitResult.Immediate).outcome
        assertEquals(marked.message, StepStatus.COMPLETED, marked.status)
        val before = rig.store.snapshot().origin!!
        // Home is (0, 0, 0) in both frames: only the origin shows the vehicle is somewhere else.
        vehicle.rebootAt(40.0, 0.0)
        waitFor("the new frame") { rig.store.snapshot().origin.let { it != null && it.distanceTo(before) > 30 } }
        val rejected = submit(plan(takeoff, numbered(FlightCommand.GOTO_POINT, 9))) as SubmitResult.Rejected
        assertEquals("Step 2 — ${MemoryRoutes.noPoint(9)}", rejected.message)
        assertEquals(FlightJournal.FRAME_CHANGED, journal.state.notice)
    }

    @Test fun aLongLegBetweenPointsIsNotCutOffByTheMoveDeadline() {
        // Review R8: with a 2 s deadline here (30 s in the app), a 20 m leg at 5 m/s takes 4 s; it gets 10 s more.
        connect(timing = FAST_TIMING.copy(stepDeadlineMs = 2_000))
        vehicle.speed = 30.0
        airborne()
        flies(plan(move("forward", 10)))
        submit(plan(numbered(FlightCommand.MARK_POINT, 1)))
        flies(plan(move("backward", 10), move("backward", 10)))
        assertAt(-10.0, 0.0)
        vehicle.speed = 5.0
        flies(plan(numbered(FlightCommand.GOTO_POINT, 1)))
        assertAt(10.0, 0.0)
    }

    @Test fun aDryRunMarksNothing() {
        connect(execution = false)
        val outcome = (submit(plan(numbered(FlightCommand.MARK_POINT, 1))) as SubmitResult.Immediate).outcome
        assertEquals(StepStatus.NOT_SENT, outcome.status)
        assertTrue(journal.state.points.isEmpty())
    }

    @Test fun aFlightWithoutATrailStartsItAtTheFirstMove() {
        connect()
        airborne()
        journal.clear() // as for a flight another ground station took off
        flies(plan(move("forward", 3)))
        assertEquals(listOf(MemoryRoutes.START_LABEL, "Move forward 3 m"), trail())
        flies(plan(goBack))
        assertAt(0.0, 0.0)
    }

    @Test fun everyFinishedPlanIsRecordedOnceWithItsOutcomes() {
        connect()
        airborne()
        flies(plan(move("forward", 2)))
        val history = journal.state.history
        assertEquals(listOf("takeoff", "move_relative"), history.map { it.name })
        assertEquals(listOf("COMPLETED", "COMPLETED"), history.map { it.outcome })
        assertEquals(listOf("typed", "typed"), history.map { it.source })
        assertEquals(2, history.map { it.planId }.distinct().size)
        assertEquals(mapOf<String, Any>(FlightCommand.DIRECTION to "forward", FlightCommand.DISTANCE to 2), history.last().arguments)
    }

    @Test fun aPointForgottenAfterThePlanWasShownFailsItBeforeAnythingFlies() {
        connect()
        airborne()
        journal.markPoint(3, Ned(2.0, 0.0, -3.0))
        val pending = (submit(plan(move("forward", 2), numbered(FlightCommand.GOTO_POINT, 3))) as SubmitResult.AwaitingConfirmation).pending
        journal.clear() // e.g. the operator cleared the memory while the plan was shown
        val frames = vehicle.positionTargets().size
        assertEquals(ConfirmResult.Started, supervisor.confirm(pending.token).get(2, TimeUnit.SECONDS))
        waitFor("plan end", 10_000) { supervisor.state.stage == PlanStage.FAILED }
        // Every step start checks the rest of the plan again, so the move never flies.
        assertEquals(listOf(StepStatus.REJECTED, StepStatus.SKIPPED), supervisor.state.outcomes.map { it.status })
        assertEquals("REJECTED — nothing sent: Step 2 — ${MemoryRoutes.noPoint(3)}", supervisor.state.outcomes[0].message)
        assertEquals(frames, vehicle.positionTargets().size)
    }

    @Test fun theAiNeverUsesTheFlightMemory() {
        for (command in listOf(returnHome, goBack, retrace, numbered(FlightCommand.GOTO_POINT, 1), numbered(FlightCommand.MARK_POINT, 1))) {
            val plan = FlightPlan(listOf(PlanStep(command, StepSource.AI)))
            assertEquals(SubmitResult.Rejected(SafetySupervisor.AI_NO_MEMORY),
                supervisor.submitAi(plan).get(2, TimeUnit.SECONDS))
        }
    }

    @Test fun aShownMemoryFlightStreamsTheMoveEchoButSendsNothing() {
        connect()
        airborne()
        flies(plan(move("forward", 3)))
        val frames = vehicle.positionTargets().size
        submit(plan(returnHome))
        waitFor("streaming while shown") { vehicle.positionTargetIntervals().lastOrNull()?.let { it > 0 } == true }
        Thread.sleep(100)
        assertEquals(frames, vehicle.positionTargets().size)
    }
}
