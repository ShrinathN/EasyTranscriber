package com.easytranscriber.recognition

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

/**
 * ASR4ALL (FUTO) via ONNX Runtime: a CTC acoustic model run as fixed windows with carried
 * caches, followed by PCEC, a second pass over the recognized tokens that adds punctuation and
 * capitalization and corrects errors. A port of the release's reference runner (onnx/asr4all_onnx.py)
 * for whole utterances; tensor shapes are read from the graphs, not assumed.
 */
class Asr4AllEngine(
    private val installed: InstalledModel,
    private val numThreads: Int = 2,
) : RecognitionEngine {

    override val model: ModelEntry get() = installed.entry
    override val sampleRate: Int = 16_000

    @Volatile private var graphs: Graphs? = null
    override val isLoaded: Boolean get() = graphs != null

    override fun load() {
        if (graphs != null) return
        if (!installed.isInstalled) {
            throw RecognitionException(
                "Model files missing in ${installed.directory}: ${installed.missingFiles.joinToString()}"
            )
        }
        try {
            graphs = Graphs(installed, numThreads)
        } catch (t: Throwable) {
            throw RecognitionException("Failed to load ${model.displayName}: ${t.message}", t)
        }
    }

    override fun createStreamingSession(): StreamingSession =
        throw RecognitionException("${model.displayName} does not support real-time dictation")

    override fun transcribe(samples: FloatArray): BatchSegmentResult {
        val g = graphs ?: throw RecognitionException("Model not loaded")
        return try {
            BatchSegmentResult(Utterance(g).decode(samples), null)
        } catch (e: OrtException) {
            throw RecognitionException("Transcription failed: ${e.message}", e)
        }
    }

    override fun close() {
        graphs?.close()
        graphs = null
    }

    /** The loaded sessions plus the geometry and vocabularies they need. */
    internal class Graphs(installed: InstalledModel, numThreads: Int) : AutoCloseable {
        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        val meta = JSONObject(installed.file("metadata").readText())
        val tier = installed.entry.files.getValue("stream").removeSuffix(".onnx")
        val rung = installed.entry.files.getValue("pcec").removeSuffix(".onnx")
        private val sessions = ArrayList<OrtSession>()
        val stream: OrtSession
        val pcec: OrtSession
        val pcecFlush: OrtSession

        init {
            try {
                stream = open(installed, "stream", numThreads)
                pcec = open(installed, "pcec", numThreads)
                pcecFlush = open(installed, "pcecFlush", numThreads)
            } catch (t: Throwable) {
                close()
                throw t
            }
        }

        val samplesPerFrame = meta.getJSONObject("streaming").getInt("samples_per_frame")
        val window = inShape(stream, "${tier}_pcm").last().toInt()
        val commit = outShape(stream, "${tier}_log_probs")[1].toInt()
        val hiddenDim = outShape(stream, "${tier}_hidden").last().toInt()
        val vocab = strings(meta.getJSONArray("vocab"))
        val blank = meta.getInt("blank_id")

        val pcecMeta: JSONObject = meta.getJSONObject("pcec")
        val pcecVocab = strings(pcecMeta.getJSONArray("vocab"))
        val pcecBlank = pcecMeta.getInt("blank_id")
        val pcecCommit = inShape(pcec, "${rung}_hyp_ids")[1].toInt()
        /** The punctuation model's settle lag in tokens, read off the flush output width. */
        val pcecLookahead = ((outShape(pcecFlush, "${rung}_tail_slots")[1] - 1) / 2 - pcecCommit).toInt()
        val wantsAcoustic = "${rung}_acoustic" in pcec.inputNames
        val wantsKeep = "${rung}_keep" in pcec.inputNames

        init {
            val assigned = pcecMeta.getJSONObject("tier_rung").optInt(tier, -1)
            val expected = pcecMeta.getJSONObject("rungs").optJSONObject(assigned.toString())?.optString("name")
            require(expected == rung) { "$tier expects punctuation model $expected, found $rung" }
        }

        fun tensor(data: FloatArray, shape: LongArray): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        fun tensor(data: LongArray, shape: LongArray): OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
        fun scalar(value: Long): OnnxTensor = OnnxTensor.createTensor(env, value)

        /** A zero tensor with the shape and type [session] declares for input [name] (caches are not all float). */
        fun zeros(session: OrtSession, name: String): OnnxTensor {
            val info = session.inputInfo.getValue(name).info as TensorInfo
            val n = info.shape.fold(1L) { a, d -> a * d }.toInt()
            return when (info.type) {
                OnnxJavaType.FLOAT -> tensor(FloatArray(n), info.shape)
                OnnxJavaType.INT64 -> tensor(LongArray(n), info.shape)
                else -> throw RecognitionException("Unsupported cache type ${info.type} for $name")
            }
        }

        private fun open(installed: InstalledModel, role: String, threads: Int): OrtSession {
            // As in the reference runner: no per-session weight copies (prepacking) and no arena
            // preallocation, so the weights file is held once however many graphs use it.
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setCPUArenaAllocator(false)
                addConfigEntry("session.disable_prepacking", "1")
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            }
            return options.use { env.createSession(installed.file(role).absolutePath, it) }.also { sessions += it }
        }

        private fun inShape(s: OrtSession, name: String) = (s.inputInfo.getValue(name).info as TensorInfo).shape
        private fun outShape(s: OrtSession, name: String) = (s.outputInfo.getValue(name).info as TensorInfo).shape
        private fun strings(a: JSONArray) = List(a.length()) { a.optString(it, "") }

        override fun close() {
            sessions.forEach { it.close() }
            sessions.clear()
        }
    }

    /** Decodes one utterance: windows -> CTC frames -> pooled tokens -> PCEC slots -> text. */
    private class Utterance(private val g: Graphs) {
        fun decode(pcm: FloatArray): String = Pcec(g).use { pcec ->
            recognize(pcm, pcec)
            pcec.flush()
            pcec.drain()
            renderSlots(pcec.slots + pcec.tail(), g.pcecMeta, g.pcecVocab, g.pcecBlank)
        }

        /** Runs the acoustic model window by window and feeds each window's committed frames to [pcec]. */
        private fun recognize(pcm: FloatArray, pcec: Pcec) {
            var filterCache = g.zeros(g.stream, "filter_cache")
            var attnCache = g.zeros(g.stream, "attn_cache")
            var held: OrtSession.Result? = null
            val totalFrames = pcm.size / g.samplesPerFrame
            var next = 0
            try {
                // Each window starts at the commit boundary; left context comes from the caches.
                // The last windows are zero-padded where the lookahead runs past the audio.
                while (next * g.samplesPerFrame < pcm.size) {
                    val start = next * g.samplesPerFrame
                    val win = FloatArray(g.window)
                    System.arraycopy(pcm, start, win, 0, minOf(g.window, pcm.size - start))
                    val result = g.tensor(win, longArrayOf(1, g.window.toLong())).use { input ->
                        g.stream.run(
                            mapOf("${g.tier}_pcm" to input, "filter_cache" to filterCache, "attn_cache" to attnCache),
                        )
                    }
                    // The new caches live in the new result; release the previous ones.
                    if (held != null) held.close() else { filterCache.close(); attnCache.close() }
                    held = result
                    filterCache = result.get("${g.tier}_filter_cache_out").get() as OnnxTensor
                    attnCache = result.get("${g.tier}_attn_cache_out").get() as OnnxTensor

                    val valid = minOf(g.commit, totalFrames - next)
                    if (valid > 0) {
                        val logProbs = (result.get("${g.tier}_log_probs").get() as OnnxTensor).floatBuffer
                        val hidden = (result.get("${g.tier}_hidden").get() as OnnxTensor).floatBuffer
                        val classes = logProbs.capacity() / g.commit
                        val ids = IntArray(valid) { t -> argmax(logProbs, t * classes, classes) }
                        pcec.pool(ids, hidden)
                        pcec.drain()
                    }
                    next += g.commit
                }
            } finally {
                if (held != null) held.close() else { filterCache.close(); attnCache.close() }
            }
        }

        private fun argmax(buf: FloatBuffer, offset: Int, n: Int): Int {
            var best = 0
            var bestValue = Float.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val v = buf.get(offset + i)
                if (v > bestValue) { bestValue = v; best = i }
            }
            return best
        }
    }

    /**
     * PCEC: pools CTC frames into tokens (mean hidden state, preceding pause) and runs them
     * through the punctuation graph in blocks of [Graphs.pcecCommit], carrying its state.
     */
    private class Pcec(private val g: Graphs) : AutoCloseable {
        private val ids = ArrayList<Int>()
        private val pauses = ArrayList<Int>()
        private val acoustic = ArrayList<FloatArray>()
        val slots = ArrayList<Int>()
        private var consumed = 0

        private var openId = -1
        private var openSum: DoubleArray? = null
        private var openFrames = 0
        private var openBlanks = 0
        private var blanks = 0
        private var prev = -1

        private var state = g.zeros(g.pcec, "${g.rung}_state")
        private var seen = g.zeros(g.pcec, "${g.rung}_seen")
        private var held: OrtSession.Result? = null

        fun pool(frameIds: IntArray, hidden: FloatBuffer) {
            val dim = g.hiddenDim
            for ((t, id) in frameIds.withIndex()) {
                val sum = openSum
                if (id != prev && id != g.blank) { // a new token starts here
                    if (sum != null) emit()
                    openId = id
                    openSum = DoubleArray(dim) { hidden.get(t * dim + it).toDouble() }
                    openFrames = 1
                    openBlanks = blanks
                    blanks = 0
                } else if (sum != null) { // a repeat or a blank belongs to the open token
                    for (i in 0 until dim) sum[i] += hidden.get(t * dim + i)
                    openFrames++
                }
                prev = id
                if (id == g.blank) blanks++
            }
        }

        /** Closes the token still held back. */
        fun flush() {
            if (openSum != null) emit()
        }

        private fun emit() {
            val sum = openSum!!
            ids += openId
            acoustic += FloatArray(sum.size) { (sum[it] / openFrames).toFloat() }
            pauses += PAUSE_THRESHOLDS.count { openBlanks >= it }
            openSum = null
        }

        /** Runs every whole block that is ready; leftovers stay buffered. */
        fun drain() {
            val k = g.pcecCommit
            while (ids.size >= k) {
                val result = run(g.pcec, k, null)
                val out = slotsOf(result.get("${g.rung}_slots").get() as OnnxTensor)
                // Until `lookahead` tokens have gone through, the delay line's head is warm-up.
                val skip = minOf(2 * k, maxOf(0, 2 * g.pcecLookahead - 2 * consumed))
                slots += out.subList(skip, 2 * k)
                if (held != null) held!!.close() else { state.close(); seen.close() }
                held = result
                state = result.get("${g.rung}_state_out").get() as OnnxTensor
                seen = result.get("${g.rung}_seen_out").get() as OnnxTensor
                repeat(k) { ids.removeAt(0); pauses.removeAt(0); acoustic.removeAt(0) }
                consumed += k
            }
        }

        /** The last partial block plus whatever is still inside the model. */
        fun tail(): List<Int> {
            val n = ids.size
            return g.scalar(n.toLong()).use { nReal ->
                run(g.pcecFlush, n, nReal).use { result ->
                    val out = slotsOf(result.get("${g.rung}_tail_slots").get() as OnnxTensor)
                    val valid = minOf(out.size, 2 * g.pcecLookahead + 2 * n + 1)
                    val skip = minOf(valid, maxOf(0, 2 * g.pcecLookahead - 2 * consumed))
                    out.subList(skip, valid).toList()
                }
            }
        }

        /** Runs [session] on a full block of `pcecCommit` tokens, padded past the [n] real ones. */
        private fun run(session: OrtSession, n: Int, nReal: OnnxTensor?): OrtSession.Result {
            val k = g.pcecCommit
            val m = g.pcecMeta
            val p = g.rung
            val hypIds = LongArray(k) { if (it < n) ids[it].toLong() else m.getLong("pad_in") }
            // A model trained with the pause channel blacked out must be fed the blackout value.
            val realPause = m.optBoolean("real_pause", false)
            val pause = LongArray(k) { if (it < n && realPause) pauses[it].toLong() else m.getLong("pause_blackout") }
            val inputs = ArrayList<OnnxTensor>()
            try {
                val feed = LinkedHashMap<String, OnnxTensor>()
                fun add(name: String, t: OnnxTensor) { inputs += t; feed[name] = t }
                add("${p}_hyp_ids", g.tensor(hypIds, longArrayOf(1, k.toLong())))
                add("${p}_pause", g.tensor(pause, longArrayOf(1, k.toLong())))
                if (g.wantsAcoustic) {
                    val dim = g.hiddenDim
                    val ac = FloatArray(k * dim)
                    for (i in 0 until n) System.arraycopy(acoustic[i], 0, ac, i * dim, dim)
                    add("${p}_acoustic", g.tensor(ac, longArrayOf(1, k.toLong(), dim.toLong())))
                }
                // `keep` marks words a vocabulary-biasing decoder inserted; none here, so all 0.
                if (g.wantsKeep) add("${p}_keep", g.tensor(FloatArray(k), longArrayOf(1, k.toLong())))
                feed["${p}_state"] = state
                feed["${p}_seen"] = seen
                if (nReal != null) feed["${p}_n_real"] = nReal
                return session.run(feed)
            } finally {
                inputs.forEach { it.close() }
            }
        }

        private fun slotsOf(t: OnnxTensor): List<Int> = when (t.info.type) {
            OnnxJavaType.INT64 -> t.longBuffer.let { b -> List(b.remaining()) { b.get(it).toInt() } }
            OnnxJavaType.INT32 -> t.intBuffer.let { b -> List(b.remaining()) { b.get(it) } }
            else -> throw RecognitionException("Unexpected punctuation output type ${t.info.type}")
        }

        override fun close() {
            if (held != null) held!!.close() else { state.close(); seen.close() }
            held = null
        }
    }

    companion object {
        /** Blank-frame counts separating the pause buckets PCEC was trained on. */
        private val PAUSE_THRESHOLDS = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128)

        /** PCEC output slots -> punctuated, cased text (CTC collapse, then case and punctuation marks). */
        internal fun renderSlots(slots: List<Int>, pcecMeta: JSONObject, vocab: List<String>, blank: Int): String {
            val ctrl = pcecMeta.getJSONObject("ctrl")
            val cap = ctrl.getInt("cap")
            val allCaps = ctrl.getInt("allcaps")
            val punct = mapOf(ctrl.getInt("period") to ".", ctrl.getInt("comma") to ",", ctrl.getInt("question") to "?")

            class Word(val text: StringBuilder, val case: Int, var punct: String = "")
            val words = ArrayList<Word>()
            var pending = -1
            var prev = -1
            for (i in slots) {
                if (i == prev || i == blank) { prev = i; continue }
                prev = i
                when {
                    i == cap || i == allCaps -> pending = i
                    i in punct -> words.lastOrNull()?.punct = punct.getValue(i)
                    i < vocab.size && vocab[i].isNotEmpty() ->
                        if (vocab[i].startsWith(" ") || words.isEmpty()) {
                            words += Word(StringBuilder(vocab[i]), pending)
                            pending = -1
                        } else {
                            words.last().text.append(vocab[i])
                        }
                }
            }
            val out = ArrayList<String>()
            var sentenceStart = true
            for (w in words) {
                var text = w.text.toString().trim()
                if (text.isEmpty()) continue
                text = when {
                    w.case == allCaps -> text.uppercase(Locale.ROOT)
                    w.case == cap || sentenceStart -> text.substring(0, 1).uppercase(Locale.ROOT) + text.substring(1)
                    else -> text
                }
                sentenceStart = w.punct == "." || w.punct == "?"
                out += text + w.punct
            }
            return out.joinToString(" ")
        }
    }
}
