package com.easytranscriber.transcript

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFormatterTest {
    private fun fmt(vararg words: String, opts: FormatOptions = FormatOptions()): String {
        val f = TextFormatter(opts)
        return f.words(words.toList()) + f.endSegment()
    }

    @Test
    fun capitalizesSentenceStartAndPronounI() {
        assertEquals("Hello world, I think I'm here.", fmt("hello", "world", "comma", "i", "think", "i'm", "here", "period"))
    }

    @Test
    fun twoWordCommandsAreRecognizedAcrossCalls() {
        val f = TextFormatter()
        val out = StringBuilder()
        out.append(f.words(listOf("is", "it", "question")))
        out.append(f.words(listOf("mark", "yes")))
        out.append(f.endSegment())
        assertEquals("Is it? Yes", out.toString())
    }

    @Test
    fun heldPrefixIsReleasedAsWordWhenNotACommand() {
        assertEquals("The new office", fmt("the", "new", "office"))
        // Held at segment end:
        assertEquals("Something new", fmt("something", "new"))
    }

    @Test
    fun newLineAndParagraph() {
        assertEquals("Dear sam,\nThanks.\n\nBye", fmt("dear", "sam", "comma", "new", "line", "thanks", "period", "new", "paragraph", "bye"))
    }

    @Test
    fun spokenPunctuationCanBeDisabled() {
        assertEquals("Hello comma world", fmt("hello", "comma", "world", opts = FormatOptions(spokenPunctuation = false)))
    }

    @Test
    fun capitalizationCanBeDisabled() {
        assertEquals("hello i am, ok", fmt("hello", "i", "am", "comma", "ok", opts = FormatOptions(autoCapitalize = false)))
    }

    @Test
    fun spacingCarriesAcrossSegmentsAndSessions() {
        val f = TextFormatter()
        val a = f.words(listOf("one", "two")) + f.endSegment()
        val b = f.words(listOf("three")) + f.endSegment()
        f.startSession(spaceBefore = true)
        val c = f.words(listOf("four"))
        f.startSession(spaceBefore = false)
        val d = f.words(listOf("five"))
        assertEquals("One two", a)
        assertEquals(" three", b)
        assertEquals(" four", c)
        assertEquals("five", d)
    }

    @Test
    fun externalTextUpdatesContext() {
        val f = TextFormatter()
        assertEquals("Hello there.", f.external(" Hello there. ", spaceBefore = true))
        // After a sentence end, the next streamed word is capitalized and spaced.
        assertEquals(" Next", f.words(listOf("next")))
    }
}
