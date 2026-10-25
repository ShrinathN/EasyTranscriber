package com.easytranscriber.transcript

import com.easytranscriber.recognition.OutputCase
import com.easytranscriber.recognition.StreamingUpdate
import java.util.Locale

/**
 * Decides which recognized words are committed for delivery.
 *
 * Policy, within the active segment:
 * - every word except the last is committed as soon as a later word appears;
 * - the last word is committed once it has been unchanged for [stableCommitMs] (never for
 *   one-letter words or spoken-command words), or when the engine finalizes the segment;
 * - if the most recently committed word is later *extended* by the recognizer ("un" ->
 *   "unbelievable") and nothing after it is committed yet, only the new suffix is emitted.
 * Committed text is never otherwise revised: any other change to a committed word is counted in
 * [postCommitRevisions] and NOT applied to the output.
 */
class StreamingTranscriptManager(
    private val formatter: TextFormatter,
    private val outputCase: OutputCase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Commit an unchanged last word after this long; null disables (wait for next word/endpoint). */
    private val stableCommitMs: Long? = 300,
    private val emit: (String) -> Unit,
) {
    private var lastWord: String? = null
    private var lastWordSince = 0L
    /** Measurement record: when raw words were committed and when (in audio time) each ended. */
    data class CommitRecord(val wallMs: Long, val words: List<String>, val wordEndSec: List<Float?>)

    private val segmentWords = ArrayList<String>()
    private val revisedIndices = HashSet<Int>()
    private val _commits = ArrayList<CommitRecord>()
    val commits: List<CommitRecord> get() = _commits
    private val committed = StringBuilder()

    /** Text emitted for delivery in this session, exactly as it will be typed. */
    val committedText: String get() = committed.toString()

    /** Uncommitted tail of the current hypothesis (display only). */
    var pendingText: String = ""
        private set

    var postCommitRevisions: Int = 0
        private set

    /** Times an early-committed word was later extended (handled by typing the suffix). */
    var extensions: Int = 0
        private set

    fun onUpdate(update: StreamingUpdate) {
        update.finalized?.let { fin ->
            commit(words(fin.text), includeLast = true, ends = fin.wordEndTimes())
            out(formatter.endSegment())
            segmentWords.clear()
            revisedIndices.clear()
            lastWord = null
        }
        val partial = words(update.partial.text)
        commit(partial, includeLast = isStableLastWord(partial), ends = update.partial.wordEndTimes())
        pendingText = partial.drop(segmentWords.size).joinToString(" ")
    }

    /** Session end: releases anything the formatter is holding. */
    fun finish() {
        if (segmentWords.isNotEmpty()) out(formatter.endSegment())
        segmentWords.clear()
        revisedIndices.clear()
        out(formatter.endSegment())
        pendingText = ""
    }

    /** True when the last partial word is uncommitted and has been unchanged long enough. */
    private fun isStableLastWord(words: List<String>): Boolean {
        val limit = stableCommitMs ?: return false
        val last = words.lastOrNull() ?: return false
        if (words.size <= segmentWords.size) return false
        val now = nowMs()
        if (last != lastWord) {
            lastWord = last
            lastWordSince = now
            return false
        }
        return now - lastWordSince >= limit && last.length > 1 && !formatter.isCommandWord(last)
    }

    private fun commit(words: List<String>, includeLast: Boolean, ends: List<Float>?) {
        // Extension of the latest committed word: type only the new suffix.
        val li = segmentWords.size - 1
        if (li >= 0 && li < words.size && words[li] != segmentWords[li] &&
            words[li].startsWith(segmentWords[li]) && (0 until li).all { words[it] == segmentWords[it] }
        ) {
            out(formatter.extend(words[li].substring(segmentWords[li].length)))
            segmentWords[li] = words[li]
            extensions++
        }
        // Count each committed word that the recognizer changed (or dropped) once per segment.
        for (i in segmentWords.indices) {
            if ((i >= words.size || words[i] != segmentWords[i]) && revisedIndices.add(i)) postCommitRevisions++
        }
        val target = if (includeLast) words.size else words.size - 1
        if (target > segmentWords.size) {
            val fresh = words.subList(segmentWords.size, target)
            if (_commits.size < MAX_COMMIT_RECORDS) {
                _commits += CommitRecord(nowMs(), fresh.toList(), (segmentWords.size until target).map { ends?.getOrNull(it) })
            }
            segmentWords += fresh
            out(formatter.words(fresh))
        }
    }

    private fun out(text: String) {
        if (text.isEmpty()) return
        committed.append(text)
        emit(text)
    }

    private companion object {
        const val MAX_COMMIT_RECORDS = 20_000
    }

    private fun words(text: String): List<String> {
        val ws = text.split(' ').filter { it.isNotEmpty() }
        return if (outputCase == OutputCase.UPPER) ws.map { it.lowercase(Locale.ROOT) } else ws
    }
}
