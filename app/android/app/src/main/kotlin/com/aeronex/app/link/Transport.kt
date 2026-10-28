package com.aeronex.app.link

/**
 * How the phone reaches the flight controller. Selects the telemetry profile ([TelemetryProfile.forKind]) and the
 * SETUP_SIGNING policy: only [USB_DIRECT_FC], a cable to the FC's own USB port, may carry a signing key (spec 4.13).
 */
enum class TransportKind { UDP, TCP, USB_DIRECT_FC, USB_RADIO }

/**
 * A byte pipe to the vehicle, owned by one [LinkSession]: [read] is called only from the link RX thread and [write]
 * only from the TX thread, concurrently. [close] may be called from any thread and unblocks a pending [read].
 * A transport is opened once and closed once.
 */
interface Transport {
    val kind: TransportKind
    val description: String

    /** Opens the transport without waiting for the vehicle; throws IOException if it cannot (e.g. port in use). */
    fun open()

    /** Sends one frame; false if it was not handed to the OS (not open, closed, not connected, I/O error). */
    fun write(bytes: ByteArray): Boolean

    /** Reads into [buffer]: the byte count, 0 if nothing was accepted within about [timeoutMs], -1 once closed. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /**
     * For a transport that reconnects by itself ([TcpTransport]): how many connections it has made so far. Bytes
     * from two connections never belong to one frame, so the link drops a half frame when this changes. Read on the
     * RX thread after [read], it counts the connection the bytes just read came from. 0 for the other transports.
     */
    val connections: Long get() = 0

    /** Idempotent. */
    fun close()
}
