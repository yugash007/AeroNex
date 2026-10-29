package com.aeronex.app.vehicle

import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.link.mavlink.StatusText
import kotlin.math.hypot
import kotlin.math.sqrt

/** A local NED vector in metres (or m/s): x north, y east, z down. */
data class Ned(val x: Double, val y: Double, val z: Double) {
    fun horizontalDistanceTo(other: Ned) = hypot(x - other.x, y - other.y)
}

/** Who chose the current flight mode (spec 4.12). */
enum class ModeOwner { APP, EXTERNAL }

/** Why the mode is [ModeOwner.EXTERNAL], for the UI. The STOP behaviour is the same for both. */
enum class ExternalCause(val label: String) {
    AUTOPILOT_FAILSAFE("Autopilot failsafe"),
    PILOT_MODE("Pilot mode"),
    /** The link stalled; the vehicle still shows the app's mode ([VehicleState.appModeAfterGap]). */
    LINK_GAP("Link gap"),
}

/**
 * A message and the store clock time it arrived at. [heartbeats] is the vehicle HEARTBEAT count when it arrived
 * (COMMAND_ACKs), so a mode ACK can be ordered against the heartbeats around it.
 */
data class Received<out T>(val message: T, val atMs: Long, val heartbeats: Long = 0)

/**
 * An immutable snapshot of the target vehicle (sys/comp 1/1), taken at [atMs] on the store's monotonic clock.
 * A null field has not been received with finite values since the last reset; other fields keep their last
 * value, so check [fresh] for the message before relying on one. Freshness follows v0.2.0: a message is fresh
 * while its age is in [0, [FRESH_MS]).
 */
data class VehicleState(
    val atMs: Long = 0,
    /** HEARTBEAT custom_mode (ArduCopter mode number, see [CopterMode]). */
    val mode: Long? = null,
    val armed: Boolean? = null,
    /** EXTENDED_SYS_STATE MAV_LANDED_STATE, see [LANDED_ON_GROUND] and siblings. */
    val landedState: Int? = null,
    /** Always EXTERNAL while not [connected]. */
    val modeOwner: ModeOwner = ModeOwner.EXTERNAL,
    /** Set while connected and EXTERNAL. */
    val externalCause: ExternalCause? = null,
    /**
     * EXTERNAL only because of a heartbeat gap, the vehicle still in the app's mode: STOP sends without the override
     * hold; new plans stay blocked until the app sets a mode again (audit L2).
     */
    val appModeAfterGap: Boolean = false,
    /** GLOBAL_POSITION_INT relative_alt, metres above home. */
    val altitudeRelM: Double? = null,
    /** LOCAL_POSITION_NED position. */
    val position: Ned? = null,
    /** LOCAL_POSITION_NED velocity. */
    val velocity: Ned? = null,
    /** ATTITUDE yaw, radians. */
    val yaw: Double? = null,
    /** SYS_STATUS battery_remaining; null when the autopilot reports it unknown (-1). */
    val batteryPercent: Int? = null,
    /** SYS_STATUS: every enabled onboard sensor is healthy (the v0.2.0 rule). */
    val healthy: Boolean? = null,
    val ekfFlags: Int? = null,
    /** HOME_POSITION x/y/z in the LOCAL_POSITION_NED frame; null until home is known. */
    val home: Ned? = null,
    /** The frame [home] (and [position]) are in, from the same HOME_POSITION ([FrameOrigin.of]). */
    val origin: FrameOrigin? = null,
    val lastStatusText: Received<StatusText>? = null,
    /** The last POSITION_TARGET_LOCAL_NED echo; [positionTargetCount] counts them. */
    val lastPositionTarget: Received<PositionTargetLocalNed>? = null,
    /** The last COMMAND_ACK addressed to this GCS (or broadcast), per command id, any result. */
    val acks: Map<Int, Received<CommandAck>> = emptyMap(),
    /** Age in ms of the last accepted message, per message id. */
    val ageMs: Map<Int, Long> = emptyMap(),
    /** Count of accepted messages per id; never reset, so "a later sample" comparisons survive a reset. */
    val samples: Map<Int, Long> = emptyMap(),
    /**
     * Per message id, how long it stays fresh when longer than [FRESH_MS]: a slow stream's interval allowance
     * ([com.aeronex.app.link.TelemetryProfile.freshnessMs]). Empty means [FRESH_MS] for every message.
     */
    val freshMs: Map<Int, Long> = emptyMap(),
) {
    val heartbeatAgeMs: Long? get() = ageMs[Heartbeat.messageId]
    val connected: Boolean get() = fresh(Heartbeat.messageId)
    val speed: Double? get() = velocity?.let { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
    val modeOwnerLabel: String? get() = externalCause?.label
    val positionTargetCount: Long get() = samples[PositionTargetLocalNed.messageId] ?: 0

    /** The v0.2.0 rule: a fresh EKF report with attitude, horizontal velocity and absolute position, no GPS glitch. */
    val positionReady: Boolean
        get() = fresh(EkfStatusReport.messageId) &&
            ekfFlags.let { it != null && (it and 51) == 51 && (it and (128 or 1024)) == 0 }

    fun fresh(messageId: Int): Boolean =
        ageMs[messageId].let { it != null && it >= 0 && it < (freshMs[messageId] ?: FRESH_MS) }

    companion object {
        const val FRESH_MS = 3000L
        const val LANDED_UNDEFINED = 0
        const val LANDED_ON_GROUND = 1
        const val LANDED_IN_AIR = 2
        const val LANDED_TAKEOFF = 3
        const val LANDED_LANDING = 4
    }
}
