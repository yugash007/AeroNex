package com.aeronex.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {
    private val vad = EnergyVad()
    private var seed = 1L

    private fun noise(dbfs: Double, frames: Int) = List(frames) { vad.accept(AudioSignals.noise(dbfs, seed++)) }
    private fun tone(dbfs: Double, frames: Int) = List(frames) { vad.accept(AudioSignals.tone(dbfs, it.toLong())) }

    @Test fun digitalSilenceIsNeverSpeech() {
        assertTrue(List(50) { vad.accept(AudioSignals.zeros()) }.none { it })
        assertTrue(tone(-50.0, 5).all { it })
        assertTrue(List(20) { vad.accept(AudioSignals.zeros()) }.none { it })
    }

    @Test fun speechOverAQuietBackgroundIsDetected() {
        assertTrue("background noise is not speech", noise(-60.0, 50).none { it })
        assertTrue("speech 40 dB above it is", tone(-20.0, 25).all { it })
        assertTrue("background again", noise(-60.0, 5).none { it })
    }

    @Test fun theFloorAdaptsUpToSteadyLouderNoiseAndStillFindsSpeechAboveIt() {
        noise(-60.0, 50)
        val rotors = noise(-35.0, 200) // 4 s of much louder steady noise
        assertTrue("adapted within 4 s: ${rotors.takeLast(50)}", rotors.takeLast(50).none { it })
        assertEquals(-35.0, vad.noiseFloorDb, 3.0)
        assertTrue("speech above the new floor", tone(-10.0, 10).all { it })
        assertFalse("a tone at the old speech level is now background", tone(-35.0, 10).last())
    }

    @Test fun theFloorFallsQuicklyWhenTheNoiseStops() {
        noise(-60.0, 25)
        noise(-30.0, 250)
        noise(-60.0, 10) // 200 ms of quiet
        assertTrue("floor followed the quiet: ${vad.noiseFloorDb}", vad.noiseFloorDb < -55.0)
        assertTrue("speech that the rotor noise masked is found again", tone(-40.0, 5).all { it })
    }

    /** Real speech has gaps between words at the background level; they keep the floor down. */
    @Test fun aLongUtteranceKeepsItsSpeechDecision() {
        noise(-60.0, 50)
        val words = (0 until 150).mapNotNull { k ->
            if (k % 10 < 8) vad.accept(AudioSignals.tone(-20.0, k.toLong())) else { noise(-60.0, 1); null }
        }
        assertTrue("3 s of speech with 40 ms gaps stays speech", words.all { it })
        assertTrue("floor stayed near the background: ${vad.noiseFloorDb}", vad.noiseFloorDb < -50.0)
    }
}
