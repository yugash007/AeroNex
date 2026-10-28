package com.aeronex.app.link

import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.SysStatus
import kotlin.math.roundToLong

/**
 * Telemetry rates requested with SET_MESSAGE_INTERVAL on every (re)connect (spec 4.15).
 *
 * [rates] maps message id to Hz. POSITION_TARGET_LOCAL_NED is not in [rates]: it streams at [positionTargetHz]
 * only while a move is pending ([LinkSession.setPositionTargetStreaming]) and is disabled otherwise. HOME_POSITION
 * is requested once per connect; ArduPilot sends it again when home changes.
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
    }
}
