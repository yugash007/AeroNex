package com.aeronex.app.vehicle

import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.HomePosition
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkMessage
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.link.mavlink.StatusText
import com.aeronex.app.link.mavlink.SysStatus
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Builds [VehicleState] from received frames (v0.2.0 `FlightTelemetry` rules, plus mode ownership, home,
 * STATUSTEXT, POSITION_TARGET echo and COMMAND_ACKs).
 *
 * - Only frames from [targetSystem]/[targetComponent] count. A HEARTBEAT from them that is not an ArduPilot
 *   (autopilot 3) quadrotor (type 2) forgets the vehicle, as in v0.2.0.
 * - A message with a non-finite value used here is dropped whole: it neither refreshes nor overwrites.
 * - The first vehicle HEARTBEAT after [reset] or after the heartbeat went stale is a connect for
 *   [ModeOwnership]: its mode is EXTERNAL until the app's next ACCEPTED DO_SET_MODE ([noteCommandedMode]).
 *
 * Thread-safe. [clock] is monotonic milliseconds. Listeners run on the calling thread, in order, while the store
 * is locked: they must be quick and must not block on another thread that uses this store.
 */
class VehicleStateStore(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val targetSystem: Int = 1,
    val targetComponent: Int = 1,
    /** COMMAND_ACKs addressed to another GCS are ignored; 0 (broadcast) is accepted. */
    val gcsSystem: Int = 255,
    val gcsComponent: Int = 190,
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(VehicleState) -> Unit>()
    private val ownership = ModeOwnership()
    /** Decoded fields; ages, samples and ownership are filled in by [snapshot]. */
    private var fields = VehicleState()
    private val receivedAt = HashMap<Int, Long>()
    private val samples = HashMap<Int, Long>()

    /** Per-message freshness of the link's telemetry profile ([VehicleState.freshMs]); kept across [reset]. */
    @Volatile var freshness: Map<Int, Long> = emptyMap()

    fun addListener(listener: (VehicleState) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (VehicleState) -> Unit) {
        listeners -= listener
    }

    fun snapshot(): VehicleState = synchronized(lock) { snapshotAt(clock()) }

    /** Feed every received frame (RX thread). */
    fun accept(frame: MavlinkFrame) {
        if (frame.sysId != targetSystem || frame.compId != targetComponent) return
        synchronized(lock) {
            val now = clock()
            val message = frame.message
            if (message is Heartbeat && (message.autopilot != AUTOPILOT_ARDUPILOT || message.type != TYPE_QUADROTOR)) {
                forget()
            } else {
                if (!update(message, now)) return
                receivedAt[frame.messageId] = now
                samples[frame.messageId] = (samples[frame.messageId] ?: 0) + 1
            }
            notifyAt(now)
        }
    }

    /** The executor got an ACCEPTED COMMAND_ACK for its DO_SET_MODE to [mode]. */
    fun noteCommandedMode(mode: Long) = synchronized(lock) {
        ownership.noteCommandedMode(mode)
        notifyAt(clock())
    }

    /** Forget the vehicle; call on every transport (re)connect. Sample counts are kept. */
    fun reset() = synchronized(lock) {
        forget()
        notifyAt(clock())
    }

    private fun forget() {
        fields = VehicleState()
        receivedAt.clear()
        ownership.reset()
    }

    /** Applies [message] to [fields]; false when it is ignored. */
    private fun update(message: MavlinkMessage, now: Long): Boolean {
        fields = when (message) {
            is Heartbeat -> {
                if (isFresh(Heartbeat.messageId, now)) ownership.heartbeat(message.customMode, now)
                else ownership.connected(message.customMode, now)
                fields.copy(mode = message.customMode, armed = (message.baseMode and MODE_FLAG_SAFETY_ARMED) != 0)
            }
            is SysStatus -> fields.copy(
                healthy = (message.onboardControlSensorsEnabled and message.onboardControlSensorsHealth) ==
                    message.onboardControlSensorsEnabled,
                batteryPercent = message.batteryRemaining.takeIf { it >= 0 },
            )
            is Attitude -> {
                if (!message.yaw.isFinite()) return false
                fields.copy(yaw = message.yaw.toDouble())
            }
            is LocalPositionNed -> with(message) {
                if (!finite(x, y, z, vx, vy, vz)) return false
                fields.copy(position = ned(x, y, z), velocity = ned(vx, vy, vz))
            }
            is GlobalPositionInt -> fields.copy(altitudeRelM = message.relativeAlt / 1000.0)
            is ExtendedSysState -> fields.copy(landedState = message.landedState)
            is EkfStatusReport -> fields.copy(ekfFlags = message.flags)
            is HomePosition -> with(message) {
                if (!finite(x, y, z)) return false
                fields.copy(home = ned(x, y, z))
            }
            is StatusText -> {
                ownership.statusText(message.severity, message.text, now)
                fields.copy(lastStatusText = Received(message, now))
            }
            is PositionTargetLocalNed -> fields.copy(lastPositionTarget = Received(message, now))
            is CommandAck -> {
                if (message.targetSystem != 0 && message.targetSystem != gcsSystem ||
                    message.targetComponent != 0 && message.targetComponent != gcsComponent
                ) return false
                fields.copy(acks = fields.acks + (message.command to
                    Received(message, now, heartbeats = samples[Heartbeat.messageId] ?: 0)))
            }
            else -> return false
        }
        return true
    }

    private fun isFresh(messageId: Int, now: Long) =
        receivedAt[messageId].let { it != null && now - it >= 0 && now - it < VehicleState.FRESH_MS }

    private fun snapshotAt(now: Long): VehicleState {
        val state = fields.copy(atMs = now, ageMs = receivedAt.mapValues { now - it.value }, samples = samples.toMap(),
            freshMs = freshness)
        return if (state.connected) {
            state.copy(modeOwner = ownership.owner, externalCause = ownership.cause, appModeAfterGap = ownership.resumedAppMode)
        } else state.copy(modeOwner = ModeOwner.EXTERNAL, externalCause = null)
    }

    private fun notifyAt(now: Long) {
        if (listeners.isEmpty()) return
        val state = snapshotAt(now)
        for (listener in listeners) listener(state)
    }

    private companion object {
        const val AUTOPILOT_ARDUPILOT = 3
        const val TYPE_QUADROTOR = 2
        const val MODE_FLAG_SAFETY_ARMED = 128

        fun finite(vararg values: Float) = values.all { it.isFinite() }
        fun ned(x: Float, y: Float, z: Float) = Ned(x.toDouble(), y.toDouble(), z.toDouble())
    }
}
