package com.easytranscriber.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit PCM mono audio to a WAV file so long recordings never sit in memory.
 * The RIFF and data sizes are patched on [close].
 */
class WavWriter(val file: File, private val sampleRate: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    var samplesWritten = 0L
        private set

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    val durationSec: Double get() = samplesWritten.toDouble() / sampleRate

    fun write(samples: FloatArray) {
        for (s in samples) {
            if (buffer.remaining() < 2) flushBuffer()
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buffer.putShort(v.toShort())
        }
        samplesWritten += samples.size
    }

    private fun flushBuffer() {
        raf.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    override fun close() {
        flushBuffer()
        raf.seek(0)
        raf.write(header(samplesWritten * 2))
        raf.close()
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}

/** Reads a 16-bit PCM mono WAV written by [WavWriter] in fixed-size chunks (bounded memory). */
object WavChunkReader {
    /** Calls [onChunk] with successive sample blocks; returns the total sample count. */
    fun read(file: File, chunkSamples: Int, onChunk: (FloatArray, Long, Long) -> Unit): Long {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(44)
            raf.readFully(head)
            val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            require(String(head, 0, 4) == "RIFF" && String(head, 36, 4) == "data") { "${file.name}: unexpected WAV layout" }
            require(hb.getShort(22).toInt() == 1 && hb.getShort(34).toInt() == 16) { "${file.name}: need 16-bit mono PCM" }
            val total = (raf.length() - 44) / 2
            val bytes = ByteArray(chunkSamples * 2)
            var done = 0L
            while (done < total) {
                val n = minOf(chunkSamples.toLong(), total - done).toInt()
                raf.readFully(bytes, 0, n * 2)
                val bb = ByteBuffer.wrap(bytes, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN)
                val out = FloatArray(n) { bb.getShort() / 32768f }
                done += n
                onChunk(out, done, total)
            }
            return total
        }
    }
}
