package com.aeronex.app.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic test frames (20 ms, 16 kHz) at a given level in dBFS (RMS relative to full scale 32768). */
internal object AudioSignals {
    const val FRAME = AudioRing.FRAME_SAMPLES

    private fun rms(dbfs: Double) = 32768.0 * 10.0.pow(dbfs / 20.0)

    fun noise(dbfs: Double, seed: Long): ShortArray {
        val random = Random(seed)
        val scale = rms(dbfs)
        return ShortArray(FRAME) { (random.nextGaussian() * scale).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    /** A 300 Hz tone (speech stand-in): amplitude sqrt(2) x RMS. */
    fun tone(dbfs: Double, frameIndex: Long = 0): ShortArray {
        val amplitude = rms(dbfs) * sqrt(2.0)
        return ShortArray(FRAME) {
            val t = (frameIndex * FRAME + it) / AudioRing.SAMPLE_RATE.toDouble()
            (amplitude * sin(2 * PI * 300.0 * t)).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun zeros(): ShortArray = ShortArray(FRAME)

    /** Frame [index] filled with its own index (wrapping), to check where samples land in the ring. */
    fun numbered(index: Long): ShortArray = ShortArray(FRAME) { (index % 30000).toInt().toShort() }
}
