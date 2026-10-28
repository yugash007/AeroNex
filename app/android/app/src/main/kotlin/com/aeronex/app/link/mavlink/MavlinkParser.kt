package com.aeronex.app.link.mavlink

import com.aeronex.app.link.mavlink.Mavlink.HEADER_V1
import com.aeronex.app.link.mavlink.Mavlink.HEADER_V2
import com.aeronex.app.link.mavlink.Mavlink.IFLAG_SIGNED
import com.aeronex.app.link.mavlink.Mavlink.MAX_FRAME
import com.aeronex.app.link.mavlink.Mavlink.SIGNATURE_LENGTH
import com.aeronex.app.link.mavlink.Mavlink.STX_V1
import com.aeronex.app.link.mavlink.Mavlink.STX_V2

/**
 * Stream parser for MAVLink 1 and 2 (UDP datagrams, TCP or serial reads; frames may span calls).
 *
 * Only the typed messages in [MavlinkMessages] are returned. A frame with a bad CRC is resynchronised one byte
 * later; a frame with an unknown message id is skipped whole (its CRC cannot be checked). With [signing] set,
 * signed frames must verify and unsigned frames (all MAVLink 1 frames included) are dropped unless
 * [acceptUnsigned] allows the message id; by default only RADIO_STATUS, which SiK radios inject. Without
 * [signing], signatures are not checked. Not thread-safe: one parser per RX thread.
 */
class MavlinkParser(
    private val signing: Signing? = null,
    private val acceptUnsigned: (messageId: Int) -> Boolean = { it == RadioStatus.messageId },
) {
    var badCrc = 0L
        private set
    /** Signed frames rejected for a wrong signature, a replayed or old timestamp. */
    var badSignature = 0L
        private set
    var unsignedRejected = 0L
        private set
    var unknownMessages = 0L
        private set
    /** Frames with unsupported incompatibility flags. */
    var badFrames = 0L
        private set

    private var buffer = ByteArray(2 * MAX_FRAME)
    private var start = 0
    private var end = 0

    /** Drops buffered bytes, e.g. after a reconnect or at the end of a UDP datagram. */
    fun reset() {
        start = 0
        end = 0
    }

    fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<MavlinkFrame> {
        append(data, offset, length)
        val frames = ArrayList<MavlinkFrame>()
        while (true) {
            while (start < end && u8(start) != STX_V2 && u8(start) != STX_V1) start++
            if (end - start < 3) break
            val v2 = u8(start) == STX_V2
            val payloadLength = u8(start + 1)
            val incompatFlags = if (v2) u8(start + 2) else 0
            if (incompatFlags and IFLAG_SIGNED.inv() != 0) {
                badFrames++
                start++
                continue
            }
            val signed = incompatFlags and IFLAG_SIGNED != 0
            val total = (if (v2) HEADER_V2 else HEADER_V1) + payloadLength + 2 + if (signed) SIGNATURE_LENGTH else 0
            if (end - start < total) break
            start += frameAt(v2, payloadLength, incompatFlags, total, frames)
        }
        if (start == end) reset()
        return frames
    }

    /** Checks the frame at [start] and returns how many bytes to consume. */
    private fun frameAt(v2: Boolean, payloadLength: Int, incompatFlags: Int, total: Int, out: MutableList<MavlinkFrame>): Int {
        val s = start
        val header = if (v2) HEADER_V2 else HEADER_V1
        val messageId = if (v2) u8(s + 7) or (u8(s + 8) shl 8) or (u8(s + 9) shl 16) else u8(s + 5)
        val spec = MavlinkMessages.specs[messageId]
        if (spec == null) {
            unknownMessages++
            return total
        }
        val crcAt = s + header + payloadLength
        if (MavlinkCrc.frame(buffer, s + 1, header - 1 + payloadLength, spec.crcExtra) != (u8(crcAt) or (u8(crcAt + 1) shl 8))) {
            badCrc++
            return 1
        }
        val sequence = u8(s + if (v2) 4 else 2)
        val sysId = u8(s + if (v2) 5 else 3)
        val compId = u8(s + if (v2) 6 else 4)
        val signature = if (incompatFlags and IFLAG_SIGNED != 0) {
            var timestamp = 0L
            for (i in 8 downTo 3) timestamp = (timestamp shl 8) or u8(crcAt + i).toLong()
            FrameSignature(u8(crcAt + 2), timestamp)
        } else null
        if (signing != null) {
            if (signature != null && !signing.accept(buffer, s, total, sysId, compId)) {
                badSignature++
                return total
            }
            if (signature == null && !acceptUnsigned(messageId)) {
                unsignedRejected++
                return total
            }
        }
        val payload = buffer.copyOfRange(s + header, s + header + payloadLength)
        out += MavlinkFrame(if (v2) 2 else 1, sequence, sysId, compId, messageId, payload, spec.decode(payload),
            incompatFlags, if (v2) u8(s + 3) else 0, signature)
        return total
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "Bad range $offset+$length of ${data.size}" }
        if (end + length > buffer.size && start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start)
            end -= start
            start = 0
        }
        if (end + length > buffer.size) buffer = buffer.copyOf(maxOf(2 * buffer.size, end + length))
        System.arraycopy(data, offset, buffer, end, length)
        end += length
    }

    private fun u8(index: Int) = buffer[index].toInt() and 0xFF
}
