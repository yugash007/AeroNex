package com.aeronex.app.link.mavlink

import org.junit.Assert.*
import org.junit.Test

class MavlinkEncoderTest {
    private val heartbeat = Heartbeat(6, 8, 0, 0, 4)

    @Test fun defaultsToGcsIdentityAndMavlink2Unsigned() {
        val frame = MavlinkEncoder().encode(heartbeat)
        assertEquals(0xFD, frame[0].toInt() and 0xFF)
        assertEquals(0, frame[2].toInt())
        assertEquals(255, frame[5].toInt() and 0xFF)
        assertEquals(190, frame[6].toInt() and 0xFF)
        assertEquals(Vectors.named("heartbeat_gcs")["hex"], frame.toHex())
    }

    @Test fun sequenceIncrementsAndWrapsAfter255() {
        val encoder = MavlinkEncoder(firstSequence = 254)
        val sequences = (1..4).map { encoder.encode(heartbeat)[4].toInt() and 0xFF }
        assertEquals(listOf(254, 255, 0, 1), sequences)
    }

    @Test fun signedFramesCarryTheFlagLinkIdAndThirteenByteTrailer() {
        val signing = Signing(Vectors.key, 5, TimestampStore.InMemory(), clock = { 37171440000000L })
        val unsigned = MavlinkEncoder().encode(heartbeat)
        val signed = MavlinkEncoder(signing = signing).encode(heartbeat)
        assertEquals(unsigned.size + 13, signed.size)
        assertEquals(1, signed[2].toInt())
        assertEquals(5, signed[unsigned.size].toInt())
    }

    @Test fun allZeroPayloadKeepsOneByte() {
        val frame = MavlinkEncoder().encode(ExtendedSysState(0, 0))
        assertEquals(1, frame[1].toInt())
        assertEquals(ExtendedSysState(0, 0), MavlinkParser().parse(frame).single().message)
    }

    @Test fun stringsLongerThanTheFieldAreTruncatedAndNonAsciiReplaced() {
        val frame = MavlinkEncoder().encode(ParamRequestRead(1, 1, "ABCDEFGHIJKLMNOPQRS"))
        assertEquals("ABCDEFGHIJKLMNOP", (MavlinkParser().parse(frame).single().message as ParamRequestRead).paramId)
        val text = MavlinkEncoder().encode(StatusText(6, "h\u00e9llo"))
        assertEquals("h?llo", (MavlinkParser().parse(text).single().message as StatusText).text)
    }

    @Test fun homePositionNeedsFourQuaternionValues() {
        assertThrows(IllegalArgumentException::class.java) { HomePosition(0, 0, 0, 0f, 0f, 0f, listOf(1f), 0f, 0f, 0f) }
    }
}
