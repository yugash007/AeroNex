package com.aeronex.app.bench

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WavPcmTest {
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val padded = body.size + body.size % 2
        return ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(body.size).put(body).array()
    }

    private fun fmt(rate: Int = 16_000, channels: Int = 1, bits: Int = 16, format: Int = 1): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(format.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).array()

    private fun pcm(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { all, c -> all + c }
        return ByteBuffer.allocate(12 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(4 + body.size).put("WAVE".toByteArray()).put(body).array()
    }

    private val samples = shortArrayOf(0, 1, -1, 32767, -32768, 1234)

    @Test fun readsMono16kHzPcm16() {
        assertArrayEquals(samples, WavPcm.read(wav(chunk("fmt ", fmt()), chunk("data", pcm(samples)))))
    }

    @Test fun skipsOtherChunksIncludingOddSizedOnes() {
        val bytes = wav(chunk("fmt ", fmt()), chunk("LIST", byteArrayOf(1, 2, 3)), chunk("data", pcm(samples)))
        assertArrayEquals(samples, WavPcm.read(bytes))
    }

    @Test fun refusesAnythingButMono16kHzPcm16() {
        for (format in listOf(fmt(rate = 8_000), fmt(channels = 2), fmt(bits = 8), fmt(format = 3))) {
            assertThrows(IllegalArgumentException::class.java) {
                WavPcm.read(wav(chunk("fmt ", format), chunk("data", pcm(samples))))
            }
        }
    }

    @Test fun refusesFilesThatAreNotWav() {
        assertThrows(IllegalArgumentException::class.java) { WavPcm.read("not a wav file at all".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { WavPcm.read(wav(chunk("fmt ", fmt()))) }
        assertThrows(IllegalArgumentException::class.java) { WavPcm.read(wav(chunk("data", pcm(samples)))) }
    }
}
