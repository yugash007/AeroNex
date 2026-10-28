package com.aeronex.app.bench

import com.aeronex.app.session.ScriptedEngine
import com.aeronex.app.session.ScriptedSpotter
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.SpeechAlternative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedEngineTest {
    /** Every reading advances 5 ms, so each timed call measures exactly 5 ms. */
    private class StepClock {
        var now = 0L
        fun read(): Long = now.also { now += 5_000_000 }
    }

    @Test fun timesAcceptAndFinishPerUtterance() {
        val inner = ScriptedEngine("ta", EngineKind.SHERPA_CTC).apply { result = listOf(SpeechAlternative("x", null)) }
        val clock = StepClock()
        val timed = TimedEngine(inner, clock::read)
        timed.begin()
        assertFalse(timed.accept(ShortArray(320), 0, 320))
        timed.accept(ShortArray(320), 0, 160)
        val result = timed.finish()
        assertEquals("x", result.text)
        assertEquals(10_000_000L, timed.acceptNanos)
        assertEquals(5_000_000L, timed.finishNanos)
        assertEquals(480L, timed.acceptedSamples)

        timed.begin()
        assertEquals(0L, timed.acceptNanos)
        assertEquals(0L, timed.finishNanos)
        assertEquals(0L, timed.acceptedSamples)
        assertEquals(2, inner.begins.get())
    }

    @Test fun passesStopTokensAndEverythingElseThrough() {
        val inner = ScriptedEngine("en", EngineKind.VOSK_GRAMMAR).apply { stopTokenAfter = 320 }
        val timed = TimedEngine(inner)
        assertEquals(EngineKind.VOSK_GRAMMAR, timed.kind)
        assertEquals("en", timed.language)
        assertTrue(timed.streaming)
        timed.begin()
        assertTrue(timed.accept(ShortArray(320), 0, 320))
        timed.cancel()
        assertEquals(1, inner.cancels.get())
        timed.close()
        assertEquals(Thread.currentThread().name, inner.closedOn)
    }

    @Test fun theSpotterTimerCountsFedAudioUntilReset() {
        val inner = ScriptedSpotter(fireAfter = 640)
        val clock = StepClock()
        val timed = TimedSpotter(inner, clock::read)
        assertFalse(timed.accept(ShortArray(320), 0, 320))
        assertTrue(timed.accept(ShortArray(320), 0, 320))
        assertEquals(640L, timed.fedSamples)
        assertEquals(10_000_000L, timed.acceptNanos)
        timed.reset()
        assertEquals("reset() is the gate's, the timers are the bench's", 640L, timed.fedSamples)
        timed.resetTimers()
        assertEquals(0L, timed.fedSamples)
        assertEquals(0L, timed.acceptNanos)
        timed.close()
        assertTrue(inner.closed)
    }
}
