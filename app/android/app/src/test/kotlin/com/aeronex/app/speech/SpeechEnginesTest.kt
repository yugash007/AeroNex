package com.aeronex.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The engine factories over the real shipped assets, with the native loaders replaced by fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SpeechEnginesTest {
    private val grammar = GrammarAssets.load { File("src/main/assets/$it").inputStream() }
    private val packs = SpeechPackManifest.parse(File("src/main/assets/${SpeechPackManifest.ASSET}").readText())

    @Test fun aVoskEngineGetsItsLanguagesGrammarsAndStopTokens() {
        val model = VoskGrammarEngineTest.FakeModel()
        var loaded: File? = null
        val engine = SpeechEngines.vosk("hi", File("/data/models/vosk-model-small-hi-0.22"), grammar) {
            loaded = it
            model
        }
        assertEquals(File("/data/models/vosk-model-small-hi-0.22"), loaded)
        assertEquals("hi", engine.language)
        assertEquals(grammar.voskGrammar("hi"), model.recognizers[0].grammar)
        assertEquals(grammar.voskStopGrammar("hi"), model.recognizers[1].grammar)
        model.recognizers[1].partial("रुक")
        model.recognizers[1].partial("रुक जाओ")
        val step = ShortArray(1600)
        assertFalse(engine.stopSpotter.accept(step, 0, step.size))
        assertTrue("रुक जाओ stops on its first word", engine.stopSpotter.accept(step, 0, step.size))
    }

    @Test fun aGrammarIsNeverPairedWithAnotherModel() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechEngines.vosk("hi", File("/data/models/vosk-model-small-te-0.42"), grammar) { error("not loaded") }
        }
        assertThrows(IllegalStateException::class.java) {
            SpeechEngines.vosk("ta", File("/data/models/vosk-model-small-te-0.42"), grammar) { error("not loaded") }
        }
    }

    @Test fun theKwsSpotterListensForTheLanguagesKeywordsOnly() {
        val pack = packs.single { it.id == "kws-gigaspeech-3.3M-chunk-16" }
        val files = pack.files.associate { it.role to File("/packs/${pack.id}/${it.name}") }
        var spec: KwsModelSpec? = null
        val spotter = SpeechEngines.kws(pack, files, grammar, "te", File("/cache/kws.txt")) {
            spec = it
            object : KwsDecoder {
                override fun accept(samples: FloatArray) = "stop"
                override fun reset() {}
                override fun close() {}
            }
        }
        assertEquals("▁ST O P @stop\n", spec!!.keywordsFileText)
        assertEquals(File("/packs/${pack.id}/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"), spec.encoder)
        assertEquals(File("/cache/kws.txt"), spec.keywordsFile)
        assertEquals(1, spec.numThreads)
        assertTrue(spotter.accept(ShortArray(1600), 0, 1600))
    }

    @Test fun theCtcEngineLoadsAVerifiedPackWithThePolicyThreads() {
        val pack = packs.single { it.id == "indicconformer-kn-ctc-int8" }
        val files = pack.files.associate { it.role to File("/packs/${pack.id}/${it.name}") }
        val specs = mutableListOf<CtcModelSpec>()
        val engine = SpeechEngines.ctc {
            specs += it
            object : CtcDecoder {
                override fun decode(samples: FloatArray) = ""
                override fun close() {}
            }
        }
        engine.load(CtcModelSpec.of(pack, files, numThreads = 4))
        assertEquals("kn", specs.single().language)
        assertEquals(File("/packs/${pack.id}/model.int8.onnx"), specs.single().model)
        assertEquals(File("/packs/${pack.id}/tokens.txt"), specs.single().tokens)
        assertEquals(4, specs.single().numThreads)
    }
}
