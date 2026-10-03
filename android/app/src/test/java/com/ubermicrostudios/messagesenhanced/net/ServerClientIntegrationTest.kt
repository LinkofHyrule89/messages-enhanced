package com.ubermicrostudios.messagesenhanced.net

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: runs only when TM_HARNESS_URL and TM_HARNESS_SECRET are set (a local
 * throwaway Messages Enhanced server). Uses fake cookie values. Prints nothing
 * secret; assertions only look at outcome types/messages (names only).
 */
class ServerClientIntegrationTest {

    private val url: String? = System.getenv("TM_HARNESS_URL")
    private val secret: String? = System.getenv("TM_HARNESS_SECRET")

    private fun fakeCookies(vararg drop: String) =
        (CookieLogic.REQUIRED + listOf("NID", "__Secure-1PSID"))
            .filter { it !in drop }
            .associateWith { "fake-$it-value+/=&" }

    @Test
    fun savesWithCorrectPassword() {
        assumeTrue(url != null && secret != null)
        val out = ServerClient().send(url!!, secret!!, fakeCookies())
        assertTrue(out.toString(), out is ServerClient.Outcome.Saved)
        val msg = (out as ServerClient.Outcome.Saved).message
        assertTrue(msg, msg.startsWith("Saved:") && msg.contains("OSID") && msg.contains("NID"))
    }

    @Test
    fun wrongPassword() {
        assumeTrue(url != null && secret != null)
        val out = ServerClient().send(url!!, "definitely-not-the-password", fakeCookies())
        assertTrue(out.toString(), out is ServerClient.Outcome.WrongPassword)
    }

    @Test
    fun serverReportsMissing() {
        assumeTrue(url != null && secret != null)
        val out = ServerClient().send(url!!, secret!!, fakeCookies("OSID"))
        assertTrue(out.toString(), out is ServerClient.Outcome.Error &&
            out.message.contains("Missing required cookies: OSID"))
    }

    @Test
    fun unreachable() {
        assumeTrue(url != null && secret != null)
        val out = ServerClient().send("http://127.0.0.1:1", "x", fakeCookies())
        assertTrue(out.toString(), out is ServerClient.Outcome.Unreachable &&
            out.message.contains("Could not reach"))
    }
}
