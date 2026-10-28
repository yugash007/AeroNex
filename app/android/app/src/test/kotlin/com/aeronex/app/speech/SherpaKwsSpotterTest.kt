package com.aeronex.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaKwsSpotterTest {
    private class FakeKws : KwsDecoder {
        val chunks = mutableListOf<FloatArray>()
        val answers = ArrayDeque<String?>()
        var resets = 0
        var closed = false
        override fun accept(samples: FloatArray): String? {
            chunks += samples.copyOf()
            return answers.removeFirstOrNull()
        }
        override fun reset() {
            resets++
        }
        override fun close() {
            closed = true
        }
    }

    private val kws = FakeKws()
    private val spotter = SherpaKwsSpotter(kws, setOf("stop"))
    private val pcm = ShortArray(1600) { 16384 }

    @Test fun keywordLinesComeFromTheGrammarManifestFilteredByTheLanguagesKeywords() {
        val lines = listOf("▁ST O P @stop", "▁HO L D @hold")
        assertEquals(listOf("▁ST O P @stop"), SherpaKwsSpotter.keywordLines(lines, listOf("stop")))
        assertThrows(IllegalArgumentException::class.java) { SherpaKwsSpotter.keywordLines(lines, listOf("halt")) }
    }

    @Test fun theSpecMatchesTheW1BenchSettings() {
        val dir = File("/packs/kws")
        val spec = KwsModelSpec(File(dir, "e.onnx"), File(dir, "d.onnx"), File(dir, "j.onnx"), File(dir, "tokens.txt"),
            File(dir, "keywords.txt"), listOf("▁ST O P @stop"))
        assertEquals(1, spec.numThreads)
        assertEquals(0.25f, spec.keywordsThreshold)
        assertEquals(1.0f, spec.keywordsScore)
        assertEquals(1, spec.numTrailingBlanks)
        assertEquals(4, spec.maxActivePaths)
        assertEquals(16_000, spec.sampleRate)
        assertEquals(80, spec.featureDim)
        assertEquals("▁ST O P @stop\n", spec.keywordsFileText)
    }

    @Test fun audioGoesToTheSpotterAsFloat100msChunks() {
        assertFalse(spotter.accept(pcm, 0, 1000))
        assertTrue(kws.chunks.isEmpty())
        assertFalse(spotter.accept(pcm, 0, 1000))
        assertEquals(1, kws.chunks.size)
        assertEquals(1600, kws.chunks[0].size)
        assertEquals(0.5f, kws.chunks[0][0])
        assertFalse(spotter.accept(pcm, 0, 1600))
        assertEquals(2, kws.chunks.size)
    }

    @Test fun onlyTheLanguagesKeywordsFire() {
        kws.answers.addAll(listOf(null, "hold", "stop"))
        assertFalse(spotter.accept(pcm, 0, 1600))
        assertFalse("hold is not a STOP", spotter.accept(pcm, 0, 1600))
        assertTrue(spotter.accept(pcm, 0, 1600))
    }

    @Test fun aDetectionDropsTheRestOfTheCallAndResetBetweenSegmentsClearsTheBuffer() {
        kws.answers.add("stop")
        assertTrue(spotter.accept(ShortArray(4000), 0, 4000))
        assertEquals("decoding stops at the detection", 1, kws.chunks.size)
        spotter.accept(pcm, 0, 1000)
        spotter.reset()
        assertEquals(1, kws.resets)
        spotter.accept(pcm, 0, 1000)
        assertEquals("the half chunk before reset was dropped", 1, kws.chunks.size)
        spotter.close()
        assertTrue(kws.closed)
    }
}
