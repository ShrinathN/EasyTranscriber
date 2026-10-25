package com.easytranscriber.hid

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystrokeSenderTest {
    /** Records reports; rejects reports whose 0-based position is in [failAt]. */
    private class RecordingSink(private val failAt: Set<Int> = emptySet()) : HidReportSink {
        val accepted = mutableListOf<ByteArray>()
        private var calls = 0
        override fun sendReport(report: ByteArray): Boolean {
            val n = calls++
            if (n in failAt) return false
            accepted += report.copyOf()
            return true
        }
    }

    private fun strokes(text: String) = (TextEncoder.encode(text, UsQwertyLayout) as EncodeResult.Ok).strokes

    @Test
    fun everyPressIsFollowedByFullRelease() = runTest {
        val sink = RecordingSink()
        val result = KeystrokeSender(sink, rollover = false).send(strokes("Hi!"))
        assertTrue(result.isComplete)
        assertEquals(6, sink.accepted.size)
        for (i in sink.accepted.indices step 2) {
            assertArrayEquals(KeyboardReport.release(), sink.accepted[i + 1])
        }
        assertArrayEquals(byteArrayOf(0x02, 0, 0x0B, 0, 0, 0, 0, 0), sink.accepted[0]) // Shift+h
        assertArrayEquals(byteArrayOf(0x00, 0, 0x0C, 0, 0, 0, 0, 0), sink.accepted[2]) // i
        assertArrayEquals(byteArrayOf(0x02, 0, 0x1E, 0, 0, 0, 0, 0), sink.accepted[4]) // Shift+1
    }

    @Test
    fun repeatedCharactersAreSeparatePresses() = runTest {
        val sink = RecordingSink()
        KeystrokeSender(sink, rollover = false).send(strokes("ll"))
        assertEquals(4, sink.accepted.size)
        assertArrayEquals(sink.accepted[0], sink.accepted[2])
        assertArrayEquals(KeyboardReport.release(), sink.accepted[1])
    }

    @Test
    fun failedPressStopsWithExactCountAndNoAmbiguity() = runTest {
        // Reports: 0=press a,1=rel,2=press b,3=rel,4=press c (fails)
        val sink = RecordingSink(failAt = setOf(4))
        val result = KeystrokeSender(sink, rollover = false).send(strokes("abcd"))
        assertEquals(2, result.completed)
        assertFalse(result.ambiguousStroke)
        assertFalse(result.isComplete)
    }

    @Test
    fun failedReleaseIsRetriedBeforeGivingUp() = runTest {
        val sink = RecordingSink(failAt = setOf(1))
        val result = KeystrokeSender(sink, releaseRetries = 3, rollover = false).send(strokes("ab"))
        assertTrue(result.isComplete)
    }

    @Test
    fun persistentReleaseFailureIsReportedAsAmbiguous() = runTest {
        // Press of 'a' accepted, then all three release attempts fail.
        val sink = RecordingSink(failAt = setOf(1, 2, 3))
        val result = KeystrokeSender(sink, releaseRetries = 3, rollover = false).send(strokes("ab"))
        assertEquals(0, result.completed)
        assertTrue(result.ambiguousStroke)
    }

    // ---- rollover ----

    @Test
    fun rolloverSkipsReleasesBetweenDifferentKeys() = runTest {
        val sink = RecordingSink()
        assertTrue(KeystrokeSender(sink).send(strokes("abc")).isComplete)
        // press a, press b, press c, release
        assertEquals(4, sink.accepted.size)
        assertArrayEquals(byteArrayOf(0, 0, 0x05, 0, 0, 0, 0, 0), sink.accepted[1])
        assertArrayEquals(KeyboardReport.release(), sink.accepted[3])
    }

    @Test
    fun rolloverReleasesForRepeatsAndModifierChanges() = runTest {
        val sink = RecordingSink()
        KeystrokeSender(sink).send(strokes("llA"))
        // press l, release, press l, release (modifier change), press Shift+a, release
        assertEquals(6, sink.accepted.size)
        assertArrayEquals(KeyboardReport.release(), sink.accepted[1])
        assertArrayEquals(KeyboardReport.release(), sink.accepted[3])
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), sink.accepted[4])
    }

    @Test
    fun rolloverFailedPressReleasesPreviousAndCountsExactly() = runTest {
        // Reports: 0 press a, 1 press b, 2 press c (fails) -> release b sent -> 2 completed.
        val sink = RecordingSink(failAt = setOf(2))
        val r = KeystrokeSender(sink).send(strokes("abcd"))
        assertEquals(2, r.completed)
        assertFalse(r.ambiguousStroke)
        assertArrayEquals(KeyboardReport.release(), sink.accepted.last())
    }

    @Test
    fun rolloverFinalReleaseFailureIsAmbiguous() = runTest {
        // Reports: 0 a, 1 b, 2..4 release attempts all fail.
        val sink = RecordingSink(failAt = setOf(2, 3, 4))
        val r = KeystrokeSender(sink).send(strokes("ab"))
        assertEquals(1, r.completed)
        assertTrue(r.ambiguousStroke)
    }
}
