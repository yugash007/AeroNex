package com.aeronex.app.vehicle

import com.aeronex.app.link.mavlink.*
import com.aeronex.app.vehicle.CopterMode.GUIDED
import com.aeronex.app.vehicle.CopterMode.LAND
import com.aeronex.app.vehicle.CopterMode.LOITER
import org.junit.Assert.*
import org.junit.Test

class VehicleStateStoreTest {
    private var now = 1_000_000L
    private val store = VehicleStateStore(clock = { now })

    private fun frame(message: MavlinkMessage, sysId: Int = 1, compId: Int = 1) =
        MavlinkFrame(2, 0, sysId, compId, message.spec.messageId, ByteArray(0), message)

    private fun receive(message: MavlinkMessage, sysId: Int = 1, compId: Int = 1) =
        store.accept(frame(message, sysId, compId))

    private fun heartbeat(mode: Long, armed: Boolean = false, type: Int = 2, autopilot: Int = 3) =
        Heartbeat(type, autopilot, if (armed) 128 or 1 else 1, mode, 4)

    private fun position(x: Float, y: Float, z: Float, vx: Float = 0f, vy: Float = 0f, vz: Float = 0f) =
        LocalPositionNed(0, x, y, z, vx, vy, vz)

    private fun attitude(yaw: Float) = Attitude(0, 0f, 0f, yaw, 0f, 0f, 0f)
    private fun ekf(flags: Int) = EkfStatusReport(flags, 0f, 0f, 0f, 0f, 0f)
    private fun sysStatus(enabled: Long, health: Long, battery: Int) = SysStatus(0, enabled, health, 0, 0, 0, battery)
    private fun home(x: Float, y: Float, z: Float) =
        HomePosition(0, 0, 0, x, y, z, listOf(1f, 0f, 0f, 0f), 0f, 0f, 0f)

    @Test fun nothingReceivedIsDisconnectedAndEmpty() {
        val state = store.snapshot()
        assertFalse(state.connected)
        assertNull(state.heartbeatAgeMs)
        assertNull(state.mode)
        assertFalse(state.positionReady)
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        assertNull(state.modeOwnerLabel)
    }

    @Test fun ignoresOtherVehiclesAndTurnsStaleAfterThreeSeconds() {
        receive(heartbeat(GUIDED), sysId = 2)
        receive(heartbeat(GUIDED), compId = 0)
        assertFalse(store.snapshot().connected)
        receive(heartbeat(GUIDED, armed = true))
        var state = store.snapshot()
        assertTrue(state.connected)
        assertEquals(0L, state.heartbeatAgeMs)
        assertEquals(GUIDED, state.mode)
        assertEquals(true, state.armed)
        now += 2999
        assertTrue(store.snapshot().connected)
        now += 1
        state = store.snapshot()
        assertFalse(state.connected)
        assertEquals(3000L, state.heartbeatAgeMs)
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        assertNull(state.modeOwnerLabel)
    }

    @Test fun aBackwardClockStepNeverMakesTelemetryFresh() {
        receive(heartbeat(GUIDED))
        now -= 60_000
        assertFalse(store.snapshot().connected)
    }

    @Test fun aNonAutopilotHeartbeatFromTheTargetClearsTheVehicle() {
        receive(heartbeat(GUIDED))
        receive(position(1f, 2f, -3f))
        receive(heartbeat(GUIDED, type = 6))
        var state = store.snapshot()
        assertFalse(state.connected)
        assertNull(state.position)
        assertNull(state.mode)
        receive(heartbeat(GUIDED))
        receive(heartbeat(GUIDED, autopilot = 8))
        state = store.snapshot()
        assertFalse(state.connected)
    }

    @Test fun decodesTheV020TelemetryFields() {
        receive(heartbeat(LOITER, armed = true))
        receive(sysStatus(enabled = 0b1011, health = 0b1111, battery = 76))
        receive(attitude(1.5f))
        receive(position(2f, 3f, -4f, vx = 3f, vy = 4f, vz = 0f))
        receive(GlobalPositionInt(0, 0, 0, 0, 4250, 0, 0, 0, 0))
        receive(ekf(0x33))
        receive(ExtendedSysState(0, VehicleState.LANDED_IN_AIR))
        val state = store.snapshot()
        assertEquals(LOITER, state.mode)
        assertEquals(true, state.armed)
        assertEquals(true, state.healthy)
        assertEquals(76, state.batteryPercent)
        assertEquals(1.5, state.yaw!!, 1e-6)
        assertEquals(Ned(2.0, 3.0, -4.0), state.position)
        assertEquals(Ned(3.0, 4.0, 0.0), state.velocity)
        assertEquals(5.0, state.speed!!, 1e-9)
        assertEquals(4.25, state.altitudeRelM!!, 1e-9)
        assertEquals(0x33, state.ekfFlags)
        assertEquals(VehicleState.LANDED_IN_AIR, state.landedState)
        for (id in listOf(0, 1, 30, 32, 33, 193, 245)) assertTrue("fresh $id", state.fresh(id))
        assertFalse(state.fresh(242))
    }

    @Test fun healthNeedsEveryEnabledSensorHealthyAndUnknownBatteryIsNull() {
        receive(sysStatus(enabled = 0b1011, health = 0b0011, battery = -1))
        val state = store.snapshot()
        assertEquals(false, state.healthy)
        assertNull(state.batteryPercent)
    }

    @Test fun nonFiniteValuesNeitherRefreshNorOverwrite() {
        receive(position(1f, 1f, -1f))
        now += 4000
        receive(position(1f, 1f, -1f, vx = Float.NaN))
        receive(attitude(Float.POSITIVE_INFINITY))
        receive(home(Float.NaN, 0f, 0f))
        val state = store.snapshot()
        assertFalse(state.fresh(32))
        assertFalse(state.fresh(30))
        assertEquals(Ned(1.0, 1.0, -1.0), state.position)
        assertEquals(0.0, state.speed!!, 0.0)
        assertNull(state.yaw)
        assertNull(state.home)
    }

    @Test fun positionReadinessRequiresAFreshAbsoluteEkfSolution() {
        assertFalse(store.snapshot().positionReady)
        receive(ekf(3))
        assertFalse(store.snapshot().positionReady)
        receive(ekf(51))
        assertTrue(store.snapshot().positionReady)
        receive(ekf(51 or 128))
        assertFalse(store.snapshot().positionReady)
        receive(ekf(51 or 1024))
        assertFalse(store.snapshot().positionReady)
        receive(ekf(51))
        now += 3000
        assertFalse(store.snapshot().positionReady)
    }

    @Test fun recordsHomeStatusTextPositionTargetEchoAndAcks() {
        receive(home(1f, -2f, 0.5f))
        receive(StatusText(4, "Battery 1 is low"))
        receive(PositionTargetLocalNed(0, 1, 3576, 5f, 0f, -3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        now += 200
        receive(PositionTargetLocalNed(0, 1, 3576, 5f, 0f, -3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        receive(CommandAck(176, 0, targetSystem = 255, targetComponent = 190))
        receive(CommandAck(400, 4, targetSystem = 0, targetComponent = 0))
        receive(CommandAck(22, 0, targetSystem = 254, targetComponent = 190))
        receive(CommandAck(21, 0, targetSystem = 255, targetComponent = 1))
        now += 100
        val state = store.snapshot()
        assertEquals(Ned(1.0, -2.0, 0.5), state.home)
        assertEquals(FrameOrigin.of(home(1f, -2f, 0.5f)), state.origin)
        assertEquals(StatusText(4, "Battery 1 is low"), state.lastStatusText!!.message)
        assertEquals(1_000_000L, state.lastStatusText!!.atMs)
        assertEquals(2L, state.positionTargetCount)
        assertEquals(1_000_200L, state.lastPositionTarget!!.atMs)
        assertEquals(5f, state.lastPositionTarget!!.message.x)
        assertEquals(setOf(176, 400), state.acks.keys)
        assertEquals(0, state.acks.getValue(176).message.result)
        assertEquals(4, state.acks.getValue(400).message.result)
        assertEquals(1_000_300L, state.atMs)
        assertEquals(100L, state.ageMs[85])
        assertEquals(300L, state.ageMs[242])
    }

    @Test fun sampleCountsNeverReset() {
        receive(position(0f, 0f, 0f))
        receive(position(0f, 0f, 0f))
        store.reset()
        receive(position(0f, 0f, 0f))
        assertEquals(3L, store.snapshot().samples[32])
    }

    @Test fun listenersSeeEveryAcceptedChange() {
        val seen = mutableListOf<VehicleState>()
        val listener: (VehicleState) -> Unit = { seen += it }
        store.addListener(listener)
        receive(heartbeat(LOITER), sysId = 3)
        assertTrue(seen.isEmpty())
        receive(heartbeat(LOITER))
        store.noteCommandedMode(GUIDED)
        receive(heartbeat(GUIDED))
        assertEquals(listOf(ModeOwner.EXTERNAL, ModeOwner.EXTERNAL, ModeOwner.APP), seen.map { it.modeOwner })
        store.removeListener(listener)
        store.reset()
        assertEquals(3, seen.size)
    }

    @Test fun resetForgetsTheVehicle() {
        receive(heartbeat(GUIDED))
        receive(home(0f, 0f, 0f))
        store.reset()
        val state = store.snapshot()
        assertFalse(state.connected)
        assertNull(state.home)
        assertTrue(state.ageMs.isEmpty())
    }

    // Mode ownership through real frames (spec 4.12).

    @Test fun connectingInLoiterIsExternalPilotMode() {
        receive(heartbeat(LOITER))
        val state = store.snapshot()
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        assertEquals("Pilot mode", state.modeOwnerLabel)
    }

    @Test fun anAcceptedGuidedIsOwnedByTheApp() {
        receive(heartbeat(LOITER))
        store.noteCommandedMode(GUIDED)
        now += 1000
        receive(heartbeat(GUIDED, armed = true))
        val state = store.snapshot()
        assertEquals(ModeOwner.APP, state.modeOwner)
        assertNull(state.modeOwnerLabel)
    }

    @Test fun anRcSwitchToLoiterIsExternalPilotMode() {
        receive(heartbeat(LOITER))
        store.noteCommandedMode(GUIDED)
        now += 1000
        receive(heartbeat(GUIDED, armed = true))
        now += 1000
        receive(heartbeat(LOITER, armed = true))
        val state = store.snapshot()
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        assertEquals("Pilot mode", state.modeOwnerLabel)
    }

    @Test fun aBatteryFailsafeLandIsExternalAutopilotFailsafe() {
        receive(heartbeat(LOITER))
        store.noteCommandedMode(GUIDED)
        now += 1000
        receive(heartbeat(GUIDED, armed = true))
        now += 1000
        receive(StatusText(2, "Battery 1 is critical 9.90V used 4500 mAh"))
        now += 400
        receive(heartbeat(LAND, armed = true))
        val state = store.snapshot()
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        assertEquals("Autopilot failsafe", state.modeOwnerLabel)
    }

    @Test fun aHeartbeatAfterAStaleLinkIsAFreshConnect() {
        receive(heartbeat(LOITER))
        store.noteCommandedMode(GUIDED)
        receive(heartbeat(GUIDED, armed = true))
        assertEquals(ModeOwner.APP, store.snapshot().modeOwner)
        now += 3000
        receive(heartbeat(GUIDED, armed = true))
        val state = store.snapshot()
        assertEquals(ModeOwner.EXTERNAL, state.modeOwner)
        // Audit L2: the app's own mode survived the gap, so STOP may send without the hold; plans stay blocked.
        assertEquals("Link gap", state.modeOwnerLabel)
        assertTrue(state.appModeAfterGap)
        receive(heartbeat(LOITER, armed = true))
        assertEquals("Pilot mode", store.snapshot().modeOwnerLabel)
        assertFalse(store.snapshot().appModeAfterGap)
    }

    /** Audit L5: a slow stream's message stays fresh for its own allowance; others keep 3 s. */
    @Test fun perMessageFreshnessFollowsTheProfile() {
        store.freshness = mapOf(GlobalPositionInt.messageId to 5_000L)
        receive(heartbeat(GUIDED))
        receive(GlobalPositionInt(0, 0, 0, 0, 3000, 0, 0, 0, 0))
        now += 4_000
        receive(heartbeat(GUIDED))
        val state = store.snapshot()
        assertTrue(state.fresh(GlobalPositionInt.messageId))
        now += 1_000
        assertFalse(store.snapshot().fresh(GlobalPositionInt.messageId))
        store.reset()
        assertEquals("kept across a reset", mapOf(GlobalPositionInt.messageId to 5_000L), store.freshness)
    }

    /** Audit L7: an ACK carries the heartbeat count at its arrival, so it can be ordered against heartbeats. */
    @Test fun anAckRecordsTheHeartbeatCountAtItsArrival() {
        receive(heartbeat(GUIDED))
        receive(heartbeat(GUIDED))
        receive(CommandAck(176, 0, targetSystem = 255, targetComponent = 190))
        receive(heartbeat(LOITER))
        val state = store.snapshot()
        assertEquals("two heartbeats came before the ACK", 2L, state.acks.getValue(176).heartbeats)
        assertEquals(3L, state.samples[Heartbeat.messageId])
    }

}
