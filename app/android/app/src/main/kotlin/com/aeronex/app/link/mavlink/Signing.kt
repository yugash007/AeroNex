package com.aeronex.app.link.mavlink

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * MAVLink 2 message signing (https://mavlink.io/en/guide/message_signing.html) for one link.
 *
 * signature = first 6 bytes of SHA-256(key + header + payload + CRC + linkId + timestamp), where the timestamp
 * is 48-bit, in 10 µs units since 2015-01-01 UTC. Outgoing timestamps follow the wall clock but are strictly
 * increasing, also across sessions: a high-water mark is reserved ahead in [timestamps] so the TX path never
 * waits for storage. Incoming frames are accepted only with a valid signature and a timestamp greater than the
 * last one accepted for the same (sysid, compid, linkid) stream; a new stream may be at most one minute old.
 * One instance is shared by a link's encoder and parser.
 */
class Signing(
    key: ByteArray,
    val linkId: Int,
    private val timestamps: TimestampStore,
    private val clock: () -> Long = { timestampAt(System.currentTimeMillis()) },
) {
    private val key = key.copyOf()
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private val streams = HashMap<Long, Long>()
    private var last: Long
    private var reserved: Long

    init {
        require(this.key.size == 32) { "MAVLink signing key must be 32 bytes" }
        require(linkId in 0..255) { "Signing linkId must fit one byte" }
        last = timestamps.load().coerceAtLeast(0)
        reserved = last
    }

    /** The next outgoing timestamp: above every timestamp used or accepted before, the wall clock if that is later. */
    @Synchronized fun nextTimestamp(): Long {
        last = maxOf(last + 1, clock())
        reserve()
        return last
    }

    /** Writes link id, timestamp and signature after the CRC at [length]; [frame] needs 13 spare bytes. */
    @Synchronized internal fun sign(frame: ByteArray, length: Int) {
        val timestamp = nextTimestamp()
        frame[length] = linkId.toByte()
        for (i in 0 until 6) frame[length + 1 + i] = (timestamp ushr (8 * i)).toByte()
        System.arraycopy(digest(frame, 0, length + 7), 0, frame, length + 7, 6)
    }

    /** Verifies the signed frame occupying [offset] until [offset] + [length] (magic byte through signature). */
    @Synchronized internal fun accept(frame: ByteArray, offset: Int, length: Int, sysId: Int, compId: Int): Boolean {
        val end = offset + length
        val expected = digest(frame, offset, length - 6)
        var difference = 0
        for (i in 0 until 6) difference = difference or (expected[i].toInt() xor frame[end - 6 + i].toInt())
        if (difference != 0) return false
        val link = frame[end - 13].toLong() and 0xFF
        var timestamp = 0L
        for (i in 5 downTo 0) timestamp = (timestamp shl 8) or (frame[end - 12 + i].toLong() and 0xFF)
        val stream = (sysId.toLong() shl 16) or (compId.toLong() shl 8) or link
        val previous = streams[stream]
        if (previous == null) {
            if (timestamp + ONE_MINUTE < maxOf(last, clock())) return false
        } else if (timestamp <= previous) {
            return false
        }
        streams[stream] = timestamp
        if (timestamp > last) {
            // As in the reference C library: our next timestamp is at least any authentic one we have seen.
            last = timestamp
            reserve()
        }
        return true
    }

    private fun digest(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        sha256.update(key)
        sha256.update(bytes, offset, length)
        return sha256.digest()
    }

    private fun reserve() {
        if (last <= reserved) return
        reserved = last + RESERVE
        try {
            timestamps.save(reserved)
        } catch (_: Exception) {
            // Losing persistence only risks the receiver rejecting our frames after a restart; never block a send.
        }
    }

    override fun toString() = "Signing(linkId=$linkId, key=<redacted>)"

    companion object {
        const val EPOCH_UNIX_MS = 1_420_070_400_000L
        private const val ONE_MINUTE = 6_000_000L
        private const val RESERVE = 1_000_000L // 10 s

        /** Signing timestamp (10 µs units since 2015-01-01 UTC) for a Unix time in milliseconds. */
        fun timestampAt(unixMillis: Long) = (unixMillis - EPOCH_UNIX_MS) * 100
    }
}

/** Persists the signing timestamp high-water mark. [save] is called on the TX path: it must not block or throw. */
interface TimestampStore {
    fun load(): Long
    fun save(timestamp: Long)

    class InMemory(private var value: Long = 0) : TimestampStore {
        @Synchronized override fun load() = value
        @Synchronized override fun save(timestamp: Long) { value = timestamp }
    }
}

/** Stores the timestamp in [file]; writes run on [executor], coalesced, durably (fsync + atomic rename). */
class FileTimestampStore(
    private val file: File,
    private val executor: Executor = defaultExecutor,
) : TimestampStore {
    private val pending = AtomicLong(NONE)

    @Volatile var healthy = true
        private set

    override fun load(): Long = try {
        file.readText().trim().toLong()
    } catch (_: Exception) {
        0L
    }

    override fun save(timestamp: Long) {
        if (pending.getAndSet(timestamp) == NONE) executor.execute { write(pending.getAndSet(NONE)) }
    }

    private fun write(timestamp: Long) {
        healthy = try {
            file.absoluteFile.parentFile?.mkdirs()
            val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
            FileOutputStream(temp).use { out ->
                out.write(timestamp.toString().toByteArray())
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        val defaultExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { Thread(it, "mavlink-signing-timestamp").apply { isDaemon = true } }
        }
    }
}
