package com.aeronex.app.speech

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * IndicConformer CTC with its model loaded lazily (0.4.0 spec, "Telugu with IndicConformer, optimised"). Voice start
 * loads nothing; [prewarm] (the language was selected) or the first PTT press ([begin]) starts the load on a loader
 * thread ([loader]), so the speech thread keeps feeding the stop spotter and buffering the utterance meanwhile: the
 * ~200 MB model takes seconds to load and the capture ring holds 3 s. The speech thread calls [finish] once the model is
 * READY (or FAILED): [modelLoad].
 *
 * One CTC model is resident at a time ([residency], process-wide): a load waits until the previous engine's model is
 * freed, at most [residencyWaitMs]. A failed load (out of memory, a native error) stays FAILED for this engine: [finish]
 * says NO_MODEL and the session shows voice off. Only the loader thread touches the decoder until the model is READY.
 */
class LazyCtcEngine(
    private val spec: CtcModelSpec,
    decoders: (CtcModelSpec) -> CtcDecoder,
    private val loader: (Runnable) -> Unit = ::loaderThread,
    private val residency: Semaphore = RESIDENCY,
    maxUtteranceMs: Int = SherpaCtcEngine.MAX_UTTERANCE_MS,
    private val residencyWaitMs: Long = RESIDENCY_WAIT_MS,
) : SpeechEngine {
    private val engine = SherpaCtcEngine(maxUtteranceMs, decoders)
    private val lock = Any()
    @Volatile private var state = ModelLoad.COLD
    private var closed = false
    private var resident = false
    private var heard = 0L

    override val kind get() = EngineKind.SHERPA_CTC
    override val language: String get() = spec.language
    override val modelLoad: ModelLoad get() = state

    override fun prewarm() {
        synchronized(lock) {
            if (closed || state != ModelLoad.COLD) return
            state = ModelLoad.LOADING
        }
        try {
            loader(Runnable(::load))
        } catch (_: Throwable) {
            // No thread for the load (out of memory): voice off rather than an utterance that waits for ever.
            synchronized(lock) { if (!closed) state = ModelLoad.FAILED }
        }
    }

    private fun load() {
        val acquired = try {
            residency.tryAcquire(residencyWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        synchronized(lock) {
            resident = acquired
            if (!acquired || closed) {
                if (!closed) state = ModelLoad.FAILED
                free()
                return
            }
        }
        val loaded = try {
            engine.load(spec)
            true
        } catch (_: Throwable) {
            // Out of memory or a native failure: voice degrades, the session and STOP do not (spec 4.11).
            false
        }
        synchronized(lock) {
            if (closed || !loaded) {
                if (!closed) state = ModelLoad.FAILED
                free()
            } else {
                state = ModelLoad.READY
            }
        }
    }

    /** Frees the model and the resident slot (under [lock]). */
    private fun free() {
        try {
            engine.close()
        } finally {
            if (resident) residency.release()
            resident = false
        }
    }

    /** A press is the latest moment to start the load. */
    override fun begin() {
        prewarm()
        heard = 0
        engine.begin()
    }

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        heard += count
        return engine.accept(samples, offset, count)
    }

    override fun finish(): SpeechResult =
        if (state == ModelLoad.READY) engine.finish()
        else SpeechResult(kind, language, emptyList(), heard, 0, SpeechResult.Failure.NO_MODEL)

    override fun cancel() {
        heard = 0
        engine.cancel()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // A load in progress frees the model itself once it returns.
            if (state != ModelLoad.LOADING) free()
        }
    }

    companion object {
        /** One IndicConformer model (~300 MiB of heap, W1) in the process at a time. */
        val RESIDENCY = Semaphore(1, true)
        const val RESIDENCY_WAIT_MS = 60_000L

        /**
         * A thread per load at the default priority: below the speech thread (FOREGROUND), capture and the link
         * threads, but not in the background group, because the operator may be waiting on the first PTT.
         */
        fun loaderThread(task: Runnable) {
            Thread(task, "aeronex-ctc-load").apply { isDaemon = true }.start()
        }
    }
}
