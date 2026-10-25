package com.easytranscriber.recognition

import java.io.File

enum class ModelArchitecture { TRANSDUCER, WHISPER, MOONSHINE }

/** Casing of raw recognizer output; uppercase-only models are lowercased before formatting. */
enum class OutputCase { LOWER, UPPER, MIXED }

/** A recognition language. [code] is passed to the engine when it supports language selection. */
data class Language(val code: String, val displayName: String)

/**
 * A known model. Its files must be installed under `<models dir>/<id>/`.
 * [files] maps a role (encoder, decoder, joiner, tokens) to a file name in that directory.
 */
data class ModelEntry(
    val id: String,
    val displayName: String,
    val shortName: String,
    val engineName: String,
    val architecture: ModelArchitecture,
    val languages: List<Language>,
    val modes: Set<TranscriptionMode>,
    val files: Map<String, String>,
    val license: String,
    val sourceUrl: String,
    val notes: String,
    val outputCase: OutputCase,
    /** True if the model emits punctuation itself. */
    val punctuates: Boolean,
    /** Longest audio the model accepts in one call (batch models), in seconds. */
    val maxInputSec: Float? = null,
    /** Measured on the reference device (moto g45 5G); null if not measured. */
    val measured: String? = null,
    /** Where the app downloads the files from (a .tar.bz2 release archive). */
    val archiveUrl: String = "",
    /** Download size of [archiveUrl] in bytes. */
    val downloadBytes: Long = 0,
    /** Expected SHA-256 of every file in [files], verified after download. */
    val sha256: Map<String, String> = emptyMap(),
    /** Shown only in debug builds (benchmark/comparison models). */
    val developerOnly: Boolean = false,
)

data class InstalledModel(val entry: ModelEntry, val directory: File, val missingFiles: List<String>, val sizeBytes: Long) {
    val isInstalled: Boolean get() = missingFiles.isEmpty()
    fun file(role: String): File = File(directory, entry.files.getValue(role))
}

object ModelCatalog {
    private const val SHERPA = "sherpa-onnx"
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    val ENGLISH = Language("en", "English")
    val HINDI = Language("hi", "Hindi")
    /** Whisper language identification; the detected language is reported with the result. */
    val AUTO = Language("", "Detect automatically")

    private val TRANSDUCER_INT8 = mapOf(
        "encoder" to "encoder.int8.onnx",
        "decoder" to "decoder.int8.onnx",
        "joiner" to "joiner.int8.onnx",
        "tokens" to "tokens.txt",
    )
    private val WHISPER_LANGUAGES = listOf(
        ENGLISH, HINDI, AUTO,
        Language("es", "Spanish"), Language("fr", "French"), Language("de", "German"),
    )

    /** Silero VAD (MIT): splits recordings at pauses for batch models. Not a recognizer. */
    const val VAD_DIR = "silero-vad"
    const val VAD_FILE = "silero_vad.onnx"
    const val VAD_URL = RELEASES + VAD_FILE
    const val VAD_BYTES = 643_854L
    const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

    val entries: List<ModelEntry> = listOf(
        ModelEntry(
            id = "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8",
            displayName = "NeMo FastConformer streaming (80 ms)",
            shortName = "NeMo 80 ms",
            engineName = SHERPA,
            architecture = ModelArchitecture.TRANSDUCER,
            languages = listOf(ENGLISH),
            modes = setOf(TranscriptionMode.REAL_TIME),
            files = TRANSDUCER_INT8,
            license = "CC-BY-4.0 (NVIDIA NeMo model card; re-verify before distribution)",
            sourceUrl = RELEASES + "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8.tar.bz2",
            archiveUrl = RELEASES + "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8.tar.bz2",
            downloadBytes = 102813625L,
            sha256 = mapOf(
                "encoder.int8.onnx" to "8b982c67b45e3b735d3fee49a4fea525b3149b450ba437f4c8a933f4aa6744c0",
                "decoder.int8.onnx" to "76eec598da07c204747a859a723b99077ef0bbdc19ef4b3f51eb43275662475d",
                "joiner.int8.onnx" to "67f4291dc170fd06b3695a8511f5199fd5965ee3c77cfbd59afc10e145f173f2",
                "tokens.txt" to "618dc110fc2213886b52e063ff42329bbdf37a266ca7705184090fa5f39f3131",
            ),
            notes = "Recommended. Lowercase output without punctuation; use spoken punctuation.",
            outputCase = OutputCase.LOWER,
            punctuates = false,
            measured = "Word latency p50 ≈ 200 ms, RAM ≈ 315 MB, 1.1–1.3 cores (2 threads)",
        ),
        ModelEntry(
            id = "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8",
            displayName = "NeMo FastConformer streaming (480 ms)",
            shortName = "NeMo 480 ms",
            engineName = SHERPA,
            architecture = ModelArchitecture.TRANSDUCER,
            languages = listOf(ENGLISH),
            modes = setOf(TranscriptionMode.REAL_TIME),
            files = TRANSDUCER_INT8,
            license = "CC-BY-4.0 (NVIDIA NeMo model card; re-verify before distribution)",
            sourceUrl = RELEASES + "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8.tar.bz2",
            archiveUrl = RELEASES + "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8.tar.bz2",
            downloadBytes = 105913204L,
            sha256 = mapOf(
                "encoder.int8.onnx" to "100c5616929a131b5b3c8c8ab0d83aaba2cdcae163acd8d190b4e5ffa5f7d051",
                "decoder.int8.onnx" to "4f04431988472f8c7b815f942aa6901976929c9e22353029681fcdb262da0164",
                "joiner.int8.onnx" to "d2d8e7290a6a8245a83ed211aa0dd8cdf8effb8a7c64fe392399561111b52f30",
                "tokens.txt" to "618dc110fc2213886b52e063ff42329bbdf37a266ca7705184090fa5f39f3131",
            ),
            developerOnly = true,
            notes = "More lookahead; about twice the latency of the 80 ms variant.",
            outputCase = OutputCase.LOWER,
            punctuates = false,
            measured = "Word latency p50 ≈ 440 ms, RAM ≈ 315 MB (2 threads)",
        ),
        ModelEntry(
            id = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
            displayName = "Zipformer streaming (LibriSpeech)",
            shortName = "Zipformer",
            engineName = SHERPA,
            architecture = ModelArchitecture.TRANSDUCER,
            languages = listOf(ENGLISH),
            modes = setOf(TranscriptionMode.REAL_TIME),
            files = mapOf(
                "encoder" to "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder" to "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "joiner" to "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "tokens" to "tokens.txt",
            ),
            license = "Apache-2.0",
            sourceUrl = RELEASES + "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
            archiveUrl = RELEASES + "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
            downloadBytes = 310414022L,
            sha256 = mapOf(
                "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" to "5022b2eca5b19d1bc104fcf33e26bc32604b7df553cd2e1f62e31dc7b05e9c87",
                "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" to "780c63ee94c7cfa314211172e5d09b406c0da2beab5c40ea2f54cc95670b76a5",
                "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx" to "abd5e30f3f16fc510605c6029dba33f10e4386bd75c5bdc30cf94076864db10d",
                "tokens.txt" to "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
            ),
            developerOnly = true,
            notes = "Smaller and lighter on CPU; trained on audiobooks only.",
            outputCase = OutputCase.UPPER,
            punctuates = false,
            measured = "Word latency p50 ≈ 250 ms, RAM ≈ 235 MB (2 threads)",
        ),
        ModelEntry(
            id = "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
            displayName = "Moonshine base (English)",
            shortName = "Moonshine base",
            engineName = SHERPA,
            architecture = ModelArchitecture.MOONSHINE,
            languages = listOf(ENGLISH),
            modes = setOf(TranscriptionMode.RECORD_THEN_TRANSCRIBE),
            files = mapOf(
                "encoder" to "encoder_model.ort",
                "decoder" to "decoder_model_merged.ort",
                "tokens" to "tokens.txt",
            ),
            license = "MIT (English models)",
            sourceUrl = RELEASES + "sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2",
            archiveUrl = RELEASES + "sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2",
            downloadBytes = 111266225L,
            sha256 = mapOf(
                "encoder_model.ort" to "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf",
                "decoder_model_merged.ort" to "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d",
                "tokens.txt" to "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049",
            ),
            notes = "Punctuation and capitalization included.",
            outputCase = OutputCase.MIXED,
            punctuates = true,
            maxInputSec = 28f,
        ),
        ModelEntry(
            id = "sherpa-onnx-whisper-base",
            displayName = "Whisper base (multilingual)",
            shortName = "Whisper base",
            engineName = SHERPA,
            architecture = ModelArchitecture.WHISPER,
            languages = WHISPER_LANGUAGES,
            modes = setOf(TranscriptionMode.RECORD_THEN_TRANSCRIBE),
            files = mapOf(
                "encoder" to "base-encoder.int8.onnx",
                "decoder" to "base-decoder.int8.onnx",
                "tokens" to "base-tokens.txt",
            ),
            license = "MIT (OpenAI Whisper)",
            sourceUrl = RELEASES + "sherpa-onnx-whisper-base.tar.bz2",
            archiveUrl = RELEASES + "sherpa-onnx-whisper-base.tar.bz2",
            downloadBytes = 207557382L,
            sha256 = mapOf(
                "base-encoder.int8.onnx" to "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
                "base-decoder.int8.onnx" to "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
                "base-tokens.txt" to "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
            notes = "Punctuated, many languages. Non-Latin output (e.g. Hindi) can't be typed with the US layout.",
            outputCase = OutputCase.MIXED,
            punctuates = true,
            maxInputSec = 28f,
        ),
        ModelEntry(
            id = "sherpa-onnx-whisper-tiny",
            displayName = "Whisper tiny (multilingual)",
            shortName = "Whisper tiny",
            engineName = SHERPA,
            architecture = ModelArchitecture.WHISPER,
            languages = WHISPER_LANGUAGES,
            modes = setOf(TranscriptionMode.RECORD_THEN_TRANSCRIBE),
            files = mapOf(
                "encoder" to "tiny-encoder.int8.onnx",
                "decoder" to "tiny-decoder.int8.onnx",
                "tokens" to "tiny-tokens.txt",
            ),
            license = "MIT (OpenAI Whisper)",
            sourceUrl = RELEASES + "sherpa-onnx-whisper-tiny.tar.bz2",
            archiveUrl = RELEASES + "sherpa-onnx-whisper-tiny.tar.bz2",
            downloadBytes = 116204861L,
            sha256 = mapOf(
                "tiny-encoder.int8.onnx" to "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
                "tiny-decoder.int8.onnx" to "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
                "tiny-tokens.txt" to "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
            notes = "Fastest Whisper; lower accuracy.",
            outputCase = OutputCase.MIXED,
            punctuates = true,
            maxInputSec = 28f,
        ),
    )

    fun byId(id: String?): ModelEntry? = entries.firstOrNull { it.id == id }

    fun forMode(mode: TranscriptionMode): List<ModelEntry> = entries.filter { mode in it.modes }

    /** Models offered to the user (developer-only comparison models are hidden in release builds). */
    fun visible(includeDeveloper: Boolean): List<ModelEntry> = entries.filter { includeDeveloper || !it.developerOnly }

    fun inspect(modelsDir: File): List<InstalledModel> = entries.map { inspect(modelsDir, it) }

    fun inspect(modelsDir: File, entry: ModelEntry): InstalledModel {
        val dir = File(modelsDir, entry.id)
        val missing = entry.files.values.filter { name -> File(dir, name).let { !it.isFile || it.length() == 0L } }
        val size = entry.files.values.sumOf { File(dir, it).takeIf(File::isFile)?.length() ?: 0L }
        return InstalledModel(entry, dir, missing, size)
    }

    fun vadFile(modelsDir: File): File = File(File(modelsDir, VAD_DIR), VAD_FILE)
}
