package com.easytranscriber.dictation

import android.content.Context
import android.os.Debug
import android.os.StatFs
import com.easytranscriber.BuildConfig
import com.easytranscriber.audio.AudioCaptureException
import com.easytranscriber.audio.AudioRecorder
import com.easytranscriber.audio.AudioSource
import com.easytranscriber.audio.MicrophoneAudioSource
import com.easytranscriber.audio.WavFileAudioSource
import com.easytranscriber.audio.WavWriter
import com.easytranscriber.batch.BatchTranscriber
import com.easytranscriber.batch.NoSpeechException
import com.easytranscriber.batch.PauseDetector
import com.easytranscriber.batch.SileroSpeechDetector
import com.easytranscriber.batch.SileroSegmenter
import com.easytranscriber.bluetooth.BluetoothHidManager
import com.easytranscriber.bluetooth.HidConnectionState
import com.easytranscriber.diagnostics.AndroidResourceSampler
import com.easytranscriber.diagnostics.EventLog
import com.easytranscriber.diagnostics.ReportWriter
import com.easytranscriber.hid.EncodeResult
import com.easytranscriber.hid.KeystrokeSender
import com.easytranscriber.hid.TextEncoder
import com.easytranscriber.hid.UnsupportedChar
import com.easytranscriber.hid.UsQwertyLayout
import com.easytranscriber.output.DeliveryQueue
import com.easytranscriber.pipeline.StreamingRecognitionPipeline
import com.easytranscriber.models.ModelDownloader
import com.easytranscriber.recognition.Asr4AllEngine
import com.easytranscriber.recognition.InstalledModel
import com.easytranscriber.recognition.ModelEntry
import com.easytranscriber.recognition.ModelArchitecture
import com.easytranscriber.recognition.ModelCatalog
import com.easytranscriber.recognition.RecognitionEngine
import com.easytranscriber.recognition.RecognitionException
import com.easytranscriber.recognition.SherpaOnnxOfflineEngine
import com.easytranscriber.recognition.SherpaOnnxStreamingEngine
import com.easytranscriber.recognition.TestSet
import com.easytranscriber.recognition.TranscriptionMode
import com.easytranscriber.settings.AppSettings
import com.easytranscriber.settings.SettingsRepository
import com.easytranscriber.transcript.FormatOptions
import com.easytranscriber.transcript.StreamingTranscriptManager
import com.easytranscriber.transcript.TextFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** What the dictation session is doing. Exactly one state at a time. */
sealed interface SessionState {
    data object Idle : SessionState

    /** Loading the model before listening can start. */
    data class Preparing(val modelName: String) : SessionState

    /** Real-Time Dictation: [committed] is queued for typing; [pending] may still change. */
    data class Streaming(
        val committed: String,
        val pending: String,
        val levelDb: Float,
        val elapsedSec: Double,
        val postCommitRevisions: Int,
        val droppedFrames: Long,
        val finishing: Boolean,
    ) : SessionState

    /**
     * [stopsAfterPauseSec]: the recording ends by itself after a pause this long (null = only by button).
     * [keepListening]: each pause instead types the phrase and listening continues; [phrasesSent] and
     * [phrasesPending] count phrases typed and still being transcribed.
     */
    data class Recording(
        val elapsedSec: Double,
        val levelDb: Float,
        val maxSec: Int,
        val stopsAfterPauseSec: Double? = null,
        val keepListening: Boolean = false,
        val phrasesSent: Int = 0,
        val phrasesPending: Int = 0,
    ) : SessionState

    data class Transcribing(val fraction: Float, val segmentsDone: Int, val recordedSec: Double) : SessionState

    /** A finished transcript waiting for the user to send (possibly after editing) or discard. */
    data class Review(
        val text: String,
        val detectedLanguage: String?,
        val unsupported: List<UnsupportedChar>,
        val summary: String,
    ) : SessionState

    data class Failed(val message: String, val hint: String?) : SessionState
}

/** Readiness of the recognition engine for the currently selected mode. */
sealed interface EngineState {
    data class NotInstalled(val modelName: String, val missing: List<String>) : EngineState
    data object NotLoaded : EngineState
    data class Loading(val modelName: String) : EngineState
    data class Ready(val modelName: String, val loadMs: Long) : EngineState
    data class Failed(val message: String) : EngineState
}

/** Summary of the last completed session, shown under the controls. */
data class LastSession(val text: String, val details: String)

/**
 * Owns the dictation workflow for both modes and the shared keyboard output path.
 * Lives for the whole process (created by the Application); the UI only observes and calls it.
 */
class DictationController(
    private val context: Context,
    val hid: BluetoothHidManager,
    private val settingsRepository: SettingsRepository,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val filesDir = context.getExternalFilesDir(null)!!
    val modelsDir = File(filesDir, "models").apply { mkdirs() }
    val testSetsDir = File(filesDir, "testsets").apply { mkdirs() }
    private val recordingsDir = File(context.cacheDir, "recordings")
    private val reports = ReportWriter(File(filesDir, "reports"))

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private val _session = MutableStateFlow<SessionState>(SessionState.Idle)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _engine = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private val _models = MutableStateFlow<List<InstalledModel>>(emptyList())
    val models: StateFlow<List<InstalledModel>> = _models.asStateFlow()

    private val _lastSession = MutableStateFlow<LastSession?>(null)
    val lastSession: StateFlow<LastSession?> = _lastSession.asStateFlow()

    /** Diagnostics only: play a test set instead of using the microphone. Shown in the UI when set. */
    private val _testInput = MutableStateFlow<TestSet?>(null)
    val testInput: StateFlow<TestSet?> = _testInput.asStateFlow()

    /** User-initiated model downloads (the app's only network use). */
    val downloader = ModelDownloader(modelsDir, scope, log) { refreshModels() }

    private val formatter = TextFormatter()
    @Volatile private var sender = KeystrokeSender(hid)
    val delivery = DeliveryQueue(scope, UsQwertyLayout, send = { strokes -> sender.send(strokes) })

    private val engineMutex = Mutex()
    private var loadedEngine: RecognitionEngine? = null
    private var loadedKey: String? = null
    private var loadedMs: Long? = null

    private var sessionJob: Job? = null
    @Volatile private var pipeline: StreamingRecognitionPipeline? = null
    @Volatile private var recording = false
    @Volatile private var cancelled = false

    init {
        recordingsDir.deleteRecursively() // retention policy: no recording survives a restart
        refreshModels()
        scope.launch {
            var lastHost: String? = null
            hid.state.collect { s ->
                val connected = s is HidConnectionState.Connected
                if (connected) {
                    val host = (s as HidConnectionState.Connected).host.address
                    if (host != lastHost) formatter.reset() // new host: no spacing/sentence context
                    lastHost = host
                }
                delivery.onConnectionChanged(connected)
            }
        }
        scope.launch {
            settings.collect { s ->
                formatter.options = FormatOptions(s.spokenPunctuation, s.autoCapitalize)
                sender = KeystrokeSender(hid, s.typingSpeed.interReportDelayMs)
            }
        }
        // Keep the engine for the selected mode/model loaded so the first tap is fast; release others.
        scope.launch {
            settings.map { engineKey(it) to it }.distinctUntilChanged { a, b -> a.first == b.first }.collectLatest { (_, s) ->
                if (_session.value.isActive()) return@collectLatest
                prepareEngine(s)
            }
        }
        // Foreground service while the keyboard is registered or the microphone is in use.
        scope.launch {
            combine(hid.state, _session) { h, s ->
                val keyboard = h is HidConnectionState.Registered || h is HidConnectionState.Connecting ||
                    h is HidConnectionState.Connected
                val mic = s is SessionState.Streaming || s is SessionState.Recording
                keyboard to mic
            }.distinctUntilChanged().collect { (keyboard, mic) ->
                withContext(Dispatchers.Main) { DictationService.update(context, keyboard, mic) }
            }
        }
    }

    fun refreshModels() {
        ModelCatalog.entries.forEach { File(modelsDir, it.id).mkdirs() }
        File(modelsDir, ModelCatalog.VAD_DIR).mkdirs()
        _models.value = ModelCatalog.inspect(modelsDir)
        if (!_session.value.isActive()) scope.launch { prepareEngine(settings.value) }
    }

    val vadInstalled: Boolean get() = ModelCatalog.vadFile(modelsDir).let { it.isFile && it.length() > 0 }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        scope.launch { settingsRepository.update(transform) }
    }

    /** Debug builds only: replaces the microphone with a recorded test set. */
    fun setTestInput(set: TestSet?) {
        if (BuildConfig.DEBUG) _testInput.value = set
    }

    /** Deletes a model's files (releasing it first if loaded). Not allowed during a session. */
    fun deleteModel(entry: ModelEntry) {
        if (_session.value.isActive()) return
        scope.launch {
            engineMutex.withLock {
                if (loadedEngine?.model?.id == entry.id) {
                    loadedEngine?.close()
                    loadedEngine = null
                    loadedKey = null
                    _engine.value = EngineState.NotLoaded
                }
                File(modelsDir, entry.id).listFiles()?.forEach { it.delete() }
            }
            log.add("Model deleted: ${entry.id}")
            refreshModels()
        }
    }

    // ---- Real-Time Dictation ----

    fun startDictation() {
        if (!canStart()) return
        cancelled = false
        sessionJob = scope.launch {
            val s = settings.value
            val engine = engineForSession(s) ?: return@launch
            val model = engine.model
            formatter.startSession(s.spaceBetweenDictations)
            val manager = StreamingTranscriptManager(
                formatter, model.outputCase, stableCommitMs = s.commitDelay.stableMs,
            ) { delivery.enqueue(it) }
            val view = TranscriptView()
            val source = inputSource(engine.sampleRate)
            val p = StreamingRecognitionPipeline(
                engine, source,
                resourceSampler = AndroidResourceSampler(),
                onUpdate = { u ->
                    manager.onUpdate(u)
                    view.committed = manager.committedText
                    view.pending = manager.pendingText
                    view.revisions = manager.postCommitRevisions
                },
            )
            pipeline = p
            log.add("Dictation started: ${model.id}, ${source.description}")
            p.start(scope)
            val ui = launch {
                p.state.collect { snap ->
                    _session.value = SessionState.Streaming(
                        committed = view.committed,
                        pending = view.pending,
                        levelDb = snap.levelDb,
                        elapsedSec = snap.audioSec,
                        postCommitRevisions = view.revisions,
                        droppedFrames = snap.framesDropped,
                        finishing = snap.status == com.easytranscriber.pipeline.PipelineStatus.Stopping,
                    )
                }
            }
            val report = p.awaitReport()
            ui.cancel()
            pipeline = null
            manager.finish()
            val file = if (!BuildConfig.DEBUG) null else reports.streaming(
                report, s.threads, loadedMs,
                extras = mapOf(
                    "committedText" to manager.committedText,
                    "postCommitRevisions" to manager.postCommitRevisions,
                    "mode" to "dictation",
                    "commitDelay" to s.commitDelay.name,
                    "typingSpeed" to s.typingSpeed.name,
                    "extensions" to manager.extensions,
                    "commits" to org.json.JSONArray(manager.commits.map { c ->
                        org.json.JSONObject().put("wallMs", c.wallMs).put("words", org.json.JSONArray(c.words))
                            .put("wordEndSec", org.json.JSONArray(c.wordEndSec.map { it ?: org.json.JSONObject.NULL }))
                    }),
                ),
            )
            log.add("Dictation ended (${file?.name ?: "no report"}): ${"%.1f".format(report.audioSec)} s, " +
                "${manager.committedText.length} chars committed, ${manager.postCommitRevisions} post-commit revisions")
            val error = report.error
            _session.value = if (error != null) {
                SessionState.Failed(error, hintFor(error))
            } else {
                SessionState.Idle
            }
            _lastSession.value = LastSession(
                manager.committedText,
                buildString {
                    append("%.1f s · %d words".format(report.audioSec, manager.committedText.split(Regex("\\s+")).count { it.isNotBlank() }))
                    report.tokenLatencyMs["p50"]?.let { append(" · word latency ~%.0f ms".format(it)) }
                    if (manager.postCommitRevisions > 0) {
                        append(" · ${manager.postCommitRevisions} later recognizer corrections were not applied")
                    }
                },
            )
        }
    }

    fun stopDictation() {
        pipeline?.stop()
    }

    // ---- Record, Then Transcribe ----

    fun startRecording() {
        if (!canStart()) return
        val s = settings.value
        val installed = _models.value.firstOrNull { it.entry.id == s.batchModelId }
        if (installed == null || !installed.isInstalled) {
            _session.value = SessionState.Failed("The selected transcription model is not installed.", "Install it, or pick another model in Settings.")
            return
        }
        if (!vadInstalled) {
            _session.value = SessionState.Failed(
                "The speech segmenter (Silero VAD) is not installed.",
                "Install it with scripts/install-models.sh, then tap Rescan in Settings.",
            )
            return
        }
        val maxSec = s.maxRecordingMinutes * 60
        val needBytes = maxSec * 32_000L + 20_000_000L
        recordingsDir.mkdirs()
        val free = StatFs(recordingsDir.absolutePath).availableBytes
        if (free < needBytes) {
            _session.value = SessionState.Failed(
                "Not enough free storage to record (${free / 1_000_000} MB free, ${needBytes / 1_000_000} MB needed).",
                "Free up space or lower the maximum recording length in Settings.",
            )
            return
        }
        cancelled = false
        recording = true
        if (s.autoStopOnPause && s.keepListening) {
            recordContinuously(s, maxSec)
            return
        }
        sessionJob = scope.launch {
            val file = File(recordingsDir, "rec-${System.currentTimeMillis()}.wav")
            // Memory sampler for the session; must be cancelled on every exit path, otherwise this
            // job never completes and canStart() refuses all later sessions.
            var sampler: Job? = null
            val pauseSec = if (s.autoStopOnPause) s.pauseToStopMs / 1000.0 else null
            var speech: SileroSpeechDetector? = null
            try {
                // Load the model while the user is speaking.
                val engineLoad = launch { prepareEngine(s) }
                val pss = java.util.Collections.synchronizedList(ArrayList<Long>())
                sampler = launch(Dispatchers.IO) {
                    while (true) {
                        pss += Debug.getPss()
                        kotlinx.coroutines.delay(2_000)
                    }
                }
                _session.value = SessionState.Recording(0.0, -60f, maxSec, pauseSec)
                val source = inputSource(16_000)
                // Auto-stop: Silero VAD watches the live audio; a pause after speech ends the recording.
                speech = pauseSec?.let { SileroSpeechDetector(ModelCatalog.vadFile(modelsDir), source.sampleRate) }
                val pauses = pauseSec?.let(::PauseDetector)
                var heardSamples = 0L
                var stoppedOnPause = false
                log.add("Recording started (${source.description})${pauseSec?.let { ", stops after a ${"%.1f".format(it)} s pause" } ?: ""}")
                val rec = withContext(Dispatchers.IO) {
                    AudioRecorder(source, maxSec).record(
                        file,
                        isActive = { recording && !cancelled },
                        onSamples = { samples ->
                            val detector = speech
                            if (detector != null && pauses != null && recording) {
                                heardSamples += samples.size
                                if (pauses.update(heardSamples.toDouble() / source.sampleRate, detector.accept(samples))) {
                                    stoppedOnPause = true
                                    recording = false
                                }
                            }
                        },
                    ) { sec, db ->
                        _session.value = SessionState.Recording(sec, db, maxSec, pauseSec)
                    }
                }
                speech?.close()
                speech = null
                recording = false
                if (cancelled) {
                    log.add("Recording cancelled; audio discarded")
                    _session.value = SessionState.Idle
                    return@launch
                }
                val why = when {
                    rec.reachedLimit -> " (limit reached)"
                    stoppedOnPause -> " (pause detected)"
                    else -> ""
                }
                log.add("Recording stopped: ${"%.1f".format(rec.durationSec)} s$why")
                _session.value = SessionState.Transcribing(0f, 0, rec.durationSec)
                engineLoad.join()
                val engine = engineForSession(s) ?: return@launch
                val vad = ModelCatalog.vadFile(modelsDir)
                val result = withContext(Dispatchers.Default) {
                    BatchTranscriber(engine) { SileroSegmenter(vad, maxSegmentSec = engine.model.maxInputSec ?: 28f) }
                        .transcribe(file, { cancelled }) { p ->
                            _session.value = SessionState.Transcribing(p.fraction, p.segmentsDone, rec.durationSec)
                        }
                }
                sampler?.cancel()
                pss += Debug.getPss()
                val reportFile = if (!BuildConfig.DEBUG) null else reports.batch(
                    result, engine.model.id, s.threads, s.batchLanguage, loadedMs, rec.durationSec,
                    source.isTestInput, Debug.getPss(), pssKbSamples = pss.toList(),
                )
                log.add("Transcribed ${"%.1f".format(result.audioSec)} s in ${"%.1f".format(result.processingSec)} s " +
                    "(${result.segments} segments)${reportFile?.let { " → ${it.name}" } ?: ""}")
                val unsupported = (TextEncoder.encode(result.text, UsQwertyLayout) as? EncodeResult.Unsupported)?.chars.orEmpty()
                val summary = "%.1f s recorded · transcribed in %.1f s".format(rec.durationSec, result.processingSec) +
                    (result.detectedLanguage?.let { " · language: $it" } ?: "")
                if (s.reviewBeforeSending || unsupported.isNotEmpty()) {
                    _session.value = SessionState.Review(result.text, result.detectedLanguage, unsupported, summary)
                } else {
                    deliverBatch(result.text, s)
                    _lastSession.value = LastSession(result.text, summary)
                    _session.value = SessionState.Idle
                }
            } catch (e: BatchTranscriber.CancellationRequested) {
                log.add("Transcription cancelled")
                _session.value = SessionState.Idle
            } catch (e: NoSpeechException) {
                _session.value = SessionState.Failed(e.message!!, "Speak closer to the microphone and try again.")
            } catch (e: AudioCaptureException) {
                _session.value = SessionState.Failed(e.message ?: "Recording failed", hintFor(e.message ?: ""))
            } catch (e: RecognitionException) {
                _session.value = SessionState.Failed(e.message ?: "Transcription failed", "Try again, or choose another model in Settings.")
            } catch (e: OutOfMemoryError) {
                _session.value = SessionState.Failed("The phone ran out of memory while transcribing.", "Use a smaller model or shorter recordings.")
            } finally {
                sampler?.cancel()
                speech?.close()
                recording = false
                // Retention policy: recorded audio is deleted as soon as transcription ends,
                // whether it succeeded, failed or was cancelled.
                if (file.exists()) file.delete()
            }
        }
    }

    /**
     * Record first, keep listening: one microphone session split into phrases at pauses. Each phrase
     * is transcribed and typed in the background while listening continues, until the user stops.
     * [maxSec] limits a single phrase, not the session.
     */
    private fun recordContinuously(s: AppSettings, maxSec: Int) {
        sessionJob = scope.launch {
            val pauseSec = s.pauseToStopMs / 1000.0
            val phrases = Channel<WavWriter>(Channel.UNLIMITED)
            val sent = AtomicInteger(0)
            val pending = AtomicInteger(0)
            val failure = AtomicReference<SessionState.Failed?>(null)
            val typed = java.util.Collections.synchronizedList(ArrayList<String>())
            var speech: SileroSpeechDetector? = null
            var writer: WavWriter? = null
            var listenedSec = 0.0
            fun fail(f: SessionState.Failed) {
                failure.compareAndSet(null, f)
                recording = false
            }
            try {
                // Load the model while the user is speaking.
                val engineLoad = launch { prepareEngine(s) }
                // Transcribes phrases one at a time, in order, while capture goes on.
                val worker = launch(Dispatchers.Default) {
                    engineLoad.join()
                    val engine = engineForSession(s)
                    if (engine == null) {
                        fail(_session.value as? SessionState.Failed ?: SessionState.Failed("The recognition model is not available.", null))
                    }
                    val vad = ModelCatalog.vadFile(modelsDir)
                    for (phrase in phrases) {
                        try {
                            if (engine == null || cancelled || failure.get() != null) continue
                            val result = BatchTranscriber(engine) { SileroSegmenter(vad, maxSegmentSec = engine.model.maxInputSec ?: 28f) }
                                .transcribe(phrase.file, { cancelled }) {}
                            if (cancelled) continue
                            deliverBatch(result.text, s)
                            typed += result.text
                            sent.incrementAndGet()
                            log.add("Phrase transcribed: ${"%.1f".format(result.audioSec)} s in ${"%.1f".format(result.processingSec)} s")
                        } catch (e: NoSpeechException) {
                            log.add("Phrase skipped: no speech detected")
                        } catch (e: BatchTranscriber.CancellationRequested) {
                            // Discarded with the rest of the session.
                        } catch (e: RecognitionException) {
                            fail(SessionState.Failed(e.message ?: "Transcription failed", "Try again, or choose another model in Settings."))
                        } catch (e: OutOfMemoryError) {
                            fail(SessionState.Failed("The phone ran out of memory while transcribing.", "Use a smaller model or shorter phrases."))
                        } finally {
                            phrase.file.delete()
                            pending.decrementAndGet()
                        }
                    }
                }

                _session.value = SessionState.Recording(0.0, -60f, maxSec, pauseSec, keepListening = true)
                val source = inputSource(16_000)
                val sr = source.sampleRate
                val maxPhraseSamples = maxSec.toLong() * sr
                speech = SileroSpeechDetector(ModelCatalog.vadFile(modelsDir), sr)
                var pauses = PauseDetector(pauseSec)
                var phraseHasSpeech = false
                var phraseIndex = 0
                fun newWriter() = try {
                    WavWriter(File(recordingsDir, "rec-${System.currentTimeMillis()}-${phraseIndex++}.wav"), sr)
                } catch (e: IOException) {
                    throw AudioCaptureException("Could not create the recording file: ${e.message}", e)
                }
                // Hands the current phrase to the worker (or drops it if silent) and starts the next.
                fun endPhrase(why: String, startNext: Boolean) {
                    val w = writer ?: return
                    writer = null
                    try {
                        w.close()
                    } catch (e: IOException) {
                        throw AudioCaptureException("Could not finish the recording file: ${e.message}", e)
                    }
                    if (phraseHasSpeech && !cancelled) {
                        log.add("Phrase recorded: ${"%.1f".format(w.durationSec)} s ($why)")
                        pending.incrementAndGet()
                        phrases.trySend(w)
                    } else {
                        w.file.delete()
                    }
                    phraseHasSpeech = false
                    pauses = PauseDetector(pauseSec)
                    if (startNext) writer = newWriter()
                }

                log.add("Listening started (${source.description}), phrases end after a ${"%.1f".format(pauseSec)} s pause")
                writer = newWriter()
                withContext(Dispatchers.IO) {
                    source.run(sr / 10, { recording && !cancelled }) { frame ->
                        val w = writer ?: return@run
                        try {
                            w.write(frame.samples)
                        } catch (e: IOException) {
                            throw AudioCaptureException("Recording stopped: storage is full or unavailable (${e.message})", e)
                        }
                        listenedSec += frame.samples.size.toDouble() / sr
                        val isSpeech = speech!!.accept(frame.samples)
                        if (isSpeech) phraseHasSpeech = true
                        when {
                            pauses.update(w.durationSec, isSpeech) -> endPhrase("pause detected", startNext = true)
                            w.samplesWritten >= maxPhraseSamples -> endPhrase("limit reached", startNext = true)
                        }
                        if (failure.get() == null) {
                            _session.value = SessionState.Recording(
                                listenedSec, AudioRecorder.levelDb(frame.samples), maxSec, pauseSec,
                                keepListening = true, phrasesSent = sent.get(), phrasesPending = pending.get(),
                            )
                        }
                    }
                }
                speech?.close()
                speech = null
                endPhrase("stopped", startNext = false)
                recording = false
                phrases.close()
                if (cancelled) {
                    log.add("Listening cancelled; untyped phrases discarded")
                } else if (failure.get() == null && pending.get() > 0) {
                    _session.value = SessionState.Transcribing(0f, sent.get(), listenedSec)
                }
                worker.join()
                log.add("Listening stopped: ${"%.1f".format(listenedSec)} s, ${sent.get()} phrases typed")
                _session.value = failure.get() ?: SessionState.Idle
                if (typed.isNotEmpty()) {
                    _lastSession.value = LastSession(
                        BatchTranscriber.joinSegments(typed.toList()),
                        "%.1f s listened · %d phrases typed".format(listenedSec, sent.get()),
                    )
                }
            } catch (e: AudioCaptureException) {
                cancelled = true
                _session.value = SessionState.Failed(e.message ?: "Recording failed", hintFor(e.message ?: ""))
            } finally {
                phrases.close()
                speech?.close()
                try {
                    writer?.close()
                } catch (_: IOException) {
                }
                recording = false
                // Retention policy: no phrase audio outlives the session.
                recordingsDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    fun stopRecording() {
        recording = false
    }

    /** Cancels recording (audio discarded) or transcription. Never sends anything. */
    fun cancel() {
        cancelled = true
        recording = false
        pipeline?.stop()
    }

    fun sendReviewed(text: String) {
        val s = settings.value
        if (_session.value !is SessionState.Review) return
        deliverBatch(text, s)
        _lastSession.value = LastSession(text, "Sent after review")
        _session.value = SessionState.Idle
    }

    fun discardReview() {
        if (_session.value is SessionState.Review) {
            log.add("Reviewed transcript discarded")
            _session.value = SessionState.Idle
        }
    }

    fun dismissError() {
        if (_session.value is SessionState.Failed) _session.value = SessionState.Idle
    }

    private fun deliverBatch(text: String, s: AppSettings) {
        val out = formatter.external(text, s.spaceBetweenDictations)
        delivery.enqueue(out)
        log.add("Batch transcript queued: ${out.length} chars")
    }

    // ---- Engines ----

    private fun engineKey(s: AppSettings): String = when (s.mode) {
        TranscriptionMode.REAL_TIME -> "${s.streamingModelId}|${s.threads}"
        TranscriptionMode.RECORD_THEN_TRANSCRIBE -> "${s.batchModelId}|${s.threads}|${s.batchLanguage}"
    }

    /** Loads (or reuses) the engine for [s]; keeps only one engine in memory. */
    private suspend fun prepareEngine(s: AppSettings): RecognitionEngine? = engineMutex.withLock {
        val key = engineKey(s)
        loadedEngine?.let { if (loadedKey == key) return it }
        val modelId = if (s.mode == TranscriptionMode.REAL_TIME) s.streamingModelId else s.batchModelId
        val installed = _models.value.firstOrNull { it.entry.id == modelId }
            ?: ModelCatalog.byId(modelId)?.let { ModelCatalog.inspect(modelsDir, it) }
            ?: return null
        if (!installed.isInstalled) {
            _engine.value = EngineState.NotInstalled(installed.entry.displayName, installed.missingFiles)
            return null
        }
        loadedEngine?.close()
        loadedEngine = null
        loadedKey = null
        _engine.value = EngineState.Loading(installed.entry.shortName)
        val e: RecognitionEngine = when {
            TranscriptionMode.REAL_TIME in installed.entry.modes -> SherpaOnnxStreamingEngine(installed, numThreads = s.threads)
            installed.entry.architecture == ModelArchitecture.ASR4ALL -> Asr4AllEngine(installed, numThreads = s.threads)
            else -> {
                val lang = if (installed.entry.architecture == ModelArchitecture.WHISPER) s.batchLanguage else "en"
                SherpaOnnxOfflineEngine(installed, numThreads = s.threads, language = lang)
            }
        }
        val t0 = System.nanoTime()
        return try {
            withContext(Dispatchers.Default) { e.load() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            loadedEngine = e
            loadedKey = key
            loadedMs = ms
            _engine.value = EngineState.Ready(installed.entry.shortName, ms)
            log.add("Model ready: ${installed.entry.id} (threads=${s.threads}) in $ms ms")
            e
        } catch (ex: RecognitionException) {
            _engine.value = EngineState.Failed(ex.message ?: "Model failed to load")
            log.add("Model load failed: ${ex.message}")
            null
        }
    }

    /**
     * Engine for a session that is starting; shows Preparing while loading and turns any
     * load problem into an actionable Failed state.
     */
    private suspend fun engineForSession(s: AppSettings): RecognitionEngine? {
        if (loadedKey != engineKey(s) && _session.value !is SessionState.Recording) {
            val id = if (s.mode == TranscriptionMode.REAL_TIME) s.streamingModelId else s.batchModelId
            _session.value = SessionState.Preparing(ModelCatalog.byId(id)?.displayName ?: id)
        }
        val e = prepareEngine(s)
        if (e == null) {
            _session.value = when (val st = _engine.value) {
                is EngineState.NotInstalled -> SessionState.Failed("${st.modelName} is not installed.", "Install it, or pick another model in Settings.")
                is EngineState.Failed -> SessionState.Failed(st.message, "Reinstall the model files or choose another model.")
                else -> SessionState.Failed("The recognition model is not available.", "Check Settings → Models.")
            }
        }
        return e
    }

    private class TranscriptView {
        @Volatile var committed = ""
        @Volatile var pending = ""
        @Volatile var revisions = 0
    }

    // ---- Helpers ----

    private fun canStart(): Boolean {
        val s = _session.value
        if (s.isActive() || s is SessionState.Review) return false
        if (sessionJob?.isActive == true) {
            log.add("Start ignored: the previous session is still finishing")
            return false
        }
        _session.value = SessionState.Idle
        return true
    }

    private fun inputSource(sampleRate: Int): AudioSource =
        _testInput.value?.let { WavFileAudioSource(it.files, sampleRate) } ?: MicrophoneAudioSource(sampleRate)

    private fun hintFor(error: String): String = when {
        error.contains("microphone", ignoreCase = true) ->
            "Check that no other app is using the microphone and that the permission is granted, then try again."
        error.contains("storage", ignoreCase = true) -> "Free up storage space and try again."
        else -> "Try again. If it keeps failing, restart the app."
    }
}

fun SessionState.isActive(): Boolean = when (this) {
    is SessionState.Preparing, is SessionState.Streaming, is SessionState.Recording, is SessionState.Transcribing -> true
    else -> false
}
