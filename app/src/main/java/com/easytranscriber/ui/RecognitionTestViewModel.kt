package com.easytranscriber.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easytranscriber.EasyTranscriberApp
import com.easytranscriber.audio.AudioSource
import com.easytranscriber.audio.MicrophoneAudioSource
import com.easytranscriber.audio.WavFileAudioSource
import com.easytranscriber.diagnostics.AndroidResourceSampler
import com.easytranscriber.diagnostics.ReportWriter
import com.easytranscriber.pipeline.PipelineSnapshot
import com.easytranscriber.pipeline.SessionReport
import com.easytranscriber.pipeline.StreamingRecognitionPipeline
import com.easytranscriber.recognition.InstalledModel
import com.easytranscriber.recognition.ModelCatalog
import com.easytranscriber.recognition.RecognitionEngine
import com.easytranscriber.recognition.RecognitionException
import com.easytranscriber.recognition.SherpaOnnxStreamingEngine
import com.easytranscriber.recognition.TestSet
import com.easytranscriber.recognition.WordErrorRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface EngineStatus {
    data object NotLoaded : EngineStatus
    data object Loading : EngineStatus
    data class Loaded(val loadMs: Long) : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/** Input for a recognition test session: the live microphone or a test set played back. */
sealed interface InputChoice {
    data object Microphone : InputChoice
    data class Playback(val testSet: TestSet) : InputChoice
}

/** Drives the Stage 3 streaming recognition test screen. */
class RecognitionTestViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyTranscriberApp
    private val log = app.eventLog
    private val modelsDir = File(application.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val testSetsDir = File(application.getExternalFilesDir(null), "testsets").apply { mkdirs() }
    private val reports = ReportWriter(File(application.getExternalFilesDir(null), "reports"))

    private val _models = MutableStateFlow<List<InstalledModel>>(emptyList())
    val models: StateFlow<List<InstalledModel>> = _models.asStateFlow()

    private val _testSets = MutableStateFlow<List<TestSet>>(emptyList())
    val testSets: StateFlow<List<TestSet>> = _testSets.asStateFlow()

    private val _selectedModelId = MutableStateFlow<String?>(null)
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

    private val _threads = MutableStateFlow(2)
    val threads: StateFlow<Int> = _threads.asStateFlow()

    private val _input = MutableStateFlow<InputChoice>(InputChoice.Microphone)
    val input: StateFlow<InputChoice> = _input.asStateFlow()

    private val _engineStatus = MutableStateFlow<EngineStatus>(EngineStatus.NotLoaded)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    private val _session = MutableStateFlow<PipelineSnapshot?>(null)
    val session: StateFlow<PipelineSnapshot?> = _session.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastReport = MutableStateFlow<String?>(null)
    val lastReport: StateFlow<String?> = _lastReport.asStateFlow()

    val modelsPath: String get() = modelsDir.absolutePath
    val testSetsPath: String get() = testSetsDir.absolutePath

    private var engine: RecognitionEngine? = null
    private var engineKey: Pair<String, Int>? = null
    private var pipeline: StreamingRecognitionPipeline? = null
    private var sessionJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        // Create app-owned folders so models can be copied in (files are readable only in
        // folders the app itself owns).
        ModelCatalog.entries.forEach { File(modelsDir, it.id).mkdirs() }
        val installed = ModelCatalog.inspect(modelsDir)
        _models.value = installed
        _testSets.value = TestSet.loadAll(testSetsDir)
        if (_selectedModelId.value == null) {
            _selectedModelId.value = installed.firstOrNull { it.isInstalled }?.entry?.id
        }
    }

    fun selectModel(id: String) {
        if (_running.value) return
        _selectedModelId.value = id
    }

    fun selectThreads(n: Int) {
        if (_running.value) return
        _threads.value = n
    }

    fun selectInput(choice: InputChoice) {
        if (_running.value) return
        _input.value = choice
    }

    fun start() {
        if (_running.value) return
        val installed = _models.value.firstOrNull { it.entry.id == _selectedModelId.value } ?: return
        val choice = _input.value
        _running.value = true
        sessionJob = viewModelScope.launch {
            try {
                val e = ensureEngine(installed) ?: return@launch
                val source: AudioSource = when (choice) {
                    InputChoice.Microphone -> MicrophoneAudioSource(e.sampleRate)
                    is InputChoice.Playback -> WavFileAudioSource(choice.testSet.files, e.sampleRate)
                }
                val p = StreamingRecognitionPipeline(e, source, resourceSampler = AndroidResourceSampler())
                pipeline = p
                log.add("Streaming session started: ${installed.entry.id}, ${source.description}, threads=${_threads.value}")
                p.start(viewModelScope)
                val collector = launch { p.state.collect { _session.value = it } }
                val report = p.awaitReport()
                collector.cancel()
                _session.value = p.state.value
                val reference = (choice as? InputChoice.Playback)?.testSet?.combinedReference
                onReport(report, reference, choice)
            } finally {
                pipeline = null
                _running.value = false
            }
        }
    }

    fun stop() {
        pipeline?.stop()
    }

    private suspend fun ensureEngine(installed: InstalledModel): RecognitionEngine? {
        val key = installed.entry.id to _threads.value
        engine?.let { if (engineKey == key && it.isLoaded) return it }
        engine?.close()
        engine = null
        _engineStatus.value = EngineStatus.Loading
        return withContext(Dispatchers.Default) {
            val e = SherpaOnnxStreamingEngine(installed, numThreads = _threads.value)
            val t0 = System.nanoTime()
            try {
                e.load()
                val ms = (System.nanoTime() - t0) / 1_000_000
                engine = e
                engineKey = key
                _engineStatus.value = EngineStatus.Loaded(ms)
                log.add("Model loaded: ${installed.entry.id} threads=${_threads.value} in $ms ms")
                e
            } catch (ex: RecognitionException) {
                _engineStatus.value = EngineStatus.Failed(ex.message ?: "unknown error")
                log.add("Model load failed: ${ex.message}")
                null
            }
        }
    }

    private suspend fun onReport(r: SessionReport, reference: String?, choice: InputChoice) {
        val wer = reference?.let { WordErrorRate.compute(it, r.text) }
        val loadMs = (engineStatus.value as? EngineStatus.Loaded)?.loadMs
        val file = withContext(Dispatchers.IO) {
            reports.streaming(
                r, _threads.value, loadMs,
                extras = mapOf("mode" to "benchmark", "testSet" to (choice as? InputChoice.Playback)?.testSet?.name),
                reference = reference, wer = wer,
            )
        }
        val summary = buildString {
            if (r.error != null) appendLine("ERROR: ${r.error}")
            appendLine("Audio %.1f s, decode %.2f s, RTF %.3f".format(r.audioSec, r.decodeSec, r.rtf))
            appendLine("Token latency ms: p50 %s, p90 %s, max %s".format(
                fmt(r.tokenLatencyMs["p50"]), fmt(r.tokenLatencyMs["p90"]), fmt(r.tokenLatencyMs["max"])))
            appendLine("Frame lag ms: p50 %s, p90 %s, max %s".format(
                fmt(r.frameLagMs["p50"]), fmt(r.frameLagMs["p90"]), fmt(r.frameLagMs["max"])))
            appendLine("Dropped frames ${r.framesDropped}, max queue ${r.maxQueueDepth}")
            appendLine("Revisions: ${r.revisionEvents} events, ${r.revisedWords} words; ${r.segments.size} segments")
            r.pssKbSamples.maxOrNull()?.let { appendLine("Peak PSS %.0f MB, CPU %s%% of one core".format(it / 1024.0, fmt(r.cpuPercentOfOneCore))) }
            wer?.let { appendLine("WER %.1f%% (%d/%d)".format(it.wer * 100, it.errors, it.referenceWords)) }
            append("Report: ${file.name}")
        }
        _lastReport.value = summary
        log.add("Session report ${file.name}: " + summary.replace("\n", " | "))
    }

    private fun fmt(v: Double?) = v?.let { "%.0f".format(it) } ?: "-"

    override fun onCleared() {
        // Never release native recognizer memory while the inference thread may still use it.
        val active = pipeline
        if (active == null) engine?.close() else active.stop()
    }
}
