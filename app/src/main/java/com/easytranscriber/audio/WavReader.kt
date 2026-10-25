package com.easytranscriber.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAudio(val sampleRate: Int, val samples: FloatArray) {
    val durationSec: Double get() = samples.size.toDouble() / sampleRate
}

/** Minimal RIFF/WAVE reader for 16-bit PCM mono files. Other formats are rejected, not converted. */
object WavReader {
    fun read(file: File): WavAudio = parse(file.readBytes(), file.name)

    fun parse(bytes: ByteArray, name: String = "wav"): WavAudio {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun fourCC(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && fourCC(0) == "RIFF" && fourCC(8) == "WAVE") { "$name: not a RIFF/WAVE file" }

        var pos = 12
        var channels = -1
        var sampleRate = -1
        var bits = -1
        var format = -1
        while (pos + 8 <= bytes.size) {
            val id = fourCC(pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            require(size >= 0 && body + size <= bytes.size || id == "data") { "$name: corrupt chunk '$id'" }
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(format == 1 && bits == 16 && channels == 1) {
                        "$name: unsupported format (format=$format, bits=$bits, channels=$channels); need 16-bit PCM mono"
                    }
                    // Streamed WAVs may carry a placeholder (e.g. 0xFFFFFFFF) data size.
                    val end = if (size < 0) bytes.size else minOf(bytes.size, body + size)
                    val count = (end - body) / 2
                    val out = FloatArray(count)
                    for (i in 0 until count) out[i] = buf.getShort(body + 2 * i) / 32768f
                    return WavAudio(sampleRate, out)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IllegalArgumentException("$name: no data chunk")
    }
}
