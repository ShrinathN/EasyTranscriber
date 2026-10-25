package com.easytranscriber.output

import com.easytranscriber.hid.KeyStroke
import com.easytranscriber.hid.SendResult
import com.easytranscriber.hid.UsQwertyLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 0L
    /** Characters "typed" on the simulated host, decoded back from strokes. */
    private val host = StringBuilder()
    /** If set, the n-th stroke from now fails (transport rejects it). */
    @Volatile private var failAfterStrokes: Int? = null
    private val reverse = (0x20..0x7E).map { it.toChar() }.plus('\n').associateBy { UsQwertyLayout.strokeFor(it.code)!! }

    private val send: suspend (List<KeyStroke>) -> SendResult = { strokes ->
        var done = 0
        var ok = true
        for (s in strokes) {
            val f = failAfterStrokes
            if (f != null && f <= 0) { ok = false; break }
            if (f != null) failAfterStrokes = f - 1
            synchronized(host) { host.append(reverse.getValue(s)) }
            now += 30_000_000 // 30 ms per character
            done++
        }
        SendResult(strokes.size, done, ambiguousStroke = false).let { if (ok) it else it }
    }

    private fun queue() = DeliveryQueue(scope, UsQwertyLayout, send, clock = { now }, uncertainWindowMs = 100, chunkChars = 4)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun DeliveryQueue.awaitState(pred: (DeliveryState) -> Boolean) =
        withTimeout(5_000) { state.first(pred) }

    @Test
    fun deliversInOrderWhenConnected() = runBlocking {
        val q = queue()
        q.onConnectionChanged(true)
        q.enqueue("Hello, ")
        q.enqueue("world!\r\nBye")
        q.awaitState { it == DeliveryState.Idle && host.length == 17 }
        assertEquals("Hello, world!\nBye", host.toString())
        assertEquals(17L, q.deliveredChars.value)
    }

    @Test
    fun textQueuedWhileDisconnectedWaitsForUserAfterConnecting() = runBlocking {
        val q = queue()
        q.enqueue("abc")
        assertTrue(q.state.value is DeliveryState.WaitingForHost)
        q.onConnectionChanged(true)
        val s = q.awaitState { it is DeliveryState.WaitingToResume }
        assertEquals("abc", (s as DeliveryState.WaitingToResume).pending)
        assertEquals("", host.toString())
        q.resume(resendUncertain = false)
        q.awaitState { it == DeliveryState.Idle }
        assertEquals("abc", host.toString())
    }

    @Test
    fun failedSendMarksRecentTextUncertainAndNeverAutoResends() = runBlocking {
        val q = queue()
        q.onConnectionChanged(true)
        failAfterStrokes = 6 // "abcdef" reach the host, then the transport fails
        q.enqueue("abcdefghij")
        val s = q.awaitState { it is DeliveryState.WaitingToResume } as DeliveryState.WaitingToResume
        assertEquals("abcdef", host.toString())
        // With a 100 ms window and 30 ms/char, the last chunk(s) submitted are uncertain.
        assertTrue("uncertain='${s.uncertain}'", s.uncertain.isNotEmpty() && "abcdef".endsWith(s.uncertain))
        assertEquals("ghij", s.pending)
        // Skip uncertain: continue with pending only, no duplicates.
        failAfterStrokes = null
        q.resume(resendUncertain = false)
        q.awaitState { it == DeliveryState.Idle }
        assertEquals("abcdefghij", host.toString())
    }

    @Test
    fun resendUncertainRetypesItFirst() = runBlocking {
        val q = queue()
        q.onConnectionChanged(true)
        q.enqueue("wxyz")
        q.awaitState { it == DeliveryState.Idle && host.length == 4 }
        q.onConnectionChanged(false) // just typed: inside the uncertain window
        val s = q.awaitState { it is DeliveryState.WaitingToResume } as DeliveryState.WaitingToResume
        assertEquals("wxyz", s.uncertain)
        q.onConnectionChanged(true)
        q.resume(resendUncertain = true)
        q.awaitState { it == DeliveryState.Idle && host.length == 8 }
        assertEquals("wxyzwxyz", host.toString())
    }

    @Test
    fun disconnectLongAfterTypingLeavesNothingUncertain() = runBlocking {
        val q = queue()
        q.onConnectionChanged(true)
        q.enqueue("done")
        q.awaitState { it == DeliveryState.Idle && host.length == 4 }
        now += 1_000_000_000 // 1 s later
        q.onConnectionChanged(false)
        assertEquals(DeliveryState.Idle, q.state.value)
    }

    @Test
    fun unsupportedCharactersBlockOnlyWhenReachedAndCanBeRemoved() = runBlocking {
        val q = queue()
        q.onConnectionChanged(true)
        q.enqueue("ok café ok")
        val s = q.awaitState { it is DeliveryState.Blocked } as DeliveryState.Blocked
        assertEquals("é", s.unsupported.single().display)
        assertTrue(host.toString().startsWith("ok ")) // text before the block was delivered
        q.skipUnsupported()
        q.awaitState { it == DeliveryState.Idle }
        assertEquals("ok caf ok", host.toString())
    }

    @Test
    fun clearDiscardsPendingText() = runBlocking {
        val q = queue()
        q.enqueue("never sent")
        q.clear()
        q.onConnectionChanged(true)
        assertEquals(DeliveryState.Idle, q.state.value)
        assertEquals("", host.toString())
    }
}
