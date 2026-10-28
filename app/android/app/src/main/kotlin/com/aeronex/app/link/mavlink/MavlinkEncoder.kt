package com.aeronex.app.link.mavlink

import com.aeronex.app.link.mavlink.Mavlink.HEADER_V2
import com.aeronex.app.link.mavlink.Mavlink.IFLAG_SIGNED
import com.aeronex.app.link.mavlink.Mavlink.SIGNATURE_LENGTH
import com.aeronex.app.link.mavlink.Mavlink.STX_V2

/**
 * Packs MAVLink 2 frames: trailing zero payload bytes stripped (at least one kept), CRC with CRC_EXTRA,
 * a sequence number per frame, and a signature when [signing] is set. Every call re-encodes, so a resend with
 * a new sequence or COMMAND_LONG confirmation always gets a fresh CRC, timestamp and signature.
 */
class MavlinkEncoder(
    val sysId: Int = 255,
    val compId: Int = 190,
    private val signing: Signing? = null,
    firstSequence: Int = 0,
) {
    private var sequence = firstSequence and 0xFF

    init {
        require(sysId in 1..255 && compId in 1..255) { "MAVLink source ids must be 1..255" }
    }

    val signed get() = signing != null

    @Synchronized fun encode(message: MavlinkMessage): ByteArray {
        val spec = message.spec
        val payload = message.payload()
        var length = payload.size
        while (length > 1 && payload[length - 1] == 0.toByte()) length--
        val crcAt = HEADER_V2 + length
        val frame = ByteArray(crcAt + 2 + if (signing != null) SIGNATURE_LENGTH else 0)
        frame[0] = STX_V2.toByte()
        frame[1] = length.toByte()
        frame[2] = (if (signing != null) IFLAG_SIGNED else 0).toByte()
        frame[4] = sequence.toByte()
        frame[5] = sysId.toByte()
        frame[6] = compId.toByte()
        frame[7] = spec.messageId.toByte()
        frame[8] = (spec.messageId shr 8).toByte()
        frame[9] = (spec.messageId shr 16).toByte()
        System.arraycopy(payload, 0, frame, HEADER_V2, length)
        val crc = MavlinkCrc.frame(frame, 1, crcAt - 1, spec.crcExtra)
        frame[crcAt] = crc.toByte()
        frame[crcAt + 1] = (crc shr 8).toByte()
        signing?.sign(frame, crcAt + 2)
        sequence = (sequence + 1) and 0xFF
        return frame
    }
}
