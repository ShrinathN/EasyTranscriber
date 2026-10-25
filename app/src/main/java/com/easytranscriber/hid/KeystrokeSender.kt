package com.easytranscriber.hid

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Destination for raw keyboard input reports (the Bluetooth HID connection in production). */
fun interface HidReportSink {
    /** Returns true if the report was accepted by the transport. */
    fun sendReport(report: ByteArray): Boolean
}

/**
 * Outcome of typing a sequence of strokes.
 *
 * [completed] counts strokes whose press AND release were both accepted by the transport.
 * When [ambiguousStroke] is true, the press of stroke number [completed] was accepted but its
 * release was not, so the host may or may not have registered that character (and may see the
 * key as held). Callers must not blindly retry that stroke.
 */
data class SendResult(
    val requested: Int,
    val completed: Int,
    val ambiguousStroke: Boolean,
) {
    val isComplete: Boolean get() = completed == requested && !ambiguousStroke
}

/**
 * Types key strokes.
 *
 * With [rollover], a press of a different key with the same modifiers directly replaces the
 * previous press (the boot keyboard report lists keys currently down, so the previous key is
 * released by the same report). An explicit all-keys-released report is sent before repeating
 * the same key, before changing modifiers, and at the end, so modifiers are never left held and
 * "ll" registers as two presses. A delay between reports gives hosts time to process each one.
 */
class KeystrokeSender(
    private val sink: HidReportSink,
    private val interReportDelayMs: Long = DEFAULT_INTER_REPORT_DELAY_MS,
    private val releaseRetries: Int = 3,
    private val rollover: Boolean = true,
) {
    private val mutex = Mutex()

    suspend fun send(strokes: List<KeyStroke>): SendResult = mutex.withLock {
        var held: KeyStroke? = null
        for ((index, stroke) in strokes.withIndex()) {
            val h = held
            if (h != null && (!rollover || h.usage == stroke.usage || h.modifiers != stroke.modifiers)) {
                // Stroke index-1 is down; if it can't be released it is ambiguous.
                if (!sendRelease()) return SendResult(strokes.size, index - 1, ambiguousStroke = true)
                held = null
                delay(interReportDelayMs)
            }
            if (!sink.sendReport(stroke.pressReport())) {
                if (held != null && !sendRelease()) {
                    return SendResult(strokes.size, index - 1, ambiguousStroke = true)
                }
                return SendResult(strokes.size, index, ambiguousStroke = false)
            }
            held = stroke
            delay(interReportDelayMs)
        }
        if (held != null && !sendRelease()) return SendResult(strokes.size, strokes.size - 1, ambiguousStroke = true)
        SendResult(strokes.size, strokes.size, ambiguousStroke = false)
    }

    /** Sends an all-released report; used for cleanup after errors or before disconnecting. */
    suspend fun releaseAll(): Boolean = mutex.withLock { sendRelease() }

    private suspend fun sendRelease(): Boolean {
        repeat(releaseRetries) { attempt ->
            if (sink.sendReport(KeyboardReport.release())) return true
            if (attempt < releaseRetries - 1) delay(interReportDelayMs)
        }
        return false
    }

    companion object {
        const val DEFAULT_INTER_REPORT_DELAY_MS = 10L
    }
}
