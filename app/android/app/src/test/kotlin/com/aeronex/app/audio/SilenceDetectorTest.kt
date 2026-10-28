package com.aeronex.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    private val detector = SilenceDetector()
    private fun feed(frame: ShortArray, n: Int) = List(n) { detector.accept(frame, 0, frame.size) }

    @Test fun moreThan500msOfDigitalZerosIsSilencedCapture() {
        assertTrue("500 ms is not yet more than 500 ms", feed(AudioSignals.zeros(), 25).none { it })
        assertTrue(detector.accept(AudioSignals.zeros(), 0, AudioSignals.FRAME))
        assertEquals(SilenceDetector.Reason.DIGITAL_ZEROS, detector.reason)
        assertTrue(feed(AudioSignals.zeros(), 100).all { it })
    }

    @Test fun anyRealSampleEndsTheZeroRun() {
        feed(AudioSignals.zeros(), 30)
        assertTrue(detector.silenced)
        val oneSample = AudioSignals.zeros().also { it[319] = 1 }
        assertFalse(detector.accept(oneSample, 0, oneSample.size))
        assertEquals(SilenceDetector.Reason.NONE, detector.reason)
        assertTrue("the run starts again from zero", feed(AudioSignals.zeros(), 25).none { it })
    }

    @Test fun quietButLiveInputIsNeverSilenced() {
        val hiss = ShortArray(AudioSignals.FRAME) { if (it % 7 == 0) 1 else 0 }
        assertTrue(feed(hiss, 200).none { it })
    }

    @Test fun thePlatformSilencedFlagCountsAtOnceAndCombinesWithZeros() {
        val live = AudioSignals.noise(-50.0, 3)
        detector.setClientSilenced(true)
        assertTrue("on the next frame, whatever it holds", detector.accept(live, 0, live.size))
        assertEquals(SilenceDetector.Reason.CLIENT_SILENCED, detector.reason)
        feed(AudioSignals.zeros(), 30)
        detector.setClientSilenced(false)
        assertTrue("zeros still flowing: still silenced", detector.accept(AudioSignals.zeros(), 0, AudioSignals.FRAME))
        assertEquals(SilenceDetector.Reason.DIGITAL_ZEROS, detector.reason)
        assertFalse(detector.accept(live, 0, live.size))
    }
}
