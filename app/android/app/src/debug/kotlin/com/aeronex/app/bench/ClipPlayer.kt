package com.aeronex.app.bench

import com.aeronex.app.audio.AudioRing
import com.aeronex.app.audio.PcmSource
import java.util.concurrent.locks.LockSupport

/**
 * The bench's microphone for [com.aeronex.app.audio.AudioCapture]: an always-on 16 kHz mono source of
 * digital silence that plays one queued clip at a time ([play]). Frame `k` is handed out no earlier than
 * `start + (k + 1) × 20 ms`, the moment a real microphone would have finished capturing it, so clips are fed at real
 * time, never faster (a capture thread that fell behind catches up at once, as from a recorder's buffer). The PTT
 * latency numbers therefore include the real-time adaptive tail.
 */
class ClipPlayer(private val nanoTime: () -> Long = System::nanoTime) : PcmSource {
    /** One clip in capture positions: [startSample] (inclusive) to [endSample] (exclusive), whole frames. */
    class Playback internal constructor(val startSample: Long, val endSample: Long) {
        val startFrame: Long get() = startSample / FRAME
        val endFrame: Long get() = endSample / FRAME

        /** When the clip's first frame was captured (the end of its 20 ms). */
        @Volatile var startedAtNanos: Long = NOT_YET
            internal set

        /** When its last frame was captured: the bench's PTT release moment. */
        @Volatile var endedAtNanos: Long = NOT_YET
            internal set

        val ended: Boolean get() = endedAtNanos != NOT_YET

        /** The real-time moment the clip began (its first frame's start). */
        val clipStartNanos: Long get() = startedAtNanos - FRAME_NANOS
    }

    private val lock = Any()
    private val current = ShortArray(FRAME)
    private var used = FRAME
    private var clip: ShortArray? = null
    private var playback: Playback? = null
    /** Frames whose content is fixed: the index of the next frame to fill. */
    private var decided = 0L
    private var origin = NOT_YET
    @Volatile private var reader: Thread? = null
    @Volatile private var stopped = false

    @Volatile var framesDelivered = 0L
        private set

    /** Queues [samples] to start at the next frame not yet filled; the previous clip must have been filled. */
    fun play(samples: ShortArray): Playback {
        require(samples.isNotEmpty()) { "Empty clip" }
        synchronized(lock) {
            val active = playback
            check(active == null || decided >= active.endFrame) { "One clip at a time" }
            val frames = (samples.size + FRAME - 1) / FRAME
            return Playback(decided * FRAME, (decided + frames) * FRAME).also {
                clip = samples
                playback = it
            }
        }
    }

    override fun start(onClientSilenced: (Boolean) -> Unit) {
        origin = nanoTime()
    }

    override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
        if (used == FRAME && !next()) return 0
        val n = minOf(count, FRAME - used)
        System.arraycopy(current, used, buffer, offset, n)
        used += n
        return n
    }

    override fun stop() {
        stopped = true
        reader?.let(LockSupport::unpark)
    }

    override fun release() {}

    /** Fills the next frame, then waits until it would have been captured. False once stopped. */
    private fun next(): Boolean {
        if (stopped) return false
        reader = Thread.currentThread()
        if (origin == NOT_YET) origin = nanoTime()
        val index: Long
        val owner: Playback?
        synchronized(lock) {
            index = decided++
            owner = playback?.takeIf { index >= it.startFrame && index < it.endFrame }
            current.fill(0)
            if (owner != null) {
                val samples = checkNotNull(clip)
                val from = ((index - owner.startFrame) * FRAME).toInt()
                System.arraycopy(samples, from, current, 0, minOf(FRAME, samples.size - from))
            }
        }
        val due = origin + (index + 1) * FRAME_NANOS
        while (!stopped) {
            val left = due - nanoTime()
            if (left <= 0) break
            LockSupport.parkNanos(this, left)
        }
        if (stopped) return false
        val now = nanoTime()
        if (owner != null) {
            if (index == owner.startFrame) owner.startedAtNanos = now
            if (index == owner.endFrame - 1) owner.endedAtNanos = now
        }
        used = 0
        framesDelivered++
        return true
    }

    companion object {
        const val NOT_YET = Long.MIN_VALUE
        private const val FRAME = AudioRing.FRAME_SAMPLES
        const val FRAME_NANOS = AudioRing.FRAME_MS * 1_000_000L
    }
}
