package com.aeronex.app.vehicle.executor

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.observability.Trace
import com.aeronex.app.vehicle.Ned
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** Spec 2026-10-28 M2: a flight-memory step flies absolute legs, each sent once and confirmed like a move. */
class ArduPilotExecutorMemoryTest {
    private val rig = Rig().connect()
    private val vehicle get() = rig.vehicle
    private val executor = ArduPilotExecutor(rig.link, rig.store, FAST_TIMING, Trace(), traceId = 1)
    private val returnHome = FlightCommand.of(FlightCommand.RETURN_HOME)
    private val retrace = FlightCommand.of(FlightCommand.RETRACE)

    private fun now() = System.nanoTime() / 1_000_000

    @After fun tearDown() = rig.close()

    private fun run(command: FlightCommand, legs: List<Ned>? = null, timeoutMs: Long = 8_000): StepUpdate {
        rig.events.drainAcks()
        var update = executor.start(command, rig.store.snapshot(), now(), legs = legs)
        val deadline = now() + timeoutMs
        while (update == StepUpdate.Running) {
            if (now() > deadline) throw AssertionError("step $command still running after $timeoutMs ms")
            Thread.sleep(5)
            update = executor.update(rig.store.snapshot(), rig.events.drainAcks(), now())
        }
        return update
    }

    private fun airborne() {
        assertEquals(StepUpdate.Completed(), run(FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to 3))))
        waitFor("hovering") { vehicle.speedNow() < 0.05 && abs(vehicle.altitude() - 3.0) < 0.05 }
    }

    private fun framesAfter(count: Int) = vehicle.positionTargets().drop(count)

    @Test fun eachLegIsSentOnceInLocalNedWithTheHeadingHeld() {
        airborne()
        vehicle.yaw = PI / 2 // facing east
        waitFor("east heading") { abs((rig.store.snapshot().yaw ?: 0.0) - PI / 2) < 1e-3 }
        val before = vehicle.positionTargets().size
        val legs = listOf(Ned(4.0, 0.0, -3.0), Ned(4.0, 3.0, -4.0))
        assertEquals(StepUpdate.Completed(), run(retrace, legs))
        val frames = framesAfter(before)
        assertEquals(2, frames.size)
        for ((frame, leg) in frames.zip(legs)) {
            assertEquals(ArduPilotExecutor.MAV_FRAME_LOCAL_NED, frame.coordinateFrame)
            assertEquals(ArduPilotExecutor.TYPE_MASK_POSITION_AND_YAW, frame.typeMask)
            assertEquals(Triple(leg.x.toFloat(), leg.y.toFloat(), leg.z.toFloat()), Triple(frame.x, frame.y, frame.z))
            assertEquals((PI / 2).toFloat(), frame.yaw)
        }
        val (n, e, d) = vehicle.position().toList()
        assertTrue("at the last leg: $n $e $d", abs(n - 4.0) < 0.5 && abs(e - 3.0) < 0.5 && abs(d + 4.0) < 0.5)
        assertTrue("the heading never turned to face a leg", abs(vehicle.yaw - PI / 2) < 1e-6)
        assertTrue("the second leg restarts the deadline", executor.legStartedAtMs > 0)
    }

    @Test fun theNextLegWaitsForTheLastToArrive() {
        airborne()
        vehicle.speed = 2.0
        val before = vehicle.positionTargets().size
        rig.events.drainAcks()
        var update = executor.start(retrace, rig.store.snapshot(), now(), legs = listOf(Ned(6.0, 0.0, -3.0), Ned(6.0, 4.0, -3.0)))
        waitFor("moving") { vehicle.speedNow() > 1.0 }
        update = executor.update(rig.store.snapshot(), rig.events.drainAcks(), now())
        assertEquals(StepUpdate.Running, update)
        assertEquals("one frame while the first leg flies", 1, framesAfter(before).size)
        while (update == StepUpdate.Running) {
            Thread.sleep(5)
            update = executor.update(rig.store.snapshot(), rig.events.drainAcks(), now())
        }
        assertEquals(StepUpdate.Completed(), update)
        assertEquals(2, framesAfter(before).size)
    }

    @Test fun alreadyThereCompletesWithNothingSent() {
        airborne()
        val before = vehicle.vehicleCommands().size
        assertEquals(StepUpdate.Completed(ArduPilotExecutor.ALREADY_THERE), run(returnHome, emptyList()))
        assertEquals(before, vehicle.vehicleCommands().size)
        assertEquals(0L, executor.legStartedAtMs)
    }

    @Test fun aLegOutsideTheHeightRangeIsRefusedBeforeAnythingIsSent() {
        airborne()
        val before = vehicle.vehicleCommands().size
        // 12 m and 0.5 m above home, from 3 m.
        for (z in listOf(-12.0, -0.5)) {
            assertEquals(StepUpdate.Rejected("Target exceeds simulator altitude limits."),
                run(retrace, listOf(Ned(2.0, 0.0, -3.0), Ned(2.0, 2.0, z))))
        }
        assertEquals(before, vehicle.vehicleCommands().size)
    }

    @Test fun aFlightMemoryStepWithoutARouteIsRefused() {
        airborne()
        assertEquals(StepUpdate.Rejected(ArduPilotExecutor.NO_ROUTE), run(returnHome, null))
    }

    @Test fun aLegWithoutItsEchoIsUnknownAndNothingMoreIsSent() {
        airborne()
        vehicle.echo = false
        val before = vehicle.positionTargets().size
        assertEquals(StepUpdate.Unknown(ArduPilotExecutor.NO_ECHO),
            run(retrace, listOf(Ned(3.0, 0.0, -3.0), Ned(3.0, 3.0, -3.0))))
        assertEquals(1, framesAfter(before).size)
    }

    @Test fun onTheGroundNothingIsSent() {
        assertEquals(StepUpdate.Rejected("Movement requires an airborne vehicle."), run(returnHome, listOf(Ned(3.0, 0.0, -3.0))))
        assertTrue(vehicle.vehicleCommands().none { it is SetPositionTargetLocalNed })
    }
}
