package com.aeronex.app.bench

import com.aeronex.app.audio.AudioRing
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPlayerTest {
    private val frame = AudioRing.FRAME_SAMPLES

    private fun ClipPlayer.readFrame(): ShortArray = ShortArray(frame).also { assertEquals(frame, read(it, 0, frame)) }

    @Test fun deliversSilenceInWholeFramesNoFasterThanRealTime() {
        val player = ClipPlayer().apply { start {} }
        val started = System.nanoTime()
        repeat(10) { assertTrue(player.readFrame().all { it == 0.toShort() }) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("10 frames took $elapsedMs ms", elapsedMs >= 195)
        assertEquals(10L, player.framesDelivered)
    }

    @Test fun aClipStartsAtTheNextFrameAndItsLastFrameIsZeroPadded() {
        val player = ClipPlayer().apply { start {} }
        repeat(3) { player.readFrame() }
        val clip = ShortArray(500) { (it + 1).toShort() }
        val playback = player.play(clip)
        assertEquals(3L * frame, playback.startSample)
        assertEquals(5L * frame, playback.endSample)
        assertEquals(3L, playback.startFrame)
        assertEquals(5L, playback.endFrame)
        assertFalse(playback.ended)

        assertArrayEquals(ShortArray(frame) { (it + 1).toShort() }, player.readFrame())
        val second = player.readFrame()
        assertArrayEquals(ShortArray(500 - frame) { (frame + it + 1).toShort() }, second.copyOf(500 - frame))
        assertTrue(second.copyOfRange(500 - frame, frame).all { it == 0.toShort() })
        assertTrue(playback.ended)
        assertTrue("the release is stamped one frame after the start",
            playback.endedAtNanos - playback.startedAtNanos >= TimeUnit.MILLISECONDS.toNanos(15))
        assertTrue(player.readFrame().all { it == 0.toShort() })
    }

    @Test fun partialReadsCoverOneFrameExactly() {
        val player = ClipPlayer().apply { start {} }
        player.play(ShortArray(frame) { 7 })
        val buffer = ShortArray(frame)
        var filled = 0
        while (filled < frame) filled += player.read(buffer, filled, minOf(100, frame - filled))
        assertTrue(buffer.all { it == 7.toShort() })
        assertEquals(1L, player.framesDelivered)
    }

    @Test fun oneClipAtATime() {
        val player = ClipPlayer().apply { start {} }
        player.play(ShortArray(frame))
        assertThrows(IllegalStateException::class.java) { player.play(ShortArray(frame)) }
        player.readFrame()
        player.play(ShortArray(frame))
        assertThrows(IllegalArgumentException::class.java) { player.play(ShortArray(0)) }
    }

    @Test fun stopEndsABlockedRead() {
        val player = ClipPlayer().apply { start {} }
        var result = -1
        val reader = Thread { result = player.read(ShortArray(frame), 0, frame) }
        player.stop()
        reader.start()
        reader.join(1_000)
        assertFalse(reader.isAlive)
        assertEquals(0, result)
    }
}
