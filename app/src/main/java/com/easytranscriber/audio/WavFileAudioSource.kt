package com.easytranscriber.audio

import java.io.File
import java.util.concurrent.locks.LockSupport

/**
 * Plays WAV files through the pipeline at real-time pace, with [gapSec] of silence between files.
 * TEST INPUT: used for reproducible latency/accuracy measurements on the device. It exercises the
 * same pipeline as the microphone but bypasses the acoustic path and AudioRecord.
 */
class WavFileAudioSource(
    private val files: List<File>,
    override val sampleRate: Int = 16_000,
    private val gapSec: Float = 1.0f,
    private val leadingSilenceSec: Float = 0.5f,
) : AudioSource {
    override val description: String =
        "WAV playback (${files.size} file${if (files.size == 1) "" else "s"}, real-time paced)"
    override val isTestInput = true

    override fun run(frameSamples: Int, isActive: () -> Boolean, onFrame: (AudioFrame) -> Unit) {
        val audio = buildTimeline()
        val frameNanos = frameSamples * 1_000_000_000L / sampleRate
        val start = System.nanoTime()
        var index = 0L
        var offset = 0
        while (offset < audio.size && isActive()) {
            val end = minOf(audio.size, offset + frameSamples)
            val due = start + (index + 1) * frameNanos
            val wait = due - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            onFrame(AudioFrame(index, audio.copyOfRange(offset, end), due))
            offset = end
            index++
        }
    }

    private fun buildTimeline(): FloatArray {
        val parts = files.map { f ->
            val wav = try {
                WavReader.read(f)
            } catch (e: Exception) {
                throw AudioCaptureException("Cannot read ${f.name}: ${e.message}", e)
            }
            if (wav.sampleRate != sampleRate) {
                throw AudioCaptureException("${f.name} is ${wav.sampleRate} Hz; this test source requires $sampleRate Hz")
            }
            wav.samples
        }
        val gap = (gapSec * sampleRate).toInt()
        val lead = (leadingSilenceSec * sampleRate).toInt()
        val total = lead + parts.sumOf { it.size } + gap * parts.size
        val out = FloatArray(total)
        var pos = lead
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size + gap
        }
        return out
    }
}
