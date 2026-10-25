package com.easytranscriber.recognition

import java.util.Locale

data class WerResult(
    val referenceWords: Int,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
) {
    val errors: Int get() = substitutions + deletions + insertions
    val wer: Double get() = if (referenceWords == 0) Double.NaN else errors.toDouble() / referenceWords
}

/**
 * Word error rate for whitespace-delimited languages (e.g. English). Text is normalized by
 * lowercasing and removing punctuation other than intra-word apostrophes and hyphens.
 * Not meaningful for scripts without word spacing; use character error rate there.
 */
object WordErrorRate {
    fun normalize(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}'\\-\\s]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim('\'', '-') }
            .filter { it.isNotEmpty() }

    fun compute(reference: String, hypothesis: String): WerResult {
        val ref = normalize(reference)
        val hyp = normalize(hypothesis)
        // dp[i][j] = (cost, subs, dels, ins) for ref[0,i) vs hyp[0,j)
        val n = ref.size
        val m = hyp.size
        val cost = Array(n + 1) { IntArray(m + 1) }
        val op = Array(n + 1) { ByteArray(m + 1) } // 0 match/sub, 1 deletion, 2 insertion
        for (i in 1..n) { cost[i][0] = i; op[i][0] = 1 }
        for (j in 1..m) { cost[0][j] = j; op[0][j] = 2 }
        for (i in 1..n) for (j in 1..m) {
            val sub = cost[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
            val del = cost[i - 1][j] + 1
            val ins = cost[i][j - 1] + 1
            when {
                sub <= del && sub <= ins -> { cost[i][j] = sub; op[i][j] = 0 }
                del <= ins -> { cost[i][j] = del; op[i][j] = 1 }
                else -> { cost[i][j] = ins; op[i][j] = 2 }
            }
        }
        var s = 0; var d = 0; var ins = 0
        var i = n; var j = m
        while (i > 0 || j > 0) {
            when (op[i][j].toInt()) {
                0 -> { if (ref[i - 1] != hyp[j - 1]) s++; i--; j-- }
                1 -> { d++; i-- }
                else -> { ins++; j-- }
            }
        }
        return WerResult(n, s, d, ins)
    }
}
