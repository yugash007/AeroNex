package com.aeronex.app.speech

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaCtcEngineTest {
    private val log = mutableListOf<String>()

    private inner class FakeDecoder(val spec: CtcModelSpec) : CtcDecoder {
        val decoded = mutableListOf<FloatArray>()
        var text = "మూడు మీటర్లు టేకాఫ్"
        var fail = false
        override fun decode(samples: FloatArray): String {
            decoded += samples
            if (fail) throw RuntimeException("ORT error")
            return text
        }
        override fun close() {
            log += "close ${spec.language}"
        }
    }

    private val decoders = mutableListOf<FakeDecoder>()
    private val engine = SherpaCtcEngine(maxUtteranceMs = 1000) { spec ->
        log += "load ${spec.language}"
        FakeDecoder(spec).also { decoders += it }
    }

    private fun spec(language: String, threads: Int = 2) =
        CtcModelSpec(language, File("/packs/$language/model.int8.onnx"), File("/packs/$language/tokens.txt"), threads)

    @Test fun theSpecIsTheNemoCtcConfigOfTheW1Bench() {
        val spec = spec("ta")
        assertEquals(16_000, spec.sampleRate)
        assertEquals(80, spec.featureDim)
        assertEquals(0f, spec.dither)
        assertEquals("greedy_search", spec.decodingMethod)
        assertEquals("cpu", spec.provider)
        assertThrows(IllegalArgumentException::class.java) { spec("ta", threads = 0) }
    }

    @Test fun itDecodesOnceAfterReleaseWithTheWholeUtterance() {
        engine.load(spec("te"))
        assertEquals(EngineKind.SHERPA_CTC, engine.kind)
        assertFalse(engine.streaming)
        engine.begin()
        val pcm = ShortArray(4000) { (it - 2000).toShort() }
        assertFalse(engine.accept(pcm, 0, 2500))
        assertFalse(engine.accept(pcm, 2500, 1500))
        assertTrue("nothing decodes before release", decoders.single().decoded.isEmpty())
        val result = engine.finish()
        val heard = decoders.single().decoded.single()
        assertEquals(4000, heard.size)
        assertArrayEquals(FloatArray(4000) { (it - 2000) / 32768f }, heard, 0f)
        assertEquals(listOf(SpeechAlternative("మూడు మీటర్లు టేకాఫ్", null)), result.alternatives)
        assertEquals("te", result.language)
        assertEquals(4000L, result.audioSamples)
        assertNull(result.failure)
    }

    @Test fun aBlankTranscriptIsNoAlternative() {
        engine.load(spec("ta"))
        decoders.single().text = "   "
        engine.begin()
        engine.accept(ShortArray(100), 0, 100)
        assertEquals(emptyList<SpeechAlternative>(), engine.finish().alternatives)
    }

    @Test fun oneModelIsResidentAtATime() {
        engine.load(spec("te"))
        engine.load(spec("te"))
        engine.load(spec("ta"))
        assertEquals("the old model is released before the next one loads",
            listOf("load te", "close te", "load ta"), log)
        assertEquals("ta", engine.language)
        engine.close()
        assertEquals("close ta", log.last())
    }

    @Test fun aFailedLoadLeavesNoModelAndFinishSaysSo() {
        engine.load(spec("te"))
        val failing = SherpaCtcEngine { throw IllegalStateException("bad model") }
        assertThrows(IllegalStateException::class.java) { failing.load(spec("kn")) }
        failing.begin()
        assertEquals(SpeechResult.Failure.NO_MODEL, failing.finish().failure)
        val replaced = SherpaCtcEngine { if (it.language == "ml") throw IllegalStateException("bad") else FakeDecoder(it) }
        replaced.load(spec("kn"))
        assertThrows(IllegalStateException::class.java) { replaced.load(spec("ml")) }
        replaced.begin()
        assertEquals(SpeechResult.Failure.NO_MODEL, replaced.finish().failure)
    }

    @Test fun anUtteranceLongerThanTheBufferIsRefusedNotTruncated() {
        engine.load(spec("ml"))
        engine.begin()
        engine.accept(ShortArray(16_000), 0, 16_000)
        engine.accept(ShortArray(10), 0, 10)
        val result = engine.finish()
        assertEquals(SpeechResult.Failure.TOO_LONG, result.failure)
        assertEquals(emptyList<SpeechAlternative>(), result.alternatives)
        assertTrue(decoders.single().decoded.isEmpty())
    }

    @Test fun aDecoderErrorIsAFailedResultNotACrash() {
        engine.load(spec("kn"))
        decoders.single().fail = true
        engine.begin()
        engine.accept(ShortArray(10), 0, 10)
        assertEquals(SpeechResult.Failure.ENGINE_ERROR, engine.finish().failure)
    }

    @Test fun cancelDiscardsTheUtterance() {
        engine.load(spec("te"))
        engine.begin()
        engine.accept(ShortArray(3000), 0, 3000)
        engine.cancel()
        engine.begin()
        engine.accept(ShortArray(500), 0, 500)
        engine.finish()
        assertEquals(500, decoders.single().decoded.single().size)
    }
}
