package com.easytranscriber.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavReaderTest {
    private fun wav(samples: ShortArray, rate: Int = 16000, channels: Int = 1, bits: Int = 16, extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) 8 + 3 + 1 else 0 // odd-sized LIST chunk + pad byte
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + extra + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + extra + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extraChunk) b.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0))
        b.put("data".toByteArray()).putInt(data)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test
    fun parsesPcm16Mono() {
        val w = WavReader.parse(wav(shortArrayOf(0, 16384, -32768)))
        assertEquals(16000, w.sampleRate)
        assertEquals(3, w.samples.size)
        assertEquals(0.5f, w.samples[1], 1e-6f)
        assertEquals(-1f, w.samples[2], 1e-6f)
    }

    @Test
    fun skipsOddSizedChunksWithPadding() {
        val w = WavReader.parse(wav(shortArrayOf(1, 2), extraChunk = true))
        assertEquals(2, w.samples.size)
    }

    @Test
    fun rejectsStereo() {
        assertThrows(IllegalArgumentException::class.java) { WavReader.parse(wav(shortArrayOf(1, 2), channels = 2)) }
    }
}
