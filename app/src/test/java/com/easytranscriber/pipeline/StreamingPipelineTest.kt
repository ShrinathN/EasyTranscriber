package com.easytranscriber.pipeline

import com.easytranscriber.audio.AudioFrame
import com.easytranscriber.audio.AudioSource
import com.easytranscriber.recognition.Hypothesis
import com.easytranscriber.recognition.ModelCatalog
import com.easytranscriber.recognition.RecognitionEngine
import com.easytranscriber.recognition.StreamingSession
import com.easytranscriber.recognition.StreamingUpdate
import com.easytranscriber.recognition.Token
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HypothesisTrackerTest {
    @Test
    fun growingLastWordIsNotARevision() {
        val t = HypothesisTracker()
        t.onPartial(listOf("hel"), 1)
        t.onPartial(listOf("hello"), 2)
        t.onPartial(listOf("hello", "wor"), 3)
        assertEquals(0L, t.revisionEvents)
    }

    @Test
    fun changingAnEarlierWordIsARevision() {
        val t = HypothesisTracker()
        t.onPartial(listOf("i", "scream", "for"), 3)
        t.onPartial(listOf("ice", "cream", "for", "you"), 4)
        assertEquals(1L, t.revisionEvents)
        assertEquals(2L, t.revisedWords)
    }

    @Test
    fun newTokenCountIsMonotonicWithinSegmentAndResetsAfterFinal() {
        val t = HypothesisTracker()
        assertEquals(2, t.onPartial(listOf("a", "b"), 2))
        assertEquals(0, t.onPartial(listOf("a"), 1)) // shrink: nothing new
        assertEquals(1, t.onPartial(listOf("a", "b", "c"), 3))
        assertEquals(0, t.onFinal(listOf("a", "b", "c"), 3))
        assertEquals(1, t.onPartial(listOf("next"), 1))
    }
}

class SampleStatsTest {
    @Test
    fun percentilesAndMax() {
        val s = SampleStats()
        (1..100).forEach { s.add(it.toDouble()) }
        assertEquals(50.5, s.percentile(50.0), 1e-9)
        assertEquals(100.0, s.max, 0.0)
        assertEquals(50.5, s.mean, 1e-9)
    }
}

/**
 * Pipeline wiring test with a scripted engine standing in for the native recognizer. This tests
 * frame ordering, finalization and report bookkeeping, not recognition (that is tested on device).
 */
class StreamingPipelineTest {
    private class ScriptedEngine : RecognitionEngine {
        val received = mutableListOf<Long>()
        override val model = ModelCatalog.entries.first()
        override val sampleRate = 16000
        override val isLoaded = true
        override fun load() = Unit
        override fun close() = Unit
        override fun transcribe(samples: FloatArray) = throw UnsupportedOperationException()
        override fun createStreamingSession(): StreamingSession = object : StreamingSession {
            var frames = 0
            override fun accept(samples: FloatArray): StreamingUpdate {
                received += samples[0].toLong()
                frames++
                return when (frames) {
                    2 -> StreamingUpdate(Hypothesis("hello", listOf(Token("hello", 0.05f))), null, 1, 1000)
                    3 -> StreamingUpdate(Hypothesis.EMPTY, Hypothesis("hello", listOf(Token("hello", 0.05f))), 1, 1000)
                    5 -> StreamingUpdate(Hypothesis("world", listOf(Token("world", 0.2f))), null, 1, 1000)
                    else -> StreamingUpdate(Hypothesis.EMPTY, null, 0, 0)
                }
            }
            override fun finish() = StreamingUpdate(Hypothesis.EMPTY, Hypothesis("world", listOf(Token("world", 0.2f))), 1, 1000)
            override fun close() = Unit
        }
    }

    private class CountingSource(private val frames: Int) : AudioSource {
        override val description = "counting"
        override val sampleRate = 16000
        override val isTestInput = true
        override fun run(frameSamples: Int, isActive: () -> Boolean, onFrame: (AudioFrame) -> Unit) {
            for (i in 0 until frames) {
                if (!isActive()) return
                onFrame(AudioFrame(i.toLong(), FloatArray(frameSamples) { i.toFloat() }, System.nanoTime()))
            }
        }
    }

    @Test
    fun framesAreProcessedInOrderAndSegmentsFinalized() = runBlocking {
        val engine = ScriptedEngine()
        val p = StreamingRecognitionPipeline(engine, CountingSource(6), frameMs = 50, queueSeconds = 10f)
        p.start(this)
        val report = p.awaitReport()
        assertEquals((0L until 6L).toList(), engine.received)
        assertEquals(listOf("hello", "world"), report.segments)
        assertEquals("hello world", report.text)
        assertEquals(6L, report.framesReceived)
        assertEquals(0L, report.framesDropped)
        assertEquals(null, report.error)
        assertTrue(report.tokenLatencyMs["count"]!! >= 2.0)
    }
}
