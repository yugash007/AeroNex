package com.aeronex.app.link.mavlink

/** One received MAVLink 1 or 2 frame whose CRC (and signature, when the parser verifies) checked out. */
class MavlinkFrame(
    val version: Int,
    val sequence: Int,
    val sysId: Int,
    val compId: Int,
    val messageId: Int,
    /** Payload exactly as received (MAVLink 2 may be truncated). */
    val payload: ByteArray,
    val message: MavlinkMessage,
    val incompatFlags: Int = 0,
    val compatFlags: Int = 0,
    val signature: FrameSignature? = null,
) {
    val signed get() = signature != null

    override fun toString() =
        "MavlinkFrame(v$version seq=$sequence sys=$sysId comp=$compId signed=$signed $message)"
}

data class FrameSignature(val linkId: Int, val timestamp: Long)

internal object Mavlink {
    const val STX_V1 = 0xFE
    const val STX_V2 = 0xFD
    const val HEADER_V1 = 6
    const val HEADER_V2 = 10
    const val IFLAG_SIGNED = 0x01
    const val SIGNATURE_LENGTH = 13
    const val MAX_FRAME = HEADER_V2 + 255 + 2 + SIGNATURE_LENGTH
}
