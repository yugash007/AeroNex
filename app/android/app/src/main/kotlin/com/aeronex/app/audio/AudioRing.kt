package com.aeronex.app.audio

import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min

/**
 * The session's preallocated capture ring (spec 4.2): 16 kHz mono PCM16 in whole 20 ms frames, plus one VAD flag
 * byte per frame. One writer (the capture thread) and one reader thread (speech decode).
 *
 * Positions are absolute: sample `n` is the n-th sample captured this session, frame `f` holds samples
 * `f * FRAME_SAMPLES until (f + 1) * FRAME_SAMPLES`. The writer never blocks or allocates: it copies a frame into its
 * slot, then publishes it by advancing the volatile [framesWritten]. The newest [readableFrames] frames (at least 1 s,
 * so 500 ms of PTT pre-roll is always there) can be read; the [GUARD_FRAMES] oldest slots are never handed out,
 * because the writer may be overwriting them while a reader copies. A reader that falls further behind gets
 * [OVERRUN] and must skip ahead to [oldestReadable].
 */
class AudioRing(capacityMs: Int = 3000) {
    val capacityFrames: Int = capacityMs / FRAME_MS
    val readableFrames: Int = capacityFrames - GUARD_FRAMES
    private val samples = ShortArray(capacityFrames * FRAME_SAMPLES)
    private val flags = ByteArray(capacityFrames)

    @Volatile private var waiter: Thread? = null

    /** Frames published so far; frame `framesWritten - 1` is the newest. */
    @Volatile var framesWritten: Long = 0
        private set

    init {
        require(readableFrames * FRAME_MS >= 1000) { "The ring must keep at least 1 s readable" }
    }

    /** The next sample the writer will publish. */
    val writePosition: Long get() = framesWritten * FRAME_SAMPLES

    /** Writer only. [frame] holds exactly one frame; a [muted] frame (own prompt playing) is never speech. */
    fun write(frame: ShortArray, speech: Boolean, muted: Boolean = false) {
        require(frame.size == FRAME_SAMPLES) { "One 20 ms frame per write" }
        val index = framesWritten
        val slot = (index % capacityFrames).toInt()
        System.arraycopy(frame, 0, samples, slot * FRAME_SAMPLES, FRAME_SAMPLES)
        flags[slot] = ((if (speech && !muted) SPEECH else 0) or (if (muted) MUTED else 0)).toByte()
        framesWritten = index + 1
        waiter?.let(LockSupport::unpark)
    }

    /** The oldest sample a reader may still copy. */
    fun oldestReadable(): Long = max(0L, framesWritten - readableFrames) * FRAME_SAMPLES

    /** Where an utterance pressed at [at] starts: [preRollMs] earlier, but never before the oldest readable sample. */
    fun preRollStart(at: Long = writePosition, preRollMs: Int = PTT_PRE_ROLL_MS): Long =
        max(oldestReadable(), at - samplesForMs(preRollMs))

    /**
     * Copies up to [count] published samples starting at sample [from] into [dst]. Returns the number copied (0 when
     * nothing new is there yet) or [OVERRUN] when [from] has left the readable window.
     */
    fun read(from: Long, dst: ShortArray, dstOffset: Int, count: Int): Int {
        require(from >= 0 && count >= 0 && dstOffset >= 0 && dstOffset + count <= dst.size) { "Bad read range" }
        if (from < oldestReadable()) return OVERRUN
        val n = min(count.toLong(), writePosition - from).toInt()
        if (n <= 0) return 0
        var copied = 0
        while (copied < n) {
            val slotStart = ((from + copied) % samples.size).toInt()
            val chunk = min(n - copied, samples.size - slotStart)
            System.arraycopy(samples, slotStart, dst, dstOffset + copied, chunk)
            copied += chunk
        }
        // The copy is valid only if the writer did not reach these slots meanwhile.
        return if (from < oldestReadable()) OVERRUN else n
    }

    /** The VAD decision of [frame], or null when that frame is not (or no longer) readable. */
    fun speechAt(frame: Long): Boolean? = flag(frame, SPEECH)

    fun isSpeech(frame: Long): Boolean = speechAt(frame) == true

    fun isMuted(frame: Long): Boolean = flag(frame, MUTED) == true

    private fun flag(frame: Long, bit: Int): Boolean? {
        if (frame < 0 || frame >= framesWritten || frame < framesWritten - readableFrames) return null
        val value = flags[(frame % capacityFrames).toInt()].toInt() and bit != 0
        return if (frame < framesWritten - readableFrames) null else value
    }

    /**
     * Blocks the (single) reader thread until at least [frames] frames are published or [timeoutNanos] passes;
     * returns whether they are. The writer wakes it without locking.
     */
    fun awaitFrames(frames: Long, timeoutNanos: Long): Boolean {
        if (framesWritten >= frames) return true
        val deadline = System.nanoTime() + timeoutNanos
        waiter = Thread.currentThread()
        try {
            while (framesWritten < frames) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return false
                LockSupport.parkNanos(this, left)
            }
            return true
        } finally {
            waiter = null
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        /** Spec 4.2: a press is backdated by 500 ms. */
        const val PTT_PRE_ROLL_MS = 500
        /** Spec 4.2: the stop spotter replays 300 ms when the VAD opens a segment. */
        const val SPOTTER_PRE_ROLL_MS = 300
        const val OVERRUN = -1
        /** 200 ms of slots the writer may be filling; never handed to a reader. */
        const val GUARD_FRAMES = 10
        private const val SPEECH = 1
        private const val MUTED = 2

        fun samplesForMs(ms: Int): Int = SAMPLE_RATE / 1000 * ms
    }
}
