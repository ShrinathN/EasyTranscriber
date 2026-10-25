package com.easytranscriber.pipeline

/** Fixed-capacity sample collector with exact summary statistics over the retained samples. */
class SampleStats(private val capacity: Int = 50_000) {
    private val values = DoubleArray(capacity)
    private var size = 0
    var count = 0L
        private set
    var max = Double.NEGATIVE_INFINITY
        private set
    var min = Double.POSITIVE_INFINITY
        private set
    private var sum = 0.0

    fun add(v: Double) {
        count++
        sum += v
        if (v > max) max = v
        if (v < min) min = v
        if (size < capacity) values[size++] = v
    }

    val mean: Double get() = if (count == 0L) Double.NaN else sum / count

    /** Percentile over retained samples (the first [capacity]); NaN if empty. */
    fun percentile(p: Double): Double {
        if (size == 0) return Double.NaN
        val sorted = values.copyOf(size).also { it.sort() }
        val rank = (p / 100.0 * (size - 1)).coerceIn(0.0, (size - 1).toDouble())
        val lo = rank.toInt()
        val hi = minOf(lo + 1, size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
    }

    fun summary(): Map<String, Double?> = mapOf(
        "count" to count.toDouble(),
        "min" to (if (count == 0L) null else min),
        "mean" to mean.orNull(),
        "p50" to percentile(50.0).orNull(),
        "p90" to percentile(90.0).orNull(),
        "p99" to percentile(99.0).orNull(),
        "max" to (if (count == 0L) null else max),
    )

    private fun Double.orNull(): Double? = if (isNaN()) null else this
}

/**
 * Tracks how a streaming hypothesis evolves, to quantify revisions and word emission latency.
 *
 * A "revision" is a change to a word that was already displayed and was NOT the last word of
 * the previous partial (the last word is routinely extended, e.g. "hel" -> "hello").
 */
class HypothesisTracker {
    private var previousWords: List<String> = emptyList()
    private var emittedTokens = 0

    var partialUpdates = 0L
        private set
    var revisedWords = 0L
        private set
    var revisionEvents = 0L
        private set

    /** Returns the number of tokens that are new since the previous update of this segment. */
    fun onPartial(words: List<String>, tokenCount: Int): Int {
        partialUpdates++
        val stable = previousWords.dropLast(1)
        var changed = 0
        for (i in stable.indices) {
            if (i >= words.size || words[i] != stable[i]) changed++
        }
        if (changed > 0) {
            revisedWords += changed
            revisionEvents++
        }
        previousWords = words
        val newTokens = (tokenCount - emittedTokens).coerceAtLeast(0)
        if (tokenCount > emittedTokens) emittedTokens = tokenCount
        return newTokens
    }

    /** Call when a segment is finalized; compares the final words with the last partial. */
    fun onFinal(words: List<String>, tokenCount: Int): Int {
        val newTokens = onPartial(words, tokenCount)
        previousWords = emptyList()
        emittedTokens = 0
        return newTokens
    }
}
