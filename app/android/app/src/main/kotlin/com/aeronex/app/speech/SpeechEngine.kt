package com.aeronex.app.speech

/** The runtime behind an engine (spec 4.0: Tier L loads exactly one per session). */
enum class SpeechRuntime { VOSK, SHERPA_ONNX }

enum class EngineKind(val runtime: SpeechRuntime, val streaming: Boolean) {
    /** Resident Vosk model with command and stop grammars (en, hi, te). */
    VOSK_GRAMMAR(SpeechRuntime.VOSK, streaming = true),
    /** IndicConformer NeMo CTC through sherpa-onnx; decodes once after release + tail. */
    SHERPA_CTC(SpeechRuntime.SHERPA_ONNX, streaming = false),
}

/** Where an engine's model is. Engines opened with their model are always [READY]; [LazyCtcEngine] starts [COLD]. */
enum class ModelLoad { COLD, LOADING, READY, FAILED }

/**
 * A push-to-talk command recognizer. One utterance at a time, driven by the speech thread only:
 * [begin] at PTT down (the caller then feeds the 500 ms pre-roll first), [accept] for every captured chunk, [finish]
 * after release + adaptive tail, or [cancel] when a STOP key ends PTT (the utterance is discarded).
 */
interface SpeechEngine : AutoCloseable {
    val kind: EngineKind
    val language: String

    /** Streaming engines report stop tokens in partials while PTT is held (spec 4.12 trigger 5). */
    val streaming: Boolean get() = kind.streaming

    /** The speech thread calls [finish] only once this is [ModelLoad.READY] or [ModelLoad.FAILED]. */
    val modelLoad: ModelLoad get() = ModelLoad.READY

    /** Starts loading a [ModelLoad.COLD] model off the speech thread (the language was selected, or PTT pressed). */
    fun prewarm() {}

    fun begin()

    /**
     * Feeds 16 kHz mono PCM16. Returns true once when a stop token has appeared in the partial result on two
     * consecutive 100 ms decode steps: the caller then runs STOP and ends PTT. Non-streaming engines never do.
     */
    fun accept(samples: ShortArray, offset: Int, count: Int): Boolean

    /** Ends the utterance and returns its final result. */
    fun finish(): SpeechResult

    /** Discards the current utterance. */
    fun cancel()
}
