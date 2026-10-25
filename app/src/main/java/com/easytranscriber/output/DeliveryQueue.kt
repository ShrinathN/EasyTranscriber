package com.easytranscriber.output

import com.easytranscriber.hid.EncodeResult
import com.easytranscriber.hid.KeyStroke
import com.easytranscriber.hid.KeyboardLayout
import com.easytranscriber.hid.SendResult
import com.easytranscriber.hid.TextEncoder
import com.easytranscriber.hid.UnsupportedChar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DeliveryState {
    /** Nothing waiting. */
    data object Idle : DeliveryState

    data class Sending(val pendingChars: Int) : DeliveryState

    /** Text is waiting and no host is connected. */
    data class WaitingForHost(val pendingChars: Int) : DeliveryState

    /**
     * Delivery stopped (disconnect or reconnect) and needs a user decision. [uncertain] was
     * submitted shortly before the connection dropped and may or may not have reached the host.
     */
    data class WaitingToResume(val pending: String, val uncertain: String, val connected: Boolean) : DeliveryState

    /** The next text contains characters the keyboard layout cannot type. */
    data class Blocked(val unsupported: List<UnsupportedChar>, val pendingChars: Int) : DeliveryState
}

/**
 * Ordered, lossless queue of committed text for the HID keyboard, shared by both modes.
 *
 * - Text is sent in small chunks while a host is connected.
 * - `sendReport()` acceptance does not guarantee host receipt (Stage 2 finding), so on a
 *   disconnect every chunk submitted within [uncertainWindowMs] becomes "uncertain".
 * - After any disconnect or reconnect, delivery waits for an explicit [resume] decision:
 *   resend the uncertain text (risking duplicates) or skip it (risking loss). Nothing is resent
 *   automatically.
 * - Unsupported characters block delivery until the user removes them ([skipUnsupported]) or clears.
 */
class DeliveryQueue(
    scope: CoroutineScope,
    private val layout: KeyboardLayout,
    private val send: suspend (List<KeyStroke>) -> SendResult,
    private val clock: () -> Long = System::nanoTime,
    private val uncertainWindowMs: Long = 1_500,
    private val chunkChars: Int = 8,
) {
    private val lock = Any()
    private val pending = StringBuilder()
    private var uncertain = ""
    private var connected = false
    private var awaitingDecision = false
    private var blocked: List<UnsupportedChar>? = null
    private var generation = 0L
    private val recent = ArrayDeque<Pair<Long, String>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<DeliveryState>(DeliveryState.Idle)
    val state: StateFlow<DeliveryState> = _state.asStateFlow()

    private val _deliveredChars = MutableStateFlow(0L)
    /** Characters whose keystrokes were accepted by the transport and are not considered uncertain. */
    val deliveredChars: StateFlow<Long> = _deliveredChars.asStateFlow()

    init {
        scope.launch {
            for (signal in wake) drain()
        }
    }

    fun enqueue(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        synchronized(lock) {
            pending.append(normalized)
            publish()
        }
        wake.trySend(Unit)
    }

    fun onConnectionChanged(isConnected: Boolean) {
        synchronized(lock) {
            if (connected == isConnected) return
            if (!isConnected) markRecentUncertain()
            // Any connection change with text outstanding requires the user to decide.
            if (pending.isNotEmpty() || uncertain.isNotEmpty()) awaitingDecision = true
            connected = isConnected
            publish()
        }
        wake.trySend(Unit)
    }

    /** Continue after an interruption; [resendUncertain] re-types the uncertain text first. */
    fun resume(resendUncertain: Boolean) {
        synchronized(lock) {
            if (resendUncertain && uncertain.isNotEmpty()) pending.insert(0, uncertain)
            uncertain = ""
            awaitingDecision = false
            publish()
        }
        wake.trySend(Unit)
    }

    /** Discards all text that has not been delivered. */
    fun clear() {
        synchronized(lock) {
            pending.setLength(0)
            uncertain = ""
            awaitingDecision = false
            blocked = null
            generation++
            publish()
        }
    }

    /** Removes the characters that the layout cannot type (explicit user action) and continues. */
    fun skipUnsupported() {
        synchronized(lock) {
            val result = TextEncoder.encode(pending.toString(), layout)
            if (result is EncodeResult.Unsupported) {
                val bad = result.chars.map { it.index }.toSet()
                val kept = StringBuilder()
                var i = 0
                while (i < pending.length) {
                    val cp = pending.codePointAt(i)
                    if (i !in bad) kept.appendCodePoint(cp)
                    i += Character.charCount(cp)
                }
                pending.setLength(0)
                pending.append(kept)
            }
            blocked = null
            publish()
        }
        wake.trySend(Unit)
    }

    private suspend fun drain() {
        while (true) {
            val (chunk, gen) = synchronized(lock) {
                if (!connected || awaitingDecision || blocked != null || pending.isEmpty()) {
                    publish()
                    return
                }
                val text = takeChunk()
                if (TextEncoder.encode(text, layout) is EncodeResult.Unsupported) {
                    // Show every untypeable character still queued, so "remove" hides nothing.
                    blocked = (TextEncoder.encode(pending.toString(), layout) as EncodeResult.Unsupported).chars
                    publish()
                    return
                }
                publish()
                text to generation
            }
            val strokes = (TextEncoder.encode(chunk, layout) as EncodeResult.Ok).strokes
            val result = send(strokes)
            synchronized(lock) {
                // After line-ending normalization every queued character is exactly one stroke.
                // An ambiguous stroke leaves the queue and is treated as uncertain below.
                val submitted = if (result.isComplete) chunk.length else result.completed + if (result.ambiguousStroke) 1 else 0
                if (gen == generation) pending.delete(0, minOf(submitted, pending.length))
                val sentText = chunk.substring(0, submitted)
                if (sentText.isNotEmpty()) {
                    recent.addLast(clock() to sentText)
                    _deliveredChars.value += sentText.length
                    pruneRecent()
                }
                if (!result.isComplete) {
                    markRecentUncertain()
                    awaitingDecision = true
                }
                publish()
            }
        }
    }

    private fun takeChunk(): String {
        var end = minOf(chunkChars, pending.length)
        if (end < pending.length && Character.isHighSurrogate(pending[end - 1])) end++
        return pending.substring(0, end)
    }

    private fun pruneRecent() {
        val cutoff = clock() - uncertainWindowMs * 1_000_000
        while (recent.isNotEmpty() && recent.first().first < cutoff) recent.removeFirst()
    }

    private fun markRecentUncertain() {
        pruneRecent()
        if (recent.isEmpty()) return
        val text = recent.joinToString("") { it.second }
        recent.clear()
        uncertain += text
        _deliveredChars.value -= text.length
    }

    private fun publish() {
        _state.value = when {
            blocked != null -> DeliveryState.Blocked(blocked!!, pending.length)
            awaitingDecision -> DeliveryState.WaitingToResume(pending.toString(), uncertain, connected)
            pending.isEmpty() -> DeliveryState.Idle
            !connected -> DeliveryState.WaitingForHost(pending.length)
            else -> DeliveryState.Sending(pending.length)
        }
    }
}
