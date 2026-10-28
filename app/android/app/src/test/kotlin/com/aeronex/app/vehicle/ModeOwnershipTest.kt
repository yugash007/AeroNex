package com.aeronex.app.vehicle

import com.aeronex.app.vehicle.CopterMode.BRAKE
import com.aeronex.app.vehicle.CopterMode.GUIDED
import com.aeronex.app.vehicle.CopterMode.LAND
import com.aeronex.app.vehicle.CopterMode.LOITER
import com.aeronex.app.vehicle.CopterMode.RTL
import org.junit.Assert.*
import org.junit.Test

class ModeOwnershipTest {
    private val ownership = ModeOwnership()

    private fun assertOwner(owner: ModeOwner, cause: ExternalCause?) {
        assertEquals(owner, ownership.owner)
        assertEquals(cause, ownership.cause)
    }

    @Test fun nothingKnownIsExternalWithoutALabel() {
        assertOwner(ModeOwner.EXTERNAL, null)
        ownership.noteCommandedMode(GUIDED)
        assertOwner(ModeOwner.EXTERNAL, null)
    }

    @Test fun theModeFoundAtConnectIsExternalEvenWhenItIsGuided() {
        ownership.connected(GUIDED, 0)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        ownership.heartbeat(GUIDED, 1000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        // Commanding the mode the vehicle is already in takes ownership at once.
        ownership.noteCommandedMode(GUIDED)
        assertOwner(ModeOwner.APP, null)
    }

    @Test fun anAcceptedModeIsOwnedOnceTheHeartbeatShowsIt() {
        ownership.connected(LOITER, 0)
        ownership.noteCommandedMode(GUIDED)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        ownership.heartbeat(GUIDED, 1000)
        assertOwner(ModeOwner.APP, null)
        ownership.heartbeat(GUIDED, 2000)
        assertOwner(ModeOwner.APP, null)
    }

    @Test fun theHeartbeatMayShowTheCommandedModeBeforeTheAckIsNoted() {
        ownership.connected(LOITER, 0)
        ownership.heartbeat(GUIDED, 1000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        ownership.noteCommandedMode(GUIDED)
        assertOwner(ModeOwner.APP, null)
    }

    @Test fun aHeartbeatInAnotherModeAfterTheAckIsAnUncommandedChange() {
        ownership.connected(LOITER, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.heartbeat(LOITER, 1000)
        ownership.heartbeat(GUIDED, 2000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
    }

    @Test fun anUncommandedChangeBackToTheCommandedModeStaysExternal() {
        ownership.connected(LOITER, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.heartbeat(GUIDED, 1000)
        ownership.heartbeat(LOITER, 2000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        ownership.heartbeat(GUIDED, 3000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        // A newly accepted command takes ownership back.
        ownership.noteCommandedMode(BRAKE)
        ownership.heartbeat(BRAKE, 4000)
        assertOwner(ModeOwner.APP, null)
    }

    @Test fun aFailsafeTextWithinTwoSecondsBeforeTheChangeLabelsIt() {
        ownership.connected(GUIDED, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.statusText(4, "Battery 1 is low 10.10V used 4000 mAh", 10_000)
        ownership.heartbeat(LAND, 12_000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.AUTOPILOT_FAILSAFE)
    }

    @Test fun aFailsafeTextWithinTwoSecondsAfterTheChangeRelabelsIt() {
        ownership.connected(GUIDED, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.heartbeat(RTL, 10_000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        ownership.statusText(6, "Fence breach", 12_000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.AUTOPILOT_FAILSAFE)
        // A later, unrelated failsafe text keeps the label of this change.
        ownership.statusText(4, "EKF variance", 30_000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.AUTOPILOT_FAILSAFE)
    }

    @Test fun failsafeTextsOutsideTheWindowLeaveItPilotMode() {
        ownership.connected(GUIDED, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.statusText(2, "GPS Glitch", 7_999)
        ownership.heartbeat(LOITER, 10_000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        ownership.statusText(2, "GPS Glitch", 12_001)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
    }

    @Test fun eachUncommandedChangeIsLabelledOnItsOwn() {
        ownership.connected(GUIDED, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.statusText(6, "Battery failsafe", 10_000)
        ownership.heartbeat(LAND, 10_500)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.AUTOPILOT_FAILSAFE)
        // The pilot then takes over with the mode switch.
        ownership.heartbeat(LOITER, 20_000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
    }

    @Test fun aFailsafeTextWhileTheAppOwnsTheModeChangesNothing() {
        ownership.connected(LOITER, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.heartbeat(GUIDED, 1000)
        ownership.statusText(0, "Crash: Disarming", 1500)
        assertOwner(ModeOwner.APP, null)
    }

    @Test fun reconnectingForgetsTheCommandedMode() {
        ownership.connected(LOITER, 0)
        ownership.noteCommandedMode(GUIDED)
        ownership.heartbeat(GUIDED, 1000)
        ownership.connected(GUIDED, 9000)
        assertOwner(ModeOwner.EXTERNAL, ExternalCause.PILOT_MODE)
        ownership.reset()
        assertOwner(ModeOwner.EXTERNAL, null)
    }

    @Test fun classifiesFailsafeTexts() {
        for (severity in 0..4) assertTrue(ModeOwnership.isFailsafeText(severity, "Mission: 1 WP"))
        for (text in listOf("Radio Failsafe", "FENCE breach", "Battery 1 is critical", "EKF3 lane switch 1"))
            assertTrue(text, ModeOwnership.isFailsafeText(6, text))
        assertFalse(ModeOwnership.isFailsafeText(5, "Mission: 1 WP"))
        assertFalse(ModeOwnership.isFailsafeText(6, "Throttle armed"))
    }
}
