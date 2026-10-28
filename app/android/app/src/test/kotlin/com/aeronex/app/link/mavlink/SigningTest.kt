package com.aeronex.app.link.mavlink

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SigningTest {
    private val t0 = 37171440000000L

    @Test fun timestampsStrictlyIncreaseWhenTheClockStallsOrGoesBack() {
        var now = t0
        val signing = Signing(Vectors.key, 1, TimestampStore.InMemory(), clock = { now })
        val first = signing.nextTimestamp()
        val second = signing.nextTimestamp()
        now = t0 - 500_000
        val third = signing.nextTimestamp()
        assertEquals(t0, first)
        assertEquals(t0 + 1, second)
        assertEquals(t0 + 2, third)
        now = t0 + 900
        assertEquals(t0 + 900, signing.nextTimestamp())
    }

    @Test fun aRestartFromTheSameStoreNeverReusesATimestamp() {
        val store = TimestampStore.InMemory()
        var now = t0
        val before = Signing(Vectors.key, 1, store, clock = { now })
        val used = (1..5).map { before.nextTimestamp() }
        now = t0 - 6_000_000 // phone clock moved back between sessions
        val after = Signing(Vectors.key, 1, store, clock = { now })
        assertTrue(after.nextTimestamp() > used.max())
    }

    @Test fun persistenceIsReservedAheadNotWrittenForEveryFrame() {
        val writes = mutableListOf<Long>()
        val store = object : TimestampStore {
            override fun load() = 0L
            override fun save(timestamp: Long) { writes += timestamp }
        }
        var now = t0
        val signing = Signing(Vectors.key, 1, store, clock = { now })
        repeat(1000) { signing.nextTimestamp(); now += 10 }
        assertEquals(1, writes.size)
        assertTrue(writes.single() > now)
    }

    @Test fun aFailingStoreDoesNotBlockSigning() {
        val store = object : TimestampStore {
            override fun load() = 0L
            override fun save(timestamp: Long) = throw java.io.IOException("disk full")
        }
        val signing = Signing(Vectors.key, 1, store, clock = { t0 })
        assertEquals(t0, signing.nextTimestamp())
        val encoded = MavlinkEncoder(signing = signing).encode(Heartbeat(6, 8, 0, 0, 4))
        assertEquals(1, MavlinkParser(Signing(Vectors.key, 2, TimestampStore.InMemory(), clock = { t0 })).parse(encoded).size)
    }

    @Test fun fileStorePersistsAcrossInstancesAndToleratesMissingOrCorruptFiles() {
        val dir = Files.createTempDirectory("signing").toFile()
        try {
            val file = File(dir, "nested/signing_timestamp")
            assertEquals(0L, FileTimestampStore(file, Runnable::run).load())
            val store = FileTimestampStore(file, Runnable::run)
            store.save(t0)
            assertTrue(store.healthy)
            assertEquals(t0, FileTimestampStore(file).load())
            val restarted = Signing(Vectors.key, 1, FileTimestampStore(file), clock = { t0 - 100 })
            assertEquals(t0 + 1, restarted.nextTimestamp())
            file.writeText("not a number")
            assertEquals(0L, FileTimestampStore(file).load())
            val blocked = FileTimestampStore(File(file, "under_a_file"), Runnable::run)
            blocked.save(t0)
            assertFalse(blocked.healthy)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun fileStoreWritesOffTheCallingThreadAndCoalesces() {
        val dir = Files.createTempDirectory("signing").toFile()
        try {
            val queued = mutableListOf<Runnable>()
            val store = FileTimestampStore(File(dir, "ts")) { queued += it }
            store.save(1); store.save(2); store.save(3)
            assertEquals(1, queued.size)
            assertEquals(0L, store.load())
            queued.single().run()
            assertEquals(3L, store.load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun receivingANewerAuthenticTimestampAdvancesOurOwn() {
        val ours = Signing(Vectors.key, 1, TimestampStore.InMemory(), clock = { t0 })
        val theirs = MavlinkEncoder(1, 1, Signing(Vectors.key, 0, TimestampStore.InMemory(), clock = { t0 + 3_000_000 }))
        assertEquals(1, MavlinkParser(ours).parse(theirs.encode(ExtendedSysState(0, 2))).size)
        assertTrue(ours.nextTimestamp() > t0 + 3_000_000)
    }

    @Test fun wallClockUsesTheMavlinkSigningEpochInTenMicrosecondUnits() {
        assertEquals(0L, Signing.timestampAt(1_420_070_400_000L))
        assertEquals(100_000L, Signing.timestampAt(1_420_070_401_000L))
        assertEquals(t0, Signing.timestampAt(1_791_784_800_000L))
    }

    @Test fun keyMustBe32BytesAndLinkIdOneByte() {
        assertThrows(IllegalArgumentException::class.java) { Signing(ByteArray(31), 0, TimestampStore.InMemory()) }
        assertThrows(IllegalArgumentException::class.java) { Signing(ByteArray(32), 256, TimestampStore.InMemory()) }
    }

    @Test fun theSigningKeyIsCopiedAndNeverPrinted() {
        val key = Vectors.key.copyOf()
        val signing = Signing(key, 1, TimestampStore.InMemory(), clock = { t0 })
        key.fill(0)
        val frame = MavlinkEncoder(signing = signing).encode(Heartbeat(6, 8, 0, 0, 4))
        assertEquals(1, MavlinkParser(Signing(Vectors.key, 2, TimestampStore.InMemory(), clock = { t0 })).parse(frame).size)
        val setup = SetupSigning(1, 1, Vectors.key, t0)
        assertFalse(setup.toString().contains(Vectors.key.toHex()))
        assertFalse(setup.toString().contains(Vectors.key.contentToString()))
        assertFalse(signing.toString().contains(Vectors.key.toHex()))
    }
}
