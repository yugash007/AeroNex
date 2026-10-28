package com.aeronex.app.link.mavlink

import org.junit.Assert.*
import org.junit.Test

/** Golden frames from pymavlink 2.4.50 (tools/bench/gen_mavlink_vectors.py). */
class MavlinkVectorsTest {
    private val root = Vectors.root
    private val frames = Vectors.frames

    @Test fun vectorsCoverEveryPlannedMessageIncludingTruncatedSignedAndV1Frames() {
        val planned = setOf(0, 1, 20, 22, 30, 32, 33, 76, 77, 84, 85, 109, 111, 193, 242, 245, 253, 256)
        assertEquals(planned, frames.map { it.int("id") }.toSet())
        assertEquals(planned, MavlinkMessages.specs.keys)
        assertTrue(frames.any { it["truncated"] == true && it["signed"] == true })
        assertTrue(frames.any { it.int("payload_length") == 1 })
        assertTrue(frames.any { it.int("version") == 1 })
        assertEquals("2.4.50", root["pymavlink"])
    }

    @Test fun messageTableMatchesPymavlinkCrcExtraAndLength() {
        @Suppress("UNCHECKED_CAST")
        for ((name, meta) in root["messages"] as Map<String, Map<String, Any?>>) {
            val spec = MavlinkMessages.specs.getValue(meta.int("id"))
            assertEquals(name, spec.name)
            assertEquals(name, meta.int("crc_extra"), spec.crcExtra)
            assertEquals(name, meta.int("max_length"), spec.maxLength)
            assertTrue(name, spec.minLength in 1..spec.maxLength)
        }
    }

    @Test fun encoderReproducesEveryMavlink2FrameByteForByte() {
        for (vector in frames.filter { it.int("version") == 2 }) {
            val signing = if (vector["signed"] == true) Signing(Vectors.key, vector.int("link_id"),
                TimestampStore.InMemory(), clock = { vector.long("timestamp") }) else null
            val encoder = MavlinkEncoder(vector.int("sysid"), vector.int("compid"), signing, firstSequence = vector.int("seq"))
            assertEquals(vector["name"] as String, vector["hex"], encoder.encode(Vectors.message(vector)).toHex())
        }
    }

    @Test fun parserDecodesEveryFrameToTheRecordedFields() {
        for (vector in frames) {
            val name = vector["name"] as String
            val signed = vector["signed"] == true
            val signing = if (signed) Signing(Vectors.key, 7, TimestampStore.InMemory(), clock = { vector.long("timestamp") }) else null
            val parser = MavlinkParser(signing)
            val parsed = parser.parse(Vectors.bytes(vector))
            assertEquals(name, 1, parsed.size)
            val frame = parsed.single()
            assertEquals(name, vector.int("version"), frame.version)
            assertEquals(name, vector.int("seq"), frame.sequence)
            assertEquals(name, vector.int("sysid"), frame.sysId)
            assertEquals(name, vector.int("compid"), frame.compId)
            assertEquals(name, vector.int("id"), frame.messageId)
            assertEquals(name, vector.int("payload_length"), frame.payload.size)
            assertEquals(name, Vectors.message(vector), frame.message)
            assertEquals(name, signed, frame.signed)
            if (signed) assertEquals(name, FrameSignature(vector.int("link_id"), vector.long("timestamp")), frame.signature)
        }
    }

    @Test fun parserDecodesAllFramesFromOneStreamFedOneByteAtATime() {
        val stream = frames.flatMap { Vectors.bytes(it).toList() }.toByteArray()
        val parser = MavlinkParser()
        val parsed = stream.indices.flatMap { parser.parse(stream, it, 1) }
        assertEquals(frames.map { Vectors.message(it) }, parsed.map { it.message })
        assertEquals(0L, parser.badCrc)
    }
}
