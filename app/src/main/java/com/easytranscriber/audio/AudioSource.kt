package com.easytranscriber.audio

/**
 * A block of mono float samples in [-1, 1].
 *
 * [endTimeNanos] is the System.nanoTime() at which the last sample of this frame was captured
 * (microphone: when AudioRecord.read returned it, which excludes hardware input latency;
 * file playback: the scheduled real-time instant of the frame's end).
 */
class AudioFrame(val index: Long, val samples: FloatArray, val endTimeNanos: Long)

class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AudioSource {
    val description: String
    val sampleRate: Int

    /** True for synthetic/test inputs (e.g. file playback); shown in reports. */
    val isTestInput: Boolean

    /**
     * Produces frames of [frameSamples] until [isActive] returns false or the source is exhausted.
     * Blocking; run on a dedicated thread. Releases its resources before returning.
     */
    @Throws(AudioCaptureException::class)
    fun run(frameSamples: Int, isActive: () -> Boolean, onFrame: (AudioFrame) -> Unit)
}
