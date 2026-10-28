package com.aeronex.app.bench

import com.aeronex.app.audio.AudioRing
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the bench clips: RIFF/WAVE, PCM, 16 kHz, mono, 16-bit (the eSpeak clips of T3). Anything else is refused. */
object WavPcm {
    fun read(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && tag(buffer, 0) == "RIFF" && tag(buffer, 8) == "WAVE") { "Not a WAV file" }
        var format = false
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = tag(buffer, offset)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            require(size >= 0 && body + size <= bytes.size) { "Truncated WAV chunk $id" }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Short WAV format chunk" }
                    val pcm = buffer.getShort(body).toInt() == 1
                    val channels = buffer.getShort(body + 2).toInt()
                    val rate = buffer.getInt(body + 4)
                    val bits = buffer.getShort(body + 14).toInt()
                    require(pcm && channels == 1 && rate == AudioRing.SAMPLE_RATE && bits == 16) {
                        "Bench clips must be 16 kHz mono PCM16 (got format pcm=$pcm, $channels ch, $rate Hz, $bits bit)"
                    }
                    format = true
                }
                "data" -> {
                    require(format) { "WAV data before its format" }
                    val samples = ShortArray(size / 2)
                    buffer.position(body)
                    buffer.asShortBuffer().get(samples)
                    return samples
                }
            }
            offset = body + size + size % 2
        }
        throw IllegalArgumentException("WAV file without a data chunk")
    }

    private fun tag(buffer: ByteBuffer, at: Int) = String(ByteArray(4) { buffer.get(at + it) }, Charsets.US_ASCII)
}
