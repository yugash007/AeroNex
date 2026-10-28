package com.aeronex.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JSON shapes are the ones the host Vosk library returns (scratchpad t11 smoke): partials, n-best and plain finals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoskGrammarEngineTest {
    /** A scripted org.vosk.Recognizer: one queued answer per 100 ms step; unscripted steps give an empty partial. */
    class FakeRecognizer(val grammar: String) : VoskRecognizerHandle {
        val steps = ArrayDeque<Step>()
        val fed = mutableListOf<Int>()
        var finalJson = """{"text" : ""}"""
        var maxAlternatives: Int? = null
        var partialWords: Boolean? = null
        var resets = 0
        var closed = false
        var failOnClose = false
        private var pendingResult: String? = null
        private var pendingPartial = """{"partial" : ""}"""

        sealed interface Step
        data class Partial(val json: String) : Step
        data class Endpoint(val json: String) : Step

        fun partial(text: String) = steps.add(Partial("{\n  \"partial\" : \"$text\"\n}"))
        fun endpoint(json: String) = steps.add(Endpoint(json))

        override fun setMaxAlternatives(max: Int) {
            maxAlternatives = max
        }
        override fun setPartialWords(enabled: Boolean) {
            partialWords = enabled
        }
        override fun acceptWaveForm(samples: ShortArray, count: Int): Boolean {
            fed += count
            return when (val step = steps.removeFirstOrNull()) {
                is Endpoint -> { pendingResult = step.json; true }
                is Partial -> { pendingPartial = step.json; false }
                null -> { pendingPartial = """{"partial" : ""}"""; false }
            }
        }
        override fun result(): String = checkNotNull(pendingResult).also { pendingResult = null }
        override fun partialResult(): String = pendingPartial
        override fun finalResult(): String = finalJson
        override fun reset() {
            resets++
            pendingPartial = """{"partial" : ""}"""
        }
        override fun close() {
            closed = true
            if (failOnClose) throw IllegalStateException("native close failed")
        }
    }

    class FakeModel : VoskModelHandle {
        val recognizers = mutableListOf<FakeRecognizer>()
        var closed = false
        var failOn: Int = -1
        override fun recognizer(grammarJson: String): VoskRecognizerHandle {
            if (recognizers.size == failOn) throw java.io.IOException("bad grammar")
            return FakeRecognizer(grammarJson).also { recognizers += it }
        }
        override fun close() {
            closed = true
        }
    }

    private val model = FakeModel()
    private val engine = VoskGrammarEngine("en", model, COMMAND, STOP, setOf("stop"))
    private val command get() = model.recognizers[0]
    private val stop get() = model.recognizers[1]
    private val step = ShortArray(1600)

    private fun nBest(vararg alternatives: Pair<String, Double>) = alternatives.joinToString(
        prefix = "{\n  \"alternatives\" : [", postfix = "]\n}") { (text, score) ->
        "{\n      \"confidence\" : $score,\n      \"text\" : \"$text\"\n    }"
    }

    @Test fun oneResidentModelCarriesTheCommandAndStopGrammars() {
        assertEquals(2, model.recognizers.size)
        assertEquals(COMMAND, command.grammar)
        assertEquals(3, command.maxAlternatives)
        assertEquals(STOP, stop.grammar)
        assertNull("the stop grammar needs no n-best", stop.maxAlternatives)
        assertTrue(engine.streaming)
        assertEquals(EngineKind.VOSK_GRAMMAR, engine.kind)
        assertEquals(SpeechRuntime.VOSK, engine.kind.runtime)
    }

    /** Host Vosk 0.3.45 returns an empty partial text when partial words are on (t11 smoke): off unless asked. */
    @Test fun partialWordsStayOffByDefault() {
        assertEquals(false, command.partialWords ?: false)
        assertEquals(false, stop.partialWords ?: false)
        val withWords = FakeModel()
        VoskGrammarEngine("en", withWords, COMMAND, STOP, setOf("stop"), partialWords = true)
        assertEquals(true, withWords.recognizers[0].partialWords)
        assertEquals(true, withWords.recognizers[1].partialWords)
    }

    @Test fun audioReachesVoskIn100msStepsAndTheRestAtFinish() {
        engine.begin()
        engine.accept(ShortArray(1000), 0, 1000)
        assertEquals(emptyList<Int>(), command.fed)
        engine.accept(ShortArray(1000), 0, 1000)
        assertEquals(listOf(1600), command.fed)
        engine.accept(ShortArray(5000), 1000, 3000)
        assertEquals(listOf(1600, 1600, 1600), command.fed)
        engine.finish()
        assertEquals("the last 200 samples are flushed before the final result", listOf(1600, 1600, 1600, 200), command.fed)
    }

    @Test fun theFinalIsTheNBestListWithRawScores() {
        command.finalJson = nBest("takeoff three meter" to 139.503952, "take off three meter" to 138.654236,
            "takeoff to three meter" to 138.407898)
        engine.begin()
        engine.accept(step, 0, step.size)
        val result = engine.finish()
        assertEquals(listOf(
            SpeechAlternative("takeoff three meter", 139.503952),
            SpeechAlternative("take off three meter", 138.654236),
            SpeechAlternative("takeoff to three meter", 138.407898)), result.alternatives)
        assertEquals("takeoff three meter", result.text)
        assertEquals("en", result.language)
        assertEquals(1600L, result.audioSamples)
        assertNull(result.failure)
    }

    @Test fun segmentsSplitByTheEndpointerAreJoinedWithSummedScores() {
        command.endpoint(nBest("take off" to 90.0, "take of" to 89.0))
        command.finalJson = nBest("three meter" to 50.0, "tree meter" to 49.5, "three meters" to 47.0)
        engine.begin()
        engine.accept(step, 0, step.size)
        val result = engine.finish()
        assertEquals(listOf(
            SpeechAlternative("take off three meter", 140.0),
            SpeechAlternative("take off tree meter", 139.5),
            SpeechAlternative("take of three meter", 139.0)), result.alternatives)
    }

    @Test fun emptySegmentsAddNothingAndSilenceGivesNoAlternative() {
        command.endpoint("""{"alternatives" : [{"confidence" : 12.0, "text" : ""}]}""")
        command.finalJson = nBest("land" to 60.0)
        engine.begin()
        engine.accept(step, 0, step.size)
        assertEquals(listOf(SpeechAlternative("land", 60.0)), engine.finish().alternatives)
        engine.begin()
        command.finalJson = """{"alternatives" : [{"confidence" : 48.4, "text" : ""}]}"""
        assertEquals(emptyList<SpeechAlternative>(), engine.finish().alternatives)
    }

    /** An empty best alternative stays in the list when a later one has words (te "ఆపు", host smoke). */
    @Test fun anEmptyBestAlternativeIsKeptWhenOthersHaveWords() {
        command.finalJson = """{"alternatives" : [{"confidence" : 48.355553, "text" : ""}, """ +
            """{"confidence" : 47.162437, "text" : "ఆపు"}]}"""
        engine.begin()
        assertEquals(listOf(SpeechAlternative("", 48.355553), SpeechAlternative("ఆపు", 47.162437)),
            engine.finish().alternatives)
    }

    @Test fun aStopTokenInPartialsOnTwoConsecutiveStepsFiresOnce() {
        command.partial("move")
        command.partial("stop")
        command.partial("stop")
        command.partial("stop")
        engine.begin()
        val fired = (0 until 4).map { engine.accept(step, 0, step.size) }
        assertEquals(listOf(false, false, true, false), fired)
    }

    @Test fun holdOrAStopOnOneStepOnlyNeverFires() {
        listOf("stop", "go", "hold", "hold", "hover", "hover").forEach(command::partial)
        engine.begin()
        assertTrue((0 until 6).none { engine.accept(step, 0, step.size) })
    }

    @Test fun partialWordsResultsAreReadWhenAVoskBuildFillsThem() {
        command.steps.add(FakeRecognizer.Partial(
            """{"partial" : "", "partial_result" : [{"conf" : 1.0, "end" : 0.9, "start" : 0.5, "word" : "stop"}]}"""))
        command.steps.add(FakeRecognizer.Partial(
            """{"partial" : "", "partial_result" : [{"conf" : 1.0, "end" : 1.0, "start" : 0.5, "word" : "stop"}]}"""))
        engine.begin()
        assertFalse(engine.accept(step, 0, step.size))
        assertTrue(engine.accept(step, 0, step.size))
    }

    @Test fun beginAndCancelStartAFreshUtterance() {
        command.endpoint(nBest("take off" to 90.0))
        engine.begin()
        engine.accept(step, 0, step.size)
        engine.accept(step, 0, 100)
        engine.cancel()
        assertTrue(command.resets >= 1)
        command.finalJson = nBest("land" to 70.0)
        engine.begin()
        val result = engine.finish()
        assertEquals("the cancelled segment is gone", listOf(SpeechAlternative("land", 70.0)), result.alternatives)
        assertEquals(listOf(1600), command.fed)
    }

    @Test fun theStopSpotterUsesTheStopGrammarAndResetsAfterADetection() {
        val spotter = engine.stopSpotter
        stop.partial("stop")
        stop.partial("stop")
        stop.partial("stop")
        assertFalse(spotter.accept(step, 0, 1000))
        assertFalse(spotter.accept(step, 0, 1000))
        val resets = stop.resets
        assertTrue(spotter.accept(step, 0, 1600))
        assertEquals("the recognizer restarts after a detection", resets + 1, stop.resets)
        assertFalse("a lone partial after the restart", spotter.accept(step, 0, 1600))
        assertEquals(emptyList<Int>(), command.fed)
    }

    @Test fun theStopSpotterFinalResultIsTheBackstop() {
        stop.endpoint("""{"text" : "stop"}""")
        assertTrue(engine.stopSpotter.accept(step, 0, 1600))
        stop.endpoint("""{"text" : "hold position"}""")
        assertFalse(engine.stopSpotter.accept(step, 0, 1600))
    }

    /** T14 emulator bench: the stop partial came about 0.5 s after the word, so the second step fell after the hangover. */
    @Test fun theStopSpotterDecidesOnItsFinalResultWhenTheSegmentEnds() {
        val spotter = engine.stopSpotter
        stop.partial("stop")
        assertFalse(spotter.accept(step, 0, 1600))
        assertFalse(spotter.accept(step, 0, 800))
        stop.finalJson = """{"text" : "stop"}"""
        val resets = stop.resets
        assertTrue(spotter.endSegment())
        assertEquals("the half step is decoded before the final result", listOf(1600, 800), stop.fed)
        assertTrue("the recognizer restarts for the next segment", stop.resets > resets)
        assertFalse("a lone partial after the restart", spotter.accept(step, 0, 1600))
    }

    @Test fun aSegmentEndWithoutAStopTokenIsNoDetection() {
        stop.finalJson = """{"text" : "hold position"}"""
        assertFalse(engine.stopSpotter.endSegment())
        stop.finalJson = """{"text" : ""}"""
        assertFalse(engine.stopSpotter.endSegment())
    }

    @Test fun theStopSpotterForgetsEverythingBetweenVadSegments() {
        val spotter = engine.stopSpotter
        stop.partial("stop")
        stop.partial("stop")
        assertFalse(spotter.accept(step, 0, 1600))
        spotter.accept(step, 0, 800)
        spotter.reset()
        assertFalse("the run and the half step were dropped", spotter.accept(step, 0, 1600))
        assertEquals(listOf(1600, 1600), stop.fed)
    }

    @Test fun closeReleasesRecognizersBeforeTheModelEvenWhenOneFails() {
        command.failOnClose = true
        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertTrue(command.closed)
        assertTrue(stop.closed)
        assertTrue(model.closed)
    }

    @Test fun aFailedGrammarClosesWhatWasOpened() {
        val failing = FakeModel().apply { failOn = 1 }
        assertThrows(java.io.IOException::class.java) {
            VoskGrammarEngine("en", failing, COMMAND, STOP, setOf("stop"))
        }
        assertTrue(failing.recognizers.single().closed)
        assertTrue(failing.closed)
    }

    private companion object {
        const val COMMAND = """["[unk]", "take off", "stop"]"""
        const val STOP = """["[unk]", "stop", "hold"]"""
    }
}
