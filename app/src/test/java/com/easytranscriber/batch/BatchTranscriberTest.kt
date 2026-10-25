package com.easytranscriber.batch

import com.easytranscriber.audio.WavChunkReader
import com.easytranscriber.audio.WavReader
import com.easytranscriber.audio.WavWriter
import com.easytranscriber.recognition.BatchSegmentResult
import com.easytranscriber.recognition.ModelCatalog
import com.easytranscriber.recognition.RecognitionEngine
import com.easytranscriber.recognition.StreamingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

class BatchTranscriberTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun wavWriterRoundTripsThroughBothReaders() {
        val f = tmp.newFile("a.wav")
        val samples = FloatArray(16_000 * 3) { ((it % 100) - 50) / 100f }
        WavWriter(f, 16_000).use { w -> samples.toList().chunked(1234).forEach { w.write(it.toFloatArray()) } }
        val whole = WavReader.read(f)
        assertEquals(samples.size, whole.samples.size)
        assertEquals(16_000, whole.sampleRate)
        var n = 0
        WavChunkReader.read(f, 5_000) { chunk, _, _ ->
            chunk.forEachIndexed { i, v -> assert(abs(v - samples[n + i]) < 1e-3f) }
            n += chunk.size
        }
        assertEquals(samples.size, n)
    }

    /** Segmenter stand-in: a "segment" ends at every run of exact zeros. */
    private class ZeroSplitSegmenter : SpeechSegmenter {
        private val current = ArrayList<Float>()
        private val done = ArrayList<FloatArray>()
        override fun accept(samples: FloatArray) {
            for (s in samples) {
                if (s == 0f) { if (current.isNotEmpty()) { done += current.toFloatArray(); current.clear() } }
                else current += s
            }
        }
        override fun flush() { if (current.isNotEmpty()) { done += current.toFloatArray(); current.clear() } }
        override fun drain(): List<FloatArray> = done.toList().also { done.clear() }
        override fun close() = Unit
    }

    /** Engine stand-in: returns the segment length so ordering is observable. */
    private class LengthEngine : RecognitionEngine {
        override val model = ModelCatalog.byId("sherpa-onnx-whisper-tiny")!!
        override val sampleRate = 16_000
        override val isLoaded = true
        override fun load() = Unit
        override fun close() = Unit
        override fun createStreamingSession(): StreamingSession = throw UnsupportedOperationException()
        override fun transcribe(samples: FloatArray) = BatchSegmentResult("seg${samples.size}", "en")
    }

    private fun wavWithSegments(vararg lengths: Int): java.io.File {
        val f = tmp.newFile()
        WavWriter(f, 16_000).use { w ->
            for (len in lengths) {
                w.write(FloatArray(len) { 0.5f })
                w.write(FloatArray(100))
            }
        }
        return f
    }

    @Test
    fun segmentsAreTranscribedInOrderAndJoined() {
        val f = wavWithSegments(16_000, 3_000, 9_000)
        val progress = mutableListOf<Float>()
        val r = BatchTranscriber(LengthEngine()) { ZeroSplitSegmenter() }.transcribe(f, { false }) { progress += it.fraction }
        assertEquals("seg16000 seg3000 seg9000", r.text)
        assertEquals(3, r.segments)
        assertEquals(1f, progress.last())
    }

    @Test
    fun silenceOnlyRecordingIsNoSpeech() {
        val f = tmp.newFile()
        WavWriter(f, 16_000).use { it.write(FloatArray(32_000)) }
        assertThrows(NoSpeechException::class.java) {
            BatchTranscriber(LengthEngine()) { ZeroSplitSegmenter() }.transcribe(f, { false }) {}
        }
    }

    @Test
    fun cancellationStopsTranscription() {
        val f = wavWithSegments(16_000, 16_000)
        assertThrows(BatchTranscriber.CancellationRequested::class.java) {
            BatchTranscriber(LengthEngine()) { ZeroSplitSegmenter() }.transcribe(f, { true }) {}
        }
    }

    @Test
    fun joinCollapsesWhitespace() {
        assertEquals("Hello there. How are you?", BatchTranscriber.joinSegments(listOf(" Hello  there. ", "How are\nyou?")))
    }
}
