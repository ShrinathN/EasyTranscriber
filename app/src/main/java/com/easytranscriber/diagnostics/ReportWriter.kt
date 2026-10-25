package com.easytranscriber.diagnostics

import android.os.Build
import com.easytranscriber.batch.BatchResult
import com.easytranscriber.pipeline.SessionReport
import com.easytranscriber.recognition.WerResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes per-session measurement reports (JSON, no audio) used as test evidence. */
class ReportWriter(private val dir: File) {
    init {
        dir.mkdirs()
    }

    private fun base(kind: String) = JSONObject().apply {
        put("kind", kind)
        put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        put("device", "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
    }

    fun streaming(
        r: SessionReport,
        threads: Int,
        modelLoadMs: Long?,
        extras: Map<String, Any?> = emptyMap(),
        reference: String? = null,
        wer: WerResult? = null,
    ): File {
        val json = base("streaming").apply {
            put("model", r.modelId)
            put("threads", threads)
            put("modelLoadMs", modelLoadMs)
            put("source", r.source)
            put("testInput", r.isTestInput)
            put("frameMs", r.frameMs)
            put("audioSec", r.audioSec)
            put("wallSec", r.wallSec)
            put("decodeSec", r.decodeSec)
            put("rtf", r.rtf)
            put("decodeCalls", r.decodeCalls)
            put("framesReceived", r.framesReceived)
            put("framesDropped", r.framesDropped)
            put("maxQueueDepth", r.maxQueueDepth)
            put("frameLagMs", JSONObject(r.frameLagMs))
            put("tokenLatencyMs", JSONObject(r.tokenLatencyMs))
            put("firstTokenLatencyMs", r.firstTokenLatencyMs)
            put("partialUpdates", r.partialUpdates)
            put("revisionEvents", r.revisionEvents)
            put("revisedWords", r.revisedWords)
            put("segments", JSONArray(r.segments))
            put("pssKbSamples", JSONArray(r.pssKbSamples))
            put("cpuPercentOfOneCore", r.cpuPercentOfOneCore)
            put("error", r.error ?: JSONObject.NULL)
            put("streamStartWallMs", r.streamStartWallMs ?: JSONObject.NULL)
            extras.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
            if (wer != null) {
                put("reference", reference)
                put("wer", wer.wer)
                put("werDetail", JSONObject(mapOf(
                    "refWords" to wer.referenceWords, "sub" to wer.substitutions,
                    "del" to wer.deletions, "ins" to wer.insertions,
                )))
            }
        }
        return write("stream", json)
    }

    fun batch(
        r: BatchResult,
        modelId: String,
        threads: Int,
        language: String,
        modelLoadMs: Long?,
        recordedSec: Double,
        testInput: Boolean,
        pssKb: Long,
        pssKbSamples: List<Long> = emptyList(),
        reference: String? = null,
        wer: WerResult? = null,
    ): File {
        val json = base("batch").apply {
            put("model", modelId)
            put("threads", threads)
            put("language", language)
            put("detectedLanguage", r.detectedLanguage ?: JSONObject.NULL)
            put("modelLoadMs", modelLoadMs)
            put("testInput", testInput)
            put("recordedSec", recordedSec)
            put("audioSec", r.audioSec)
            put("speechSec", r.speechSec)
            put("segments", r.segments)
            put("processingSec", r.processingSec)
            put("rtf", if (r.audioSec > 0) r.processingSec / r.audioSec else JSONObject.NULL)
            put("pssKbAfter", pssKb)
            put("pssKbSamples", JSONArray(pssKbSamples))
            put("text", r.text)
            if (wer != null) {
                put("reference", reference)
                put("wer", wer.wer)
                put("werDetail", JSONObject(mapOf(
                    "refWords" to wer.referenceWords, "sub" to wer.substitutions,
                    "del" to wer.deletions, "ins" to wer.insertions,
                )))
            }
        }
        return write("batch", json)
    }

    private fun write(prefix: String, json: JSONObject): File =
        File(dir, "$prefix-${System.currentTimeMillis()}.json").apply { writeText(json.toString(1)) }
}
