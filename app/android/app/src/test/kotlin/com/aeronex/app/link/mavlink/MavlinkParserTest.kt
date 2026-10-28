package com.aeronex.app.link.mavlink

import org.junit.Assert.*
import org.junit.Test

class MavlinkParserTest {
    private val t0 = 37171440000000L
    private val brake = CommandLong(1, 1, 176, param1 = 1f, param2 = 17f)

    private fun signing(linkId: Int = 0, key: ByteArray = Vectors.key, clock: () -> Long = { t0 }) =
        Signing(key, linkId, TimestampStore.InMemory(), clock)

    private fun signedBy(encoderSigning: Signing, message: MavlinkMessage = brake, sysId: Int = 255) =
        MavlinkEncoder(sysId, 190, encoderSigning).encode(message)

    @Test fun corruptedCrcIsRejectedAndTheNextFrameStillParses() {
        val bad = Vectors.bytes(Vectors.named("command_long_set_mode_guided")).also { it[14] = (it[14] + 1).toByte() }
        val good = Vectors.bytes(Vectors.named("heartbeat_gcs"))
        val parser = MavlinkParser()
        val parsed = parser.parse(bad + good)
        assertEquals(listOf(Vectors.message(Vectors.named("heartbeat_gcs"))), parsed.map { it.message })
        assertEquals(1L, parser.badCrc)
    }

    @Test fun corruptedCrcBytesAreRejected() {
        for (name in listOf("heartbeat_vehicle_v1", "command_ack_minimal_truncated", "extended_sys_state_in_air_signed")) {
            val bytes = Vectors.bytes(Vectors.named(name))
            val crcAt = 1 + if (bytes[0] == 0xFE.toByte()) 5 + bytes[1] else 9 + bytes[1]
            bytes[crcAt] = (bytes[crcAt].toInt() xor 0x01).toByte()
            val parser = MavlinkParser()
            assertTrue(name, parser.parse(bytes).isEmpty())
            assertEquals(name, 1L, parser.badCrc)
        }
    }

    @Test fun garbageBeforeAndBetweenFramesIsSkipped() {
        val a = Vectors.bytes(Vectors.named("heartbeat_gcs"))
        val b = Vectors.bytes(Vectors.named("command_ack_full"))
        val noise = byteArrayOf(0x00, 0x55, 0xFD.toByte(), 0x03, 0x7F)
        val parsed = MavlinkParser().parse(noise + a + noise + b)
        assertEquals(listOf(0, 77), parsed.map { it.messageId })
    }

    @Test fun framesSplitAcrossReadsAreReassembled() {
        val bytes = Vectors.bytes(Vectors.named("set_position_target_body_offset_signed"))
        val parser = MavlinkParser()
        assertTrue(parser.parse(bytes, 0, 20).isEmpty())
        assertTrue(parser.parse(bytes, 20, 40).isEmpty())
        assertEquals(84, parser.parse(bytes, 60, bytes.size - 60).single().messageId)
    }

    @Test fun unknownMessageIdsAreSkippedWholeAndCounted() {
        val vfrHud = rawV2(74, ByteArray(20) { 0x41 })
        val good = Vectors.bytes(Vectors.named("heartbeat_vehicle_guided_armed"))
        val parser = MavlinkParser()
        assertEquals(listOf(0), parser.parse(vfrHud + good).map { it.messageId })
        assertEquals(1L, parser.unknownMessages)
    }

    @Test fun unknownIncompatibilityFlagsAreRejected() {
        val bytes = Vectors.bytes(Vectors.named("heartbeat_gcs"))
        bytes[2] = 0x02
        val crc = MavlinkCrc.frame(bytes, 1, bytes.size - 3, Heartbeat.crcExtra)
        bytes[bytes.size - 2] = crc.toByte(); bytes[bytes.size - 1] = (crc shr 8).toByte()
        val parser = MavlinkParser()
        assertTrue(parser.parse(bytes).isEmpty())
        assertEquals(1L, parser.badFrames)
    }

    @Test fun payloadsLongerThanKnownDecodeTheKnownFields() {
        // A newer dialect appends TIMESYNC target_system/target_component; CRC_EXTRA is unchanged.
        val payload = byteArrayOf(5, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, 0, 0, 0, 0, 255.toByte(), 190.toByte())
        val frame = MavlinkParser().parse(rawV2(111, payload, crcExtra = Timesync.crcExtra)).single()
        assertEquals(Timesync(5, 9), frame.message)
        assertEquals(18, frame.payload.size)
    }

    @Test fun signedFramesWithAWrongSignatureOrWrongKeyAreRejected() {
        val bytes = Vectors.bytes(Vectors.named("command_ack_signed"))
        val flipped = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        val parser = MavlinkParser(signing())
        assertTrue(parser.parse(flipped).isEmpty())
        assertEquals(1L, parser.badSignature)
        val wrongKey = MavlinkParser(signing(key = ByteArray(32) { 7 }))
        assertTrue(wrongKey.parse(bytes).isEmpty())
        assertEquals(1L, wrongKey.badSignature)
        assertEquals(77, parser.parse(bytes).single().messageId)
    }

    @Test fun signatureCoversThePayloadEvenWhenTheCrcIsRecomputed() {
        val bytes = Vectors.bytes(Vectors.named("command_long_brake_signed"))
        bytes[10 + 4] = 0x40 // param2 17.0 -> a different mode, CRC fixed up by an attacker
        val crcAt = 10 + bytes[1]
        val crc = MavlinkCrc.frame(bytes, 1, crcAt - 1, CommandLong.crcExtra)
        bytes[crcAt] = crc.toByte(); bytes[crcAt + 1] = (crc shr 8).toByte()
        assertEquals(1, MavlinkParser().parse(bytes).size)
        val parser = MavlinkParser(signing())
        assertTrue(parser.parse(bytes).isEmpty())
        assertEquals(1L, parser.badSignature)
    }

    @Test fun replayedOrOlderTimestampsAreRejectedPerStream() {
        var now = t0
        val sender = signing(linkId = 3, clock = { now })
        val first = signedBy(sender)
        now += 50
        val second = signedBy(sender)
        val parser = MavlinkParser(signing())
        assertEquals(1, parser.parse(second).size)
        assertTrue("older timestamp", parser.parse(first).isEmpty())
        assertTrue("replay", parser.parse(second).isEmpty())
        assertEquals(2L, parser.badSignature)
        val otherLink = signedBy(signing(linkId = 4, clock = { t0 }))
        assertEquals("separate (sysid, compid, linkid) stream", 1, parser.parse(otherLink).size)
        val otherSystem = signedBy(signing(linkId = 3, clock = { t0 }), sysId = 254)
        assertEquals(1, parser.parse(otherSystem).size)
    }

    @Test fun aRejectedForgeryDoesNotAdvanceTheStreamTimestamp() {
        val sender = signing(linkId = 3)
        val genuine = signedBy(sender)
        val forged = signedBy(signing(linkId = 3, key = ByteArray(32), clock = { t0 + 1_000_000 }))
        val parser = MavlinkParser(signing())
        assertTrue(parser.parse(forged).isEmpty())
        assertEquals(1, parser.parse(genuine).size)
    }

    @Test fun aNewStreamMoreThanOneMinuteBehindIsRejected() {
        val old = signedBy(signing(linkId = 2, clock = { t0 - 6_000_001 }))
        val recent = signedBy(signing(linkId = 3, clock = { t0 - 6_000_000 }))
        val parser = MavlinkParser(signing())
        assertTrue(parser.parse(old).isEmpty())
        assertEquals(1, parser.parse(recent).size)
    }

    @Test fun unsignedFramesAreRejectedWhenSigningIsOnExceptRadioStatus() {
        val parser = MavlinkParser(signing())
        assertTrue(parser.parse(Vectors.bytes(Vectors.named("heartbeat_vehicle_guided_armed"))).isEmpty())
        assertTrue(parser.parse(Vectors.bytes(Vectors.named("heartbeat_vehicle_v1"))).isEmpty())
        assertEquals(2L, parser.unsignedRejected)
        assertEquals(109, parser.parse(Vectors.bytes(Vectors.named("radio_status_sik_v1"))).single().messageId)
        val strict = MavlinkParser(signing(), acceptUnsigned = { false })
        assertTrue(strict.parse(Vectors.bytes(Vectors.named("radio_status_sik_v1"))).isEmpty())
    }

    @Test fun withoutSigningSignedFramesAreAcceptedUnverified() {
        val forged = signedBy(signing(key = ByteArray(32)))
        assertTrue(MavlinkParser().parse(forged).single().signed)
    }

    @Test fun stopResendWithIncrementedConfirmationKeepsValidCrcAndSignature() {
        var now = t0
        val encoder = MavlinkEncoder(signing = signing(linkId = 1, clock = { now }))
        val frames = (0..3).map { confirmation -> encoder.encode(brake.copy(confirmation = confirmation)) }
        now = t0 + 1
        val parser = MavlinkParser(signing(linkId = 9))
        val parsed = frames.flatMap { parser.parse(it) }
        assertEquals(listOf(0, 1, 2, 3), parsed.map { (it.message as CommandLong).confirmation })
        assertEquals(listOf(0, 1, 2, 3), parsed.map { it.sequence })
        assertEquals(parsed.map { it.signature!!.timestamp }.sorted().distinct(), parsed.map { it.signature!!.timestamp })
        assertEquals(0L, parser.badSignature + parser.badCrc)
    }

    @Test fun resetDropsAPartialFrame() {
        val bytes = Vectors.bytes(Vectors.named("heartbeat_gcs"))
        val parser = MavlinkParser()
        parser.parse(bytes, 0, 8)
        parser.reset()
        assertEquals(1, parser.parse(bytes).size)
    }

    private fun rawV2(messageId: Int, payload: ByteArray, crcExtra: Int = 0): ByteArray {
        val header = byteArrayOf(0xFD.toByte(), payload.size.toByte(), 0, 0, 0, 1, 1,
            messageId.toByte(), (messageId shr 8).toByte(), (messageId shr 16).toByte())
        val body = header + payload
        val crc = MavlinkCrc.frame(body, 1, body.size - 1, crcExtra)
        return body + byteArrayOf(crc.toByte(), (crc shr 8).toByte())
    }
}
