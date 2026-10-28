package com.aeronex.app.link.mavlink

/** MAVLink X.25 (CRC-16/MCRF4XX) checksum. */
internal object MavlinkCrc {
    fun accumulate(byte: Int, crc: Int): Int {
        var tmp = (byte xor crc) and 0xFF
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)) and 0xFFFF
    }

    /** CRC over [length] bytes (header after the magic byte, then payload) followed by the message's CRC_EXTRA. */
    fun frame(buffer: ByteArray, offset: Int, length: Int, crcExtra: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) crc = accumulate(buffer[i].toInt(), crc)
        return accumulate(crcExtra, crc)
    }
}
