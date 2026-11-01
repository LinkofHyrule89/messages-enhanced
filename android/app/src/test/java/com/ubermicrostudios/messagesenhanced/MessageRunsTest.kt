package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.util.MessageRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRunsTest {

    private val t0 = 1_730_000_000_000L

    private fun msg(
        id: String,
        name: String,
        number: String = "",
        fromMe: Boolean = false,
        replyTo: String = "",
        ts: Long = t0,
    ) = MessageDto(
        messageId = id,
        senderName = name,
        senderNumber = number,
        isFromMe = fromMe,
        replyToId = replyTo,
        timestampMs = ts,
        body = "x",
    )

    @Test
    fun replyAfterOtherSender_showsSenderHeader() {
        val stephen = msg("1", "Stephen Compton", "+15551111", ts = t0)
        val scottReply = msg("2", "Scott Irvine", "+15552222", replyTo = "1", ts = t0 + 1_000)
        val rows = MessageRuns.rows(listOf(stephen, scottReply), isGroup = true)
        assertTrue(rows[0].showSender)
        assertTrue("reply that starts a run must show the reply author", rows[1].showSender)
        assertEquals("Scott Irvine", rows[1].message.senderName)
    }

    @Test
    fun consecutiveRepliesFromSameSender_onlyFirstShowsHeader() {
        val stephen = msg("1", "Stephen Compton", "+15551111", ts = t0)
        val r1 = msg("2", "Scott Irvine", "+15552222", replyTo = "1", ts = t0 + 1_000)
        val r2 = msg("3", "Scott Irvine", "+15552222", replyTo = "1", ts = t0 + 2_000)
        val r3 = msg("4", "Scott Irvine", "+15552222", replyTo = "3", ts = t0 + 3_000)
        val rows = MessageRuns.rows(listOf(stephen, r1, r2, r3), isGroup = true)
        assertTrue(rows[0].showSender)
        assertTrue(rows[1].showSender)
        assertFalse(rows[2].showSender)
        assertFalse(rows[3].showSender)
    }

    @Test
    fun replyDoesNotSuppressHeader_whenSenderChanges() {
        val a = msg("1", "Ada", "+1", replyTo = "old", ts = t0)
        val b = msg("2", "Bea", "+2", replyTo = "1", ts = t0 + 500)
        val rows = MessageRuns.rows(listOf(a, b), isGroup = true)
        assertTrue(rows[0].showSender)
        assertTrue(rows[1].showSender)
    }

    @Test
    fun nonGroup_neverShowsSender() {
        val a = msg("1", "Ada", "+1", replyTo = "x")
        val rows = MessageRuns.rows(listOf(a), isGroup = false)
        assertFalse(rows[0].showSender)
    }

    @Test
    fun sameRun_ignoresReplyToId() {
        val a = msg("1", "Scott", "+15552222", replyTo = "", ts = t0)
        val b = msg("2", "Scott", "+15552222", replyTo = "1", ts = t0 + 1_000)
        assertTrue(MessageRuns.sameRun(a, b))
    }
}
