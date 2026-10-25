package com.easytranscriber.batch

import com.easytranscriber.audio.WavChunkReader
import com.easytranscriber.recognition.RecognitionEngine
import java.io.File

/** Splits audio into speech segments (e.g. a VAD). Not thread-safe. */
interface SpeechSegmenter : AutoCloseable {
    fun accept(samples: FloatArray)
    /** Call after the last [accept] to release any trailing speech. */
    fun flush()
    /** Returns and removes completed segments. */
    fun drain(): List<FloatArray>
}

data class BatchProgress(val fraction: Float, val segmentsDone: Int)

data class BatchResult(
    val text: String,
    val segments: Int,
    val audioSec: Double,
    val speechSec: Double,
    val processingSec: Double,
    val detectedLanguage: String?,
)

class NoSpeechException : Exception("No speech was detected in the recording.")

/**
 * Transcribes a recorded WAV file: audio is read in chunks, split at pauses by the segmenter,
 * and each segment is transcribed as soon as it is complete, so memory holds at most one segment.
 */
class BatchTranscriber(
    private val engine: RecognitionEngine,
    private val segmenterFactory: () -> SpeechSegmenter,
) {
    fun transcribe(
        wav: File,
        isCancelled: () -> Boolean,
        onProgress: (BatchProgress) -> Unit,
    ): BatchResult {
        val t0 = System.nanoTime()
        val texts = ArrayList<String>()
        var speechSamples = 0L
        var language: String? = null
        val sr = engine.sampleRate

        fun transcribeSegments(segmenter: SpeechSegmenter, fraction: Float) {
            for (seg in segmenter.drain()) {
                if (isCancelled()) throw CancellationRequested()
                speechSamples += seg.size
                val r = engine.transcribe(seg)
                if (r.text.isNotBlank()) texts += r.text
                if (language == null) language = r.language
                onProgress(BatchProgress(fraction, texts.size))
            }
        }

        val total = segmenterFactory().use { segmenter ->
            val n = WavChunkReader.read(wav, chunkSamples = sr / 2) { chunk, done, total ->
                if (isCancelled()) throw CancellationRequested()
                segmenter.accept(chunk)
                transcribeSegments(segmenter, done.toFloat() / total)
            }
            segmenter.flush()
            transcribeSegments(segmenter, 1f)
            n
        }
        if (texts.isEmpty()) throw NoSpeechException()
        return BatchResult(
            text = joinSegments(texts),
            segments = texts.size,
            audioSec = total.toDouble() / sr,
            speechSec = speechSamples.toDouble() / sr,
            processingSec = (System.nanoTime() - t0) / 1e9,
            detectedLanguage = language,
        )
    }

    class CancellationRequested : Exception("Transcription cancelled")

    companion object {
        /** Joins segment texts with single spaces and collapses whitespace inside them. */
        fun joinSegments(texts: List<String>): String =
            texts.joinToString(" ") { it.trim().replace(Regex("\\s+"), " ") }.trim()
    }
}
