package com.aeronex.app.speech

/** One hypothesis. [score] is the engine's raw score: a Vosk utterance score (about 50-180, W1), null for CTC. */
data class SpeechAlternative(val text: String, val score: Double?)

/**
 * The final result of one push-to-talk utterance.
 *
 * [alternatives] is best first. Vosk grammar engines give up to 3 with raw scores; the parser (T10) applies the
 * n-best margin in those score units. With n-best on, Vosk produces no separate 1-best text, and alternatives[0] is
 * simply the best-scoring path (W1: do not assume it equals a 1-best decode). CTC gives one alternative without a
 * score. Empty alternatives mean nothing was recognised; [failure] then says why when it was not just silence.
 */
data class SpeechResult(
    val engine: EngineKind,
    val language: String,
    val alternatives: List<SpeechAlternative>,
    /** Samples of audio the engine heard for this utterance (16 kHz). */
    val audioSamples: Long,
    /** Wall time spent in [SpeechEngine.finish] (the final decode), ns. */
    val finishNanos: Long,
    val failure: Failure? = null,
) {
    enum class Failure {
        /** No model is loaded (pack missing or failed to load). */
        NO_MODEL,
        /** The utterance was longer than the engine's buffer; refused rather than decoded in part. */
        TOO_LONG,
        /** The native decoder threw. */
        ENGINE_ERROR,
    }

    val text: String get() = alternatives.firstOrNull()?.text.orEmpty()
}
