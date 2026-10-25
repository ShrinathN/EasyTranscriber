package com.easytranscriber.recognition

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * sherpa-onnx streaming transducer adapter.
 *
 * Endpointing uses sherpa-onnx's built-in rules: a segment is finalized after [trailingSilenceSec]
 * of silence following speech, or when it reaches [maxSegmentSec].
 */
class SherpaOnnxStreamingEngine(
    private val installed: InstalledModel,
    private val numThreads: Int = 2,
    private val trailingSilenceSec: Float = 1.2f,
    private val maxSegmentSec: Float = 20f,
) : RecognitionEngine {

    override val model: ModelEntry get() = installed.entry
    override val sampleRate: Int = 16_000

    @Volatile private var recognizer: OnlineRecognizer? = null
    override val isLoaded: Boolean get() = recognizer != null

    override fun load() {
        if (recognizer != null) return
        if (!installed.isInstalled) {
            throw RecognitionException(
                "Model files missing in ${installed.directory}: ${installed.missingFiles.joinToString()}"
            )
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = installed.file("encoder").absolutePath,
                    decoder = installed.file("decoder").absolutePath,
                    joiner = installed.file("joiner").absolutePath,
                ),
                tokens = installed.file("tokens").absolutePath,
                numThreads = numThreads,
                provider = "cpu",
                debug = false,
            ),
            endpointConfig = EndpointConfig(
                // Rule 1: long silence with no speech at all.
                rule1 = EndpointRule(false, 2.4f, 0f),
                // Rule 2: trailing silence after some speech.
                rule2 = EndpointRule(true, trailingSilenceSec, 0f),
                // Rule 3: maximum segment length.
                rule3 = EndpointRule(false, 0f, maxSegmentSec),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        try {
            recognizer = OnlineRecognizer(null, config)
        } catch (t: Throwable) {
            throw RecognitionException("Failed to load ${model.displayName}: ${t.message}", t)
        }
    }

    override fun createStreamingSession(): StreamingSession {
        if (TranscriptionMode.REAL_TIME !in model.modes) {
            throw RecognitionException("${model.displayName} does not support real-time dictation")
        }
        val r = recognizer ?: throw RecognitionException("Model not loaded")
        return Session(r, r.createStream())
    }

    override fun transcribe(samples: FloatArray): BatchSegmentResult =
        throw RecognitionException("${model.displayName} is a streaming model; it does not transcribe recordings")

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    private inner class Session(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
    ) : StreamingSession {
        private var samplesFed = 0L
        /** Audio time at which the active segment began; token timestamps are relative to it. */
        private var segmentStartSec = 0f

        override fun accept(samples: FloatArray): StreamingUpdate {
            stream.acceptWaveform(samples, sampleRate)
            samplesFed += samples.size
            return decodeAvailable()
        }

        override fun finish(): StreamingUpdate {
            // Trailing silence lets the final chunk (and lookahead) be decoded.
            stream.acceptWaveform(FloatArray((sampleRate * TAIL_PADDING_SEC).toInt()), sampleRate)
            stream.inputFinished()
            val update = decodeAvailable()
            if (update.finalized != null) return update
            val last = update.partial
            return update.copy(partial = Hypothesis.EMPTY, finalized = last.takeUnless { it.isEmpty })
        }

        private fun decodeAvailable(): StreamingUpdate {
            var calls = 0
            val t0 = System.nanoTime()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                calls++
            }
            val decodeNanos = System.nanoTime() - t0
            val current = currentHypothesis()
            if (recognizer.isEndpoint(stream)) {
                recognizer.reset(stream)
                // The segment boundary is approximately at the audio decoded so far.
                segmentStartSec = samplesFed.toFloat() / sampleRate
                return StreamingUpdate(
                    partial = Hypothesis.EMPTY,
                    finalized = current.takeUnless { it.isEmpty },
                    decodeCalls = calls,
                    decodeNanos = decodeNanos,
                )
            }
            return StreamingUpdate(current, null, calls, decodeNanos)
        }

        private fun currentHypothesis(): Hypothesis {
            val result = recognizer.getResult(stream)
            val text = result.text.trim()
            if (text.isEmpty()) return Hypothesis.EMPTY
            val stamps = result.timestamps
            val tokens = result.tokens.mapIndexed { i, tok ->
                Token(tok, segmentStartSec + (stamps.getOrNull(i) ?: 0f))
            }
            return Hypothesis(text, tokens)
        }

        override fun close() {
            stream.release()
        }
    }

    companion object {
        private const val TAIL_PADDING_SEC = 0.8f
    }
}
