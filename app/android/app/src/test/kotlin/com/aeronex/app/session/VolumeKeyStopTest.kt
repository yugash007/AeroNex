package com.aeronex.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyStopTest {
    private var active = true
    private var stops = 0
    private val key = VolumeKeyStop(active = { active }, stop = { stops++ })

    @Test fun aPressDuringASessionIsOneStopAndTheWholePressIsConsumed() {
        assertTrue(key.onVolumeDown(down = true, repeatCount = 0))
        assertEquals(1, stops)
        // Holding the key repeats ACTION_DOWN: consumed, no further STOP (the volume key never overrides, spec 4.12).
        repeat(30) { assertTrue(key.onVolumeDown(down = true, repeatCount = it + 1)) }
        assertTrue(key.onVolumeDown(down = false, repeatCount = 0))
        assertEquals(1, stops)
        assertTrue(key.onVolumeDown(down = true, repeatCount = 0))
        assertEquals("each press is a STOP", 2, stops)
    }

    @Test fun withoutASessionTheKeyStaysTheVolumeKey() {
        active = false
        assertFalse(key.onVolumeDown(down = true, repeatCount = 0))
        active = true // a session starts mid-press: this press still belongs to the volume
        assertFalse(key.onVolumeDown(down = true, repeatCount = 1))
        assertFalse(key.onVolumeDown(down = false, repeatCount = 0))
        assertEquals(0, stops)
    }

    @Test fun aPressThatStartedAsStopIsConsumedToItsEndEvenIfTheSessionEnds() {
        assertTrue(key.onVolumeDown(down = true, repeatCount = 0))
        active = false
        assertTrue(key.onVolumeDown(down = true, repeatCount = 1))
        assertTrue(key.onVolumeDown(down = false, repeatCount = 0))
        assertFalse(key.onVolumeDown(down = true, repeatCount = 0))
        assertEquals(1, stops)
    }

    @Test fun aRepeatWithoutItsFirstDownIsNotTaken() {
        assertFalse(key.onVolumeDown(down = true, repeatCount = 3))
        assertFalse(key.onVolumeDown(down = false, repeatCount = 0))
        assertEquals(0, stops)
    }
}
