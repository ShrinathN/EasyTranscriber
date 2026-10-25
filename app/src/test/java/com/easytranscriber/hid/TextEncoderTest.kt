package com.easytranscriber.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEncoderTest {
    private fun ok(text: String): List<KeyStroke> {
        val result = TextEncoder.encode(text, UsQwertyLayout)
        assertTrue("expected Ok for '$text' but got $result", result is EncodeResult.Ok)
        return (result as EncodeResult.Ok).strokes
    }

    @Test
    fun lowercaseAndUppercaseLetters() {
        assertEquals(listOf(KeyStroke(Modifier.NONE, 0x04), KeyStroke(Modifier.NONE, 0x1D)), ok("az"))
        assertEquals(listOf(KeyStroke(Modifier.LEFT_SHIFT, 0x04), KeyStroke(Modifier.LEFT_SHIFT, 0x1D)), ok("AZ"))
    }

    @Test
    fun digitsAndShiftedDigitSymbols() {
        val digits = ok("1234567890")
        assertEquals((0x1E..0x27).toList(), digits.map { it.usage })
        assertTrue(digits.all { it.modifiers == Modifier.NONE })

        val symbols = ok("!@#$%^&*()")
        assertEquals((0x1E..0x27).toList(), symbols.map { it.usage })
        assertTrue(symbols.all { it.modifiers == Modifier.LEFT_SHIFT })
    }

    @Test
    fun everyPrintableAsciiCharacterIsMapped() {
        val printable = (0x20..0x7E).map { it.toChar() }.joinToString("")
        assertEquals(printable.length, ok(printable).size)
    }

    @Test
    fun punctuationUsesExpectedKeys() {
        assertEquals(KeyStroke(Modifier.NONE, 0x37), ok(".").single())
        assertEquals(KeyStroke(Modifier.NONE, 0x36), ok(",").single())
        assertEquals(KeyStroke(Modifier.LEFT_SHIFT, 0x38), ok("?").single())
        assertEquals(KeyStroke(Modifier.LEFT_SHIFT, 0x33), ok(":").single())
        assertEquals(KeyStroke(Modifier.LEFT_SHIFT, 0x34), ok("\"").single())
        assertEquals(KeyStroke(Modifier.NONE, 0x34), ok("'").single())
    }

    @Test
    fun lineBreakVariantsProduceOneEnterEach() {
        val enter = KeyStroke(Modifier.NONE, KeyUsage.ENTER)
        assertEquals(listOf(enter), ok("\n"))
        assertEquals(listOf(enter), ok("\r\n"))
        assertEquals(listOf(enter), ok("\r"))
        assertEquals(listOf(enter, enter), ok("\r\n\r\n"))
        assertEquals(KeyStroke(Modifier.NONE, KeyUsage.TAB), ok("\t").single())
    }

    @Test
    fun unsupportedCharactersAreReportedNotSubstituted() {
        val result = TextEncoder.encode("café ok — नमस्ते", UsQwertyLayout)
        assertTrue(result is EncodeResult.Unsupported)
        val chars = (result as EncodeResult.Unsupported).chars
        assertEquals(3, chars[0].index)
        assertEquals('é'.code, chars[0].codePoint)
        assertEquals('—'.code, chars[1].codePoint)
        // Every Devanagari code point is reported individually.
        assertEquals(2 + "नमस्ते".length, chars.size)
    }

    @Test
    fun supplementaryCodePointReportedOnceWithCorrectIndex() {
        val result = TextEncoder.encode("a😀b", UsQwertyLayout) as EncodeResult.Unsupported
        val c = result.chars.single()
        assertEquals(1, c.index)
        assertEquals(0x1F600, c.codePoint)
        assertNotNull(c.unicodeName)
    }

}
