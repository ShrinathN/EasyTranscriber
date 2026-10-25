package com.easytranscriber.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Live microphone capture, 16-bit mono, via AudioRecord. Caller must hold RECORD_AUDIO. */
class MicrophoneAudioSource(override val sampleRate: Int = 16_000) : AudioSource {
    override val description = "Microphone ($sampleRate Hz, VOICE_RECOGNITION)"
    override val isTestInput = false

    @SuppressLint("MissingPermission") // Checked by the caller before starting a session.
    override fun run(frameSamples: Int, isActive: () -> Boolean, onFrame: (AudioFrame) -> Unit) {
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) throw AudioCaptureException("Microphone does not support $sampleRate Hz mono 16-bit (code $minBytes)")
        val record = try {
            AudioRecord(
                // VOICE_RECOGNITION asks the platform to avoid AGC/noise processing tuned for calls.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes, frameSamples * 2 * 4),
            )
        } catch (e: Exception) {
            throw AudioCaptureException("Could not open the microphone: ${e.message}", e)
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw AudioCaptureException("Microphone failed to initialize (in use by another app, or permission revoked)")
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioCaptureException("Microphone did not start recording (it may be in use by another app)")
            }
            val pcm = ShortArray(frameSamples)
            var index = 0L
            while (isActive()) {
                var filled = 0
                while (filled < frameSamples) {
                    val n = record.read(pcm, filled, frameSamples - filled, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw AudioCaptureException("Microphone read failed (error $n)")
                    if (n == 0 && !isActive()) return
                    filled += n
                }
                val now = System.nanoTime()
                val samples = FloatArray(frameSamples) { pcm[it] / 32768f }
                onFrame(AudioFrame(index++, samples, now))
            }
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }
}
