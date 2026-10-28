package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ModeOwner
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopLaneTest {
    private val rig = Rig().connect()
    private val vehicle get() = rig.vehicle
    private val trace = Trace()

    private fun now() = System.nanoTime() / 1_000_000

    @After fun tearDown() {
        assertTrue("never a forced disarm", vehicle.commands(400).none { it.param2 != 0f })
        rig.close()
    }

    private fun lane(first: StopAction, timing: ExecutorTiming = FAST_TIMING) =
        StopLane(rig.link, rig.store, timing, trace, traceId = 9, first = first)

    /** Drives the lane the way the supervisor does until it settles (verified, or not confirmed). */
    private fun drive(lane: StopLane, timeoutMs: Long = 5_000, started: Boolean = false): StopProgress {
        if (!started) {
            rig.events.drainAcks()
            lane.start(now())
        }
        var progress = lane.update(emptyList(), rig.store.snapshot(), now())
        val deadline = now() + timeoutMs
        while (!lane.settled) {
            if (now() > deadline) throw AssertionError("STOP not settled after $timeoutMs ms: $progress")
            Thread.sleep(2)
            progress = lane.update(rig.events.drainAcks(), rig.store.snapshot(), now())
        }
        return progress
    }

    private fun airborneInAppGuided() {
        rig.link.send(CommandLong(1, 1, 176, param1 = 1f, param2 = CopterMode.GUIDED.toFloat()))
        rig.link.send(CommandLong(1, 1, 400, param1 = 1f))
        rig.link.send(CommandLong(1, 1, 22, param7 = 3f))
        waitFor("airborne") { vehicle.altitude() >= 2.9 }
        rig.store.noteCommandedMode(CopterMode.GUIDED)
        waitFor("APP") { rig.store.snapshot().modeOwner == ModeOwner.APP }
    }

    @Test fun brakeIsConfirmedByItsAckAndOwnedByTheApp() {
        airborneInAppGuided()
        val lane = lane(StopAction.BRAKE)
        assertEquals(StopProgress.Confirmed(StopAction.BRAKE), drive(lane))
        assertEquals(CopterMode.BRAKE, vehicle.modeCommands().last())
        assertFalse(lane.alarm)
        assertTrue("a heartbeat showed BRAKE", lane.verified)
        waitFor("APP owns BRAKE") { rig.store.snapshot().let { it.mode == CopterMode.BRAKE && it.modeOwner == ModeOwner.APP } }
        val names = trace.events().filter { it.id == 9L }.map { it.name }
        assertEquals(TracePoint.STOP_TX, names.first())
        assertEquals(listOf(TracePoint.STOP_ACK, TracePoint.STOP_VERIFIED), names.takeLast(2))
    }

    @Test fun brakeRefusedWithoutAPositionFallsBackToLand() {
        airborneInAppGuided()
        vehicle.gpsDenied = true
        val lane = lane(StopAction.BRAKE)
        assertEquals(StopProgress.Confirmed(StopAction.LAND), drive(lane))
        assertEquals(listOf(CopterMode.BRAKE, CopterMode.LAND), vehicle.modeCommands().takeLast(2))
        assertEquals(listOf(StopAction.BRAKE, StopAction.LAND), lane.actions)
        assertTrue(vehicle.modeCommands().none { it == CopterMode.LOITER })
        waitFor("APP owns LAND") { rig.store.snapshot().let { it.mode == CopterMode.LAND && it.modeOwner == ModeOwner.APP } }
    }

    @Test fun disarmOnTheGroundIsNonForced() {
        rig.link.send(CommandLong(1, 1, 176, param1 = 1f, param2 = CopterMode.GUIDED.toFloat()))
        rig.link.send(CommandLong(1, 1, 400, param1 = 1f))
        // As a real decision sees it: the STOP lane counts the modes known at the decision as nobody else's.
        waitFor("armed in GUIDED") { rig.store.snapshot().let { it.armed == true && it.mode == CopterMode.GUIDED } }
        val lane = lane(StopAction.DISARM)
        assertEquals(StopProgress.Confirmed(StopAction.DISARM), drive(lane))
        assertFalse(vehicle.armed)
        val disarm = vehicle.commands(400).last()
        assertEquals(0f, disarm.param1)
        assertEquals(0f, disarm.param2)
        assertFalse(lane.alarm)
        assertTrue("a heartbeat showed it disarmed", lane.verified)
    }

    @Test fun aRefusedDisarmBrakesAndRaisesTheAlarm() {
        airborneInAppGuided() // the snapshot said ON_GROUND, but it already lifted off
        val lane = lane(StopAction.DISARM)
        assertEquals(StopProgress.Confirmed(StopAction.BRAKE), drive(lane))
        assertTrue(lane.alarm)
        assertTrue(vehicle.armed)
        assertEquals(CopterMode.BRAKE, vehicle.modeCommands().last())
    }

    @Test fun anUnacknowledgedStopIsResentForTheWindowThenNotConfirmed() {
        airborneInAppGuided()
        vehicle.lose[176] = AtomicInteger(1_000)
        val started = now()
        val lane = lane(StopAction.BRAKE)
        assertEquals(StopProgress.NotConfirmed(StopAction.BRAKE), drive(lane))
        val elapsed = now() - started
        assertTrue("bounded by the window: $elapsed ms", elapsed in FAST_TIMING.stopWindowMs..FAST_TIMING.stopWindowMs + 400)
        val brakes = vehicle.commands(176).filter { it.param2 == CopterMode.BRAKE.toFloat() }
        val expected = (FAST_TIMING.stopWindowMs / FAST_TIMING.commandRetryMs).toInt()
        assertTrue("about one send per retry interval: ${brakes.size}", brakes.size in expected - 1..expected + 1)
        assertEquals((0 until brakes.size).toList(), brakes.map { it.confirmation })
        assertTrue(lane.alarm)
        assertTrue(trace.events().any { it.name == TracePoint.STOP_NOT_CONFIRMED })
        Thread.sleep(300)
        assertEquals("nothing after the window", brakes.size, vehicle.commands(176).count { it.param2 == CopterMode.BRAKE.toFloat() })
    }

    @Test fun cancelEndsTheResends() {
        airborneInAppGuided()
        vehicle.lose[176] = AtomicInteger(1_000)
        val lane = lane(StopAction.BRAKE)
        lane.start(now())
        lane.cancel()
        assertEquals(StopProgress.NotConfirmed(StopAction.BRAKE), lane.update(emptyList(), rig.store.snapshot(), now() + 10_000))
        Thread.sleep(300)
        assertEquals(1, vehicle.commands(176).count { it.param2 == CopterMode.BRAKE.toFloat() })
    }

    @Test fun anInProgressAckDoesNotExtendTheWindow() {
        airborneInAppGuided()
        vehicle.refuse[176] = 5 // MAV_RESULT_IN_PROGRESS, and no final ACK ever follows
        val started = now()
        val lane = lane(StopAction.BRAKE)
        assertEquals(StopProgress.NotConfirmed(StopAction.BRAKE), drive(lane))
        val elapsed = now() - started
        assertTrue("bounded by the window: $elapsed ms", elapsed in FAST_TIMING.stopWindowMs..FAST_TIMING.stopWindowMs + 400)
        assertEquals("not re-sent while in progress", 1, vehicle.commands(176).count { it.param2 == CopterMode.BRAKE.toFloat() })
        assertTrue(lane.alarm)
    }

    @Test fun anAckTheHeartbeatContradictsIsNotConfirmed() {
        airborneInAppGuided()
        vehicle.lose[176] = AtomicInteger(1_000) // the BRAKE never reaches the vehicle
        val lane = lane(StopAction.BRAKE)
        rig.events.drainAcks()
        lane.start(now())
        // The ACK of an earlier DO_SET_MODE (a plan's GUIDED re-send) arrives now: ACKs carry only the command id.
        val progress = lane.update(listOf(CommandAck(176, 0, targetSystem = 255, targetComponent = 190)), rig.store.snapshot(), now())
        assertEquals(StopProgress.Confirmed(StopAction.BRAKE), progress)
        assertFalse(lane.verified)
        assertEquals(StopProgress.NotConfirmed(StopAction.BRAKE), drive(lane, started = true))
        assertTrue(lane.alarm)
        assertEquals(CopterMode.GUIDED, vehicle.mode)
        assertTrue(trace.events().any { it.name == TracePoint.STOP_NOT_CONFIRMED })
        // The ACK was never noted as the app's BRAKE: the app still owns its GUIDED, so a next STOP still brakes.
        assertEquals(ModeOwner.APP, rig.store.snapshot().modeOwner)
    }

    @Test fun aModeChangeAwayFromTheBrakeAHeartbeatShowedEndsTheResends() {
        airborneInAppGuided()
        vehicle.loseAcks[176] = AtomicInteger(1_000) // the BRAKE takes effect; no ACK ever comes
        val lane = lane(StopAction.BRAKE, FAST_TIMING.copy(commandRetryMs = 1_000, stopWindowMs = 3_000))
        rig.events.drainAcks()
        lane.start(now())
        waitFor("a heartbeat shows BRAKE") { rig.store.snapshot().mode == CopterMode.BRAKE }
        assertEquals(StopProgress.Sending(StopAction.BRAKE), lane.update(emptyList(), rig.store.snapshot(), now()))
        // Another ground station selects GUIDED, the mode the STOP was decided in: it is not the app's any more.
        vehicle.externalMode(CopterMode.GUIDED, heartbeatNow = true)
        assertEquals(StopProgress.TakenOver(StopAction.BRAKE, CopterMode.GUIDED), drive(lane, started = true))
        assertTrue(lane.alarm)
        Thread.sleep(1_200)
        assertEquals("never re-sent", 1, vehicle.commands(176).count { it.param2 == CopterMode.BRAKE.toFloat() })
        assertEquals(CopterMode.GUIDED, vehicle.mode)
    }
}
