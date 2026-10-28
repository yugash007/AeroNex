package com.aeronex.app.speech

import java.text.Normalizer

/**
 * The voice stop spotter (spec 4.12 trigger 4): armed only while the vehicle is armed or airborne, the app owns the
 * mode and PTT is not held; fed VAD-gated audio ([SpotterGate]) on the speech thread. Lenient by design: a false
 * STOP while the app owns the mode is a benign hover.
 */
interface StopSpotter : AutoCloseable {
    /** Feeds 16 kHz mono PCM16. Returns true once per detection; the spotter then starts afresh. */
    fun accept(samples: ShortArray, offset: Int, count: Int): Boolean

    /**
     * The VAD segment ended (its hangover ran out): decide on what is still undecided. Returns true for a detection.
     * A spotter that decides only in [accept] keeps the default.
     */
    fun endSegment(): Boolean = false

    /** Called between VAD segments: forget all decoding state. */
    fun reset()
}

/**
 * Spec 4.12: a stop token in the partial result on [steps] consecutive 100 ms decode steps. A final (endpoint) result
 * that contains a token counts at once (the backstop). Fires once, then needs [reset].
 *
 * Tokens are the first word of each stop-lane phrase (T9: "रुक जाओ" -> "रुक"), compared after NFC normalisation and
 * lower-casing. "hold" and "hover" are never tokens: hold is a PTT command, not a STOP (T9 decision).
 */
class StopTokenRule(tokens: Collection<String>, private val steps: Int = 2) {
    private val tokens: Set<String> = tokens.map(::normalize).filter { it.isNotEmpty() }.toSet()
    private var run = 0
    private var fired = false

    init {
        require(this.tokens.isNotEmpty()) { "No stop tokens" }
        require(steps >= 1)
    }

    /** One 100 ms decode step whose partial result had [words]. */
    fun partial(words: List<String>): Boolean {
        if (fired) return false
        run = if (words.any { normalize(it) in tokens }) run + 1 else 0
        return fire(run >= steps)
    }

    /** A final (endpoint) result with [words]. */
    fun final(words: List<String>): Boolean {
        if (fired) return false
        run = 0
        return fire(words.any { normalize(it) in tokens })
    }

    fun reset() {
        run = 0
        fired = false
    }

    private fun fire(hit: Boolean): Boolean {
        if (hit) fired = true
        return hit
    }

    companion object {
        /** The stop tokens of a language's stop lane: the first word of each phrase. */
        fun tokensFor(stopLane: List<String>): Set<String> =
            stopLane.mapNotNull { phrase -> words(phrase).firstOrNull() }.map(::normalize).toSet()

        fun words(text: String): List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

        fun normalize(word: String): String =
            Normalizer.normalize(word.trim(), Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)

        private val WHITESPACE = Regex("\\s+")
    }
}
