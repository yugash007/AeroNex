package com.aeronex.app.link

import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.HomePosition
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.SysStatus
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Telemetry rates requested with SET_MESSAGE_INTERVAL on every (re)connect (spec 4.15).
 *
 * [rates] maps message id to Hz. POSITION_TARGET_LOCAL_NED is not in [rates]: it streams at [positionTargetHz]
 * only while a move is pending ([LinkSession.setPositionTargetStreaming]) and is disabled otherwise. HOME_POSITION
 * is requested once per connect and also streams slowly (0.2 Hz, about 12 B/s): ArduPilot sends it only once home is
 * set, and a single lost datagram used to leave home unknown for the whole session, which also disabled the 30 m
 * home-distance check (audit L4).
 */
data class TelemetryProfile(
    val name: String,
    val rates: Map<Int, Double>,
    val positionTargetHz: Double,
) {
    companion object {
        /** Wi-Fi/UDP, TCP and a direct USB cable to the FC. */
        val WIFI = TelemetryProfile(
            "wifi",
            linkedMapOf(
                Heartbeat.messageId to 1.0,
                LocalPositionNed.messageId to 5.0,
                GlobalPositionInt.messageId to 5.0,
                Attitude.messageId to 5.0,
                ExtendedSysState.messageId to 2.0,
                SysStatus.messageId to 1.0,
                EkfStatusReport.messageId to 1.0,
                HomePosition.messageId to HOME_HZ,
            ),
            positionTargetHz = 5.0,
        )

        /** SiK and ELRS radios: about 25% of the ELRS 333 Hz Full downlink steady, 45% during a move. */
        val RADIO = TelemetryProfile(
            "radio",
            linkedMapOf(
                Heartbeat.messageId to 1.0,
                LocalPositionNed.messageId to 3.0,
                GlobalPositionInt.messageId to 0.5,
                Attitude.messageId to 1.0,
                ExtendedSysState.messageId to 1.0,
                SysStatus.messageId to 0.5,
                EkfStatusReport.messageId to 0.5,
                HomePosition.messageId to HOME_HZ,
            ),
            positionTargetHz = 4.0,
        )

        /**
         * The default profile for a transport kind. A UDP link that crosses a radio (ELRS Backpack, a Wi-Fi bridge
         * on a SiK link) must be given [RADIO] explicitly: the kind cannot tell.
         */
        fun forKind(kind: TransportKind) = when (kind) {
            TransportKind.UDP, TransportKind.TCP, TransportKind.USB_DIRECT_FC -> WIFI
            TransportKind.USB_RADIO -> RADIO
        }

        /** SET_MESSAGE_INTERVAL param2: the interval in µs, or -1 to disable (0 would mean "default rate"). */
        fun intervalUs(hz: Double): Long = if (hz > 0) (1_000_000 / hz).roundToLong() else -1

        const val HOME_HZ = 0.2
        /** A message is fresh for at least this long ([com.aeronex.app.vehicle.VehicleState.FRESH_MS]). */
        const val FRESH_FLOOR_MS = 3_000L
        /** ... and for this many of its own intervals, so one lost sample of a slow stream is not "stale". */
        const val FRESH_INTERVALS = 2.5
    }

    /**
     * How long each streamed message stays fresh: [FRESH_FLOOR_MS], or [FRESH_INTERVALS] of its interval when that is
     * longer. On the radio profile a 0.5 Hz message (GLOBAL_POSITION_INT, SYS_STATUS, EKF_STATUS_REPORT) used to go
     * stale after a single lost sample (a 4 s gap against 3 s), which aborted a climbing takeoff and dropped a
     * pending plan (audit L5); it now stays fresh for 5 s. The Wi-Fi rates all keep the 3 s floor.
     */
    fun freshnessMs(): Map<Int, Long> =
        rates.mapValues { (_, hz) -> if (hz > 0) max(FRESH_FLOOR_MS, ceil(FRESH_INTERVALS * 1000 / hz).toLong()) else FRESH_FLOOR_MS }
}
