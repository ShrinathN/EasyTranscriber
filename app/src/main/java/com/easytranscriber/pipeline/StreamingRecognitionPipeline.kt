package com.easytranscriber.pipeline

import com.easytranscriber.audio.AudioCaptureException
import com.easytranscriber.audio.AudioFrame
import com.easytranscriber.audio.AudioRecorder
import com.easytranscriber.audio.AudioSource
import com.easytranscriber.recognition.Hypothesis
import com.easytranscriber.recognition.RecognitionEngine
import com.easytranscriber.recognition.StreamingUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Process resource readings; implemented with Android APIs in production. */
fun interface ResourceSampler {
    /** Returns (process PSS in KiB, process CPU time in ms). */
    fun sample(): Pair<Long, Long>
}

sealed interface PipelineStatus {
    data object Running : PipelineStatus
    data object Stopping : PipelineStatus
    data object Finished : PipelineStatus
    data class Failed(val message: String) : PipelineStatus
}

data class PipelineSnapshot(
    val status: PipelineStatus,
    val finalized: List<String>,
    val partial: String,
    val audioSec: Double,
    val framesDropped: Long,
    val lastLagMs: Double?,
    /** Input level of the latest frame in dBFS (-60..0). */
    val levelDb: Float = -60f,
)

data class SessionReport(
    val modelId: String,
    val source: String,
    val isTestInput: Boolean,
    val frameMs: Int,
    val audioSec: Double,
    val wallSec: Double,
    val decodeSec: Double,
    val decodeCalls: Long,
    val framesReceived: Long,
    val framesDropped: Long,
    val maxQueueDepth: Int,
    val frameLagMs: Map<String, Double?>,
    val tokenLatencyMs: Map<String, Double?>,
    val firstTokenLatencyMs: Double?,
    val partialUpdates: Long,
    val revisionEvents: Long,
    val revisedWords: Long,
    val segments: List<String>,
    val pssKbSamples: List<Long>,
    val cpuPercentOfOneCore: Double?,
    val error: String?,
    /** Wall-clock time (ms) corresponding to audio time 0 of the stream. */
    val streamStartWallMs: Long? = null,
) {
    /** Real-time factor of decoding alone: decode time / audio time (< 1 means faster than real time). */
    val rtf: Double get() = if (audioSec > 0) decodeSec / audioSec else Double.NaN
    val text: String get() = segments.joinToString(" ")
}

/**
 * Microphone (or test source) -> bounded queue -> single inference thread.
 *
 * Capture never blocks on inference: if the queue (default 2 s of audio) is full, the new frame
 * is dropped and counted, and the drop is surfaced in state and reports.
 */
class StreamingRecognitionPipeline(
    private val engine: RecognitionEngine,
    private val source: AudioSource,
    private val frameMs: Int = 50,
    queueSeconds: Float = 2f,
    private val resourceSampler: ResourceSampler? = null,
    private val resourceSampleIntervalMs: Long = 2_000,
    /** Called on the inference thread with every recognizer update, in order. */
    private val onUpdate: ((StreamingUpdate) -> Unit)? = null,
) {
    private val frameSamples = engine.sampleRate * frameMs / 1000
    private val queueCapacity = maxOf(1, (queueSeconds * 1000 / frameMs).toInt())
    private val channel = Channel<AudioFrame>(queueCapacity)
    private val queueDepth = AtomicInteger(0)
    private val dropped = AtomicLong(0)

    @Volatile private var capturing = true
    private val report = CompletableDeferred<SessionReport>()

    private val _state = MutableStateFlow(
        PipelineSnapshot(PipelineStatus.Running, emptyList(), "", 0.0, 0, null)
    )
    val state: StateFlow<PipelineSnapshot> = _state.asStateFlow()

    // Metrics; written only by the inference thread except where atomic.
    private val frameLag = SampleStats()
    private val tokenLatency = SampleStats()
    private val tracker = HypothesisTracker()
    private val segments = ArrayList<String>()
    private val pss = ArrayList<Long>()
    @Volatile private var maxDepth = 0
    private var currentPartial = ""
    private var framesReceived = 0L
    private var decodeNanos = 0L
    private var decodeCalls = 0L
    private var firstTokenLatencyMs: Double? = null
    @Volatile private var streamStartWallMs: Long? = null

    fun start(scope: CoroutineScope) {
        require(engine.isLoaded) { "Engine must be loaded before starting" }
        val inference = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-inference") }.asCoroutineDispatcher()
        val startedNanos = System.nanoTime()
        var cpuStart: Long? = null
        var cpuEnd: Long? = null
        var error: String? = null

        val sampler: Job? = resourceSampler?.let { s ->
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val (kb, cpuMs) = s.sample()
                    synchronized(pss) { pss += kb }
                    if (cpuStart == null) cpuStart = cpuMs
                    cpuEnd = cpuMs
                    delay(resourceSampleIntervalMs)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                source.run(frameSamples, { capturing }) { frame ->
                    if (channel.trySend(frame).isSuccess) {
                        val d = queueDepth.incrementAndGet()
                        if (d > maxDepth) maxDepth = d
                    } else {
                        dropped.incrementAndGet()
                    }
                }
            } catch (e: AudioCaptureException) {
                error = e.message
            } catch (e: Exception) {
                error = "Audio capture failed: ${e.message}"
            } finally {
                channel.close()
            }
        }

        scope.launch(inference) {
            var streamStartNanos: Long? = null
            try {
                engine.createStreamingSession().use { session ->
                    for (frame in channel) {
                        queueDepth.decrementAndGet()
                        framesReceived++
                        if (streamStartNanos == null) {
                            streamStartNanos = frame.endTimeNanos - frame.samples.size * 1_000_000_000L / engine.sampleRate
                            streamStartWallMs = System.currentTimeMillis() - (System.nanoTime() - streamStartNanos!!) / 1_000_000
                        }
                        val update = session.accept(frame.samples)
                        val now = System.nanoTime()
                        val lagMs = (now - frame.endTimeNanos) / 1e6
                        frameLag.add(lagMs)
                        handle(update, now, streamStartNanos!!)
                        onUpdate?.invoke(update)
                        publish(PipelineStatus.Running, frame, lagMs)
                    }
                    _state.update { it.copy(status = PipelineStatus.Stopping) }
                    val last = session.finish()
                    handle(last, System.nanoTime(), streamStartNanos ?: System.nanoTime())
                    onUpdate?.invoke(last)
                }
            } catch (e: Exception) {
                error = error ?: "Recognition failed: ${e.message}"
                capturing = false
            } finally {
                sampler?.cancel()
                resourceSampler?.let { s -> val (kb, cpuMs) = s.sample(); synchronized(pss) { pss += kb }; cpuEnd = cpuMs }
                val wallSec = (System.nanoTime() - startedNanos) / 1e9
                val audioSec = framesReceived * frameMs / 1000.0
                val cpuPct = cpuStart?.let { a -> cpuEnd?.let { b -> 100.0 * (b - a) / 1000.0 / wallSec } }
                val finalStatus = error?.let { PipelineStatus.Failed(it) } ?: PipelineStatus.Finished
                _state.value = PipelineSnapshot(
                    finalStatus, segments.toList(), "", audioSec, dropped.get(), null
                )
                report.complete(
                    SessionReport(
                        modelId = engine.model.id,
                        source = source.description,
                        isTestInput = source.isTestInput,
                        frameMs = frameMs,
                        audioSec = audioSec,
                        wallSec = wallSec,
                        decodeSec = decodeNanos / 1e9,
                        decodeCalls = decodeCalls,
                        framesReceived = framesReceived,
                        framesDropped = dropped.get(),
                        maxQueueDepth = maxDepth,
                        frameLagMs = frameLag.summary(),
                        tokenLatencyMs = tokenLatency.summary(),
                        firstTokenLatencyMs = firstTokenLatencyMs,
                        partialUpdates = tracker.partialUpdates,
                        revisionEvents = tracker.revisionEvents,
                        revisedWords = tracker.revisedWords,
                        segments = segments.toList(),
                        pssKbSamples = synchronized(pss) { pss.toList() },
                        cpuPercentOfOneCore = cpuPct,
                        error = error,
                        streamStartWallMs = streamStartWallMs,
                    )
                )
                inference.close()
            }
        }
    }

    /** Stops capture; queued audio is still recognized and the session is flushed. */
    fun stop() {
        capturing = false
    }

    suspend fun awaitReport(): SessionReport = report.await()

    private fun handle(update: StreamingUpdate, now: Long, streamStartNanos: Long) {
        decodeNanos += update.decodeNanos
        decodeCalls += update.decodeCalls
        update.finalized?.let { fin ->
            recordNewTokens(fin, tracker.onFinal(words(fin), fin.tokens.size), now, streamStartNanos)
            segments += fin.text
        }
        val p = update.partial
        currentPartial = p.text
        if (!p.isEmpty) {
            recordNewTokens(p, tracker.onPartial(words(p), p.tokens.size), now, streamStartNanos)
        }
    }

    private fun recordNewTokens(h: Hypothesis, newCount: Int, now: Long, streamStartNanos: Long) {
        if (newCount <= 0) return
        for (t in h.tokens.takeLast(newCount)) {
            val spokenAt = streamStartNanos + (t.audioTimeSec * 1e9).toLong()
            val ms = (now - spokenAt) / 1e6
            tokenLatency.add(ms)
            if (firstTokenLatencyMs == null) firstTokenLatencyMs = ms
        }
    }

    private fun publish(status: PipelineStatus, frame: AudioFrame, lagMs: Double) {
        _state.value = PipelineSnapshot(
            status = status,
            finalized = segments.toList(),
            partial = currentPartial,
            audioSec = (frame.index + 1) * frameMs / 1000.0,
            framesDropped = dropped.get(),
            lastLagMs = lagMs,
            levelDb = AudioRecorder.levelDb(frame.samples),
        )
    }

    private fun words(h: Hypothesis) = h.text.split(' ').filter { it.isNotEmpty() }
}
