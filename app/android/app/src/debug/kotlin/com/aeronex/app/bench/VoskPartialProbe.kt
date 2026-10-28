package com.aeronex.app.bench

import com.aeronex.app.speech.VoskGrammarEngine
import com.aeronex.app.speech.VoskJson
import com.aeronex.app.speech.VoskModelHandle
import org.json.JSONObject

/**
 * The T11 open question for T14: does vosk-android 0.3.75 still return partial text with `setPartialWords(true)`?
 * (Host Vosk 0.3.45 empties every partial, so the engine keeps it off: VoskGrammarEngine.partialWords.) Feeds one clip
 * in 100 ms steps through two fresh command-grammar recognizers on the loaded model, partial words off and on, and
 * counts the partial results that carry words. Not a timing measurement; the recognizers are closed afterwards.
 */
object VoskPartialProbe {
    fun run(model: VoskModelHandle, grammar: String, samples: ShortArray): Map<String, Any?> = linkedMapOf(
        "partialWordsOff" to pass(model, grammar, samples, partialWords = false),
        "partialWordsOn" to pass(model, grammar, samples, partialWords = true),
    )

    private fun pass(model: VoskModelHandle, grammar: String, samples: ShortArray, partialWords: Boolean): Map<String, Any?> =
        model.recognizer(grammar).use { recognizer ->
            recognizer.setMaxAlternatives(VoskGrammarEngine.MAX_ALTERNATIVES)
            if (partialWords) recognizer.setPartialWords(true)
            var steps = 0
            var endpoints = 0
            var nonEmpty = 0
            var withWordList = 0
            var last = ""
            var at = 0
            while (at < samples.size) {
                val n = minOf(VoskGrammarEngine.STEP_SAMPLES, samples.size - at)
                val step = samples.copyOfRange(at, at + n)
                at += n
                steps++
                if (recognizer.acceptWaveForm(step, n)) {
                    endpoints++
                    continue
                }
                val json = recognizer.partialResult()
                val words = VoskJson.partialWords(json)
                if (words.isNotEmpty()) {
                    nonEmpty++
                    last = words.joinToString(" ")
                }
                if ((JSONObject(json).optJSONArray("partial_result")?.length() ?: 0) > 0) withWordList++
            }
            linkedMapOf(
                "steps" to steps,
                "endpoints" to endpoints,
                "nonEmptyPartials" to nonEmpty,
                "partialsWithWordList" to withWordList,
                "lastPartial" to last,
                "final" to VoskJson.alternatives(recognizer.finalResult()).firstOrNull()?.text.orEmpty(),
            )
        }
}
