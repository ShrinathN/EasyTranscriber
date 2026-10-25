package com.easytranscriber.transcript

import com.easytranscriber.recognition.Hypothesis
import com.easytranscriber.recognition.OutputCase
import com.easytranscriber.recognition.StreamingUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTranscriptManagerTest {
    private val emitted = mutableListOf<String>()
    private fun manager(case: OutputCase = OutputCase.LOWER) =
        StreamingTranscriptManager(TextFormatter(), case) { emitted += it }

    private fun partial(text: String) = StreamingUpdate(Hypothesis(text, emptyList()), null, 0, 0)
    private fun final(text: String, next: String = "") =
        StreamingUpdate(Hypothesis(next, emptyList()), Hypothesis(text, emptyList()), 0, 0)

    @Test
    fun lastWordIsHeldUntilNextWordOrEndpoint() {
        val m = manager()
        m.onUpdate(partial("hel"))
        assertEquals(emptyList<String>(), emitted)
        m.onUpdate(partial("hello"))
        m.onUpdate(partial("hello wor"))
        assertEquals(listOf("Hello"), emitted)
        assertEquals("wor", m.pendingText)
        m.onUpdate(final("hello world"))
        assertEquals("Hello world", m.committedText)
        assertEquals("", m.pendingText)
    }

    @Test
    fun noDuplicatesWhenPartialsRepeatOrShrink() {
        val m = manager()
        listOf("a", "a b", "a b", "a b c", "a b", "a b c d").forEach { m.onUpdate(partial(it)) }
        m.onUpdate(final("a b c d"))
        assertEquals("A b c d", m.committedText)
        assertEquals(0, m.postCommitRevisions)
    }

    @Test
    fun revisionsOfCommittedWordsAreCountedNotApplied() {
        val m = manager()
        m.onUpdate(partial("i scream for"))  // commits "i scream"
        m.onUpdate(final("ice cream for you"))
        assertEquals("I scream for you", m.committedText)
        assertEquals(2, m.postCommitRevisions)
    }

    @Test
    fun segmentsAreJoinedInOrderWithSpaces() {
        val m = manager()
        m.onUpdate(partial("first segment"))
        m.onUpdate(final("first segment here"))
        m.onUpdate(partial("second"))
        m.onUpdate(final("second one"))
        m.finish()
        assertEquals("First segment here second one", m.committedText)
        assertEquals(m.committedText, emitted.joinToString(""))
    }

    @Test
    fun uppercaseModelsAreLowercasedBeforeFormatting() {
        val m = manager(OutputCase.UPPER)
        m.onUpdate(final("HELLO I AM HERE"))
        assertEquals("Hello I am here", m.committedText)
    }

    @Test
    fun spokenCommandSplitAcrossCommitBoundary() {
        val m = manager()
        m.onUpdate(partial("really question"))   // commits "really"
        m.onUpdate(partial("really question mark"))  // commits "question" (held by formatter)
        m.onUpdate(final("really question mark"))
        assertEquals("Really?", m.committedText)
    }

    // ---- stable last word ----

    private var clock = 0L
    private fun timed() = StreamingTranscriptManager(TextFormatter(), OutputCase.LOWER, nowMs = { clock }, stableCommitMs = 300) { emitted += it }

    @Test
    fun stableLastWordIsCommittedBeforeNextWord() {
        val m = timed()
        m.onUpdate(partial("hello"))
        clock += 200; m.onUpdate(partial("hello"))
        assertEquals("", m.committedText)
        clock += 150; m.onUpdate(partial("hello"))
        assertEquals("Hello", m.committedText)
        m.onUpdate(partial("hello world"))
        m.onUpdate(final("hello world"))
        assertEquals("Hello world", m.committedText)
        assertEquals(0, m.postCommitRevisions)
    }

    @Test
    fun extendedWordTypesOnlyTheSuffix() {
        val m = timed()
        m.onUpdate(partial("un")); clock += 400; m.onUpdate(partial("un"))
        assertEquals("Un", m.committedText)
        m.onUpdate(partial("unbelievable"))
        m.onUpdate(final("unbelievable story"))
        assertEquals("Unbelievable story", m.committedText)
        assertEquals(1, m.extensions)
        assertEquals(0, m.postCommitRevisions)
    }

    @Test
    fun commandWordsAndSingleLettersAreNotCommittedEarly() {
        val m = timed()
        m.onUpdate(partial("yes comma")); clock += 1000; m.onUpdate(partial("yes comma"))
        assertEquals("Yes", m.committedText)   // "comma" waits
        m.onUpdate(partial("yes comma a")); clock += 1000; m.onUpdate(partial("yes comma a"))
        assertEquals("Yes,", m.committedText)  // "a" (one letter) waits
        m.onUpdate(final("yes comma a dog"))
        assertEquals("Yes, a dog", m.committedText)
    }

    @Test
    fun persistentRevisionIsCountedOnce() {
        val m = manager()
        m.onUpdate(partial("i scream for"))
        repeat(5) { m.onUpdate(partial("ice cream for you")) }
        assertEquals(2, m.postCommitRevisions)
    }
}
