package com.easytranscriber.batch

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Decides when a recording should end by itself: once speech has been heard, a pause of
 * [pauseSec] without speech ends it. Silence before the first word never does.
 */
class PauseDetector(private val pauseSec: Double) {
    private var heardSpeech = false
    private var lastSpeechSec = 0.0

    /** [timeSec] is the recording time at the end of the audio [speech] describes; true = stop now. */
    fun update(timeSec: Double, speech: Boolean): Boolean {
        if (speech) {
            heardSpeech = true
            lastSpeechSec = timeSec
        }
        return heardSpeech && timeSec - lastSpeechSec >= pauseSec
    }
}

/** Live speech/no-speech decisions from Silero VAD while recording. Not thread-safe. */
class SileroSpeechDetector(model: File, sampleRate: Int = 16_000) : AutoCloseable {
    private val windowSize = 512
    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.absolutePath,
                threshold = 0.5f,
                // Short, so the pause is timed by PauseDetector rather than hidden inside the VAD.
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = windowSize,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
        ),
    )
    private var carry = FloatArray(0)

    /** Feeds [samples]; returns whether speech is currently detected. */
    fun accept(samples: FloatArray): Boolean {
        val all = if (carry.isEmpty()) samples else carry + samples
        var i = 0
        while (i + windowSize <= all.size) {
            vad.acceptWaveform(all.copyOfRange(i, i + windowSize))
            i += windowSize
        }
        carry = all.copyOfRange(i, all.size)
        // Finished segments aren't needed here; drop them so memory stays flat.
        while (!vad.empty()) vad.pop()
        return vad.isSpeechDetected()
    }

    override fun close() = vad.release()
}
