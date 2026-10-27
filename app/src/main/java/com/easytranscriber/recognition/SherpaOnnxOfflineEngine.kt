package com.easytranscriber.recognition

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * sherpa-onnx non-streaming adapter for complete utterances (Whisper, Moonshine).
 * [language] is a Whisper language code; "" asks Whisper to detect the language.
 */
class SherpaOnnxOfflineEngine(
    private val installed: InstalledModel,
    private val numThreads: Int = 2,
    private val language: String = "en",
) : RecognitionEngine {

    override val model: ModelEntry get() = installed.entry
    override val sampleRate: Int = 16_000

    @Volatile private var recognizer: OfflineRecognizer? = null
    override val isLoaded: Boolean get() = recognizer != null

    override fun load() {
        if (recognizer != null) return
        if (!installed.isInstalled) {
            throw RecognitionException(
                "Model files missing in ${installed.directory}: ${installed.missingFiles.joinToString()}"
            )
        }
        val tokens = installed.file("tokens").absolutePath
        val modelConfig = when (model.architecture) {
            ModelArchitecture.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = installed.file("encoder").absolutePath,
                    decoder = installed.file("decoder").absolutePath,
                    language = language,
                    task = "transcribe",
                ),
                tokens = tokens,
                numThreads = numThreads,
                modelType = "whisper",
            )
            ModelArchitecture.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = installed.file("encoder").absolutePath,
                    mergedDecoder = installed.file("decoder").absolutePath,
                ),
                tokens = tokens,
                numThreads = numThreads,
            )
            ModelArchitecture.TRANSDUCER ->
                throw RecognitionException("${model.displayName} is a streaming model")
            ModelArchitecture.ASR4ALL ->
                throw RecognitionException("${model.displayName} runs on Asr4AllEngine, not sherpa-onnx")
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80, dither = 0f),
            modelConfig = modelConfig,
        )
        try {
            recognizer = OfflineRecognizer(null, config)
        } catch (t: Throwable) {
            throw RecognitionException("Failed to load ${model.displayName}: ${t.message}", t)
        }
    }

    override fun createStreamingSession(): StreamingSession =
        throw RecognitionException("${model.displayName} does not support real-time dictation")

    override fun transcribe(samples: FloatArray): BatchSegmentResult {
        val r = recognizer ?: throw RecognitionException("Model not loaded")
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            r.decode(stream)
            val result = r.getResult(stream)
            return BatchSegmentResult(result.text.trim(), result.lang.takeIf { it.isNotBlank() })
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }
}
