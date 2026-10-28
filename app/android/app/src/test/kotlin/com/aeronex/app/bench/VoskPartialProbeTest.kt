package com.aeronex.app.bench

import com.aeronex.app.speech.VoskModelHandle
import com.aeronex.app.speech.VoskRecognizerHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoskPartialProbeTest {
    /** Behaves like host Vosk 0.3.45 (T11): partial words on empties every partial. */
    private class Recognizer(val grammar: String) : VoskRecognizerHandle {
        var wordsOn = false
        var alternatives = 0
        var fed = 0
        var closed = false

        override fun setMaxAlternatives(max: Int) { alternatives = max }
        override fun setPartialWords(enabled: Boolean) { wordsOn = enabled }
        override fun acceptWaveForm(samples: ShortArray, count: Int): Boolean {
            fed += count
            return false
        }
        override fun result() = """{"text" : ""}"""
        override fun partialResult() = if (wordsOn) """{"partial" : ""}""" else """{"partial" : "take off"}"""
        override fun finalResult() = """{"alternatives" : [{"confidence" : 140.5, "text" : "take off to three metres"}]}"""
        override fun reset() {}
        override fun close() { closed = true }
    }

    private class Model : VoskModelHandle {
        val made = mutableListOf<Recognizer>()
        override fun recognizer(grammarJson: String): VoskRecognizerHandle = Recognizer(grammarJson).also { made += it }
        override fun close() = error("the probe never closes the engine's model")
    }

    @Test fun comparesPartialsWithPartialWordsOffAndOn() {
        val model = Model()
        val probe = VoskPartialProbe.run(model, "[\"take off\"]", ShortArray(4_000))

        assertEquals(mapOf("steps" to 3, "endpoints" to 0, "nonEmptyPartials" to 3, "partialsWithWordList" to 0,
            "lastPartial" to "take off", "final" to "take off to three metres"), probe["partialWordsOff"])
        assertEquals(mapOf("steps" to 3, "endpoints" to 0, "nonEmptyPartials" to 0, "partialsWithWordList" to 0,
            "lastPartial" to "", "final" to "take off to three metres"), probe["partialWordsOn"])
        assertEquals(2, model.made.size)
        assertTrue(model.made.all { it.closed && it.fed == 4_000 && it.alternatives == 3 && it.grammar == "[\"take off\"]" })
        assertEquals(listOf(false, true), model.made.map { it.wordsOn })
    }
}
