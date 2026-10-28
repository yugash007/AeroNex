package com.aeronex.app.bench

import com.aeronex.app.speech.SpeechEngine
import com.aeronex.app.speech.SpeechResult
import com.aeronex.app.speech.StopSpotter

/**
 * Measures the compute time of a command recognizer per utterance: wall time inside [accept] (streaming decode while
 * PTT is held) and [finish] (the final decode). [begin], called at PTT down, restarts the timers. RTF is
 * `(acceptNanos + finishNanos) / audio duration`.
 */
class TimedEngine(
    private val inner: SpeechEngine,
    private val nanoTime: () -> Long = System::nanoTime,
) : SpeechEngine by inner {
    var acceptNanos = 0L
        private set
    var finishNanos = 0L
        private set
    var acceptedSamples = 0L
        private set

    override fun begin() {
        acceptNanos = 0
        finishNanos = 0
        acceptedSamples = 0
        inner.begin()
    }

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        val started = nanoTime()
        try {
            return inner.accept(samples, offset, count)
        } finally {
            acceptNanos += nanoTime() - started
            acceptedSamples += count
        }
    }

    override fun finish(): SpeechResult {
        val started = nanoTime()
        try {
            return inner.finish()
        } finally {
            finishNanos += nanoTime() - started
        }
    }
}

/** Measures a stop spotter's compute time and the audio the [com.aeronex.app.speech.SpotterGate] fed it. */
class TimedSpotter(
    private val inner: StopSpotter,
    private val nanoTime: () -> Long = System::nanoTime,
) : StopSpotter by inner {
    var acceptNanos = 0L
        private set
    var fedSamples = 0L
        private set

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        val started = nanoTime()
        try {
            return inner.accept(samples, offset, count)
        } finally {
            acceptNanos += nanoTime() - started
            fedSamples += count
        }
    }

    /** The bench's per-clip restart (the gate's [reset] is the spotter's own, between VAD segments). */
    fun resetTimers() {
        acceptNanos = 0
        fedSamples = 0
    }
}
