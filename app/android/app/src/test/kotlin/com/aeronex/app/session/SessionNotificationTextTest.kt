package com.aeronex.app.session

import com.aeronex.app.safety.StopPhase
import com.aeronex.app.safety.StopPolicy
import com.aeronex.app.safety.StopStatus
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ExternalCause
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.executor.StopAction
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNotificationTextTest {
    private val up = LinkInfo(LinkState.UP, "udp", "UDP 10.0.0.1:14550 local *:0", "radio", unsigned = true,
        heartbeatAgeMs = 200, txErrors = 0, droppedDatagrams = 0, foregroundService = true)
    private val connected = VehicleState(mode = CopterMode.GUIDED, armed = true, modeOwner = ModeOwner.APP, ageMs = mapOf(0 to 100L))

    @Test fun showsTheLinkTheModeAndItsOwner() {
        assertEquals("Not connected", SessionNotificationText.line(LinkInfo.down(true), VehicleState(), null))
        assertEquals("Waiting for the vehicle", SessionNotificationText.line(
            up.copy(state = LinkState.CONNECTING, heartbeatAgeMs = null), VehicleState(), null))
        assertEquals("Link lost, use RC", SessionNotificationText.line(
            up.copy(state = LinkState.CONNECTING, heartbeatAgeMs = 4_000), VehicleState(), null))
        assertEquals("Link up · GUIDED (app) · Armed", SessionNotificationText.line(up, connected, null))
        assertEquals("Link up · LOITER (Pilot mode) · Armed", SessionNotificationText.line(up,
            connected.copy(mode = CopterMode.LOITER, modeOwner = ModeOwner.EXTERNAL, externalCause = ExternalCause.PILOT_MODE), null))
        assertEquals("Link up · STABILIZE · Disarmed", SessionNotificationText.line(up,
            connected.copy(mode = CopterMode.STABILIZE, armed = false, modeOwner = ModeOwner.EXTERNAL), null))
    }

    @Test fun anUnconfirmedStopTakesTheLine() {
        val alarm = StopStatus(3, StopTrigger.NOTIFICATION, StopPhase.NOT_CONFIRMED, StopAction.BRAKE,
            StopPolicy.NOT_CONFIRMED, alarm = true, override = false)
        assertEquals(StopPolicy.NOT_CONFIRMED, SessionNotificationText.line(up, connected, alarm))
        assertEquals("Link up · GUIDED (app) · Armed", SessionNotificationText.line(up, connected, alarm.copy(alarm = false)))
    }
}
