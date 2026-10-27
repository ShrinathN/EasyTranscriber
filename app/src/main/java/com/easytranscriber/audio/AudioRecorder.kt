package com.easytranscriber.audio

import java.io.File
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt

data class RecordingResult(val file: File, val durationSec: Double, val reachedLimit: Boolean)

/**
 * Records from an [AudioSource] into a WAV file (streamed to disk, bounded memory).
 * Stops when [isActive] turns false or [maxDurationSec] is reached.
 */
class AudioRecorder(
    private val source: AudioSource,
    private val maxDurationSec: Int,
) {
    @Throws(AudioCaptureException::class)
    fun record(
        file: File,
        isActive: () -> Boolean,
        /** Sees every captured block, on the capture thread, after it is written. */
        onSamples: (FloatArray) -> Unit = {},
        onProgress: (elapsedSec: Double, levelDb: Float) -> Unit,
    ): RecordingResult {
        val maxSamples = maxDurationSec.toLong() * source.sampleRate
        var limit = false
        val writer = try {
            WavWriter(file, source.sampleRate)
        } catch (e: IOException) {
            throw AudioCaptureException("Could not create the recording file: ${e.message}", e)
        }
        try {
            source.run(source.sampleRate / 10, { isActive() && !limit }) { frame ->
                try {
                    writer.write(frame.samples)
                } catch (e: IOException) {
                    throw AudioCaptureException("Recording stopped: storage is full or unavailable (${e.message})", e)
                }
                if (writer.samplesWritten >= maxSamples) limit = true
                onSamples(frame.samples)
                onProgress(writer.durationSec, levelDb(frame.samples))
            }
        } finally {
            try {
                writer.close()
            } catch (e: IOException) {
                throw AudioCaptureException("Could not finish the recording file: ${e.message}", e)
            }
        }
        return RecordingResult(file, writer.durationSec, limit)
    }

    companion object {
        /** RMS level in dBFS, floored at -60. */
        fun levelDb(samples: FloatArray): Float {
            if (samples.isEmpty()) return -60f
            var sum = 0.0
            for (s in samples) sum += s * s
            val rms = sqrt(sum / samples.size)
            return if (rms <= 1e-6) -60f else maxOf(-60f, (20 * log10(rms)).toFloat())
        }
    }
}
