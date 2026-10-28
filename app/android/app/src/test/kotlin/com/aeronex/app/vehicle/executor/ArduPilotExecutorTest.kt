package com.aeronex.app.vehicle.executor

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.link.mavlink.SysStatus
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ArduPilotExecutorTest {
    private val rig = Rig().connect()
    private val vehicle get() = rig.vehicle
    private val trace = Trace()
    private var executor = newExecutor()

    private fun newExecutor() = ArduPilotExecutor(rig.link, rig.store, FAST_TIMING, trace, traceId = 1)
    private fun now() = System.nanoTime() / 1_000_000

    @After fun tearDown() {
        // STOP and plan steps never send a forced disarm (spec 4.12, 8.3).
        assertTrue(vehicle.commands(400).none { it.param2 != 0f })
        rig.close()
    }

    /** Drives one step the way the supervisor does: fresh snapshot and new ACKs on every turn. */
    private fun run(command: FlightCommand, timeoutMs: Long = 8_000): StepUpdate {
        rig.events.drainAcks()
        var update = executor.start(command, rig.store.snapshot(), now())
        val deadline = now() + timeoutMs
        while (update == StepUpdate.Running) {
            if (now() > deadline) throw AssertionError("step $command still running after $timeoutMs ms")
            Thread.sleep(5)
            update = executor.update(rig.store.snapshot(), rig.events.drainAcks(), now())
        }
        return update
    }

    private fun takeoff(altitude: Int = 3) = run(FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to altitude)))
    private fun move(direction: String, distance: Number) =
        run(FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to distance)))
    private fun land() = run(FlightCommand.of(FlightCommand.LAND))
    private fun hold() = run(FlightCommand.of(FlightCommand.HOLD))

    @Test fun timingFollowsTheLink() {
        assertEquals(500L, ExecutorTiming.forProfile(TelemetryProfile.WIFI).commandRetryMs)
        assertEquals(1_200L, ExecutorTiming.forProfile(TelemetryProfile.RADIO).commandRetryMs)
        with(ExecutorTiming.WIFI) {
            assertEquals(3, commandTries)
            assertEquals(30_000L, stepDeadlineMs)
            assertEquals(3_000L, stopWindowMs)
        }
        // SITL move frame -> echo: p95 up to 0.7 s on loopback (see ExecutorTiming); radio adds its round trip.
        assertEquals(1_500L, ExecutorTiming.WIFI.echoTimeoutMs)
        assertEquals(2_500L, ExecutorTiming.RADIO.echoTimeoutMs)
    }

    private fun withWatch(): ModeWatch = ModeWatch(rig.store).also {
        executor = ArduPilotExecutor(rig.link, rig.store, FAST_TIMING, trace, traceId = 1, watch = it)
    }

    @Test fun anArmOnTheWireCountsAsArmedEvenAfterTheStepIsCancelled() {
        val watch = withWatch()
        vehicle.heartbeats = false // no heartbeat shows the vehicle armed
        vehicle.loseAcks[400] = AtomicInteger(1) // and the ARM's ACK is lost
        rig.events.drainAcks()
        var update = executor.start(FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to 3)),
            rig.store.snapshot(), now())
        while (update == StepUpdate.Running && vehicle.commands(400).isEmpty()) {
            Thread.sleep(5)
            update = executor.update(rig.store.snapshot(), rig.events.drainAcks(), now())
        }
        assertEquals(false, rig.store.snapshot().armed)
        assertTrue(watch.armed(rig.store.snapshot()))
        executor.cancel() // a STOP: the ARM may still arm the vehicle
        assertTrue(watch.armed(rig.store.snapshot()))
        watch.disarmAccepted()
        assertFalse(watch.armed(rig.store.snapshot()))
    }

    @Test fun aRefusedArmIsNotArmedButAnUnansweredOneIsUntilAHeartbeatShowsOtherwise() {
        val watch = withWatch()
        vehicle.refuse[400] = 4
        assertTrue(takeoff() is StepUpdate.Unknown)
        assertFalse("a refusal clears it at once", watch.armed(rig.store.snapshot()))
        vehicle.refuse.remove(400)
        vehicle.lose[400] = AtomicInteger(100)
        vehicle.heartbeats = false
        assertEquals(StepUpdate.Unknown(ArduPilotExecutor.NO_CONFIRMATION), takeoff())
        assertEquals(3, vehicle.commands(400).count { it.param1 == 1f } - 1)
        // Only the ACKs may have been lost: the vehicle may be armed until a later heartbeat says otherwise.
        assertTrue(watch.armed(rig.store.snapshot()))
        vehicle.heartbeats = true
        waitFor("a heartbeat shows it disarmed") { !watch.armed(rig.store.snapshot()) }
        assertFalse(vehicle.armed)
    }

    @Test fun takeoffSetsGuidedThenArmsThenTakesOffAndWaitsForAltitudeInAir() {
        assertEquals(StepUpdate.Completed(), takeoff(3))
        val commands = vehicle.vehicleCommands().map { (it as CommandLong).command }
        assertEquals(listOf(176, 400, 22), commands)
        assertEquals(listOf(CopterMode.GUIDED), vehicle.modeCommands())
        val arm = vehicle.commands(400).single()
        assertEquals(1f, arm.param1)
        assertEquals(3f, vehicle.commands(22).single().param7)
        assertTrue(vehicle.altitude() >= 2.6)
        waitFor("APP owns GUIDED") { rig.store.snapshot().modeOwner == ModeOwner.APP }
        val names = trace.events().map { it.name }
        assertTrue(names.containsAll(listOf(TracePoint.TX, TracePoint.ACK, TracePoint.IN_AIR, TracePoint.MODE_OBSERVED)))
        assertTrue("each point names its plan step", trace.events().filter { it.name == TracePoint.TX || it.name == TracePoint.ACK }
            .all { it.fields["step"] == 1 })
    }

    @Test fun anAcceptedArmCountsAsArmedUntilAHeartbeatSpeaks() {
        val watch = ModeWatch(rig.store)
        executor = ArduPilotExecutor(rig.link, rig.store, FAST_TIMING, trace, traceId = 1, watch = watch)
        vehicle.heartbeats = false // the ARM ACK arrives, the heartbeat showing armed does not (yet)
        vehicle.lose[22] = java.util.concurrent.atomic.AtomicInteger(1_000)
        rig.events.drainAcks()
        var update = executor.start(FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to 3)),
            rig.store.snapshot(), now())
        while (update == StepUpdate.Running && vehicle.commands(22).isEmpty()) {
            Thread.sleep(5)
            update = executor.update(rig.store.snapshot(), rig.events.drainAcks(), now())
        }
        val state = rig.store.snapshot()
        assertEquals(false, state.armed)
        assertTrue(watch.armed(state))
        executor.cancel()
        vehicle.heartbeats = true
        waitFor("heartbeat shows armed") { rig.store.snapshot().armed == true }
        assertTrue(watch.armed(rig.store.snapshot()))
    }

    @Test fun takeoffPreconditionsRejectWithoutSending() {
        vehicle.battery = 10
        waitFor("battery") { rig.store.snapshot().batteryPercent == 10 }
        assertEquals(StepUpdate.Rejected("Takeoff requires disarmed, landed vehicle and known battery >=20%."), takeoff())
        vehicle.battery = 90
        vehicle.gpsDenied = true
        waitFor("EKF without position") { !rig.store.snapshot().positionReady }
        assertEquals(StepUpdate.Rejected(ArduPilotExecutor.POSITION_NOT_READY), takeoff())
        assertTrue(vehicle.vehicleCommands().isEmpty())
        assertFalse(executor.sentInStep)
    }

    @Test fun moveSendsExactlyOneBodyOffsetFrameAndWaitsForEchoThenArrival() {
        assertEquals(StepUpdate.Completed(), takeoff())
        waitFor("APP owns GUIDED") { rig.store.snapshot().modeOwner == ModeOwner.APP }
        assertEquals(StepUpdate.Completed(), move("forward", 2))
        val frame = vehicle.positionTargets().single()
        assertEquals(9, frame.coordinateFrame)
        assertEquals(3576, frame.typeMask)
        assertEquals(listOf(2f, 0f, 0f), listOf(frame.x, frame.y, frame.z))
        assertEquals("GUIDED is not re-sent when already GUIDED", listOf(CopterMode.GUIDED), vehicle.modeCommands())
        waitFor("streaming on, then off") { vehicle.positionTargetIntervals().takeLast(2) == listOf(200_000L, -1L) }
        val position = vehicle.position()
        assertEquals(2.0, position[0], 0.5)
        assertEquals(0.0, position[1], 0.5)
        val names = trace.events().map { it.name }
        assertTrue(names.containsAll(listOf(TracePoint.MOVE_TX, TracePoint.ECHO, TracePoint.ARRIVED)))
        assertTrue(names.indexOf(TracePoint.ECHO) < names.indexOf(TracePoint.ARRIVED))
    }

    @Test fun aMoveLeavesAStreamThePlanOwnsAlone() {
        executor = ArduPilotExecutor(rig.link, rig.store, FAST_TIMING, trace, traceId = 1, planStreamsPositionTarget = { true })
        assertEquals(StepUpdate.Completed(), takeoff())
        waitFor("APP owns GUIDED") { rig.store.snapshot().modeOwner == ModeOwner.APP }
        rig.link.setPositionTargetStreaming(true) // as the supervisor does for a plan with moves
        waitFor("stream on") { vehicle.positionTargetIntervals().last() == 200_000L }
        val requests = vehicle.positionTargetIntervals().size
        assertEquals(StepUpdate.Completed(), move("forward", 2))
        assertEquals(StepUpdate.Completed(), move("left", 1))
        Thread.sleep(200)
        assertEquals("the moves neither re-request nor stop the plan's stream", requests, vehicle.positionTargetIntervals().size)
        assertEquals(2, vehicle.positionTargets().size)
    }

    @Test fun bodyFrameFollowsTheHeading() {
        assertEquals(StepUpdate.Completed(), takeoff())
        vehicle.yaw = PI / 2 // facing east: right is south
        waitFor("yaw") { (rig.store.snapshot().yaw ?: 0.0) > 1.5 }
        assertEquals(StepUpdate.Completed(), move("right", 2))
        val frame = vehicle.positionTargets().single()
        assertEquals(listOf(0f, 2f, 0f), listOf(frame.x, frame.y, frame.z))
        assertEquals(-2.0, vehicle.position()[0], 0.5)
        assertEquals(StepUpdate.Completed(), move("up", 1.5))
        assertEquals(-1.5f, vehicle.positionTargets().last().z)
        assertEquals(4.5, vehicle.altitude(), 0.5)
    }

    @Test fun moveSetsGuidedFirstWhenTheVehicleIsInAnotherAppMode() {
        assertEquals(StepUpdate.Completed(), takeoff())
        assertEquals(StepUpdate.Completed(), hold())
        assertEquals(StepUpdate.Completed(), move("left", 1))
        assertEquals(listOf(CopterMode.GUIDED, CopterMode.BRAKE, CopterMode.GUIDED), vehicle.modeCommands())
        val order = vehicle.vehicleCommands()
        assertTrue("GUIDED before the move frame", order.last() is SetPositionTargetLocalNed)
    }

    @Test fun aMissingEchoIsUnknownAndTheMoveIsNeverRetried() {
        assertEquals(StepUpdate.Completed(), takeoff())
        vehicle.echo = false
        assertEquals(StepUpdate.Unknown(ArduPilotExecutor.NO_ECHO), move("forward", 2))
        assertEquals(1, vehicle.positionTargets().size)
        waitFor("streaming off") { vehicle.positionTargetIntervals().last() == -1L }
        Thread.sleep(300)
        assertEquals(1, vehicle.positionTargets().size)
    }

    @Test fun movePreconditionsAndAltitudeLimits() {
        assertEquals(StepUpdate.Rejected("Movement requires an airborne vehicle."), move("forward", 1))
        assertEquals(StepUpdate.Completed(), takeoff(2))
        assertEquals(StepUpdate.Rejected("Target exceeds simulator altitude limits."), move("down", 1.5))
        assertEquals(StepUpdate.Rejected("Target exceeds simulator altitude limits."), move("up", 8.5))
        assertTrue(vehicle.positionTargets().isEmpty())
    }

    @Test fun landSetsLandAndWaitsForTheGround() {
        assertEquals(StepUpdate.Rejected("Vehicle is not armed."), land())
        assertEquals(StepUpdate.Completed(), takeoff())
        assertEquals(StepUpdate.Completed(), land())
        assertEquals(CopterMode.LAND, vehicle.modeCommands().last())
        assertEquals(0.0, vehicle.altitude(), 0.01)
    }

    @Test fun anUnacknowledgedCommandIsResentWithConfirmationIncremented() {
        vehicle.lose[176] = java.util.concurrent.atomic.AtomicInteger(2)
        assertEquals(StepUpdate.Completed(), takeoff())
        assertEquals(listOf(0, 1, 2), vehicle.commands(176).map { it.confirmation })
        assertEquals(listOf(0), vehicle.commands(400).map { it.confirmation })
    }

    @Test fun aLostAckIsResentAndTheRepeatIsAccepted() {
        vehicle.loseAcks[176] = java.util.concurrent.atomic.AtomicInteger(1)
        assertEquals(StepUpdate.Completed(), takeoff())
        assertEquals(listOf(0, 1), vehicle.commands(176).map { it.confirmation })
    }

    @Test fun noAckAfterThreeTriesIsUnknownAndNothingFollows() {
        vehicle.lose[176] = java.util.concurrent.atomic.AtomicInteger(100)
        assertEquals(StepUpdate.Unknown(ArduPilotExecutor.NO_CONFIRMATION), takeoff())
        assertEquals(3, vehicle.commands(176).size)
        assertTrue(vehicle.commands(400).isEmpty())
        assertTrue(executor.sentInStep)
    }

    @Test fun aRefusedCommandIsUnknownAndStopsTheStep() {
        vehicle.refuse[400] = 4
        assertEquals(StepUpdate.Unknown("execution failed: ArduPilot rejected command 400 (result 4)."), takeoff())
        assertTrue(vehicle.commands(22).isEmpty())
    }

    @Test fun cancelStopsEverythingIncludingTheEchoStream() {
        assertEquals(StepUpdate.Completed(), takeoff())
        rig.events.drainAcks()
        val update = executor.start(FlightCommand.of(FlightCommand.MOVE, mapOf("direction" to "forward", "distance_m" to 8)),
            rig.store.snapshot(), now())
        assertEquals(StepUpdate.Running, update)
        executor.cancel()
        waitFor("streaming off") { vehicle.positionTargetIntervals().last() == -1L }
        val sends = vehicle.vehicleCommands().size
        repeat(10) { executor.update(rig.store.snapshot(), rig.events.drainAcks(), now()); Thread.sleep(20) }
        assertEquals(sends, vehicle.vehicleCommands().size)
    }

    @Test fun anExternalModeChangeIsSeenOnlyOnAHeartbeatAfterTheAppsModeAck() {
        assertEquals(StepUpdate.Completed(), takeoff())
        waitFor("APP owns GUIDED") { rig.store.snapshot().modeOwner == ModeOwner.APP }
        assertFalse(executor.externalModeChange(rig.store.snapshot()))
        vehicle.externalMode(CopterMode.LOITER)
        waitFor("external mode seen") { executor.externalModeChange(rig.store.snapshot()) }
        assertFalse(executor.ownsMode(rig.store.snapshot()))
    }

    @Test fun rightAfterTheModeAckTheOldHeartbeatModeIsNotExternal() {
        assertEquals(StepUpdate.Completed(), takeoff())
        waitFor("APP owns GUIDED") { rig.store.snapshot().modeOwner == ModeOwner.APP }
        vehicle.heartbeats = false // the BRAKE ACK arrives, the next heartbeat does not (yet)
        assertEquals(StepUpdate.Completed(), hold())
        val state = rig.store.snapshot()
        assertEquals(CopterMode.GUIDED, state.mode)
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        assertTrue(executor.ownsMode(state))
        assertFalse(executor.externalModeChange(state))
        vehicle.heartbeats = true
        waitFor("APP owns BRAKE") { rig.store.snapshot().let { it.modeOwner == ModeOwner.APP && it.mode == CopterMode.BRAKE } }
        assertFalse(executor.externalModeChange(rig.store.snapshot()))
    }

    @Test fun holdBrakesUntilSlow() {
        assertEquals(StepUpdate.Rejected("Hold requires an airborne vehicle."), hold())
        assertEquals(StepUpdate.Completed(), takeoff())
        assertEquals(StepUpdate.Completed(), hold())
        assertEquals(CopterMode.BRAKE, vehicle.modeCommands().last())
        assertTrue(rig.store.snapshot().speed!! < 0.3)
    }

    @Test fun statusReportsTelemetryWithoutSending() {
        val update = run(FlightCommand.of(FlightCommand.STATUS))
        assertEquals(StepUpdate.Completed("Connected · Disarmed · On ground · Stabilize · Alt 0.0 m · Battery 90% · Position ready"),
            update)
        assertTrue(vehicle.vehicleCommands().isEmpty())
    }

    @Test fun theStatusLineFollowsV020() {
        val fresh = mapOf(Heartbeat.messageId to 0L, SysStatus.messageId to 0L, GlobalPositionInt.messageId to 0L,
            EkfStatusReport.messageId to 0L, ExtendedSysState.messageId to 0L)
        val flying = VehicleState(mode = 17, armed = true, landedState = 2, altitudeRelM = 3.04, batteryPercent = 76,
            healthy = false, ekfFlags = 0x0F, ageMs = fresh)
        assertEquals("Connected · Armed · In air · Brake · Alt 3.0 m · Battery 76% · Sensors not healthy · Position not ready",
            ArduPilotExecutor.statusReport(flying))
        val stale = flying.copy(mode = 21, ageMs = fresh + (GlobalPositionInt.messageId to 5_000L))
        assertEquals("Connected · Armed · In air · Mode 21 · Battery 76% · Sensors not healthy · Position not ready · Telemetry stale",
            ArduPilotExecutor.statusReport(stale))
        assertEquals("Not connected", ArduPilotExecutor.statusReport(VehicleState()))
    }

    @Test fun staleTelemetryRejectsBeforeSending() {
        vehicle.telemetry = false
        vehicle.heartbeats = false
        waitFor("stale", timeoutMs = 5_000) { !rig.store.snapshot().connected }
        assertEquals(StepUpdate.Rejected(ArduPilotExecutor.STALE), takeoff())
        assertTrue(vehicle.vehicleCommands().isEmpty())
    }
}
