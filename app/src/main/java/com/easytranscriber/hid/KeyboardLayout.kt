package com.easytranscriber.hid

/**
 * Maps characters to HID key strokes for one host keyboard layout.
 *
 * A HID keyboard sends physical key positions, not characters. The host translates positions
 * to characters using ITS configured layout, so the layout selected here must match the layout
 * configured on the host. Characters the layout cannot produce with a single (modifier, key)
 * stroke return null and are reported to the caller; they are never substituted.
 */
interface KeyboardLayout {
    val id: String
    val displayName: String
    fun strokeFor(codePoint: Int): KeyStroke?
}

/** US English (QWERTY) layout. Covers printable ASCII, space, tab and newline. */
object UsQwertyLayout : KeyboardLayout {
    override val id = "us-qwerty"
    override val displayName = "English (US) QWERTY"

    private val table: Map<Int, KeyStroke> = buildMap {
        for (i in 0 until 26) {
            put('a'.code + i, KeyStroke(Modifier.NONE, KeyUsage.A + i))
            put('A'.code + i, KeyStroke(Modifier.LEFT_SHIFT, KeyUsage.A + i))
        }
        // Digits 1-9 are usages 0x1E-0x26, 0 is 0x27; their shifted symbols share the key.
        val shiftedDigits = "!@#$%^&*("
        for (i in 0 until 9) {
            put('1'.code + i, KeyStroke(Modifier.NONE, KeyUsage.DIGIT_1 + i))
            put(shiftedDigits[i].code, KeyStroke(Modifier.LEFT_SHIFT, KeyUsage.DIGIT_1 + i))
        }
        put('0'.code, KeyStroke(Modifier.NONE, KeyUsage.DIGIT_0))
        put(')'.code, KeyStroke(Modifier.LEFT_SHIFT, KeyUsage.DIGIT_0))

        put('\n'.code, KeyStroke(Modifier.NONE, KeyUsage.ENTER))
        put('\t'.code, KeyStroke(Modifier.NONE, KeyUsage.TAB))
        put(' '.code, KeyStroke(Modifier.NONE, KeyUsage.SPACE))

        // (unshifted, shifted, usage)
        val symbols = listOf(
            Triple('-', '_', 0x2D),
            Triple('=', '+', 0x2E),
            Triple('[', '{', 0x2F),
            Triple(']', '}', 0x30),
            Triple('\\', '|', 0x31),
            Triple(';', ':', 0x33),
            Triple('\'', '"', 0x34),
            Triple('`', '~', 0x35),
            Triple(',', '<', 0x36),
            Triple('.', '>', 0x37),
            Triple('/', '?', 0x38),
        )
        for ((plain, shifted, usage) in symbols) {
            put(plain.code, KeyStroke(Modifier.NONE, usage))
            put(shifted.code, KeyStroke(Modifier.LEFT_SHIFT, usage))
        }
    }

    override fun strokeFor(codePoint: Int): KeyStroke? = table[codePoint]
}
