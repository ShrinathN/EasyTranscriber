package com.easytranscriber.recognition

import com.easytranscriber.audio.WavReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class Asr4AllEngineTest {
    // Pieces 0..3, blank 4, then the control tokens.
    private val vocab = listOf(" hello", " world", "s", " nasa")
    private val meta = JSONObject("""{"ctrl": {"cap": 5, "allcaps": 6, "period": 7, "comma": 8, "question": 9}}""")

    private fun render(vararg slots: Int) = Asr4AllEngine.renderSlots(slots.toList(), meta, vocab, blank = 4)

    @Test
    fun rendersCaseAndPunctuationAndCollapsesRepeats() {
        assertEquals("Hello, worlds. NASA?", render(0, 0, 8, 4, 1, 2, 2, 7, 6, 3, 9))
    }

    @Test
    fun capitalizesAfterSentenceEndOnly() {
        assertEquals("Hello world. Hello world", render(0, 4, 1, 7, 0, 1))
        assertEquals("Hello World", render(0, 5, 1))
    }

    @Test
    fun punctuationBeforeAnyWordIsIgnored() {
        assertEquals("Hello", render(7, 0))
        assertEquals("", render())
    }

    @Test
    fun catalogEntriesAreConsistent() {
        val entries = ModelCatalog.entries.filter { it.architecture == ModelArchitecture.ASR4ALL }
        assertEquals(listOf("asr4all-s", "asr4all-m", "asr4all-l"), entries.map { it.id })
        for (e in entries) {
            assertEquals(e.id, e.files.values.toSet(), e.fileUrls.keys)
            assertEquals(e.id, e.files.values.toSet(), e.sha256.keys)
            assertTrue(e.id, e.fileUrls.values.all { it.startsWith("https://huggingface.co/futo-org/${e.id}/resolve/") })
            assertEquals(setOf(TranscriptionMode.RECORD_THEN_TRANSCRIBE), e.modes)
        }
    }

    /**
     * Runs a real model on LibriSpeech clips. Opt-in: set ASR4ALL_DIR to a folder holding the
     * model's five files and ASR4ALL_WAVS to a folder of 16 kHz WAVs with a trans.txt
     * ("<file> <reference>" per line).
     */
    @Test
    fun transcribesSpeechWithRealModel() {
        val dir = System.getenv("ASR4ALL_DIR")?.let(::File)
        val wavs = System.getenv("ASR4ALL_WAVS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && wavs != null && wavs.isDirectory)
        val entry = ModelCatalog.byId(System.getenv("ASR4ALL_ID") ?: "asr4all-s")!!
        val installed = ModelCatalog.inspect(dir!!, entry).copy(directory = dir, missingFiles = emptyList())
        Asr4AllEngine(installed, numThreads = 2).use { engine ->
            val t0 = System.nanoTime()
            engine.load()
            println("load ${(System.nanoTime() - t0) / 1_000_000} ms")
            val refs = File(wavs, "trans.txt").readLines().filter { it.isNotBlank() }
                .associate { it.substringBefore(' ') to it.substringAfter(' ') }
            var errors = 0
            var words = 0
            for ((name, ref) in refs) {
                val wav = File(wavs!!, name).takeIf { it.isFile } ?: continue
                val audio = WavReader.read(wav)
                if (audio.sampleRate != 16_000) continue
                val t = System.nanoTime()
                val text = engine.transcribe(audio.samples).text
                val sec = (System.nanoTime() - t) / 1e9
                val wer = WordErrorRate.compute(ref, text)
                errors += wer.errors
                words += wer.referenceWords
                println("%s %.1f s audio in %.2f s (RTFx %.1f), WER %.3f\n  %s".format(
                    name, audio.samples.size / 16_000.0, sec, audio.samples.size / 16_000.0 / sec, wer.wer, text))
                assertTrue("not punctuated/cased: $text", text.first().isUpperCase() && text.any { it in ".,?" })
            }
            assertTrue("no test clips found", words > 0)
            println("overall WER %.3f".format(errors.toDouble() / words))
            assertTrue("WER too high", errors.toDouble() / words < 0.15)
        }
    }
}
