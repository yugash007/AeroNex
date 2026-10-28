package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.executor.ArduPilotExecutor
import com.aeronex.app.vehicle.executor.ExecutorTiming
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.Rig
import com.aeronex.app.vehicle.executor.StopAction
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetySupervisorTest {
    private val rig = Rig()
    private val vehicle get() = rig.vehicle
    private val trace = Trace()
    private var supervisor = newSupervisor()

    private fun newSupervisor(validator: PlanValidator = PlanValidator(), ttlMs: Long = SafetySupervisor.PENDING_TTL_MS) =
        SafetySupervisor(rig.store, validator, trace, pendingTtlMs = ttlMs, tickMs = 20)

    private fun connect(timing: ExecutorTiming = FAST_TIMING, execution: Boolean = true) {
        rig.linkListener = { supervisor.onLinkState(it) }
        rig.connect()
        supervisor.attach(rig.link, timing).get(2, TimeUnit.SECONDS)
        if (execution) supervisor.setExecution(true).get(2, TimeUnit.SECONDS)
    }

    @After fun tearDown() {
        assertTrue("never a forced disarm", vehicle.commands(400).none { it.param2 != 0f })
        supervisor.close()
        rig.close()
    }

    private fun plan(vararg commands: FlightCommand) = FlightPlan(commands.map { PlanStep(it, StepSource.RULES) })
    private fun takeoff(m: Int = 3) = FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to m))
    private fun move(direction: String, m: Number) =
        FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to m))
    private val land = FlightCommand.of(FlightCommand.LAND)
    private val hold = FlightCommand.of(FlightCommand.HOLD)
    private val status = FlightCommand.of(FlightCommand.STATUS)

    private fun submit(plan: FlightPlan, source: PlanSource = PlanSource.TYPED) =
        supervisor.submit(plan, source).get(2, TimeUnit.SECONDS)

    private fun confirm(token: String) = supervisor.confirm(token).get(2, TimeUnit.SECONDS)

    private fun token(result: SubmitResult) = (result as SubmitResult.AwaitingConfirmation).pending.token

    /** Submits, confirms and waits for the plan to end. */
    private fun fly(plan: FlightPlan, timeoutMs: Long = 10_000): SupervisorState {
        assertEquals(ConfirmResult.Started, confirm(token(submit(plan))))
        waitFor("plan end", timeoutMs) { supervisor.state.stage.let { it == PlanStage.FINISHED || it == PlanStage.FAILED } }
        return supervisor.state
    }

    private fun airborne() {
        val state = fly(plan(takeoff()))
        assertEquals(PlanStage.FINISHED, state.stage)
        waitFor("APP owns GUIDED") { rig.store.snapshot().modeOwner == ModeOwner.APP }
    }

    /** Starts a long move and waits until the vehicle is moving. */
    private fun startLongMove(distance: Int = 8) {
        vehicle.speed = 2.0
        assertEquals(ConfirmResult.Started, confirm(token(submit(plan(move("forward", distance))))))
        waitFor("moving") { vehicle.speedNow() > 1.0 }
    }

    private fun statuses() = supervisor.state.outcomes.map { it.status }

    /** In arrival order: "on"/"off" for each POSITION_TARGET_LOCAL_NED interval request, "move" for each move frame. */
    private fun streamEvents(): List<String> = vehicle.received.map { it.message }.mapNotNull { m ->
        when {
            m is SetPositionTargetLocalNed -> "move"
            m is CommandLong && m.command == 511 && m.param1.toInt() == PositionTargetLocalNed.messageId ->
                if (m.param2 > 0) "on" else "off"
            else -> null
        }
    }

    /** The STEP_OUTCOME trace points: (step, status, message). */
    private fun tracedOutcomes() = trace.events().filter { it.name == TracePoint.STEP_OUTCOME }
        .map { Triple(it.fields["step"], it.fields["status"], it.fields["message"]) }

    @Test fun constants() {
        assertEquals(15_000L, SafetySupervisor.PENDING_TTL_MS)
    }

    @Test fun motionPlansNeedOneConfirmationThatWorksOnce() {
        connect()
        val result = submit(plan(takeoff(), land), PlanSource.PHONE) as SubmitResult.AwaitingConfirmation
        val pending = result.pending
        assertEquals(PlanSource.PHONE, pending.source)
        val left = pending.expiresAtMs - System.nanoTime() / 1_000_000
        assertTrue("about 15 s: $left", left in 14_000..15_000)
        assertEquals(PlanStage.AWAITING_CONFIRMATION, supervisor.state.stage)
        assertEquals(pending, supervisor.state.pending)
        Thread.sleep(100)
        assertTrue("nothing sent before confirmation", vehicle.vehicleCommands().isEmpty())
        assertEquals(ConfirmResult.Rejected(SafetySupervisor.TOKEN_MISMATCH), confirm("not-the-token"))
        assertEquals(ConfirmResult.Started, confirm(pending.token))
        assertEquals(ConfirmResult.Rejected(SafetySupervisor.NO_PLAN), confirm(pending.token))
        waitFor("finished", 10_000) { supervisor.state.stage == PlanStage.FINISHED }
        assertEquals(listOf(
            "COMPLETED — confirmed by simulator telemetry: takeoff",
            "COMPLETED — confirmed by simulator telemetry: land",
        ), supervisor.state.outcomes.map { it.message })
        assertEquals("1. Take off to 3 m — COMPLETED — confirmed by simulator telemetry: takeoff\n" +
            "2. Land — COMPLETED — confirmed by simulator telemetry: land", supervisor.state.result)
        assertTrue(trace.durationsMs(TracePoint.CONFIRM, TracePoint.ACK).isNotEmpty())
        assertTrue(trace.durationsMs(TracePoint.CONFIRM, TracePoint.TX).isNotEmpty())
    }

    @Test fun aPendingPlanExpires() {
        supervisor.close()
        supervisor = newSupervisor(ttlMs = 300)
        connect()
        val token = token(submit(plan(takeoff())))
        waitFor("expired") { supervisor.state.pending == null }
        assertEquals(SafetySupervisor.EXPIRED, supervisor.state.notice)
        assertEquals(ConfirmResult.Rejected(SafetySupervisor.NO_PLAN), confirm(token))
        assertTrue(vehicle.vehicleCommands().isEmpty())
    }

    @Test fun pendingIsInvalidatedByNewInputCancelStopBackgroundingAndExecutionToggle() {
        connect()
        val invalidators = listOf<() -> Unit>(
            { submit(plan(takeoff(4))) },
            { supervisor.cancel() },
            { supervisor.stop(StopTrigger.BUTTON) },
            { supervisor.invalidatePending("App in background.") },
            { supervisor.setExecution(false).get(); supervisor.setExecution(true).get() },
        )
        for (invalidate in invalidators) {
            val token = token(submit(plan(takeoff())))
            invalidate()
            waitFor("invalidated") { supervisor.state.pending?.token != token }
            assertTrue(confirm(token) is ConfirmResult.Rejected)
        }
        assertTrue(vehicle.vehicleCommands().isEmpty())
    }

    @Test fun pendingIsInvalidatedByDisconnectAndByAReadinessChange() {
        connect()
        var token = token(submit(plan(takeoff())))
        vehicle.gpsDenied = true // the EKF loses its position: readiness changed
        waitFor("invalidated by readiness") { supervisor.state.pending == null }
        assertTrue(confirm(token) is ConfirmResult.Rejected)
        vehicle.gpsDenied = false
        waitFor("ready again") { rig.store.snapshot().positionReady }
        token = token(submit(plan(takeoff())))
        supervisor.detach().get()
        assertNull(supervisor.state.pending)
        assertTrue(confirm(token) is ConfirmResult.Rejected)
        assertTrue(vehicle.vehicleCommands().isEmpty())
    }

    @Test fun dryRunNeverTouchesTheLink() {
        connect(execution = false)
        val state = fly(plan(takeoff(), move("forward", 2), land))
        assertEquals(PlanStage.FINISHED, state.stage)
        assertEquals(listOf(
            "NOT SENT — dry run: takeoff({altitude_m: 3})",
            "NOT SENT — dry run: move_relative({direction: forward, distance_m: 2})",
            "NOT SENT — dry run: land({})",
        ), state.outcomes.map { it.message })
        assertEquals(StepStatus.NOT_SENT, state.outcomes.first().status)
        assertEquals(state.outcomes.mapIndexed { i, o -> Triple(i + 1, StepStatus.NOT_SENT, o.message) }, tracedOutcomes())
        assertEquals(PlanStage.FINISHED, trace.events().last { it.name == TracePoint.PLAN_END }.fields["stage"])
        val hold = submit(plan(hold)) as SubmitResult.Immediate
        assertEquals("NOT SENT — dry run: hold({})", hold.outcome.message)
        Thread.sleep(100)
        assertTrue(vehicle.vehicleCommands().isEmpty())
        assertTrue("a dry run never asks for the echo stream", vehicle.positionTargetIntervals().none { it > 0 })
    }

    @Test fun theEchoStreamRunsFromAMovePlansSubmitThroughItsLastMove() {
        connect()
        airborne()
        val before = streamEvents().size
        val token = token(submit(plan(move("forward", 2), move("left", 2), land)))
        // Already running when the operator confirms, so the first move's echo is the next sample (spec 5 M2).
        waitFor("stream requested while the plan waits for confirmation") { streamEvents().drop(before) == listOf("on") }
        assertEquals(ConfirmResult.Started, confirm(token))
        waitFor("plan end", 10_000) { supervisor.state.stage == PlanStage.FINISHED }
        assertEquals(listOf(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.COMPLETED), statuses())
        waitFor("stream off after the last move") { streamEvents().drop(before).lastOrNull() == "off" }
        Thread.sleep(200)
        assertEquals("kept on between the moves, off once after the last", listOf("on", "move", "move", "off"),
            streamEvents().drop(before))
    }

    @Test fun theEchoStreamStopsWhenTheMovePlanIsDroppedOrStopped() {
        connect()
        airborne()
        var before = streamEvents().size
        submit(plan(move("forward", 1)))
        waitFor("on") { streamEvents().drop(before) == listOf("on") }
        supervisor.cancel()
        waitFor("off once the plan is dropped") { streamEvents().drop(before) == listOf("on", "off") }
        before = streamEvents().size
        startLongMove()
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("STOP confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        waitFor("off after STOP") { streamEvents().drop(before) == listOf("on", "move", "off") }
        waitFor("STOP checked") { trace.events().any { it.name == TracePoint.STOP_VERIFIED } }
        before = streamEvents().size
        assertTrue(submit(plan(land)) is SubmitResult.AwaitingConfirmation)
        Thread.sleep(200)
        assertEquals("a plan without a move does not stream", emptyList<String>(), streamEvents().drop(before))
    }

    @Test fun theEchoStreamStopsWhenTheMovePlanExpires() {
        supervisor.close()
        supervisor = newSupervisor(ttlMs = 400)
        connect()
        airborne()
        val before = streamEvents().size
        submit(plan(move("right", 1)))
        waitFor("on") { streamEvents().drop(before) == listOf("on") }
        waitFor("expired") { supervisor.state.notice == SafetySupervisor.EXPIRED }
        waitFor("off") { streamEvents().drop(before) == listOf("on", "off") }
    }

    @Test fun dryRunWorksWithoutAnyLink() {
        val state = fly(plan(takeoff(), land))
        assertEquals(listOf(StepStatus.NOT_SENT, StepStatus.NOT_SENT), state.outcomes.map { it.status })
        assertFalse(state.linked)
    }

    @Test fun takeoffMoveLandIsConfirmedByTelemetry() {
        connect()
        val state = fly(plan(takeoff(), move("forward", 2), land))
        assertEquals(state.result, PlanStage.FINISHED, state.stage)
        assertEquals(listOf(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.COMPLETED), statuses())
        assertEquals(1, vehicle.positionTargets().size)
        assertEquals(2.0, vehicle.position()[0], 0.5)
        assertTrue(trace.durationsMs(TracePoint.MOVE_TX, TracePoint.ECHO).isNotEmpty())
    }

    @Test fun statusAndHoldRunWithoutConfirmation() {
        connect()
        val report = submit(plan(status)) as SubmitResult.Immediate
        assertEquals(StepStatus.COMPLETED, report.outcome.status)
        assertEquals("COMPLETED — Connected · Disarmed · On ground · Stabilize · Alt 0.0 m · Battery 90% · Position ready",
            report.outcome.message)
        airborne()
        val held = submit(plan(hold)) as SubmitResult.Immediate
        assertNull(supervisor.state.pending)
        waitFor("hold confirmed") { supervisor.state.outcomes.singleOrNull()?.status == StepStatus.COMPLETED }
        assertEquals("COMPLETED — confirmed by ACK: hold", supervisor.state.outcomes.single().message)
        assertTrue(held.outcome.status == StepStatus.RUNNING || held.outcome.status == StepStatus.COMPLETED)
        assertEquals(CopterMode.BRAKE, vehicle.modeCommands().last())
        assertEquals(StopPhase.CONFIRMED, supervisor.state.stop!!.phase)
    }

    @Test fun motionPlansAreRejectedOnTheGroundWithTheValidatorMessage() {
        connect()
        val rejected = submit(plan(move("forward", 1))) as SubmitResult.Rejected
        assertEquals("The drone is on the ground. Start with a takeoff height, for example: take off to 3 m and fly forward 3 m.",
            rejected.message)
        assertNull(supervisor.state.pending)
    }

    @Test fun stopMidMoveBrakesAtOnceAndTheRunningStepIsUnknown() {
        connect()
        airborne()
        startLongMove()
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("STOP confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        val state = supervisor.state
        assertEquals(StopAction.BRAKE, state.stop!!.action)
        assertEquals(StopPolicy.confirmedMessage(StopAction.BRAKE), state.stop.message)
        assertEquals(PlanStage.FAILED, state.stage)
        assertEquals(StepStatus.UNKNOWN, state.outcomes.single().status)
        assertEquals("UNKNOWN — " + SafetySupervisor.INTERRUPTED_BY_STOP, state.outcomes.single().message)
        assertEquals(CopterMode.BRAKE, vehicle.mode)
        waitFor("stopped", 3_000) { vehicle.speedNow() < 0.3 }
        assertEquals(1, vehicle.positionTargets().size)
        assertTrue(trace.durationsMs(TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK).single() < 1_000)
        waitFor("POSITION_TARGET streaming off") { vehicle.positionTargetIntervals().last() == -1L }
    }

    @Test fun cancelDuringExecutionIsStopAndNeverDisconnects() {
        connect()
        airborne()
        startLongMove()
        supervisor.cancel()
        waitFor("STOP confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        assertEquals(StopTrigger.CANCEL, supervisor.state.stop!!.trigger)
        assertEquals(CopterMode.BRAKE, vehicle.mode)
        assertTrue(supervisor.state.linked)
        assertTrue(rig.link.stats.up)
        assertEquals(StepStatus.UNKNOWN, supervisor.state.outcomes.single().status)
    }

    @Test fun cancelWithoutAPlanRunningSendsNothing() {
        connect()
        airborne()
        val before = vehicle.vehicleCommands().size
        supervisor.cancel()
        Thread.sleep(200)
        assertEquals(before, vehicle.vehicleCommands().size)
        assertNull(supervisor.state.stop)
    }

    @Test fun stopWhileArmedOnTheGroundDisarmsAndTheTakeoffIsNeverSentAfterIt() {
        connect()
        vehicle.lose[22] = AtomicInteger(1) // the first NAV_TAKEOFF is lost, so a re-send would be due
        confirm(token(submit(plan(takeoff()))))
        waitFor("armed") { vehicle.armed && vehicle.commands(22).isNotEmpty() }
        supervisor.stop(StopTrigger.VOLUME_DOWN)
        waitFor("STOP confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        assertEquals(StopAction.DISARM, supervisor.state.stop!!.action)
        assertFalse(vehicle.armed)
        Thread.sleep(3 * FAST_TIMING.commandRetryMs)
        assertEquals("no NAV_TAKEOFF after STOP", 1, vehicle.commands(22).size)
        val disarm = vehicle.commands(400).last()
        assertEquals(listOf(0f, 0f), listOf(disarm.param1, disarm.param2))
    }

    @Test fun stopSendsNothingWhenNotConnectedOrDisarmed() {
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("decided") { supervisor.state.stop != null }
        assertEquals(StopStatus(supervisor.state.stop!!.id, StopTrigger.BUTTON, StopPhase.NOTHING_SENT, null,
            StopPolicy.NOT_CONNECTED, alarm = false, override = false), supervisor.state.stop)
        val first = supervisor.state.stop!!.id
        connect()
        supervisor.stop(StopTrigger.NOTIFICATION)
        waitFor("decided") { supervisor.state.stop?.trigger == StopTrigger.NOTIFICATION }
        assertEquals(StopPolicy.DISARMED, supervisor.state.stop!!.message)
        assertTrue("each STOP has its own id", supervisor.state.stop!!.id > first)
        Thread.sleep(100)
        assertTrue(vehicle.vehicleCommands().isEmpty())
        assertTrue(trace.events().any { it.name == TracePoint.STOP_NOTHING_SENT })
    }

    @Test fun externalModeAbortsThePlanAndStopNeedsTheOneSecondHold() {
        connect()
        airborne()
        startLongMove()
        vehicle.externalMode(CopterMode.LOITER)
        waitFor("aborted") { supervisor.state.stage == PlanStage.FAILED }
        assertEquals("UNKNOWN — Flight mode changed outside this app; vehicle outcome unknown.",
            supervisor.state.outcomes.single().message)
        assertEquals(ModeOwner.EXTERNAL, rig.store.snapshot().modeOwner)
        val before = vehicle.vehicleCommands().size
        supervisor.stop(StopTrigger.VOLUME_DOWN, heldOneSecond = true)
        supervisor.stop(StopTrigger.VOICE)
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("decided") { supervisor.state.stop?.trigger == StopTrigger.BUTTON }
        assertEquals("Pilot mode LOITER, use RC", supervisor.state.stop!!.message)
        Thread.sleep(150)
        assertEquals("nothing sent without the hold", before, vehicle.vehicleCommands().size)
        assertTrue(submit(plan(move("left", 1))) is SubmitResult.Rejected)
        supervisor.stop(StopTrigger.BUTTON, heldOneSecond = true)
        waitFor("override confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        assertTrue(supervisor.state.stop!!.override)
        assertEquals(CopterMode.BRAKE, vehicle.mode)
        assertTrue(trace.events().any { it.name == TracePoint.STOP_OVERRIDE })
    }

    @Test fun aFailsafeTextAbortsThePlanButInfoTextsDoNot() {
        connect()
        vehicle.climbRate = 2.0
        assertEquals(ConfirmResult.Started, confirm(token(submit(plan(takeoff())))))
        waitFor("climbing") { vehicle.altitude() > 0.5 }
        vehicle.statusText(6, "EKF3 IMU0 MAG0 in-flight yaw alignment complete")
        waitFor("takeoff done") { supervisor.state.stage == PlanStage.FINISHED }
        startLongMove()
        vehicle.statusText(2, "Fence Breached")
        waitFor("aborted") { supervisor.state.stage == PlanStage.FAILED }
        assertEquals("UNKNOWN — Autopilot reported \"Fence Breached\"; vehicle outcome unknown.",
            supervisor.state.outcomes.single().message)
    }

    @Test fun aStaleLinkAbortsAndNothingIsReplayedWhenItReturns() {
        connect()
        airborne()
        startLongMove()
        vehicle.heartbeats = false
        vehicle.telemetry = false
        waitFor("aborted", 5_000) { supervisor.state.stage == PlanStage.FAILED }
        assertEquals("UNKNOWN — " + ArduPilotExecutor.LOST, supervisor.state.outcomes.single().message)
        val before = vehicle.vehicleCommands().size
        vehicle.heartbeats = true
        vehicle.telemetry = true
        waitFor("link back") { rig.store.snapshot().connected }
        Thread.sleep(500)
        assertEquals("nothing replayed", before, vehicle.vehicleCommands().size)
    }

    @Test fun aStepWithoutTelemetryConfirmationTimesOut() {
        connect(timing = FAST_TIMING.copy(stepDeadlineMs = 1_500))
        airborne()
        vehicle.speed = 0.5
        assertEquals(ConfirmResult.Started, confirm(token(submit(plan(move("forward", 8))))))
        waitFor("deadline") { supervisor.state.stage == PlanStage.FAILED }
        assertEquals("UNKNOWN — " + ArduPilotExecutor.NO_CONFIRMATION, supervisor.state.outcomes.single().message)
        assertEquals(1, vehicle.positionTargets().size)
    }

    @Test fun eachStepIsRevalidatedAgainstAFreshState() {
        supervisor.close()
        supervisor = newSupervisor(validator = PlanValidator(maxHomeDistanceM = 5.0))
        connect()
        vehicle.climbRate = 2.0
        val token = token(submit(plan(takeoff(), move("forward", 4), land)))
        assertEquals(ConfirmResult.Started, confirm(token))
        waitFor("climbing") { vehicle.altitude() > 0.5 }
        vehicle.drift(3.0, 0.0) // wind: the move would now end 7 m from home
        waitFor("rejected") { supervisor.state.stage == PlanStage.FAILED }
        assertEquals(listOf(StepStatus.COMPLETED, StepStatus.REJECTED, StepStatus.SKIPPED), statuses())
        assertEquals("REJECTED — nothing sent: Step 2 would go more than 5 m from home.", supervisor.state.outcomes[1].message)
        assertEquals("SKIPPED — not sent", supervisor.state.outcomes[2].message)
        assertTrue(vehicle.positionTargets().isEmpty())
        assertEquals(listOf(
            Triple(1, StepStatus.COMPLETED, "COMPLETED — confirmed by simulator telemetry: takeoff"),
            Triple(2, StepStatus.REJECTED, "REJECTED — nothing sent: Step 2 would go more than 5 m from home."),
            Triple(3, StepStatus.SKIPPED, "SKIPPED — not sent"),
        ), tracedOutcomes())
        assertEquals(PlanStage.FAILED, trace.events().last { it.name == TracePoint.PLAN_END }.fields["stage"])
    }

    @Test fun aPlanIsRejectedWhileAnotherModeOwnerFliesTheArmedVehicle() {
        connect()
        vehicle.hover(3.0, CopterMode.LOITER)
        waitFor("seen") { rig.store.snapshot().let { it.armed == true && it.mode == CopterMode.LOITER } }
        assertEquals(SubmitResult.Rejected("Pilot mode LOITER, use RC"), submit(plan(move("forward", 1))))
    }

    @Test fun disconnectAbortsTheRunAndAReconnectReplaysNothing() {
        connect()
        airborne()
        startLongMove()
        supervisor.detach().get()
        assertEquals(PlanStage.FAILED, supervisor.state.stage)
        assertEquals("UNKNOWN — " + SafetySupervisor.DISCONNECTED, supervisor.state.outcomes.single().message)
        assertFalse(supervisor.state.execution)
        waitFor("the echo stream is switched off before the link goes") { vehicle.positionTargetIntervals().last() == -1L }
        val before = vehicle.vehicleCommands().size
        supervisor.attach(rig.reconnect(), FAST_TIMING).get()
        waitFor("reconnected") { rig.store.snapshot().connected }
        Thread.sleep(500)
        assertEquals(before, vehicle.vehicleCommands().size)
        assertFalse("execution is per connection", supervisor.state.execution)
    }

    @Test fun switchingExecutionOffDuringARunStops() {
        connect()
        airborne()
        startLongMove()
        supervisor.setExecution(false)
        waitFor("STOP confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        assertEquals(StopTrigger.EXECUTION_OFF, supervisor.state.stop!!.trigger)
        assertEquals(CopterMode.BRAKE, vehicle.mode)
        assertFalse(supervisor.state.execution)
    }

    @Test fun aMotionPlanWhileOneIsRunningIsRefusedButStatusIsAnswered() {
        connect()
        airborne()
        startLongMove()
        assertEquals(SubmitResult.Rejected(SafetySupervisor.BUSY), submit(plan(move("left", 1))))
        assertTrue(submit(plan(status)) is SubmitResult.Immediate)
        assertEquals(PlanStage.EXECUTING, supervisor.state.stage)
    }

    @Test fun holdWhileAPlanRunsIsStop() {
        connect()
        airborne()
        startLongMove()
        submit(plan(hold), PlanSource.PHONE)
        waitFor("STOP confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        assertEquals(StopTrigger.HOLD_COMMAND, supervisor.state.stop!!.trigger)
        assertEquals(CopterMode.BRAKE, vehicle.mode)
    }

    @Test fun aHeartbeatShowingTheRequestedModeBeforeItsAckIsNotAnExternalChange() {
        connect()
        airborne()
        assertTrue(submit(plan(hold)) is SubmitResult.Immediate)
        waitFor("hold confirmed") { supervisor.state.stop?.phase == StopPhase.CONFIRMED }
        waitFor("APP owns BRAKE") { rig.store.snapshot().let { it.mode == CopterMode.BRAKE && it.modeOwner == ModeOwner.APP } }
        vehicle.heartbeatBeforeModeAck = true
        val state = fly(plan(move("forward", 2), land))
        assertEquals(state.result, PlanStage.FINISHED, state.stage)
        assertEquals(listOf(CopterMode.BRAKE, CopterMode.GUIDED, CopterMode.LAND), vehicle.modeCommands().takeLast(3))
    }

    @Test fun noPlanStartsWhileAStopIsStillBeingSent() {
        connect()
        airborne()
        vehicle.lose[176] = AtomicInteger(1_000)
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("sending") { supervisor.state.stop?.phase == StopPhase.SENDING }
        assertEquals(SubmitResult.Rejected(SafetySupervisor.STOP_IN_PROGRESS), submit(plan(move("left", 1))))
        waitFor("not confirmed", 3_000) { supervisor.state.stop?.phase == StopPhase.NOT_CONFIRMED }
        assertTrue(submit(plan(move("left", 1))) is SubmitResult.AwaitingConfirmation)
    }

    @Test fun anUnconfirmedStopRaisesTheAlarm() {
        connect()
        airborne()
        vehicle.lose[176] = AtomicInteger(1_000)
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("not confirmed", 3_000) { supervisor.state.stop?.phase == StopPhase.NOT_CONFIRMED }
        assertEquals(StopPolicy.NOT_CONFIRMED, supervisor.state.stop!!.message)
        assertTrue(supervisor.state.stop!!.alarm)
    }

    @Test fun listenersSeeEveryPublishedState() {
        val stages = java.util.concurrent.CopyOnWriteArrayList<PlanStage>()
        supervisor.addListener { stages += it.stage }
        connect()
        confirm(token(submit(plan(takeoff()))))
        waitFor("finished") { supervisor.state.stage == PlanStage.FINISHED }
        assertTrue(stages.containsAll(listOf(PlanStage.AWAITING_CONFIRMATION, PlanStage.EXECUTING, PlanStage.FINISHED)))
    }

    @Test fun stopDuringTheArmRoundTripDisarmsAndNoTakeoffFollows() {
        connect()
        vehicle.heartbeats = false // no heartbeat shows the vehicle armed
        vehicle.loseAcks[400] = AtomicInteger(1) // and the ARM's ACK is lost: the app cannot know it armed
        confirm(token(submit(plan(takeoff()))))
        waitFor("ARM reached the vehicle") { vehicle.armed }
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("STOP decided") { supervisor.state.stop != null }
        vehicle.heartbeats = true
        waitFor("STOP settled") { supervisor.state.stop!!.phase.let { it == StopPhase.CONFIRMED || it == StopPhase.NOT_CONFIRMED } }
        assertEquals(StopAction.DISARM, supervisor.state.stop!!.action)
        assertEquals(StopPhase.CONFIRMED, supervisor.state.stop!!.phase)
        waitFor("disarmed") { !vehicle.armed }
        Thread.sleep(3 * FAST_TIMING.commandRetryMs)
        assertTrue("no NAV_TAKEOFF after STOP", vehicle.commands(22).isEmpty())
        val commands = vehicle.commands(400)
        assertEquals(1f, commands.first().param1)
        assertEquals(listOf(0f, 0f), commands.last().let { listOf(it.param1, it.param2) })
        assertFalse(vehicle.armed)
    }

    @Test fun anInProgressStopIsStillBoundedAndALaterStopIsAccepted() {
        connect()
        airborne()
        vehicle.refuse[176] = 5 // MAV_RESULT_IN_PROGRESS, never a final ACK
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("not confirmed", 3_000) { supervisor.state.stop?.phase == StopPhase.NOT_CONFIRMED }
        assertEquals(StopPolicy.NOT_CONFIRMED, supervisor.state.stop!!.message)
        assertTrue(supervisor.state.stop!!.alarm)
        vehicle.refuse.remove(176)
        val first = supervisor.state.stop!!.id
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("a new STOP confirmed") { supervisor.state.stop!!.let { it.id > first && it.phase == StopPhase.CONFIRMED } }
        assertEquals(CopterMode.BRAKE, vehicle.mode)
    }

    @Test fun anAckQueuedBeforeTheStopIsNeverTakenAsItsAck() {
        connect()
        airborne()
        token(submit(plan(move("left", 1))))
        vehicle.lose[176] = AtomicInteger(1_000) // every BRAKE (and LAND) is lost
        val phases = CopyOnWriteArrayList<StopPhase>()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        supervisor.addListener { s ->
            s.stop?.let { phases += it.phase }
            if (s.notice == "hold the actor") {
                blocked.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        vehicle.heartbeats = false // no store update may queue a turn ahead of the STOP
        vehicle.telemetry = false
        Thread.sleep(150)
        supervisor.invalidatePending("hold the actor")
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        val before = rig.store.snapshot().acks[176]
        supervisor.stop(StopTrigger.BUTTON) // queued behind the blocked actor
        vehicle.ack(176, 0) // a late ACK of an earlier DO_SET_MODE arrives before the STOP is sent
        waitFor("late ACK stored") { rig.store.snapshot().acks[176] !== before }
        release.countDown()
        vehicle.heartbeats = true
        vehicle.telemetry = true
        waitFor("STOP settled", 3_000) { supervisor.state.stop?.phase == StopPhase.NOT_CONFIRMED }
        assertFalse("never shown as confirmed: $phases", StopPhase.CONFIRMED in phases)
        assertTrue(supervisor.state.stop!!.alarm)
    }

    /** A STOP's re-send waits 0.5 s here, so a test can act between the first send and the retry. */
    private val slowRetry = FAST_TIMING.copy(commandRetryMs = 500, stopWindowMs = 2_000)

    private fun brakesSent() = vehicle.modeCommands().count { it == CopterMode.BRAKE }

    /** Waits for the current STOP to end NOT CONFIRMED, then past its next re-send and its window. */
    private fun notConfirmedAndQuiet(timing: ExecutorTiming = slowRetry): StopStatus {
        waitFor("STOP not confirmed", 3_000) { supervisor.state.stop?.phase == StopPhase.NOT_CONFIRMED }
        Thread.sleep(timing.commandRetryMs + 200)
        return supervisor.state.stop!!
    }

    @Test fun aStopWhoseAckIsLostNeverOverridesThePilotsModeChange() {
        connect(slowRetry)
        airborne()
        vehicle.loseAcks[176] = AtomicInteger(1) // the BRAKE takes effect, its ACK is lost
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("BRAKE reached the vehicle") { vehicle.mode == CopterMode.BRAKE }
        vehicle.externalMode(CopterMode.LOITER, heartbeatNow = true) // the pilot's RC switch, before the re-send
        val stop = notConfirmedAndQuiet()
        assertEquals("exactly one BRAKE", 1, brakesSent())
        assertEquals(CopterMode.LOITER, vehicle.mode)
        assertTrue(stop.alarm)
        assertEquals(StopPolicy.takenOverMessage(CopterMode.LOITER), stop.message)
        val state = rig.store.snapshot()
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        assertEquals("Pilot mode", state.modeOwnerLabel)
        assertTrue(trace.events().any { it.name == TracePoint.STOP_NOT_CONFIRMED && it.id == stop.id })
    }

    @Test fun aStopWhoseAckIsLostNeverOverridesAFailsafeLand() {
        connect(slowRetry)
        airborne()
        vehicle.landRate = 1.0
        vehicle.loseAcks[176] = AtomicInteger(1)
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("BRAKE reached the vehicle") { vehicle.mode == CopterMode.BRAKE }
        vehicle.statusText(2, "Battery failsafe") // ArduPilot will not trigger it again if the mode is taken away
        vehicle.externalMode(CopterMode.LAND, heartbeatNow = true)
        val stop = notConfirmedAndQuiet()
        assertEquals("exactly one BRAKE", 1, brakesSent())
        assertTrue("the app never sent LAND", CopterMode.LAND !in vehicle.modeCommands())
        assertEquals(CopterMode.LAND, vehicle.mode)
        assertTrue(stop.alarm)
        assertEquals(StopPolicy.takenOverMessage(CopterMode.LAND), stop.message)
        assertEquals("Autopilot failsafe", rig.store.snapshot().modeOwnerLabel)
    }

    @Test fun noFallbackIsSentAfterAModeChangeBetweenTheRefusedBrakeAndLand() {
        connect()
        airborne()
        vehicle.refuseModes += CopterMode.BRAKE
        // The pilot's switch lands between the BRAKE's refusal and its ACK reaching the app.
        vehicle.beforeAck = { if (it.command == 176 && it.param2.toLong() == CopterMode.BRAKE) vehicle.externalMode(CopterMode.LOITER, heartbeatNow = true) }
        supervisor.stop(StopTrigger.BUTTON)
        val stop = notConfirmedAndQuiet(FAST_TIMING)
        assertTrue("LAND never sent", CopterMode.LAND !in vehicle.modeCommands())
        assertEquals(1, brakesSent())
        assertEquals(CopterMode.LOITER, vehicle.mode)
        assertTrue(stop.alarm)
    }

    @Test fun anOverrideStopStopsResendingWhenTheModeChangesAgain() {
        connect(slowRetry)
        airborne()
        vehicle.externalMode(CopterMode.LOITER, heartbeatNow = true)
        waitFor("EXTERNAL") { rig.store.snapshot().let { it.mode == CopterMode.LOITER && it.modeOwner == ModeOwner.EXTERNAL } }
        vehicle.loseAcks[176] = AtomicInteger(1)
        supervisor.stop(StopTrigger.BUTTON, heldOneSecond = true)
        waitFor("BRAKE reached the vehicle") { vehicle.mode == CopterMode.BRAKE }
        vehicle.externalMode(CopterMode.ALT_HOLD, heartbeatNow = true) // neither the mode at the decision nor BRAKE
        val stop = notConfirmedAndQuiet()
        assertTrue(stop.override && stop.alarm)
        assertEquals(1, brakesSent())
        assertEquals(CopterMode.ALT_HOLD, vehicle.mode)
    }

    @Test fun aLateAckTakenForALostBrakeLeavesTheModeWithTheAppSoTheNextStopBrakes() {
        connect()
        airborne()
        vehicle.lose[176] = AtomicInteger(1) // the first BRAKE never arrives
        val injected = AtomicBoolean(false)
        // A late duplicate ACK of an earlier DO_SET_MODE arrives right after the STOP frame went out.
        trace.addSink { e -> if (e.name == TracePoint.STOP_TX && injected.compareAndSet(false, true)) vehicle.ack(176, 0) }
        supervisor.stop(StopTrigger.BUTTON)
        val first = notConfirmedAndQuiet(FAST_TIMING)
        assertTrue(first.alarm)
        assertEquals(CopterMode.GUIDED, vehicle.mode)
        assertEquals("the app still owns its GUIDED", ModeOwner.APP, rig.store.snapshot().modeOwner)
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("the next STOP confirmed") { supervisor.state.stop!!.let { it.id > first.id && it.phase == StopPhase.CONFIRMED } }
        assertEquals(StopAction.BRAKE, supervisor.state.stop!!.action)
        assertEquals(CopterMode.BRAKE, vehicle.mode)
    }

    @Test fun anUnansweredArmCountsAsArmedUntilAHeartbeatShowsOtherwise() {
        connect()
        vehicle.loseAcks[400] = AtomicInteger(3) // the first ARM arms the vehicle; no ACK of any send arrives
        vehicle.heartbeats = false // and no heartbeat shows it, while the link is not yet stale
        val state = fly(plan(takeoff()))
        assertEquals("UNKNOWN — " + ArduPilotExecutor.NO_CONFIRMATION, state.outcomes.single().message)
        assertTrue(vehicle.armed)
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("STOP decided") { supervisor.state.stop != null }
        assertEquals(StopAction.DISARM, supervisor.state.stop!!.action)
        vehicle.heartbeats = true
        waitFor("STOP confirmed") { supervisor.state.stop!!.phase == StopPhase.CONFIRMED }
        assertFalse(vehicle.armed)
        assertTrue("never a takeoff", vehicle.commands(22).isEmpty())
    }

    @Test fun aStopThatSendsNothingKeepsTheAlarmUntilItIsAcknowledged() {
        connect()
        airborne()
        vehicle.lose[176] = AtomicInteger(1_000)
        supervisor.stop(StopTrigger.BUTTON)
        assertTrue(notConfirmedAndQuiet(FAST_TIMING).alarm)
        vehicle.externalMode(CopterMode.LOITER, heartbeatNow = true) // the operator took RC control
        waitFor("EXTERNAL") { rig.store.snapshot().modeOwner == ModeOwner.EXTERNAL }
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("nothing sent") { supervisor.state.stop?.phase == StopPhase.NOTHING_SENT }
        assertEquals("Pilot mode LOITER, use RC", supervisor.state.stop!!.message)
        assertTrue("still alarming", supervisor.state.stop!!.alarm)
        supervisor.acknowledgeAlarm()
        waitFor("alarm acknowledged") { supervisor.state.stop?.alarm == false }
    }

    @Test fun aStopPressThatWouldSendNothingLetsTheLastStopsCheckFinish() {
        connect()
        airborne()
        vehicle.externalMode(CopterMode.LOITER, heartbeatNow = true)
        waitFor("EXTERNAL") { rig.store.snapshot().let { it.mode == CopterMode.LOITER && it.modeOwner == ModeOwner.EXTERNAL } }
        vehicle.heartbeats = false // the heartbeat showing BRAKE comes late
        supervisor.stop(StopTrigger.BUTTON, heldOneSecond = true)
        waitFor("BRAKE ACKed") { supervisor.state.stop?.let { it.override && it.phase == StopPhase.CONFIRMED } == true }
        val override = supervisor.state.stop!!
        supervisor.stop(StopTrigger.BUTTON) // no hold, and the app has seen only the pilot's LOITER so far
        waitFor("absorbed") { trace.events().any { it.name == TracePoint.STOP_NOTHING_SENT && it.fields["previous"] != null } }
        assertEquals(override, supervisor.state.stop)
        vehicle.heartbeats = true
        waitFor("checked") { trace.events().any { it.name == TracePoint.STOP_VERIFIED && it.id == override.id } }
        assertEquals(override.id, supervisor.state.stop!!.id)
        waitFor("APP owns BRAKE") { rig.store.snapshot().let { it.mode == CopterMode.BRAKE && it.modeOwner == ModeOwner.APP } }
        assertEquals(1, brakesSent())
    }

    @Test fun aStopDuringTheMovesGuidedRoundTripStillBrakes() {
        connect()
        airborne()
        assertTrue(submit(plan(hold)) is SubmitResult.Immediate)
        waitFor("APP owns BRAKE") { rig.store.snapshot().let { it.mode == CopterMode.BRAKE && it.modeOwner == ModeOwner.APP } }
        vehicle.heartbeatBeforeModeAck = true
        val pressed = AtomicBoolean(false)
        // The move's GUIDED reaches the vehicle and its heartbeat the app; STOP is pressed before the ACK follows.
        vehicle.beforeAck = {
            if (it.command == 176 && it.param2.toLong() == CopterMode.GUIDED && pressed.compareAndSet(false, true)) {
                waitFor("heartbeat shows GUIDED") { rig.store.snapshot().mode == CopterMode.GUIDED }
                supervisor.stop(StopTrigger.BUTTON)
                waitFor("STOP decided") { supervisor.state.stop!!.trigger == StopTrigger.BUTTON }
            }
        }
        assertEquals(ConfirmResult.Started, confirm(token(submit(plan(move("forward", 2))))))
        waitFor("STOP settled") { supervisor.state.stop!!.let { it.trigger == StopTrigger.BUTTON && it.phase != StopPhase.SENDING } }
        waitFor("STOP checked") { trace.events().any { it.name == TracePoint.STOP_VERIFIED && it.id == supervisor.state.stop!!.id } }
        val stop = supervisor.state.stop!!
        assertEquals("the app's own GUIDED is not a pilot's: $stop", StopPhase.CONFIRMED, stop.phase)
        assertEquals(StopAction.BRAKE, stop.action)
        assertEquals(CopterMode.BRAKE, vehicle.mode)
        assertTrue(vehicle.positionTargets().isEmpty())
        waitFor("APP owns BRAKE") { rig.store.snapshot().modeOwner == ModeOwner.APP }
    }

    @Test fun aHoldWhoseBrakeTheHeartbeatNeverShowsBecomesUnknown() {
        connect()
        airborne()
        vehicle.ignoreModes += CopterMode.BRAKE // ACKed, but the vehicle stays in GUIDED
        submit(plan(hold))
        waitFor("STOP settled", 3_000) { supervisor.state.stop?.phase == StopPhase.NOT_CONFIRMED }
        assertTrue(supervisor.state.stop!!.alarm)
        assertEquals("UNKNOWN — " + StopPolicy.NOT_CONFIRMED, supervisor.state.outcomes.single().message)
        assertEquals(PlanStage.FAILED, supervisor.state.stage)
    }

    /** Audit F4: "Got it" silences the alarm while the same STOP keeps re-sending; an escalation sounds it again. */
    @Test fun anAcknowledgedAlarmStaysSilentUntilTheStopEscalates() {
        connect(slowRetry)
        vehicle.lose[22] = AtomicInteger(1_000) // armed on the ground: the takeoff never happens
        confirm(token(submit(plan(takeoff()))))
        waitFor("armed on the ground") { vehicle.armed }
        vehicle.refuse[400] = 4 // the DISARM is refused: alarm, then BRAKE
        vehicle.loseAcks[176] = AtomicInteger(1_000) // and BRAKE is never acknowledged, so it keeps re-sending
        supervisor.stop(StopTrigger.BUTTON)
        waitFor("alarm while BRAKE re-sends") {
            supervisor.state.stop?.let { it.alarm && it.phase == StopPhase.SENDING && it.action == StopAction.BRAKE } == true
        }
        supervisor.acknowledgeAlarm()
        waitFor("acknowledged") { supervisor.state.stop?.alarm == false }
        Thread.sleep(300) // several supervisor ticks while the lane is still re-sending
        val still = supervisor.state.stop!!
        assertEquals(StopPhase.SENDING, still.phase)
        assertFalse("silent while the same STOP re-sends", still.alarm)
        waitFor("not confirmed", 4_000) { supervisor.state.stop?.phase == StopPhase.NOT_CONFIRMED }
        assertTrue("an escalation sounds again", supervisor.state.stop!!.alarm)
    }

}
