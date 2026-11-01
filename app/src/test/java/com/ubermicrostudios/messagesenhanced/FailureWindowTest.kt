package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.FailureWindow
import com.ubermicrostudios.messagesenhanced.util.NetErrors
import com.ubermicrostudios.messagesenhanced.util.NetworkAvailability
import com.ubermicrostudios.messagesenhanced.util.ResumeRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.UnknownHostException

class FailureWindowTest {

    @Test
    fun needsFiveFailuresOverThirtySeconds_afterQuiet() {
        var now = 0L
        val window = FailureWindow(clock = { now })
        now = 10_000L
        repeat(5) { assertNull(window.failure("down")) }
        now = 25_000L
        assertNull(window.failure("down"))
        now = 41_000L
        assertEquals("down", window.failure("down"))
        window.success()
        now = 80_000L
        window.onForeground()
        now = 90_000L
        assertNull(window.failure("down"))
        now = 121_000L
        repeat(3) { assertNull(window.failure("down")) }
        assertEquals("down", window.failure("down"))
    }

    @Test
    fun onForeground_resetsStreak_soQuietDoesNotShowImmediately() {
        var now = 0L
        val window = FailureWindow(clock = { now })
        now = 10_000L
        repeat(5) { window.failure("down") }
        now = 41_000L
        assertEquals("down", window.failure("down"))
        now = 50_000L
        window.onForeground()
        now = 71_000L
        assertNull(window.failure("down"))
        now = 102_000L
        repeat(3) { assertNull(window.failure("down")) }
        assertEquals("down", window.failure("down"))
    }

    @Test
    fun onBackground_resetsStreak() {
        var now = 0L
        val window = FailureWindow(clock = { now })
        now = 10_000L
        repeat(5) { window.failure("down") }
        now = 41_000L
        assertEquals("down", window.failure("down"))
        window.onBackground()
        now = 42_000L
        assertNull(window.failure("down"))
        now = 73_000L
        repeat(3) { assertNull(window.failure("down")) }
        assertEquals("down", window.failure("down"))
    }

    @Test
    fun ignoresFailuresWhileBackgrounded() {
        var now = 0L
        val gate = FakeNetwork(isForeground = false)
        val window = FailureWindow(clock = { now }, gate = gate)
        now = 10_000L
        repeat(8) { assertNull(window.failure("down")) }
        gate.isForeground = true
        now = 41_000L
        assertNull(window.failure("down"))
        now = 72_000L
        repeat(3) { assertNull(window.failure("down")) }
        assertEquals("down", window.failure("down"))
    }

    @Test
    fun ignoresFailuresWhileOffline() {
        var now = 0L
        val gate = FakeNetwork(isNetworkValidated = false)
        val window = FailureWindow(clock = { now }, gate = gate)
        now = 10_000L
        repeat(8) { assertNull(window.failure("down")) }
        gate.isNetworkValidated = true
        now = 41_000L
        assertNull(window.failure("down"))
        now = 72_000L
        repeat(3) { assertNull(window.failure("down")) }
        assertEquals("down", window.failure("down"))
    }

    @Test
    fun fiveFailuresOverThirtySeconds_onlyWhenForegroundedAndValidated() {
        var now = 0L
        val gate = FakeNetwork()
        val window = FailureWindow(clock = { now }, gate = gate)
        now = 21_000L
        repeat(4) { assertNull(window.failure("down")) }
        now = 52_000L
        assertEquals("down", window.failure("down"))
    }

    @Test
    fun fiveFailuresInsideThirtySeconds_stayQuiet() {
        var now = 0L
        val window = FailureWindow(clock = { now })
        now = 21_000L
        repeat(4) { assertNull(window.failure("down")) }
        now = 51_000L
        assertNull(window.failure("down"))
        now = 51_001L
        assertEquals("down", window.failure("down"))
    }

    @Test
    fun inQuiet_tracksForegroundWindow() {
        var now = 0L
        val window = FailureWindow(clock = { now })
        assertTrue(window.inQuiet())
        now = 19_999L
        assertTrue(window.inQuiet())
        now = 20_000L
        assertFalse(window.inQuiet())
        window.onForeground()
        assertTrue(window.inQuiet())
        now = 40_000L
        assertFalse(window.inQuiet())
    }

    @Test
    fun netErrors_mapsUnknownHost_andRecognizesBanner() {
        val text = NetErrors.friendly(
            UnknownHostException("Unable to resolve host \"x\": No address associated with hostname"),
        )
        assertEquals(NetErrors.UNREACHABLE, text)
        assertTrue(NetErrors.isNetworkMessage(text))
        assertTrue(NetErrors.isNetworkMessage("Unable to resolve host foo"))
        assertFalse(NetErrors.isNetworkMessage("login required"))
        assertFalse(NetErrors.isNetworkMessage(null))
    }

    @Test
    fun resumeRetry_backsOffThenSucceeds() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val result = runBlocking {
            ResumeRetry.run(
                gate = FakeNetwork(),
                inQuiet = { true },
                delayMs = { waits += it },
            ) {
                attempts++
                if (attempts < 3) throw UnknownHostException("x")
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 3_000L), waits)
        assertEquals(longArrayOf(1_000L, 3_000L, 7_000L).toList(), ResumeRetry.BACKOFF_MS.toList())
        assertEquals(3, ResumeRetry.MAX_ATTEMPTS)
    }

    @Test
    fun resumeRetry_givesUpAfterThreeIoFailures() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        try {
            runBlocking {
                ResumeRetry.run(
                    gate = FakeNetwork(),
                    inQuiet = { true },
                    delayMs = { waits += it },
                ) {
                    attempts++
                    throw UnknownHostException("x")
                }
            }
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertEquals("x", e.message)
        }
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 3_000L), waits)
    }

    @Test
    fun resumeRetry_skipsNonIoErrors() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        try {
            runBlocking {
                ResumeRetry.run(
                    gate = FakeNetwork(),
                    inQuiet = { true },
                    delayMs = { waits += it },
                ) {
                    attempts++
                    error("boom")
                }
            }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, attempts)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun resumeRetry_doesNotRetryWhileBackgroundedOrOutsideQuiet() {
        var attempts = 0
        try {
            runBlocking {
                ResumeRetry.run(
                    gate = FakeNetwork(isForeground = false),
                    inQuiet = { true },
                    delayMs = { fail("delay") },
                ) {
                    attempts++
                    throw UnknownHostException("x")
                }
            }
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertEquals("x", e.message)
        }
        assertEquals(1, attempts)

        attempts = 0
        try {
            runBlocking {
                ResumeRetry.run(
                    gate = FakeNetwork(),
                    inQuiet = { false },
                    delayMs = { fail("delay") },
                ) {
                    attempts++
                    throw UnknownHostException("x")
                }
            }
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertEquals("x", e.message)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun resumeRetry_stopsAfterBackgroundDuringBackoff() {
        var attempts = 0
        val gate = FakeNetwork()
        try {
            runBlocking {
                ResumeRetry.run(
                    gate = gate,
                    inQuiet = { true },
                    delayMs = { gate.isForeground = false },
                ) {
                    attempts++
                    throw UnknownHostException("x")
                }
            }
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertEquals("x", e.message)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun resumeRetry_propagatesCancellation() {
        try {
            runBlocking {
                ResumeRetry.run(
                    gate = FakeNetwork(),
                    inQuiet = { true },
                    delayMs = { throw CancellationException("stop") },
                ) {
                    throw UnknownHostException("x")
                }
            }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertTrue(e.message == "stop" || e.cause?.message == "stop" || e.message?.contains("cancel", true) == true)
        }
    }

    private class FakeNetwork(
        override var isForeground: Boolean = true,
        override var isNetworkValidated: Boolean = true,
        override var foregroundGeneration: Long = 1L,
    ) : NetworkAvailability
}
