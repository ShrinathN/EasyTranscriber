package com.easytranscriber.batch

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Silero VAD via sherpa-onnx. Segments end after [minSilenceSec] of silence or at
 * [maxSegmentSec] (kept under Whisper's 30 s window; a forced cut can split a word).
 */
class SileroSegmenter(
    model: File,
    sampleRate: Int = 16_000,
    minSilenceSec: Float = 0.5f,
    maxSegmentSec: Float = 28f,
) : SpeechSegmenter {
    private val windowSize = 512
    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = minSilenceSec,
                minSpeechDuration = 0.25f,
                windowSize = windowSize,
                maxSpeechDuration = maxSegmentSec,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
        ),
    )
    private var carry = FloatArray(0)

    override fun accept(samples: FloatArray) {
        // The VAD consumes fixed windows; keep the remainder for the next call.
        val all = if (carry.isEmpty()) samples else carry + samples
        var i = 0
        while (i + windowSize <= all.size) {
            vad.acceptWaveform(all.copyOfRange(i, i + windowSize))
            i += windowSize
        }
        carry = all.copyOfRange(i, all.size)
    }

    override fun flush() {
        if (carry.isNotEmpty()) {
            vad.acceptWaveform(carry + FloatArray(windowSize - carry.size))
            carry = FloatArray(0)
        }
        vad.flush()
    }

    override fun drain(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        while (!vad.empty()) {
            out += vad.front().samples
            vad.pop()
        }
        return out
    }

    override fun close() = vad.release()
}
