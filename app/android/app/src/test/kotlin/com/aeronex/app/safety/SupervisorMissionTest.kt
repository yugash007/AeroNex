package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.memory.FlightJournal
import com.aeronex.app.memory.SavedStep
import com.aeronex.app.memory.SavedStage
import com.aeronex.app.memory.SavedMission
import com.aeronex.app.mission.Mission
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.Rig
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Spec 2026-10-28 "Missions" in the supervisor, flown against the fake ArduCopter. */
class SupervisorMissionTest {
    private val rig = Rig()
    private val vehicle get() = rig.vehicle
    private val journal = FlightJournal()
    private val supervisor = SafetySupervisor(rig.store, PlanValidator(), tickMs = 20, journal = journal)

    @After fun tearDown() {
        assertTrue("never a forced disarm", vehicle.commands(400).none { it.param2 != 0f })
        supervisor.close()
        rig.close()
    }

    private fun <T> await(future: java.util.concurrent.Future<T>): T = future.get(2, TimeUnit.SECONDS)

    private fun connect(execution: Boolean = true) {
        rig.linkListener = { supervisor.onLinkState(it) }
        rig.connect()
        await(supervisor.attach(rig.link, FAST_TIMING))
        if (execution) await(supervisor.setExecution(true))
    }

    private fun plan(vararg commands: FlightCommand) = FlightPlan(commands.map { PlanStep(it, StepSource.RULES) })
    private fun move(direction: String, m: Number) =
        FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to m))
    private fun takeoff(m: Int = 3) = FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to m))
    private val land = FlightCommand.of(FlightCommand.LAND)
    private val hold = FlightCommand.of(FlightCommand.HOLD)
    private val returnHome = FlightCommand.of(FlightCommand.RETURN_HOME)

    private fun submit(plan: FlightPlan) = await(supervisor.submit(plan, PlanSource.TYPED))
    private fun mission() = supervisor.state.mission
    private fun statuses() = supervisor.state.outcomes.map { it.status }

    private fun ended() = supervisor.state.stage.let { it == PlanStage.FINISHED || it == PlanStage.FAILED }

    /** Builds [stages] while building is on, then starts and confirms the mission. */
    private fun build(vararg stages: FlightPlan) {
        await(supervisor.setMissionBuilding(true))
        for ((i, stage) in stages.withIndex()) assertEquals(SubmitResult.AddedToMission(i + 1), submit(stage))
    }

    private fun startAndConfirm(): PendingPlan {
        val pending = (await(supervisor.startMission()) as SubmitResult.AwaitingConfirmation).pending
        assertEquals(ConfirmResult.Started, await(supervisor.confirm(pending.token)))
        return pending
    }

    private fun assertAt(n: Double, e: Double, altitude: Double = 3.0) {
        val p = vehicle.position()
        assertTrue("at ($n, $e, $altitude): ${p.toList()}",
            abs(p[0] - n) < 0.5 && abs(p[1] - e) < 0.5 && abs(-p[2] - altitude) < 0.5)
    }

    @Test fun aMissionIsBuiltOnTheGroundStageByStageAndFlownAfterOneConfirm() {
        connect()
        build(plan(takeoff(), move("forward", 4)), plan(move("right", 3)), plan(returnHome, land))
        // Nothing is shown or sent while building: each plan became a stage.
        assertNull(supervisor.state.pending)
        assertTrue(vehicle.vehicleCommands().isEmpty())
        assertEquals(3, mission().stages.size)
        assertEquals(SafetySupervisor.stageAdded(3), supervisor.state.notice)
        val pending = startAndConfirm()
        assertEquals(PlanSource.MISSION, pending.source)
        assertEquals(listOf(0, 0, 1, 2, 2), pending.mission!!.stageOfStep)
        waitFor("mission end", 20_000) { ended() }
        assertEquals(supervisor.state.result, PlanStage.FINISHED, supervisor.state.stage)
        assertEquals(List(5) { StepStatus.COMPLETED }, statuses())
        assertEquals(listOf(0, 0, 1, 2, 2), mission().stageOfStep)
        // The confirm cleared the stages built; building ended with the start.
        assertTrue(mission().stages.isEmpty())
        assertTrue(!mission().building)
        // The history keeps the stages, so "run again" can show it as a mission again.
        assertEquals(listOf(0, 0, 1, 2, 2), journal.state.history.map { it.stage })
    }

    @Test fun stopHoldAndStatusStillRunAtOnceWhileBuilding() {
        connect()
        await(supervisor.setMissionBuilding(true))
        assertTrue(submit(plan(FlightCommand.of(FlightCommand.STATUS))) is SubmitResult.Immediate)
        assertTrue(submit(plan(hold)) is SubmitResult.Immediate)
        assertTrue(mission().stages.isEmpty())
        // A lone "mark point n" is a stage while building: part of the mission.
        assertEquals(SubmitResult.AddedToMission(1),
            submit(plan(FlightCommand.of(FlightCommand.MARK_POINT, mapOf(FlightCommand.POINT to 1)))))
    }

    @Test fun aStageThatBreaksTheMissionRulesIsRefusedAndTheRestKept() {
        connect()
        build(plan(takeoff(), move("forward", 2)))
        assertEquals(SubmitResult.Rejected("Takeoff can only be the first step."), submit(plan(takeoff())))
        assertEquals(1, mission().stages.size)
        await(supervisor.removeStage(0))
        assertTrue(mission().stages.isEmpty())
        assertEquals(SubmitResult.Rejected(Mission.EMPTY), await(supervisor.startMission()))
    }

    @Test fun aShownPlanCanBecomeTheFirstStage() {
        connect()
        val shown = (submit(plan(takeoff(), move("forward", 2))) as SubmitResult.AwaitingConfirmation).pending
        assertEquals(SubmitResult.AddedToMission(1), await(supervisor.addStage(shown.token)))
        assertNull(supervisor.state.pending)
        assertTrue(mission().building)
        assertEquals(SubmitResult.Rejected(SafetySupervisor.NO_PLAN), await(supervisor.addStage(shown.token)))
    }

    @Test fun theWholeMissionIsValidatedWhenItStarts() {
        connect()
        // Each stage is fine on its own; together they go 31 m from home.
        build(plan(takeoff(), move("forward", 10), move("forward", 10)), plan(move("forward", 10), move("forward", 1)))
        assertEquals(SubmitResult.Rejected("Step 5 would go more than 30 m from home."), await(supervisor.startMission()))
        // Building goes on, with the stages, so one can be removed.
        assertTrue(mission().building)
        assertEquals(2, mission().stages.size)
    }

    @Test fun checkpointsHoldBetweenStagesUntilTheOperatorContinues() {
        connect()
        await(supervisor.setCheckpoints(true))
        build(plan(takeoff(), move("forward", 3)), plan(move("right", 2), land))
        startAndConfirm()
        waitFor("checkpoint", 15_000) { mission().checkpoint != null }
        val checkpoint = mission().checkpoint!!
        assertEquals(Checkpoint(checkpoint.token, 1, 2), checkpoint)
        assertEquals(listOf(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.PENDING, StepStatus.PENDING), statuses())
        val sent = vehicle.vehicleCommands().size
        Thread.sleep(400)
        assertEquals("nothing is sent while holding", sent, vehicle.vehicleCommands().size)
        assertAt(3.0, 0.0)
        assertEquals(ConfirmResult.Rejected(SafetySupervisor.TOKEN_MISMATCH), await(supervisor.continueMission("other")))
        assertEquals(ConfirmResult.Started, await(supervisor.continueMission(checkpoint.token)))
        waitFor("mission end", 15_000) { ended() }
        assertEquals(supervisor.state.result, List(4) { StepStatus.COMPLETED }, statuses())
        assertEquals(ConfirmResult.Rejected(SafetySupervisor.NO_CHECKPOINT), await(supervisor.continueMission(checkpoint.token)))
    }

    @Test fun aStopAtACheckpointEndsTheMissionAndItCanBeResumed() {
        connect()
        await(supervisor.setCheckpoints(true))
        build(plan(takeoff(), move("forward", 3)), plan(move("right", 2)), plan(returnHome))
        startAndConfirm()
        waitFor("checkpoint", 15_000) { mission().checkpoint != null }
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("ended") { supervisor.state.stage == PlanStage.FAILED }
        // Nothing of the next stage was sent: REJECTED, not UNKNOWN.
        assertEquals(listOf(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.REJECTED, StepStatus.SKIPPED), statuses())
        assertEquals(Resume(2, 2, atCheckpoint = true), mission().resume)
        // Review R5: nothing was interrupted, so there is no step to skip (it would drop one never flown).
        assertEquals(SubmitResult.Rejected(SafetySupervisor.NOTHING_INTERRUPTED), await(supervisor.resumeMission(true)))
        // Resume shows the rest, in its stages, for one CONFIRM (once the STOP's heartbeat check has ended).
        val pending = resumeWhenSettled(skipInterrupted = false)
        assertEquals(listOf("Move right 2 m", "Return home"), pending.plan.steps.map { it.command.description })
        assertEquals(listOf(0, 1), pending.mission!!.stageOfStep)
        assertEquals(PlanSource.MISSION, pending.source)
    }

    /** Resumes as soon as the STOP lane let go (STOP_IN_PROGRESS until then). */
    private fun resumeWhenSettled(skipInterrupted: Boolean): PendingPlan {
        var result: SubmitResult? = null
        waitFor("resume shown after the STOP settled", 5_000) {
            result = await(supervisor.resumeMission(skipInterrupted))
            result is SubmitResult.AwaitingConfirmation
        }
        return (result as SubmitResult.AwaitingConfirmation).pending
    }

    @Test fun aMissionInterruptedMidMoveResumesFromThatStepOrTheNext() {
        connect()
        build(plan(takeoff(), move("forward", 2)), plan(move("forward", 8), move("left", 2)))
        vehicle.speed = 2.0
        startAndConfirm()
        waitFor("long move running", 15_000) { supervisor.state.outcomes.getOrNull(2)?.status == StepStatus.RUNNING &&
            vehicle.speedNow() > 1.0 }
        supervisor.cancel() // STOP while running
        waitFor("ended") { supervisor.state.stage == PlanStage.FAILED }
        assertEquals(StepStatus.UNKNOWN, supervisor.state.outcomes[2].status)
        assertEquals(Resume(2, 2), mission().resume)
        // Skipping the interrupted move: only what came after it.
        assertEquals(listOf("Move left 2 m"), resumeWhenSettled(skipInterrupted = true).plan.steps.map { it.command.description })
        // Or from it: the move runs again in full from where the vehicle is now.
        assertEquals(listOf("Move forward 8 m", "Move left 2 m"),
            resumeWhenSettled(skipInterrupted = false).plan.steps.map { it.command.description })
    }

    @Test fun theCheckpointSettingHoldsForEveryMissionShown() {
        // Review R7: "run again" rebuilt a recorded mission without checkpoints, whatever the operator had chosen.
        connect(execution = false)
        val recorded = Mission(listOf(listOf(PlanStep(takeoff(), StepSource.RULES)), listOf(PlanStep(land, StepSource.RULES))))
        await(supervisor.setCheckpoints(true))
        val shown = (await(supervisor.submitMission(recorded)) as SubmitResult.AwaitingConfirmation).pending
        assertTrue(shown.mission!!.checkpoints)
        await(supervisor.setCheckpoints(false))
        val again = (await(supervisor.submitMission(recorded.withCheckpoints(true))) as SubmitResult.AwaitingConfirmation).pending
        assertTrue(!again.mission!!.checkpoints)
    }

    @Test fun aSavedMissionLoadsIntoTheStagesAndStartsLikeOneBuiltByHand() {
        connect(execution = false)
        assertEquals(SafetySupervisor.NOTHING_TO_SAVE, await(supervisor.saveMission("Perimeter")))
        build(plan(takeoff(), move("forward", 2)), plan(land))
        assertEquals(SafetySupervisor.BAD_MISSION_NAME, await(supervisor.saveMission("   ")))
        assertNull(await(supervisor.saveMission("  Perimeter ")))
        assertEquals(SafetySupervisor.missionSaved("Perimeter"), supervisor.state.notice)
        assertEquals(listOf("typed", "typed"), journal.mission("perimeter")!!.stages.map { it.source })
        await(supervisor.clearMission())
        assertTrue(mission().stages.isEmpty())
        assertEquals(SafetySupervisor.noSavedMission("Survey"), await(supervisor.loadMission(" Survey")))
        assertNull(await(supervisor.loadMission("PERIMETER")))
        assertEquals(SafetySupervisor.missionLoaded("Perimeter", 2), supervisor.state.notice)
        assertEquals(listOf(listOf("Take off to 3 m", "Move forward 2 m"), listOf("Land")),
            mission().stages.map { stage -> stage.plan.steps.map { it.command.description } })
        assertEquals(listOf(PlanSource.TYPED, PlanSource.TYPED), mission().stages.map { it.source })
        val shown = (await(supervisor.startMission()) as SubmitResult.AwaitingConfirmation).pending
        assertEquals(listOf(0, 0, 1), shown.mission!!.stageOfStep)
    }

    @Test fun aSavedMissionThatNoLongerParsesIsRefusedWithItsReason() {
        connect(execution = false)
        journal.saveMission(SavedMission("Old", listOf(SavedStage("typed", listOf(SavedStep("barrel_roll", emptyMap(), "Barrel roll"))))))
        val refused = await(supervisor.loadMission("old"))!!
        assertTrue(refused, refused.startsWith("\"Old\" can no longer be flown: "))
        assertTrue(mission().stages.isEmpty())
    }

    @Test fun aDryRunMissionReportsEveryStepNotSent() {
        connect(execution = false)
        build(plan(takeoff(), move("forward", 2)), plan(land))
        startAndConfirm()
        waitFor("finished") { supervisor.state.stage == PlanStage.FINISHED }
        assertEquals(List(3) { StepStatus.NOT_SENT }, statuses())
        assertTrue(vehicle.vehicleCommands().isEmpty())
        assertEquals(listOf(0, 0, 1), journal.state.history.map { it.stage })
    }

    @Test fun clearMissionForgetsTheStagesAndTheMissionToResume() {
        connect()
        build(plan(takeoff()), plan(land))
        await(supervisor.clearMission())
        assertTrue(mission().stages.isEmpty())
        assertTrue(!mission().building)
        assertNull(mission().resume)
        assertEquals(SubmitResult.Rejected(SafetySupervisor.NOTHING_TO_RESUME), await(supervisor.resumeMission(false)))
    }

    @Test fun clientsCannotNameTheMissionSource() {
        assertEquals(SubmitResult.Rejected(SafetySupervisor.MISSION_ONLY_FROM_STAGES),
            await(supervisor.submit(plan(land), PlanSource.MISSION)))
        assertTrue(PlanSource.MISSION !in PlanSource.fromChannel)
    }
}
