package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.TelemetryProfile

/**
 * Link-dependent executor timing (spec 4.12, 4.14). The retry intervals are the spec's estimates (about 0.5 s on
 * Wi-Fi, 1–1.5 s on SiK or ELRS), to be tuned by measurement. The echo timeouts are measured: in ArduCopter 4.6.3
 * SITL over loopback (MAVProxy, 0.5–0.8x real time), with the stream requested together with the move frame, the
 * move frame → echo time was p50 0.36–0.65 s and p95 up to 0.7 s (max 0.76 s) over 50 moves: the first
 * POSITION_TARGET_LOCAL_NED after the frame was always the echo, so that time is the stream starting, not the target.
 * The session now starts the stream when a plan with a move is shown (plan deviation row), which leaves about one
 * stream period (≈ 0.2 s at real time) per move; the executor's own enable with the move frame is the fallback, so
 * the timeouts still cover a stream that starts with the move. A late echo makes the step UNKNOWN and stops the plan,
 * so Wi-Fi keeps about 2x margin; radio adds its round trip and the slower 4 Hz stream (not yet measured on a real
 * radio).
 */
data class ExecutorTiming(
    /** An unacknowledged COMMAND_LONG is re-sent, `confirmation` + 1, after this long. */
    val commandRetryMs: Long,
    /** Sends of one plan COMMAND_LONG, the first included; then the step is UNKNOWN. */
    val commandTries: Int = 3,
    /** The POSITION_TARGET_LOCAL_NED echo of a move must arrive within this long of the move frame. */
    val echoTimeoutMs: Long,
    /** A step that telemetry has not confirmed within this long is UNKNOWN. */
    val stepDeadlineMs: Long = 30_000,
    /** A STOP frame is re-sent until acknowledged, for at most this long. */
    val stopWindowMs: Long = 3_000,
) {
    init {
        require(commandRetryMs > 0 && commandTries > 0 && echoTimeoutMs > 0 && stepDeadlineMs > 0 && stopWindowMs > 0) {
            "Executor timings must be positive"
        }
    }

    companion object {
        /** Wi-Fi/UDP, TCP and a direct USB cable: POSITION_TARGET echo at 5 Hz. */
        val WIFI = ExecutorTiming(commandRetryMs = 500, echoTimeoutMs = 1_500)

        /** SiK and ELRS: about 100 ms each way and the echo at 4 Hz. */
        val RADIO = ExecutorTiming(commandRetryMs = 1_200, echoTimeoutMs = 2_500)

        fun forProfile(profile: TelemetryProfile) = if (profile.name == TelemetryProfile.RADIO.name) RADIO else WIFI
    }
}
