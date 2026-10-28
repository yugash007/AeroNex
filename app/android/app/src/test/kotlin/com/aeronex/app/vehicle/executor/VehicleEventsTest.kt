package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkMessage
import com.aeronex.app.link.mavlink.StatusText
import com.aeronex.app.vehicle.VehicleStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleEventsTest {
    private var now = 1_000L
    private val store = VehicleStateStore(clock = { now })
    private val events = VehicleEvents().also { store.addListener(it::onState) }

    private fun receive(message: MavlinkMessage) =
        store.accept(MavlinkFrame(2, 0, 1, 1, message.spec.messageId, ByteArray(0), message))

    private fun ack(command: Int, result: Int) = CommandAck(command, result, targetSystem = 255, targetComponent = 190)

    @Test fun everyAckAndStatusTextIsKeptInArrivalOrder() {
        receive(Heartbeat(2, 3, 1, 4, 3))
        receive(ack(176, 0))
        receive(ack(400, 4))
        receive(ack(176, 0)) // the same command again (a re-send's ACK) is a new event
        receive(StatusText(6, "EKF3 IMU0 MAG0 in-flight yaw alignment complete"))
        receive(StatusText(2, "Fence Breached"))
        assertEquals(listOf(176 to 0, 400 to 4, 176 to 0), events.drainAcks().map { it.command to it.result })
        assertEquals(listOf("EKF3 IMU0 MAG0 in-flight yaw alignment complete", "Fence Breached"), events.drainTexts().map { it.text })
        assertTrue(events.drainAcks().isEmpty())
        assertTrue(events.drainTexts().isEmpty())
    }

    @Test fun acksForAnotherGroundStationAreNotEvents() {
        receive(CommandAck(176, 0, targetSystem = 255, targetComponent = 230)) // MAVProxy's
        assertTrue(events.drainAcks().isEmpty())
    }

    @Test fun aResetDoesNotReplayOldEvents() {
        receive(ack(176, 0))
        events.drainAcks()
        store.reset()
        receive(Heartbeat(2, 3, 1, 4, 3))
        assertTrue(events.drainAcks().isEmpty())
        receive(ack(176, 0))
        assertEquals(1, events.drainAcks().size)
    }

    /** Texts and severities as ArduCopter 4.6.3 sends them. */
    @Test fun planAbortTextsAreWarningsOrWorseAndFenceBreaches() {
        assertTrue(VehicleEvents.abortsPlan(StatusText(2, "EKF Failsafe: changed to LAND Mode")))
        assertTrue(VehicleEvents.abortsPlan(StatusText(4, "Radio Failsafe - Continuing Auto Mode")))
        assertTrue(VehicleEvents.abortsPlan(StatusText(4, "GCS Failsafe")))
        assertTrue(VehicleEvents.abortsPlan(StatusText(4, "Battery 1 is low 10.40V used 1200 mAh")))
        assertTrue(VehicleEvents.abortsPlan(StatusText(2, "Dead Reckoning started")))
        assertTrue(VehicleEvents.abortsPlan(StatusText(4, "EKF3 IMU0 stopped aiding")))
        assertTrue(VehicleEvents.abortsPlan(StatusText(5, "Circle fence breached")))
        assertTrue(VehicleEvents.abortsPlan(StatusText(5, "Max Alt fence breached")))
        assertFalse("INFO, sent on every LAND", VehicleEvents.abortsPlan(StatusText(6, "PrecLand: Failsafe Measures")))
        assertFalse(VehicleEvents.abortsPlan(StatusText(6, "EKF3 IMU0 MAG0 in-flight yaw alignment complete")))
        assertFalse(VehicleEvents.abortsPlan(StatusText(6, "EKF3 IMU1 is using GPS")))
        assertFalse(VehicleEvents.abortsPlan(StatusText(6, "SIM Hit ground at 0.4 m/s")))
        assertFalse(VehicleEvents.abortsPlan(StatusText(6, "Arming motors")))
    }
}
