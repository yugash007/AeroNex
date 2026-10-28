package com.aeronex.app.safety

import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ExternalCause
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.executor.StopAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** The spec 4.12 action table. */
class StopPolicyTest {
    private fun vehicle(
        armed: Boolean = true,
        landed: Int? = VehicleState.LANDED_IN_AIR,
        owner: ModeOwner = ModeOwner.APP,
        cause: ExternalCause? = null,
        mode: Long = CopterMode.GUIDED,
        connected: Boolean = true,
        landedFresh: Boolean = true,
    ) = VehicleState(
        mode = mode, armed = armed, landedState = landed, modeOwner = owner, externalCause = cause,
        ageMs = buildMap {
            put(Heartbeat.messageId, if (connected) 100L else 3_000L)
            put(ExtendedSysState.messageId, if (landedFresh) 100L else 5_000L)
        },
    )

    private fun decide(state: VehicleState?, trigger: StopTrigger = StopTrigger.BUTTON, held: Boolean = false,
                       owns: Boolean = state?.modeOwner == ModeOwner.APP, execution: Boolean = true) =
        StopPolicy.decide(state, trigger, held, owns, execution)

    private val send = { action: StopAction -> StopDecision.Send(action, override = false) }

    @Test fun notConnectedSendsNothing() {
        assertEquals(StopDecision.NothingSent(StopPolicy.NOT_CONNECTED), decide(null))
        assertEquals(StopDecision.NothingSent(StopPolicy.NOT_CONNECTED), decide(vehicle(connected = false)))
    }

    @Test fun disarmedSendsNothing() {
        assertEquals(StopDecision.NothingSent(StopPolicy.DISARMED), decide(vehicle(armed = false)))
    }

    @Test fun anAcceptedArmThatNoHeartbeatShowsYetCountsAsArmed() {
        val justArmed = vehicle(armed = false, landed = VehicleState.LANDED_ON_GROUND)
        assertEquals(send(StopAction.DISARM), StopPolicy.decide(justArmed, StopTrigger.BUTTON, false,
            appOwnsMode = true, execution = true, armed = true))
    }

    @Test fun appOwnedAirborneOrUnknownBrakesForEveryTrigger() {
        for (landed in listOf(VehicleState.LANDED_IN_AIR, VehicleState.LANDED_TAKEOFF, VehicleState.LANDED_LANDING,
            VehicleState.LANDED_UNDEFINED, null)) {
            for (trigger in StopTrigger.entries) {
                assertEquals("$landed $trigger", send(StopAction.BRAKE), decide(vehicle(landed = landed), trigger))
            }
        }
        assertEquals("a stale landed state is unknown", send(StopAction.BRAKE),
            decide(vehicle(landed = VehicleState.LANDED_ON_GROUND, landedFresh = false)))
    }

    @Test fun appOwnedArmedOnTheGroundDisarms() {
        assertEquals(send(StopAction.DISARM), decide(vehicle(landed = VehicleState.LANDED_ON_GROUND)))
        assertEquals(send(StopAction.DISARM), decide(vehicle(landed = VehicleState.LANDED_ON_GROUND), StopTrigger.VOICE))
    }

    @Test fun externalSendsNothingWithoutTheOneSecondButtonHold() {
        val pilot = vehicle(owner = ModeOwner.EXTERNAL, cause = ExternalCause.PILOT_MODE, mode = CopterMode.LOITER)
        for (trigger in StopTrigger.entries) {
            assertEquals(trigger.name, StopDecision.NothingSent("Pilot mode LOITER, use RC"), decide(pilot, trigger))
            if (trigger != StopTrigger.BUTTON) {
                assertEquals("$trigger never overrides", StopDecision.NothingSent("Pilot mode LOITER, use RC"),
                    decide(pilot, trigger, held = true))
            }
        }
        assertEquals(StopDecision.Send(StopAction.BRAKE, override = true), decide(pilot, held = true))
        val failsafe = vehicle(owner = ModeOwner.EXTERNAL, cause = ExternalCause.AUTOPILOT_FAILSAFE, mode = CopterMode.LAND)
        assertEquals(StopDecision.NothingSent("Autopilot failsafe active, use RC"), decide(failsafe, StopTrigger.VOLUME_DOWN))
        assertEquals(StopDecision.Send(StopAction.BRAKE, override = true), decide(failsafe, held = true))
        val onGround = vehicle(owner = ModeOwner.EXTERNAL, cause = ExternalCause.PILOT_MODE, landed = VehicleState.LANDED_ON_GROUND)
        assertEquals(StopDecision.Send(StopAction.DISARM, override = true), decide(onGround, held = true))
    }

    @Test fun theAppsJustAcceptedModeCountsAsOwned() {
        val justAccepted = vehicle(owner = ModeOwner.EXTERNAL, cause = ExternalCause.PILOT_MODE)
        assertEquals(send(StopAction.BRAKE), decide(justAccepted, owns = true))
    }

    @Test fun dryRunOnlyStopsWhatTheAppItselfStarted() {
        assertEquals(send(StopAction.BRAKE), decide(vehicle(), execution = false))
        val pilot = vehicle(owner = ModeOwner.EXTERNAL, cause = ExternalCause.PILOT_MODE, mode = CopterMode.LOITER)
        assertEquals(StopDecision.NothingSent(StopPolicy.DRY_RUN), decide(pilot, held = true, execution = false))
    }

    /** Audit L2: after a link gap that left the app's mode in place, STOP sends without the 1 s hold. */
    @Test fun aLinkGapWithTheAppsModeStillInPlaceStopsWithoutTheHold() {
        val gap = vehicle(owner = ModeOwner.EXTERNAL, cause = ExternalCause.LINK_GAP).copy(appModeAfterGap = true)
        assertEquals(send(StopAction.BRAKE), decide(gap, StopTrigger.VOICE, owns = false))
        assertEquals(send(StopAction.BRAKE), decide(gap, StopTrigger.VOLUME_DOWN, owns = false))
        val pilot = vehicle(owner = ModeOwner.EXTERNAL, cause = ExternalCause.PILOT_MODE)
        assertEquals(StopDecision.NothingSent("Pilot mode GUIDED, use RC"), decide(pilot, StopTrigger.VOICE, owns = false))
        assertEquals(StopPolicy.LINK_GAP, StopPolicy.externalBanner(gap))
    }

}
