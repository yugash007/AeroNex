package com.aeronex.app.audio

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRingTest {
    private fun AudioRing.writeNumbered(frames: Int, speech: (Long) -> Boolean = { false }) {
        repeat(frames) {
            val index = framesWritten
            write(AudioSignals.numbered(index), speech(index))
        }
    }

    @Test fun holdsAtLeastOneSecondPlusGuard() {
        val ring = AudioRing()
        assertEquals(16_000, AudioRing.SAMPLE_RATE)
        assertEquals(320, AudioRing.FRAME_SAMPLES)
        assertTrue("readable window >= 1 s", ring.readableFrames * AudioRing.FRAME_MS >= 1000)
        assertThrows(IllegalArgumentException::class.java) { AudioRing(capacityMs = 1000) }
        assertThrows(IllegalArgumentException::class.java) { ring.write(ShortArray(100), false) }
    }

    @Test fun samplesComeBackInOrderAcrossTheWrap() {
        val ring = AudioRing(capacityMs = 1400)
        ring.writeNumbered(ring.capacityFrames * 3 + 7)
        val start = ring.writePosition - ring.readableFrames.toLong() * AudioRing.FRAME_SAMPLES
        assertEquals(start, ring.oldestReadable())
        val out = ShortArray(ring.readableFrames * AudioRing.FRAME_SAMPLES)
        assertEquals(out.size, ring.read(start, out, 0, out.size))
        for (i in out.indices) {
            val frame = (start + i) / AudioRing.FRAME_SAMPLES
            assertEquals("sample $i", (frame % 30000).toInt().toShort(), out[i])
        }
    }

    @Test fun preRollReachesBack500msAndNeverBeforeTheFirstSample() {
        val ring = AudioRing()
        ring.writeNumbered(10) // 200 ms written: pre-roll is everything there is
        assertEquals(0L, ring.preRollStart())
        ring.writeNumbered(90) // 2 s written
        val now = ring.writePosition
        val start = ring.preRollStart()
        assertEquals(now - 8000, start)
        assertEquals(now - 4800, ring.preRollStart(now, 300))
        val out = ShortArray(8000)
        assertEquals(8000, ring.read(start, out, 0, 8000))
        assertEquals(75.toShort(), out[0]) // frame 75 = 1.5 s, 500 ms before the 2 s mark
        assertEquals(99.toShort(), out[7999])
    }

    @Test fun readsReturnOnlyWhatWasWrittenAndFlagOverruns() {
        val ring = AudioRing(capacityMs = 1400)
        val out = ShortArray(1000)
        assertEquals(0, ring.read(0, out, 0, 1000))
        ring.writeNumbered(2)
        assertEquals(640, ring.read(0, out, 0, 1000))
        assertEquals(140, ring.read(500, out, 0, 1000))
        assertEquals(0, ring.read(640, out, 0, 1000))
        ring.writeNumbered(ring.capacityFrames * 2)
        assertEquals(AudioRing.OVERRUN, ring.read(0, out, 0, 1000))
        assertEquals(AudioRing.OVERRUN, ring.read(ring.oldestReadable() - 1, out, 0, 1))
        assertEquals(1, ring.read(ring.oldestReadable(), out, 0, 1))
        assertThrows(IllegalArgumentException::class.java) { ring.read(0, out, 900, 200) }
    }

    @Test fun speechAndMuteFlagsArePerFrame() {
        val ring = AudioRing()
        ring.write(AudioSignals.zeros(), speech = false)
        ring.write(AudioSignals.zeros(), speech = true)
        ring.write(AudioSignals.zeros(), speech = true, muted = true)
        assertFalse(ring.isSpeech(0))
        assertTrue(ring.isSpeech(1))
        assertFalse("a muted frame is never speech", ring.isSpeech(2))
        assertTrue(ring.isMuted(2))
        assertFalse("not written yet", ring.isSpeech(3))
        assertNull(ring.speechAt(3))
        ring.writeNumbered(ring.capacityFrames)
        assertNull("overwritten", ring.speechAt(1))
    }

    @Test fun awaitWakesTheReaderAndTimesOut() {
        val ring = AudioRing()
        assertFalse(ring.awaitFrames(1, 20_000_000))
        val woke = AtomicReference<Boolean>()
        val reader = Thread { woke.set(ring.awaitFrames(5, 5_000_000_000)) }
        reader.start()
        Thread.sleep(50)
        ring.writeNumbered(5)
        reader.join(5000)
        assertEquals(true, woke.get())
    }

    /**
     * One writer thread, one reader thread: every sample arrives intact and in order. The writer never gets more than
     * half a window ahead (so a loaded test machine cannot turn a slow reader into a legitimate overrun).
     */
    @Test fun aConcurrentReaderSeesEverySampleInOrder() {
        val ring = AudioRing()
        val total = 3000L // 60 s of frames
        val failure = AtomicReference<String?>()
        val readFrames = AtomicLong()
        val reader = Thread {
            val buffer = ShortArray(AudioRing.FRAME_SAMPLES * 4)
            var position = 0L
            while (position < total * AudioRing.FRAME_SAMPLES && failure.get() == null) {
                if (!ring.awaitFrames(position / AudioRing.FRAME_SAMPLES + 1, 2_000_000_000)) {
                    failure.set("timed out at $position"); break
                }
                val n = ring.read(position, buffer, 0, buffer.size)
                if (n == AudioRing.OVERRUN) { failure.set("overrun at $position"); break }
                for (i in 0 until n) {
                    val frame = (position + i) / AudioRing.FRAME_SAMPLES
                    if (buffer[i] != (frame % 30000).toInt().toShort()) {
                        failure.set("sample ${position + i}: ${buffer[i]}"); break
                    }
                }
                position += n
                readFrames.set(position / AudioRing.FRAME_SAMPLES)
            }
        }
        reader.start()
        for (i in 0 until total) {
            while (i - readFrames.get() > ring.readableFrames / 2 && failure.get() == null && reader.isAlive) {
                Thread.sleep(1)
            }
            ring.write(AudioSignals.numbered(i), false)
        }
        reader.join(30_000)
        assertNull(failure.get())
        assertFalse(reader.isAlive)
    }
}
