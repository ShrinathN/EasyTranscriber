package com.easytranscriber.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class WordErrorRateTest {
    @Test
    fun identicalTextAfterNormalizationIsZero() {
        val r = WordErrorRate.compute("Hello, world! It's fine.", "hello world it's fine")
        assertEquals(0, r.errors)
        assertEquals(4, r.referenceWords)
    }

    @Test
    fun countsSubstitutionDeletionInsertion() {
        // ref: a b c d ; hyp: a x c d e  -> 1 sub (b->x), 1 ins (e)
        val r = WordErrorRate.compute("a b c d", "a x c d e")
        assertEquals(1, r.substitutions)
        assertEquals(0, r.deletions)
        assertEquals(1, r.insertions)
        assertEquals(0.5, r.wer, 1e-9)

        val d = WordErrorRate.compute("the quick brown fox", "the fox")
        assertEquals(2, d.deletions)
        assertEquals(2, d.errors)
    }

    @Test
    fun emptyHypothesisIsAllDeletions() {
        val r = WordErrorRate.compute("one two three", "")
        assertEquals(3, r.deletions)
        assertEquals(1.0, r.wer, 1e-9)
    }
}
