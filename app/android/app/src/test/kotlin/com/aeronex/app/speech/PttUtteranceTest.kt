package com.aeronex.app.speech

import com.aeronex.app.audio.AudioRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PttUtteranceTest {
    private val frame = AudioRing.FRAME_SAMPLES

    /** Records every sample position it hears (each sample of frame f holds f % 30000, offsets tracked). */
    private class RecordingEngine : SpeechEngine {
        override val kind = EngineKind.VOSK_GRAMMAR
        override val language = "en"
        var heard = 0L
        val frames = mutableListOf<Short>()
        var stopAfterSamples = Long.MAX_VALUE
        var begins = 0
        var cancels = 0
        override fun begin() {
            begins++
            heard = 0
            frames.clear()
        }
        override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
            for (i in offset until offset + count) if (frames.lastOrNull() != samples[i]) frames += samples[i]
            heard += count
            return heard >= stopAfterSamples
        }
        override fun finish() = SpeechResult(kind, language, emptyList(), heard, 0)
        override fun cancel() {
            cancels++
        }
        override fun close() {}
    }

    private val ring = AudioRing()
    private val engine = RecordingEngine()
    private val utterance = PttUtterance(ring, engine)

    private fun write(n: Int, speech: Boolean) = repeat(n) {
        val index = ring.framesWritten
        ring.write(ShortArray(frame) { index.toShort() }, speech)
    }

    @Test fun thePressIsBackdated500msAndTheTailEndsAfter60msOfSilence() {
        write(100, false) // 2 s before the press
        utterance.press()
        assertEquals(1, engine.begins)
        write(50, true) // 1 s of speech
        assertEquals(PttUtterance.Status.LISTENING, utterance.poll())
        utterance.release()
        write(2, false)
        assertEquals(PttUtterance.Status.LISTENING, utterance.poll())
        write(1, false)
        assertEquals(PttUtterance.Status.ENDED, utterance.poll())
        assertEquals("500 ms + 1 s + 60 ms", (8000L + 16000 + 960), engine.heard)
        assertEquals((75..152).map { it.toShort() }, engine.frames)
        assertEquals(engine.heard, utterance.finish().audioSamples)
        write(10, true)
        assertEquals("nothing after the end is fed", PttUtterance.Status.ENDED, utterance.poll())
        assertEquals(8000L + 16000 + 960, engine.heard)
    }

    @Test fun releasingMidWordCapturesExactly250msMore() {
        utterance.press()
        write(25, true)
        utterance.release()
        write(12, true)
        assertEquals(PttUtterance.Status.LISTENING, utterance.poll())
        write(1, true) // the frame holding the 250 ms mark
        assertEquals(PttUtterance.Status.ENDED, utterance.poll())
        assertEquals("500 ms held (no audio before the press) + 250 ms", 8000L + 4000, engine.heard)
    }

    @Test fun aStopTokenEndsTheUtteranceAtOnce() {
        engine.stopAfterSamples = 3200
        utterance.press()
        write(5, true)
        assertEquals(PttUtterance.Status.LISTENING, utterance.poll())
        write(10, true)
        assertEquals(PttUtterance.Status.STOP_TOKEN, utterance.poll())
        utterance.cancel()
        assertEquals(1, engine.cancels)
    }

    @Test fun aStalledReaderReportsAnOverrunInsteadOfAGap() {
        utterance.press()
        write(ring.capacityFrames * 2, true)
        assertEquals(PttUtterance.Status.OVERRUN, utterance.poll())
    }

    @Test fun releaseNeedsAPressAndReleaseIsAtTheCapturePosition() {
        assertThrows(IllegalStateException::class.java) { PttUtterance(ring, engine).release() }
        write(10, false)
        utterance.press()
        utterance.release()
        write(3, false)
        assertEquals(PttUtterance.Status.ENDED, utterance.poll())
        assertEquals("200 ms of pre-roll (all there was) + 60 ms", 3200L + 960, engine.heard)
    }

    /** Audit S5: a press handled after its own moment left the ring is an overrun, never a decode of the remainder. */
    @Test fun aPressWhoseMomentLeftTheRingIsAnOverrun() {
        write(10, false)
        val pressedAt = ring.writePosition
        write(AudioRing().readableFrames + 20, true) // the speech thread was busy (a slow model load) meanwhile
        utterance.press(pressedAt)
        assertEquals(PttUtterance.Status.OVERRUN, utterance.poll())
        assertEquals("nothing decoded", 0L, engine.heard)
    }

    @Test fun aPressStillInTheRingLosesAtMostItsPreRoll() {
        write(10, false)
        val pressedAt = ring.writePosition
        utterance.press(pressedAt)
        write(5, true)
        assertEquals(PttUtterance.Status.LISTENING, utterance.poll())
    }

}
