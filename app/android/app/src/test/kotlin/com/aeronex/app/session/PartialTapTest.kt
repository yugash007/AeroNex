package com.aeronex.app.session

import com.aeronex.app.speech.VoskGrammarEngine
import com.aeronex.app.speech.VoskGrammarEngineTest.FakeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The live partial transcript for the PTT bar, read off the command recognizer only (org.json: Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PartialTapTest {
    private val model = FakeModel()
    private val tap = PartialTap(model, COMMAND)
    private val engine = VoskGrammarEngine("en", tap, COMMAND, STOP, setOf("stop"))
    private val command get() = model.recognizers[0]
    private val stop get() = model.recognizers[1]
    private val step = ShortArray(VoskGrammarEngine.STEP_SAMPLES)

    @Test fun showsTheCommandPartialAndKeepsEndpointSegmentsUntilTheNextUtterance() {
        engine.begin()
        command.partial("take")
        command.partial("take off")
        engine.accept(step, 0, step.size)
        assertEquals("take", tap.text)
        engine.accept(step, 0, step.size)
        assertEquals("take off", tap.text)
        command.endpoint("""{"alternatives" : [{"confidence" : 120.0, "text" : "take off to three"}]}""")
        command.partial("metres")
        engine.accept(step, 0, step.size)
        engine.accept(step, 0, step.size)
        assertEquals("take off to three metres", tap.text)
        engine.begin()
        assertEquals("a new utterance starts empty", "", tap.text)
    }

    @Test fun theStopGrammarNeverReachesTheTranscript() {
        engine.begin()
        stop.partial("stop")
        engine.stopSpotter.accept(step, 0, step.size)
        assertEquals("", tap.text)
        engine.close()
        assertTrue(model.closed)
    }

    private companion object {
        const val COMMAND = """["take off", "[unk]"]"""
        const val STOP = """["stop", "[unk]"]"""
    }
}
