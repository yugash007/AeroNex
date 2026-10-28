package com.aeronex.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyPttTest {
    private var enabled = true
    private val events = mutableListOf<String>()
    private val key = VolumeKeyPtt(enabled = { enabled }, down = { events += "down" }, up = { events += "up" })

    @Test fun holdingTheKeyIsOnePushToTalkAndTheWholePressIsConsumed() {
        assertTrue(key.onVolumeUp(down = true, repeatCount = 0))
        repeat(20) { assertTrue(key.onVolumeUp(down = true, repeatCount = it + 1)) }
        assertEquals(listOf("down"), events)
        assertTrue(key.onVolumeUp(down = false, repeatCount = 0))
        assertEquals(listOf("down", "up"), events)
    }

    @Test fun withoutASessionOnTheFlightScreenItStaysTheVolumeKey() {
        enabled = false
        assertFalse(key.onVolumeUp(down = true, repeatCount = 0))
        enabled = true // decided by the first ACTION_DOWN of the press
        assertFalse(key.onVolumeUp(down = true, repeatCount = 1))
        assertFalse(key.onVolumeUp(down = false, repeatCount = 0))
        assertTrue(events.isEmpty())
    }

    @Test fun aPressThatStartedAsTalkEndsAsTalkEvenIfTheScreenChanges() {
        assertTrue(key.onVolumeUp(down = true, repeatCount = 0))
        enabled = false
        assertTrue(key.onVolumeUp(down = false, repeatCount = 0))
        assertEquals(listOf("down", "up"), events)
        assertFalse(key.onVolumeUp(down = true, repeatCount = 0))
    }
}
