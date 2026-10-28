package com.aeronex.app.audio

import kotlin.math.log10

/**
 * Frame-energy VAD with an adaptive noise floor (plan deviation: replaces libfvad, no NDK build). It only ends the PTT
 * tail (60 ms of non-speech) and gates the stop spotter, so it errs towards "speech": a missed silence costs at most
 * the 250 ms tail cap or some spotter CPU, a missed word could clip a command.
 *
 * A frame is speech when its energy is at least [marginDb] above the noise floor and above [minSpeechDb] (digital
 * silence and hiss never count). The floor follows quieter frames at once ([fallRate]), rises with non-speech frames
 * quickly ([riseRate], about 0.4 s) and with speech frames only slowly ([speechRiseRate], about 2 s), so steady rotor
 * or wind noise becomes background within about 2 s while an utterance, whose gaps between words pull the floor back
 * down, keeps its decision. Not thread-safe: the capture thread owns it. Allocates nothing.
 */
class EnergyVad(
    private val marginDb: Double = 10.0,
    private val minSpeechDb: Double = -55.0,
    private val riseRate: Double = 0.05,
    private val speechRiseRate: Double = 0.01,
    private val fallRate: Double = 0.5,
) {
    /** The current noise-floor estimate in dBFS. */
    var noiseFloorDb: Double = Double.NaN
        private set

    /** Energy of the last frame in dBFS (RMS relative to full scale). */
    var lastEnergyDb: Double = SILENCE_DB
        private set

    fun accept(frame: ShortArray, offset: Int = 0, count: Int = frame.size): Boolean {
        var sum = 0L
        for (i in offset until offset + count) {
            val s = frame[i].toLong()
            sum += s * s
        }
        val meanSquare = if (count == 0) 0.0 else sum.toDouble() / count
        val db = if (meanSquare <= 0.0) SILENCE_DB else 10.0 * log10(meanSquare / FULL_SCALE_SQUARED)
        lastEnergyDb = db
        if (noiseFloorDb.isNaN()) {
            noiseFloorDb = db
            return false
        }
        val speech = db >= minSpeechDb && db >= noiseFloorDb + marginDb
        val rate = when {
            db < noiseFloorDb -> fallRate
            speech -> speechRiseRate
            else -> riseRate
        }
        noiseFloorDb += (db - noiseFloorDb) * rate
        return speech
    }

    fun reset() {
        noiseFloorDb = Double.NaN
        lastEnergyDb = SILENCE_DB
    }

    private companion object {
        const val FULL_SCALE_SQUARED = 32768.0 * 32768.0
        const val SILENCE_DB = -120.0
    }
}
