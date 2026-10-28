package com.aeronex.app.audio

import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioCaptureTest {
    /**
     * A counting fake microphone. It plays [frames] in a loop (copied from preallocated arrays, so the fake itself
     * allocates nothing), counts reads, and remembers whether the capture loop ever passed a different buffer.
     */
    private class CountingSource(
        private val frames: List<ShortArray>,
        private val chunk: Int = AudioRing.FRAME_SAMPLES,
    ) : PcmSource {
        var reads = 0L
        var buffer: ShortArray? = null
        var buffersSwapped = 0
        @Volatile var started = false
        @Volatile var stopped = false
        var errorWhenStopped = false
        @Volatile var released = 0
        var failAfter = Long.MAX_VALUE
        @Volatile var silencedCallback: ((Boolean) -> Unit)? = null
        private var frame = 0
        private var offsetInFrame = 0

        override fun start(onClientSilenced: (Boolean) -> Unit) {
            started = true
            silencedCallback = onClientSilenced
        }

        override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
            if (this.buffer !== buffer) {
                if (this.buffer != null) buffersSwapped++
                this.buffer = buffer
            }
            if (stopped) return if (errorWhenStopped) -3 else 0
            if (++reads > failAfter) return -3 // AudioRecord.ERROR_INVALID_OPERATION
            val source = frames[frame]
            val n = minOf(count, chunk, source.size - offsetInFrame)
            System.arraycopy(source, offsetInFrame, buffer, offset, n)
            offsetInFrame += n
            if (offsetInFrame == source.size) {
                offsetInFrame = 0
                frame = (frame + 1) % frames.size
            }
            return n
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released++
        }
    }

    private class Events : AudioCapture.Listener {
        val silenced = CopyOnWriteArrayList<Boolean>()
        val failures = CopyOnWriteArrayList<Int>()
        override fun onSilencedChanged(silenced: Boolean) {
            this.silenced += silenced
        }
        override fun onCaptureFailed(code: Int) {
            failures += code
        }
    }

    private val speechAndNoise = List(10) { AudioSignals.noise(-60.0, it.toLong()) } +
        List(10) { AudioSignals.tone(-20.0, it.toLong()) }

    /**
     * The host JVM's per-thread allocation counter (com.sun.management.ThreadMXBean), reached by reflection because
     * unit tests compile against android.jar. Each reading itself allocates a few bytes (boxing, argument array):
     * [overhead] measures that cost so it can be subtracted.
     */
    private class AllocationCounter {
        private val bean: Any? = try {
            Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        } catch (_: Exception) { null }
        private val type = try { Class.forName("com.sun.management.ThreadMXBean") } catch (_: Exception) { null }
        val supported = bean != null && type != null && type.isInstance(bean) &&
            type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true
        private val read = type?.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        @Suppress("DEPRECATION") // Thread.threadId() is not in android.jar
        private val id = Thread.currentThread().id

        fun bytes(): Long = read!!.invoke(bean, id) as Long

        fun overhead(): Long {
            repeat(50) { bytes() }
            return (0 until 20).minOf { val a = bytes(); bytes() - a }
        }
    }

    /** Spec 4.2/6.2: no allocations in the audio loop. Measured on this thread with the JVM's allocation counter. */
    @Test fun theReadLoopAllocatesNothing() {
        val counter = AllocationCounter()
        assumeTrue("needs a HotSpot-style JVM allocation counter", counter.supported)
        // 30 zero frames per 50: every cycle goes silenced (after 26 zero frames) and back, inside the measurement.
        val source = CountingSource(speechAndNoise + List(30) { AudioSignals.zeros() })
        val transitions = object : AudioCapture.Listener {
            var changes = 0
            override fun onSilencedChanged(silenced: Boolean) {
                changes++
            }
        }
        val capture = AudioCapture(source, listener = transitions)
        repeat(5_000) { assertTrue(capture.step()) } // warm-up
        val overhead = counter.overhead()
        val changesBefore = transitions.changes
        fun allocatedBy(iterations: Int): Long {
            val before = counter.bytes()
            var ok = true
            for (i in 0 until iterations) ok = capture.step() and ok
            val allocated = counter.bytes() - before - overhead
            assertTrue(ok)
            return allocated
        }
        // The smallest JVM object is 16 bytes: one allocation per frame would show as >= 160 KB here, one per
        // silenced transition as >= 6 KB. A few hundred bytes that do not grow with the run are the counter's own
        // reflective call, not the loop.
        val short = allocatedBy(10_000)
        val long = allocatedBy(40_000)
        assertTrue("bytes allocated by 10,000 loop iterations: $short", short < 1024)
        assertTrue("bytes allocated by 40,000 loop iterations (800 s of audio): $long", long < 1024)
        assertEquals("silenced and recovered 1,000 times", 2_000, transitions.changes - changesBefore)
        assertEquals("one preallocated buffer, every read", 0, source.buffersSwapped)
        assertEquals(55_000L, source.reads)
        assertEquals(55_000L, capture.ring.framesWritten)
    }

    @Test fun framesReachTheRingWithTheirVadDecision() {
        val capture = AudioCapture(CountingSource(speechAndNoise))
        repeat(20) { capture.step() }
        assertEquals(20L, capture.ring.framesWritten)
        assertTrue((0L until 10L).none { capture.ring.isSpeech(it) })
        assertTrue((10L until 20L).all { capture.ring.isSpeech(it) })
        val out = ShortArray(AudioRing.FRAME_SAMPLES)
        capture.ring.read(15L * AudioRing.FRAME_SAMPLES, out, 0, out.size)
        assertTrue(out.contentEquals(speechAndNoise[15]))
    }

    @Test fun shortReadsAreAssembledIntoWholeFrames() {
        val source = CountingSource(speechAndNoise, chunk = 100)
        val capture = AudioCapture(source)
        repeat(20) { capture.step() }
        assertEquals(20L, capture.ring.framesWritten)
        assertEquals("4 reads per 320-sample frame", 80L, source.reads)
        val out = ShortArray(AudioRing.FRAME_SAMPLES * 20)
        capture.ring.read(0, out, 0, out.size)
        assertTrue(out.contentEquals(speechAndNoise.reduce { a, b -> a + b }))
    }

    @Test fun silencedCaptureIsReportedOnceAndItsRecoveryToo() {
        val events = Events()
        val source = CountingSource(List(40) { AudioSignals.zeros() } + List(5) { AudioSignals.noise(-50.0, 9) })
        val capture = AudioCapture(source, listener = events)
        repeat(26) { capture.step() }
        assertEquals(listOf(true), events.silenced.toList())
        assertTrue(capture.silenced)
        repeat(19) { capture.step() }
        assertEquals(listOf(true, false), events.silenced.toList())
        assertFalse(capture.silenced)
    }

    @Test fun thePlatformSilencedCallbackSwitchesToNoVoice() {
        val events = Events()
        val source = CountingSource(speechAndNoise)
        val capture = AudioCapture(source, listener = events)
        capture.start()
        try {
            waitFor("source started", 5_000) { source.silencedCallback != null }
            source.silencedCallback!!(true)
            waitFor("silenced reported", 5_000) { events.silenced.toList() == listOf(true) }
            source.silencedCallback!!(false)
            waitFor("recovery reported", 5_000) { events.silenced.toList() == listOf(true, false) }
        } finally {
            capture.stop()
        }
    }

    @Test fun theThreadRunsItsSetupAndStopsCleanly() {
        var setupThread: Thread? = null
        val source = CountingSource(speechAndNoise)
        val capture = AudioCapture(source, threadSetup = { setupThread = Thread.currentThread() })
        capture.start()
        waitFor("frames flowing", 5_000) { capture.ring.framesWritten > 100 }
        assertTrue(source.started)
        capture.stop()
        assertTrue(source.stopped)
        assertEquals("released once, on the capture thread, after the loop", 1, source.released)
        assertFalse(capture.running)
        assertSame(capture.thread, setupThread)
        assertFalse(setupThread!!.isAlive)
        val written = capture.ring.framesWritten
        Thread.sleep(50)
        assertEquals(written, capture.ring.framesWritten)
    }

    @Test fun aReadErrorEndsTheLoopAndIsReported() {
        val events = Events()
        val source = CountingSource(speechAndNoise).apply { failAfter = 5 }
        val capture = AudioCapture(source, listener = events)
        capture.start()
        waitFor("loop ended", 5_000) { !capture.thread!!.isAlive }
        assertEquals(listOf(-3), events.failures.toList())
        assertEquals(5L, capture.ring.framesWritten)
        assertFalse(capture.running)
        assertEquals(1, source.released)
        capture.stop()
    }

    @Test fun aSourceThatCannotOpenIsReportedAndReleased() {
        val events = Events()
        val source = object : PcmSource {
            var released = false
            override fun start(onClientSilenced: (Boolean) -> Unit) = throw IllegalStateException("no microphone")
            override fun read(buffer: ShortArray, offset: Int, count: Int) = error("not opened")
            override fun stop() {}
            override fun release() {
                released = true
            }
        }
        val capture = AudioCapture(source, listener = events)
        capture.start()
        waitFor("loop ended", 5_000) { !capture.thread!!.isAlive }
        assertEquals(listOf(AudioCapture.START_FAILED), events.failures.toList())
        assertTrue(source.released)
        assertFalse(capture.running)
    }

    @Test fun anErrorAfterStopIsNotAFailure() {
        val events = Events()
        val source = CountingSource(speechAndNoise).apply { errorWhenStopped = true }
        val capture = AudioCapture(source, listener = events)
        capture.start()
        waitFor("frames flowing", 5_000) { capture.ring.framesWritten > 10 }
        capture.stop()
        assertTrue("a stopped AudioRecord answers ERROR_INVALID_OPERATION: not a failure", events.failures.isEmpty())
        assertEquals(1, source.released)
    }

    @Test fun playbackMuteMarksFramesAsNeverSpeech() {
        val capture = AudioCapture(CountingSource(speechAndNoise))
        repeat(15) { capture.step() }
        capture.setPlaybackMuted(true)
        repeat(3) { capture.step() }
        capture.setPlaybackMuted(false)
        repeat(2) { capture.step() }
        assertTrue(capture.ring.isSpeech(14))
        assertTrue((15L until 18L).none { capture.ring.isSpeech(it) })
        assertTrue((15L until 18L).all { capture.ring.isMuted(it) })
        assertTrue(capture.ring.isSpeech(18))
    }
}
