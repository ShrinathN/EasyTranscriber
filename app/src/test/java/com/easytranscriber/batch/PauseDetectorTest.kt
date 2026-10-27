package com.easytranscriber.batch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseDetectorTest {
    @Test
    fun silenceBeforeSpeechNeverStops() {
        val d = PauseDetector(2.0)
        for (i in 1..100) assertFalse(d.update(i * 0.1, speech = false))
    }

    @Test
    fun stopsAfterPauseFollowingSpeech() {
        val d = PauseDetector(2.0)
        assertFalse(d.update(1.0, speech = true))
        assertFalse(d.update(2.9, speech = false))
        assertTrue(d.update(3.0, speech = false))
    }

    @Test
    fun speechResetsThePause() {
        val d = PauseDetector(2.0)
        d.update(1.0, speech = true)
        assertFalse(d.update(2.5, speech = false))
        assertFalse(d.update(2.6, speech = true))
        assertFalse(d.update(4.5, speech = false))
        assertTrue(d.update(4.7, speech = false))
    }
}
