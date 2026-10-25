package com.easytranscriber.transcript

import java.util.Locale

data class FormatOptions(
    /** Replace spoken commands ("comma", "new line", ...) with punctuation/line breaks. */
    val spokenPunctuation: Boolean = true,
    /** Capitalize the first word of each sentence and the pronoun "I". */
    val autoCapitalize: Boolean = true,
)

/**
 * Turns committed recognizer words into the exact text to type, deterministically.
 *
 * Words arrive one at a time and are never revised, so a word that may start a two-word command
 * ("question" in "question mark", "new" in "new line") is held until the next word arrives or
 * the segment ends. Recognizers without punctuation get it only from these spoken commands; no
 * punctuation is ever guessed.
 *
 * The formatter persists across segments and sessions so spacing and sentence state carry over.
 */
class TextFormatter(var options: FormatOptions = FormatOptions()) {
    private var needSpace = false
    private var sentenceStart = true
    private var held: String? = null

    /** Forget context (e.g. a different host or the user moved the cursor). */
    fun reset() {
        needSpace = false
        sentenceStart = true
        held = null
    }

    /** True if text has been produced since the last reset (the next word gets a leading space). */
    val hasOutput: Boolean get() = needSpace

    /** Starts a dictation; without [spaceBefore] the first word is typed with no leading space. */
    fun startSession(spaceBefore: Boolean) {
        if (!spaceBefore) needSpace = false
        held = null
    }

    /**
     * Returns [text] (already punctuated, e.g. from a batch model) with the separator the current
     * context needs, and updates spacing/sentence state as if it had been formatted here.
     */
    fun external(text: String, spaceBefore: Boolean): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val out = if (needSpace && spaceBefore) " $t" else t
        needSpace = !t.endsWith('\n')
        sentenceStart = t.last() in ".?!\n"
        return out
    }

    /** Formats newly committed words; returns text to type (possibly empty if a word is held). */
    fun words(words: List<String>): String {
        val out = StringBuilder()
        for (raw in words) {
            val w = raw.lowercase(Locale.ROOT)
            if (!options.spokenPunctuation) {
                emitWord(raw, out)
                continue
            }
            val prefix = held
            if (prefix != null) {
                held = null
                val command = TWO_WORD[prefix to w]
                if (command != null) {
                    emitSymbol(command, out)
                    continue
                }
                emitWord(prefix, out)
            }
            val single = ONE_WORD[w]
            when {
                single != null -> emitSymbol(single, out)
                TWO_WORD_PREFIXES.contains(w) -> held = raw
                else -> emitWord(raw, out)
            }
        }
        return out.toString()
    }

    /**
     * The last formatted word grew (recognizer extended it, e.g. "un" -> "unbelievable");
     * returns the characters to append. Only valid directly after that word was formatted.
     */
    fun extend(suffix: String): String {
        val h = held
        if (h != null) {
            held = h + suffix
            return ""
        }
        return if (options.autoCapitalize) suffix.lowercase(Locale.ROOT) else suffix
    }

    /** Words that must never be committed early: spoken commands and their first words. */
    fun isCommandWord(word: String): Boolean {
        if (!options.spokenPunctuation) return false
        val w = word.lowercase(Locale.ROOT)
        return w in ONE_WORD || w in TWO_WORD_PREFIXES
    }

    /** End of a recognizer segment: a held word is released as a plain word. */
    fun endSegment(): String {
        val h = held ?: return ""
        held = null
        return StringBuilder().also { emitWord(h, it) }.toString()
    }

    private fun emitWord(raw: String, out: StringBuilder) {
        var w = raw.lowercase(Locale.ROOT)
        if (options.autoCapitalize) {
            w = PRONOUN_I[w] ?: w
            if (sentenceStart) w = w.replaceFirstChar { it.titlecase(Locale.ROOT) }
        } else {
            w = raw
        }
        if (needSpace) out.append(' ')
        out.append(w)
        needSpace = true
        sentenceStart = false
    }

    private fun emitSymbol(symbol: String, out: StringBuilder) {
        when (symbol) {
            "\n", "\n\n" -> {
                out.append(symbol)
                needSpace = false
                sentenceStart = true
            }
            else -> {
                // Attach to the previous word with no space.
                out.append(symbol)
                needSpace = true
                if (symbol in SENTENCE_END) sentenceStart = true
            }
        }
    }

    companion object {
        private val ONE_WORD = mapOf(
            "comma" to ",",
            "period" to ".",
            "colon" to ":",
            "semicolon" to ";",
        )
        private val TWO_WORD = mapOf(
            ("full" to "stop") to ".",
            ("question" to "mark") to "?",
            ("exclamation" to "mark") to "!",
            ("exclamation" to "point") to "!",
            ("new" to "line") to "\n",
            ("new" to "paragraph") to "\n\n",
        )
        private val TWO_WORD_PREFIXES = TWO_WORD.keys.map { it.first }.toSet()
        private val SENTENCE_END = setOf(".", "?", "!")
        private val PRONOUN_I = mapOf("i" to "I", "i'm" to "I'm", "i'll" to "I'll", "i've" to "I've", "i'd" to "I'd")

        /** Commands shown in the UI help text. */
        val COMMAND_HELP: List<Pair<String, String>> = listOf(
            "comma" to ",", "period / full stop" to ".", "question mark" to "?",
            "exclamation mark" to "!", "colon" to ":", "semicolon" to ";",
            "new line" to "↵", "new paragraph" to "↵↵",
        )
    }
}
