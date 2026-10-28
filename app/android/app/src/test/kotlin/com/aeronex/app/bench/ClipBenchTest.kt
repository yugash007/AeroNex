package com.aeronex.app.bench

import com.aeronex.app.audio.AudioCapture
import com.aeronex.app.audio.AudioRing
import com.aeronex.app.audio.AudioSignals
import com.aeronex.app.command.ParseResult
import com.aeronex.app.session.ScriptedEngine
import com.aeronex.app.session.ScriptedSpotter
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.SpeechAlternative
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-time runs through the service's capture path (AudioCapture → AudioRing → PttUtterance / SpotterGate). */
class ClipBenchTest {
    private val captures = mutableListOf<AudioCapture>()

    @After fun stopCapture() = captures.forEach { it.stop() }

    private fun bench(engine: TimedEngine?, spotter: TimedSpotter?): ClipBench {
        val player = ClipPlayer()
        val capture = AudioCapture(player).also { captures += it }
        capture.start()
        return ClipBench(capture.ring, player, engine, spotter, settleMs = 300)
    }

    /** 100 ms of digital silence, [toneMs] of a -20 dBFS tone (speech for the VAD), 100 ms of silence. */
    private fun clip(id: String, toneMs: Int = 400): BenchClip {
        val silence = ShortArray(AudioRing.samplesForMs(100))
        val tone = (0 until toneMs / AudioRing.FRAME_MS).flatMap { AudioSignals.tone(-20.0, it.toLong()).toList() }
        val samples = silence + tone.toShortArray() + silence
        return BenchClip(ClipInfo("en/$id.wav", "en", id, id, id, samples.size / 16_000.0), samples)
    }

    @Test fun pttUpToFinalRunsAtRealTimeThroughTheTailAndTheWholeUtteranceReachesTheEngine() {
        val inner = ScriptedEngine("en", EngineKind.SHERPA_CTC).apply { result = listOf(SpeechAlternative("take off to 3 m", null)) }
        val bench = bench(TimedEngine(inner), null)
        val started = System.nanoTime()
        val outcome = bench.run(clip("takeoff_3"))
        val wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("a 600 ms clip took $wallMs ms", wallMs >= 600)

        val ptt = outcome.ptt!!
        assertEquals(PttStatus.ENDED, ptt.status)
        assertEquals("500 ms pre-roll + 600 ms clip + 60 ms tail", AudioRing.samplesForMs(500 + 600 + 60).toLong(),
            ptt.result!!.audioSamples)
        assertEquals(1160.0, ptt.audioMs, 0.01)
        // ptt_up is when the last clip frame was actually delivered, while the 60 ms tail stays on the clip's fixed
        // schedule: a late wake-up of that frame (one Windows timer tick is 15.6 ms) shortens the span, so allow a frame.
        assertTrue("the tail's 3 non-speech frames arrive at real time: ${ptt.pttUpToDrainedMs}",
            ptt.pttUpToDrainedMs!! in 40.0..300.0)
        assertTrue(ptt.pttUpToFinalMs!! >= ptt.pttUpToDrainedMs!!)
        assertTrue(ptt.pttUpToParsedMs!! >= ptt.pttUpToFinalMs!!)
        assertNotNull(ptt.rtf)
        assertTrue(outcome.parsed is ParseResult.Accepted)
        assertEquals(Verdict.EXACT, outcome.verdict)
        assertNull(outcome.spotter)
        assertEquals(1, inner.finishes.get())
    }

    @Test fun aStopTokenEndsTheUtteranceAsTheServiceDoes() {
        val inner = ScriptedEngine("en").apply { stopTokenAfter = AudioRing.samplesForMs(500 + 300).toLong() }
        val outcome = bench(TimedEngine(inner), null).run(clip("stop"))
        val ptt = outcome.ptt!!
        assertEquals(PttStatus.STOP_TOKEN, ptt.status)
        assertEquals(1, inner.cancels.get())
        assertEquals(0, inner.finishes.get())
        assertNull(ptt.result)
        assertTrue("fired before the release: ${ptt.stopTokenAfterReleaseMs}", ptt.stopTokenAfterReleaseMs!! < 0)
        assertEquals(Verdict.EXACT, outcome.verdict)
    }

    @Test fun theSpotterHearsTheSpeechSegmentAndItsDetectionIsTimed() {
        val inner = ScriptedSpotter(fireAfter = AudioRing.samplesForMs(200).toLong())
        val spotter = TimedSpotter(inner)
        val outcome = bench(null, spotter).run(clip("stop"))
        assertNull(outcome.ptt)
        val heard = outcome.spotter!!
        assertEquals(1, heard.detections)
        assertTrue("first detection ${heard.firstAfterClipStartMs} ms after the clip start",
            heard.firstAfterClipStartMs!! in 150.0..700.0)
        assertTrue(heard.firstAfterReleaseMs!! < 0)
        assertTrue("the gate feeds speech, not the whole clip", heard.fedMs in 100.0..600.0)
        assertEquals(SpotterVerdict.HIT, outcome.spotterVerdict)
        assertNull(outcome.verdict)
    }

    @Test fun clipsRunBackToBackWithSilenceBetween() {
        val inner = ScriptedEngine("en", EngineKind.SHERPA_CTC).apply { result = listOf(SpeechAlternative("land", null)) }
        val bench = bench(TimedEngine(inner), TimedSpotter(ScriptedSpotter().apply { enabled = false }))
        val first = bench.run(clip("land"))
        val second = bench.run(clip("land", toneMs = 200))
        assertEquals(PttStatus.ENDED, first.ptt!!.status)
        assertEquals(PttStatus.ENDED, second.ptt!!.status)
        assertTrue("the next clip starts after the settle time",
            second.startSample >= first.endSample + AudioRing.samplesForMs(300))
        assertEquals(AudioRing.samplesForMs(500 + 400 + 60).toLong(), second.ptt!!.result!!.audioSamples)
        assertEquals(SpotterVerdict.QUIET, second.spotterVerdict)
        assertEquals(2, inner.finishes.get())
    }
}
