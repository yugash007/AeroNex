package com.aeronex.app.audio

/** A blocking 16 kHz mono PCM16 input: [AndroidPcmSource] on a phone, a fake in JVM tests. */
interface PcmSource {
    /**
     * Opens and starts the input. [onClientSilenced] reports the platform's "client silenced" state (another app took
     * the microphone) from any thread; sources without that signal never call it.
     */
    fun start(onClientSilenced: (Boolean) -> Unit)

    /** Blocks until samples arrive; returns how many were read into [buffer], 0 once stopped, or a negative error. */
    fun read(buffer: ShortArray, offset: Int, count: Int): Int

    /** Stops the input and unblocks a pending [read]. Called from any thread. */
    fun stop()

    /** Frees the input. Called once on the capture thread after its last [read]. */
    fun release()
}

/**
 * Session-long audio capture (spec 4.2): one dedicated thread does blocking reads of whole 20 ms frames into one
 * preallocated buffer, then per frame runs the [EnergyVad], writes the frame and its decision into the [ring] and
 * runs the [SilenceDetector]. The loop allocates nothing (tested). The speech thread reads the ring; nothing here
 * touches the link or STOP threads.
 *
 * [threadSetup] runs first on the capture thread (on Android: THREAD_PRIORITY_URGENT_AUDIO). [listener] is called
 * on the capture thread, only on changes, and must not block.
 */
class AudioCapture(
    private val source: PcmSource,
    val ring: AudioRing = AudioRing(),
    private val vad: EnergyVad = EnergyVad(),
    private val silence: SilenceDetector = SilenceDetector(),
    private val listener: Listener = Listener.NONE,
    private val threadSetup: () -> Unit = {},
) {
    interface Listener {
        /** Silenced capture began (true) or ended (false): switch to or leave the "No voice" level (4.11). */
        fun onSilencedChanged(silenced: Boolean) {}

        /** The source returned error [code] (an AudioRecord ERROR_* value, or [START_FAILED]); capture has stopped. */
        fun onCaptureFailed(code: Int) {}

        companion object {
            val NONE = object : Listener {}
        }
    }

    private val frame = ShortArray(AudioRing.FRAME_SAMPLES)
    private val clientSilenced: (Boolean) -> Unit = { silence.setClientSilenced(it) }
    private var reportedSilenced = false

    @Volatile private var playbackMuted = false

    @Volatile var running = false
        private set

    var thread: Thread? = null
        private set

    /** Whether capture is silenced (as of the last frame). */
    val silenced: Boolean get() = silence.silenced

    /** While the app plays its own prompt or alarm audio, frames are marked muted and never count as speech. */
    fun setPlaybackMuted(muted: Boolean) {
        playbackMuted = muted
    }

    @Synchronized fun start() {
        check(thread == null) { "Capture already started" }
        running = true
        thread = Thread(::run, "aeronex-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the source and waits for the capture thread to end. */
    fun stop() {
        running = false
        source.stop()
        thread?.let { if (it !== Thread.currentThread()) it.join(STOP_JOIN_MS) }
    }

    private fun run() {
        try {
            threadSetup()
        } catch (_: Exception) {
            // Default priority rather than no capture.
        }
        try {
            source.start(clientSilenced)
            while (running && step()) {
                // One frame per iteration.
            }
        } catch (error: Exception) {
            if (running) listener.onCaptureFailed(START_FAILED)
        } finally {
            running = false
            source.release()
        }
    }

    /**
     * One loop iteration: read one whole frame and process it. Returns false when the source failed or was stopped.
     * Runs on the capture thread (tests call it directly).
     */
    internal fun step(): Boolean {
        var filled = 0
        while (filled < frame.size) {
            val n = source.read(frame, filled, frame.size - filled)
            if (n < 0) {
                // After stop() a source may answer with an error: that is the end, not a failure.
                if (running) {
                    running = false
                    listener.onCaptureFailed(n)
                }
                return false
            }
            if (n == 0 && !running) return false
            filled += n
        }
        val muted = playbackMuted
        val speech = vad.accept(frame, 0, frame.size)
        ring.write(frame, speech, muted)
        val silenced = silence.accept(frame, 0, frame.size)
        if (silenced != reportedSilenced) {
            reportedSilenced = silenced
            listener.onSilencedChanged(silenced)
        }
        return true
    }

    companion object {
        /** [Listener.onCaptureFailed] code: the source could not be opened (no RECORD_AUDIO, device busy). */
        const val START_FAILED = -1000
        private const val STOP_JOIN_MS = 2_000L
    }
}
