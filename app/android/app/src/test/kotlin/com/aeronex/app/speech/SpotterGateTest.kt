package com.aeronex.app.speech

import com.aeronex.app.audio.AudioRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotterGateTest {
    private val frame = AudioRing.FRAME_SAMPLES

    /** Records which frames it was fed (every sample of frame f holds f) and when it was reset. */
    private class RecordingSpotter : StopSpotter {
        val events = mutableListOf<String>()
        var fireOnFrame = -1L
        var fireAtEnd = false
        override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
            val first = samples[offset].toLong()
            val last = samples[offset + count - 1].toLong()
            events += "feed $first..$last"
            return fireOnFrame in first..last
        }
        override fun endSegment(): Boolean {
            events += "end"
            return fireAtEnd
        }
        override fun reset() {
            events += "reset"
        }
        override fun close() {}
        val fedFrames get() = events.filter { it.startsWith("feed") }.flatMap {
            val (a, b) = it.removePrefix("feed ").split("..").map(String::toLong); (a..b).toList()
        }.distinct()
    }

    private val ring = AudioRing()
    private val spotter = RecordingSpotter()
    private val gate = SpotterGate(ring, spotter)

    private fun write(vararg speech: Boolean) = speech.forEach {
        val index = ring.framesWritten
        ring.write(ShortArray(frame) { index.toShort() }, it)
    }

    private fun quiet(n: Int) = write(*BooleanArray(n))
    private fun voice(n: Int) = write(*BooleanArray(n) { true })

    @Test fun silenceIsNeverFed() {
        gate.start()
        quiet(100)
        assertFalse(gate.poll())
        assertTrue(spotter.events.isEmpty())
    }

    @Test fun aSegmentStartsWithAResetAndA300msReplay() {
        quiet(50)
        gate.start()
        quiet(30)
        assertFalse(gate.poll())
        voice(5)
        assertFalse(gate.poll())
        assertEquals("reset", spotter.events.first())
        assertEquals("15 frames (300 ms) before the onset, then the onset", (65L..84L).toList(), spotter.fedFrames)
    }

    @Test fun theReplayNeverReachesBeforeTheArmingPoint() {
        quiet(10)
        gate.start()
        quiet(5)
        voice(2)
        gate.poll()
        assertEquals((10L..16L).toList(), spotter.fedFrames)
    }

    @Test fun feedingContinuesThroughAHangoverThenStopsAndTheNextSegmentResetsAgain() {
        gate.start()
        voice(10)
        quiet(40) // hangover is 600 ms = 30 frames
        gate.poll()
        assertEquals("speech plus 30 frames of hangover", (0L..39L).toList(), spotter.fedFrames)
        quiet(20)
        voice(2)
        gate.poll()
        assertEquals(2, spotter.events.count { it == "reset" })
        assertTrue("the replay starts after what was fed already", spotter.fedFrames.containsAll((55L..71L).toList()))
        assertFalse(spotter.fedFrames.any { it in 40L..54L })
    }

    @Test fun aDetectionIsReportedAndTheSegmentEnds() {
        gate.start()
        spotter.fireOnFrame = 3
        voice(10)
        assertTrue(gate.poll())
        assertEquals((0L..3L).toList(), spotter.fedFrames)
        quiet(5)
        assertFalse(gate.poll())
        voice(1)
        assertFalse(gate.poll())
        assertEquals("a new segment after a detection starts with a reset", 2, spotter.events.count { it == "reset" })
    }

    @Test fun theSpotterDecidesOnceWhenTheHangoverRunsOut() {
        gate.start()
        voice(10)
        quiet(40)
        assertFalse(gate.poll())
        assertEquals("after the last hangover frame", listOf("feed 39..39", "end"), spotter.events.takeLast(2))
        quiet(20)
        assertFalse(gate.poll())
        assertEquals(1, spotter.events.count { it == "end" })
    }

    /** T14 emulator bench: Vosk's stop partial came 420-520 ms after the word, too late for two steps in the hangover. */
    @Test fun aDecisionAtTheSegmentEndIsADetection() {
        gate.start()
        spotter.fireAtEnd = true
        voice(10)
        quiet(40)
        assertTrue(gate.poll())
        assertFalse("one segment fires once", gate.poll())
        quiet(5)
        voice(1)
        assertFalse(gate.poll())
        assertEquals("the next segment starts afresh", 2, spotter.events.count { it == "reset" })
    }

    @Test fun aSegmentThatAlreadyFiredIsNotDecidedAgain() {
        gate.start()
        spotter.fireOnFrame = 3
        spotter.fireAtEnd = true
        voice(10)
        quiet(40)
        assertTrue(gate.poll())
        assertFalse(gate.poll())
        assertFalse(spotter.events.contains("end"))
    }

    @Test fun aGateThatFellBehindTheRingResynchronises() {
        gate.start()
        voice(ring.capacityFrames * 2)
        assertFalse(gate.poll())
        assertTrue(spotter.fedFrames.first() >= ring.framesWritten - ring.readableFrames)
        assertEquals(ring.framesWritten - 1, spotter.fedFrames.last())
    }
}
