package com.easytranscriber.recognition

enum class TranscriptionMode { REAL_TIME, RECORD_THEN_TRANSCRIBE }

/** A recognized token with its position in the audio stream (seconds since the session began). */
data class Token(val text: String, val audioTimeSec: Float)

/** Recognizer output for one segment: full text plus its tokens. */
data class Hypothesis(val text: String, val tokens: List<Token>) {
    val isEmpty: Boolean get() = text.isBlank()

    /**
     * Audio time of the last token of each word in [text], using the SentencePiece word-start
     * marker ("▁", or a leading space). Null if tokens and words can't be matched one-to-one.
     */
    fun wordEndTimes(): List<Float>? {
        val words = text.split(' ').count { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val ends = ArrayList<Float>()
        for ((i, t) in tokens.withIndex()) {
            val startsWord = i == 0 || t.text.startsWith('▁') || t.text.startsWith(' ')
            if (startsWord) ends += t.audioTimeSec else ends[ends.size - 1] = t.audioTimeSec
        }
        return ends.takeIf { it.size == words }
    }

    companion object {
        val EMPTY = Hypothesis("", emptyList())
    }
}

/**
 * Result of feeding audio to a [StreamingSession].
 *
 * [partial] is the current, still-revisable hypothesis for the active segment. When the engine
 * detects an endpoint (end of utterance), [finalized] holds that segment's final text. After an
 * endpoint, [partial] belongs to the next segment.
 */
data class StreamingUpdate(
    val partial: Hypothesis,
    val finalized: Hypothesis?,
    val decodeCalls: Int,
    val decodeNanos: Long,
)

/**
 * One continuous streaming recognition session. Recognizer state (encoder caches, decoder
 * context) persists across [accept] calls. Not thread-safe: use from a single thread.
 */
interface StreamingSession : AutoCloseable {
    /** Feeds mono float samples in [-1, 1] at the engine's sample rate and runs available decoding. */
    fun accept(samples: FloatArray): StreamingUpdate

    /** Signals end of input, flushes buffered audio and returns the last update. */
    fun finish(): StreamingUpdate
}

class RecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A speech recognition engine bound to one model. Implementations keep model-specific
 * behavior to themselves; callers only see this interface.
 */
interface RecognitionEngine : AutoCloseable {
    val model: ModelEntry
    val sampleRate: Int
    val isLoaded: Boolean

    /** Loads the model. Blocking and slow; call off the main thread. */
    @Throws(RecognitionException::class)
    fun load()

    /** Starts a streaming session. Only valid when the model supports [TranscriptionMode.REAL_TIME]. */
    @Throws(RecognitionException::class)
    fun createStreamingSession(): StreamingSession

    /**
     * Transcribes one complete utterance (at most [ModelEntry.maxInputSec] long). Only valid when
     * the model supports [TranscriptionMode.RECORD_THEN_TRANSCRIBE].
     */
    @Throws(RecognitionException::class)
    fun transcribe(samples: FloatArray): BatchSegmentResult
}

/** Text for one utterance plus the language the engine reports (Whisper detection), if any. */
data class BatchSegmentResult(val text: String, val language: String?)
