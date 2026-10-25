package com.easytranscriber.hid

/** A character that the selected layout cannot type, with its UTF-16 index in the source text. */
data class UnsupportedChar(val index: Int, val codePoint: Int) {
    val display: String get() = String(Character.toChars(codePoint))
    val unicodeName: String get() = "U+%04X".format(codePoint)
}

sealed interface EncodeResult {
    data class Ok(val strokes: List<KeyStroke>) : EncodeResult
    data class Unsupported(val chars: List<UnsupportedChar>) : EncodeResult
}

/**
 * Converts text to key strokes. Encoding is all-or-nothing: if any character cannot be typed,
 * nothing is encoded and every offending character is reported, so text is never sent with
 * characters silently dropped or replaced.
 *
 * "\r\n" and a lone "\r" are both treated as a single line break (Enter).
 */
object TextEncoder {
    fun encode(text: String, layout: KeyboardLayout): EncodeResult {
        val strokes = ArrayList<KeyStroke>(text.length)
        val unsupported = ArrayList<UnsupportedChar>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (cp == '\r'.code) {
                val next = i + 1
                if (next >= text.length || text[next] != '\n') {
                    strokes += layout.strokeFor('\n'.code)!!
                }
                i += width
                continue
            }
            val stroke = layout.strokeFor(cp)
            if (stroke == null) unsupported += UnsupportedChar(i, cp) else strokes += stroke
            i += width
        }
        return if (unsupported.isEmpty()) EncodeResult.Ok(strokes) else EncodeResult.Unsupported(unsupported)
    }
}
