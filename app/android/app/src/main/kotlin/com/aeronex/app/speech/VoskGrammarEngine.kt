package com.aeronex.app.speech

import org.json.JSONObject

/** The org.vosk.Recognizer calls the engines use; results are the JSON strings Vosk returns. */
interface VoskRecognizerHandle : AutoCloseable {
    fun setMaxAlternatives(max: Int)
    fun setPartialWords(enabled: Boolean)
    /** Returns true at an endpoint: [result] then holds the finished segment. */
    fun acceptWaveForm(samples: ShortArray, count: Int): Boolean
    fun result(): String
    fun partialResult(): String
    fun finalResult(): String
    fun reset()
}

/** A loaded Vosk model (all heap, spec 4.6); recognizers are created on it. */
interface VoskModelHandle : AutoCloseable {
    /** A recognizer restricted to [grammarJson], a JSON array of phrases. */
    fun recognizer(grammarJson: String): VoskRecognizerHandle
}

/**
 * Vosk grammar mode (spec 4.6, Tier L streaming path; en, hi and te): one resident model per session with two
 * recognizers created on it, the command grammar (n-best of [maxAlternatives]) and the stop-only grammar
 * ([stopSpotter]). Audio goes to Vosk in 100 ms decode steps. While PTT is held, a stop token in the command
 * partials on two consecutive steps makes [accept] return true (spec 4.12 trigger 5).
 *
 * [partialWords] is off by default: with `setPartialWords(true)` the host Vosk 0.3.45 library returns an empty
 * partial text (and no `partial_result`) in open and grammar mode, which would silently disable the partial-token
 * STOP (T11 host smoke). The partial-word list is still read when a build provides it. The Android AAR is 0.3.75;
 * whether it behaves the same is to be checked on a device (T14) before turning this on.
 *
 * Used from the speech thread only. [close] releases the recognizers, then the model.
 */
class VoskGrammarEngine(
    override val language: String,
    private val model: VoskModelHandle,
    commandGrammar: String,
    stopGrammar: String,
    stopTokens: Set<String>,
    partialWords: Boolean = false,
    private val maxAlternatives: Int = MAX_ALTERNATIVES,
) : SpeechEngine {
    override val kind = EngineKind.VOSK_GRAMMAR

    private val rule = StopTokenRule(stopTokens)
    private val recognizers = openRecognizers(model, commandGrammar, stopGrammar, partialWords, maxAlternatives)
    private val command: VoskRecognizerHandle = recognizers.first
    /** The stop-only grammar on the same resident model (spec 4.12 Tier L Vosk path). */
    val stopSpotter = VoskStopSpotter(recognizers.second, StopTokenRule(stopTokens))
    private val steps = VoskSteps()
    private val segments = mutableListOf<List<SpeechAlternative>>()
    private var audioSamples = 0L

    override fun begin() {
        command.reset()
        rule.reset()
        steps.clear()
        segments.clear()
        audioSamples = 0
    }

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        audioSamples += count
        var fired = false
        steps.feed(samples, offset, count) { step, n ->
            if (command.acceptWaveForm(step, n)) {
                val alternatives = VoskJson.alternatives(command.result())
                addSegment(alternatives)
                if (rule.final(StopTokenRule.words(alternatives.firstOrNull()?.text.orEmpty()))) fired = true
            } else if (rule.partial(VoskJson.partialWords(command.partialResult()))) {
                fired = true
            }
            true
        }
        return fired
    }

    override fun finish(): SpeechResult {
        val started = System.nanoTime()
        steps.flush { step, n ->
            if (command.acceptWaveForm(step, n)) addSegment(VoskJson.alternatives(command.result()))
        }
        addSegment(VoskJson.alternatives(command.finalResult()))
        val alternatives = NBest.join(segments, maxAlternatives)
        segments.clear()
        return SpeechResult(kind, language, alternatives, audioSamples, System.nanoTime() - started)
    }

    override fun cancel() {
        begin()
    }

    override fun close() {
        closeAll(listOf(command, stopSpotter, model), null)
    }

    /** A segment whose alternatives are all empty (silence) adds nothing. */
    private fun addSegment(alternatives: List<SpeechAlternative>) {
        if (alternatives.any { it.text.isNotEmpty() }) segments += alternatives
    }

    companion object {
        /** Spec 4.6: `setMaxAlternatives(3)`. */
        const val MAX_ALTERNATIVES = 3
        /** One Vosk decode step: 100 ms at 16 kHz (spec 4.12: "two consecutive 100 ms decode steps"). */
        const val STEP_SAMPLES = 1600

        /** Command and stop recognizers on [model]; on failure everything opened, the model too, is closed. */
        private fun openRecognizers(model: VoskModelHandle, commandGrammar: String, stopGrammar: String,
                                    partialWords: Boolean, maxAlternatives: Int): Pair<VoskRecognizerHandle, VoskRecognizerHandle> {
            val opened = mutableListOf<AutoCloseable>()
            try {
                val command = model.recognizer(commandGrammar).also { opened += it }
                command.setMaxAlternatives(maxAlternatives)
                if (partialWords) command.setPartialWords(true)
                val stop = model.recognizer(stopGrammar).also { opened += it }
                if (partialWords) stop.setPartialWords(true)
                return command to stop
            } catch (error: Throwable) {
                closeAll(opened + model, error)
                throw error
            }
        }

        internal fun closeAll(resources: List<AutoCloseable?>, primary: Throwable?) {
            var failure = primary
            for (resource in resources) {
                try {
                    resource?.close()
                } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                }
            }
            if (primary == null) failure?.let { throw it }
        }
    }
}

/**
 * The Tier L Vosk voice stop spotter (spec 4.12): the stop-only grammar on the resident model. Detection uses the
 * partial result on two consecutive 100 ms steps; an endpoint result, and the final result when the VAD segment ends
 * ([endSegment]), are the backstop. After a detection, at a segment's end, or on [reset] between VAD segments, the
 * recognizer starts afresh.
 */
class VoskStopSpotter internal constructor(
    private val recognizer: VoskRecognizerHandle,
    private val rule: StopTokenRule,
) : StopSpotter {
    private val steps = VoskSteps()

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        var fired = false
        steps.feed(samples, offset, count) { step, n ->
            fired = if (recognizer.acceptWaveForm(step, n)) {
                rule.final(words(recognizer.result()))
            } else {
                rule.partial(VoskJson.partialWords(recognizer.partialResult()))
            }
            !fired
        }
        if (fired) reset()
        return fired
    }

    /** The segment's end: decode the part step, then the final result is the backstop (spec 4.12). */
    override fun endSegment(): Boolean {
        var endpoint = emptyList<String>()
        steps.flush { step, n -> if (recognizer.acceptWaveForm(step, n)) endpoint = words(recognizer.result()) }
        val fired = rule.final(endpoint) || rule.final(words(recognizer.finalResult()))
        reset()
        return fired
    }

    override fun reset() {
        recognizer.reset()
        rule.reset()
        steps.clear()
    }

    private fun words(json: String) = StopTokenRule.words(VoskJson.alternatives(json).firstOrNull()?.text.orEmpty())

    override fun close() {
        recognizer.close()
    }
}

/** Collects audio into whole 100 ms decode steps (one preallocated buffer). */
internal class VoskSteps {
    private val step = ShortArray(VoskGrammarEngine.STEP_SAMPLES)
    private var filled = 0

    /** Calls [decode] for every completed step until it returns false (then the rest of the call is dropped). */
    fun feed(samples: ShortArray, offset: Int, count: Int, decode: (ShortArray, Int) -> Boolean) {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size) { "Bad sample range" }
        var index = offset
        val end = offset + count
        while (index < end) {
            val n = minOf(step.size - filled, end - index)
            System.arraycopy(samples, index, step, filled, n)
            filled += n
            index += n
            if (filled == step.size) {
                filled = 0
                if (!decode(step, step.size)) {
                    return
                }
            }
        }
    }

    /** Hands a partly filled last step to [decode]. */
    fun flush(decode: (ShortArray, Int) -> Unit) {
        if (filled > 0) decode(step, filled)
        filled = 0
    }

    fun clear() {
        filled = 0
    }
}

/** Vosk's JSON results. */
internal object VoskJson {
    /** Words of a partial result: `partial_result` when the build fills it (partial words on), else `partial`. */
    fun partialWords(json: String): List<String> {
        val result = JSONObject(json)
        val words = result.optJSONArray("partial_result")
        if (words != null && words.length() > 0) {
            return (0 until words.length()).mapNotNull { words.optJSONObject(it)?.optString("word")?.takeIf(String::isNotEmpty) }
        }
        return StopTokenRule.words(result.optString("partial"))
    }

    /** A final or endpoint result: the n-best `alternatives` with their raw `confidence`, or a plain `text`. */
    fun alternatives(json: String): List<SpeechAlternative> {
        val result = JSONObject(json)
        val list = result.optJSONArray("alternatives")
            ?: return listOf(SpeechAlternative(result.optString("text").trim(), null))
        return (0 until list.length()).map {
            val alternative = list.getJSONObject(it)
            SpeechAlternative(alternative.optString("text").trim(),
                if (alternative.has("confidence")) alternative.getDouble("confidence") else null)
        }
    }
}

/**
 * Joins the n-best lists of consecutive segments (the endpointer may split one PTT utterance). Vosk's alternative
 * scores are utterance log-likelihoods, so a joined hypothesis scores the sum of its parts; the best [max] joined
 * hypotheses are kept, best first. With one segment the list is returned as Vosk ordered it.
 */
internal object NBest {
    fun join(segments: List<List<SpeechAlternative>>, max: Int): List<SpeechAlternative> {
        if (segments.isEmpty()) return emptyList()
        if (segments.size == 1) return segments[0].take(max)
        var joined = segments[0].take(max)
        for (segment in segments.drop(1)) {
            joined = joined.flatMap { left ->
                segment.take(max).map { right ->
                    SpeechAlternative(listOf(left.text, right.text).filter(String::isNotEmpty).joinToString(" "),
                        if (left.score != null && right.score != null) left.score + right.score else null)
                }
            }.sortedByDescending { it.score ?: Double.NEGATIVE_INFINITY }.take(max)
        }
        return joined
    }
}
