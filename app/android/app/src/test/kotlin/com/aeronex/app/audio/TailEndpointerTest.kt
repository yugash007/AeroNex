package com.aeronex.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TailEndpointerTest {
    private val release = 32_000L
    private val frame = AudioRing.FRAME_SAMPLES

    /** Feeds one VAD flag per frame after the release; returns the tail end in ms after release, and frames fed. */
    private fun TailEndpointer.run(vararg speech: Boolean): Pair<Long, Int> {
        start(release)
        speech.forEachIndexed { index, flag ->
            val end = accept(release + index.toLong() * frame, flag)
            if (end != TailEndpointer.NOT_YET) return (end - release) / 16 to index + 1
        }
        return TailEndpointer.NOT_YET to speech.size
    }

    private fun repeat(flag: Boolean, n: Int) = BooleanArray(n) { flag }

    @Test fun releasedAfterSpeakingEndsAfter60msOfNonSpeech() {
        assertEquals(60L to 3, TailEndpointer().run(false, false, false, false))
    }

    @Test fun releasedMidWordCapturesUntilTheWordEnds() {
        val flags = repeat(true, 5) + repeat(false, 5)
        assertEquals("100 ms of speech + 60 ms", 160L to 8, TailEndpointer().run(*flags))
    }

    @Test fun aShortPauseInsideTheWordDoesNotEndTheTail() {
        assertEquals(140L to 7, TailEndpointer().run(true, false, false, true, false, false, false))
    }

    @Test fun continuousSpeechIsCutAt250ms() {
        assertEquals("the frame holding 250 ms arrives as frame 13", 250L to 13, TailEndpointer().run(*repeat(true, 20)))
        assertEquals(150L to 8, TailEndpointer(maxTailMs = 150).run(*repeat(true, 20)))
        assertEquals(240L to 12, TailEndpointer(maxTailMs = 240).run(*repeat(true, 20)))
    }

    @Test fun theMaximumIsConfigurableOnlyWithin150To250ms() {
        assertThrows(IllegalArgumentException::class.java) { TailEndpointer(maxTailMs = 140) }
        assertThrows(IllegalArgumentException::class.java) { TailEndpointer(maxTailMs = 260) }
        assertThrows(IllegalArgumentException::class.java) { TailEndpointer(nonSpeechMs = 0) }
    }

    @Test fun theResultIsStableAndStartResets() {
        val tail = TailEndpointer()
        tail.start(release)
        tail.accept(release, false)
        tail.accept(release + frame, false)
        val end = tail.accept(release + 2L * frame, false)
        assertEquals(release + 960, end)
        assertEquals(end, tail.accept(release + 3L * frame, true))
        assertEquals(end, tail.end)
        assertEquals(60L to 3, tail.run(false, false, false))
        assertThrows(IllegalStateException::class.java) { TailEndpointer().accept(0, false) }
        assertThrows("frames must follow the release in order", IllegalArgumentException::class.java) {
            TailEndpointer().apply { start(release) }.accept(release + frame, false)
        }
    }
}
