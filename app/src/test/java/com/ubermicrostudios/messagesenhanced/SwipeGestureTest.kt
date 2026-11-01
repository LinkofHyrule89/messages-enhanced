package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.util.ReplyTexts
import com.ubermicrostudios.messagesenhanced.util.SwipeMath
import com.ubermicrostudios.messagesenhanced.util.SwipeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGestureTest {

    @Test
    fun decide_verticalBeatsASmallerHorizontalMove() {
        assertEquals(SwipeMath.Decision.VERTICAL, SwipeMath.decide(10f, 19f))
        assertEquals(SwipeMath.Decision.VERTICAL, SwipeMath.decide(-5f, -30f))
        assertEquals(SwipeMath.Decision.VERTICAL, SwipeMath.decide(18f, 19f))
    }

    @Test
    fun decide_waitsUntilHorizontalIntentClearsTheLockAndTheBias() {
        assertEquals(SwipeMath.Decision.PENDING, SwipeMath.decide(10f, 0f))
        assertEquals(SwipeMath.Decision.PENDING, SwipeMath.decide(17f, 0f))
        assertEquals(SwipeMath.Decision.PENDING, SwipeMath.decide(20f, 15f))
        assertEquals(SwipeMath.Decision.PENDING, SwipeMath.decide(18f, 18f))
        assertEquals(SwipeMath.Decision.PENDING, SwipeMath.decide(19f, 18f))
    }

    @Test
    fun decide_rightIsReplyAndLeftIsTime() {
        assertEquals(SwipeMath.Decision.REPLY, SwipeMath.decide(18f, 0f))
        assertEquals(SwipeMath.Decision.REPLY, SwipeMath.decide(30f, 18f))
        assertEquals(SwipeMath.Decision.TIME, SwipeMath.decide(-18f, 0f))
        assertEquals(SwipeMath.Decision.TIME, SwipeMath.decide(-40f, 10f))
    }

    @Test
    fun replyOffset_matchesTheWebRubberBand() {
        assertEquals(0f, SwipeMath.replyOffset(-10f), 0.001f)
        assertEquals(0f, SwipeMath.replyOffset(0f), 0.001f)
        assertEquals(50f, SwipeMath.replyOffset(50f), 0.001f)
        assertEquals(96f, SwipeMath.replyOffset(96f), 0.001f)
        assertEquals(131f, SwipeMath.replyOffset(196f), 0.001f)
        assertEquals(SwipeMath.REPLY_MAX, SwipeMath.replyOffset(10_000f), 0.001f)
    }

    @Test
    fun replyArmsAtTheThreshold_andScalesTheIcon() {
        assertFalse(SwipeMath.replyArmed(SwipeMath.replyOffset(95f)))
        assertTrue(SwipeMath.replyArmed(SwipeMath.replyOffset(96f)))
        assertEquals(0.5f, SwipeMath.replyProgress(48f), 0.001f)
        assertEquals(1f, SwipeMath.replyProgress(96f), 0.001f)
        assertEquals(1f, SwipeMath.replyProgress(200f), 0.001f)
        assertEquals(0.6f, SwipeMath.replyIconScale(0f), 0.001f)
        assertEquals(0.8f, SwipeMath.replyIconScale(0.5f), 0.001f)
        assertEquals(1f, SwipeMath.replyIconScale(1f), 0.001f)
    }

    @Test
    fun timeOffset_matchesTheWebRubberBand() {
        assertEquals(0f, SwipeMath.timeOffset(40f), 0.001f)
        assertEquals(0f, SwipeMath.timeOffset(0f), 0.001f)
        assertEquals(40f, SwipeMath.timeOffset(-40f), 0.001f)
        val knee = SwipeMath.TIME_MAX * SwipeMath.TIME_KNEE
        assertEquals(knee, SwipeMath.timeOffset(-knee), 0.001f)
        assertEquals(190f, SwipeMath.timeOffset(-(knee + 100f)), 0.001f)
        assertEquals(SwipeMath.TIME_MAX, SwipeMath.timeOffset(-10_000f), 0.001f)
    }

    @Test
    fun edgeBand_isStrictAndUsesTheViewport() {
        assertTrue(SwipeMath.blockedByEdge(10f, 400f))
        assertTrue(SwipeMath.blockedByEdge(27.9f, 400f))
        assertFalse(SwipeMath.blockedByEdge(28f, 400f))
        assertFalse(SwipeMath.blockedByEdge(100f, 400f))
        assertFalse(SwipeMath.blockedByEdge(372f, 400f))
        assertTrue(SwipeMath.blockedByEdge(372.1f, 400f))
        assertTrue(SwipeMath.blockedByEdge(390f, 400f))
    }

    @Test
    fun springAndHaptic_matchTheWebTiming() {
        assertEquals(220L, SwipeMath.SPRING_MS)
        assertEquals(150L, SwipeMath.ICON_FADE_MS)
        assertEquals(10L, SwipeMath.HAPTIC_MS)
        assertEquals(0.25f, SwipeMath.EASE_X1, 0.001f)
        assertEquals(0.1f, SwipeMath.EASE_Y1, 0.001f)
        assertEquals(0.25f, SwipeMath.EASE_X2, 0.001f)
        assertEquals(1f, SwipeMath.EASE_Y2, 0.001f)
        assertEquals(70f, SwipeMath.TIME_LABEL_GAP, 0.001f)
    }

    @Test
    fun session_lockRebasesTheOriginSoTheRowDoesNotJump() {
        val session = SwipeSession()
        assertTrue(session.down(100f, 100f, 400f))
        val locked = session.move(120f, 100f, canReply = true)
        assertEquals(SwipeSession.Mode.REPLY, session.mode)
        val reply = locked as SwipeSession.Move.Reply
        assertEquals(0f, reply.offset, 0.001f)
        assertFalse(reply.armed)

        val armed = session.move(120f + SwipeMath.REPLY_AT, 104f, canReply = true) as SwipeSession.Move.Reply
        assertEquals(SwipeMath.REPLY_AT, armed.offset, 0.001f)
        assertTrue(armed.armed)
        assertTrue(armed.justArmed)

        val held = session.move(120f + SwipeMath.REPLY_AT + 20f, 104f, canReply = true) as SwipeSession.Move.Reply
        assertTrue(held.armed)
        assertFalse(held.justArmed)
    }

    @Test
    fun session_leftDragRevealsTimes_andDoesNotBecomeAReply() {
        val session = SwipeSession()
        assertTrue(session.down(200f, 80f, 800f))
        val locked = session.move(180f, 82f, canReply = true)
        assertEquals(SwipeSession.Mode.TIME, session.mode)
        assertEquals(0f, (locked as SwipeSession.Move.Time).offset, 0.001f)

        val shown = session.move(180f - 50f, 90f, canReply = true) as SwipeSession.Move.Time
        assertEquals(50f, shown.offset, 0.001f)

        val stillTime = session.move(400f, 90f, canReply = true)
        assertTrue(stillTime is SwipeSession.Move.Time)
        assertEquals(0f, (stillTime as SwipeSession.Move.Time).offset, 0.001f)
    }

    @Test
    fun session_verticalScrollReleases_andABlockedRowCannotReply() {
        val session = SwipeSession()
        assertTrue(session.down(100f, 40f, 500f))
        assertEquals(SwipeSession.Move.Pending, session.move(108f, 48f, canReply = true))
        assertEquals(SwipeSession.Move.Release, session.move(110f, 70f, canReply = true))
        assertEquals(SwipeSession.Move.Release, session.move(200f, 70f, canReply = true))

        val blocked = SwipeSession()
        assertTrue(blocked.down(100f, 40f, 500f))
        assertEquals(SwipeSession.Move.Release, blocked.move(140f, 40f, canReply = false))

        val edge = SwipeSession()
        assertFalse(edge.down(10f, 40f, 500f))
        assertFalse(edge.down(490f, 40f, 500f))
    }

    @Test
    fun canReply_skipsLocalRowsAndInFlightPlaceholders() {
        assertTrue(SwipeMath.canReply(MessageDto(messageId = "m1", isFromMe = false)))
        assertTrue(SwipeMath.canReply(MessageDto(messageId = "m2", isFromMe = true, status = "OUTGOING_COMPLETE")))
        assertFalse(SwipeMath.canReply(MessageDto(messageId = "local-1", isFromMe = true, status = "OUTGOING_SENDING")))
        assertFalse(SwipeMath.canReply(MessageDto(messageId = "local-2", isFromMe = true, status = "OUTGOING_FAILED")))
        assertFalse(
            SwipeMath.canReply(MessageDto(messageId = "tm-1", isFromMe = true, status = "OUTGOING_SENDING")),
        )
        assertFalse(
            SwipeMath.canReply(MessageDto(messageId = "tmp_9", isFromMe = true, status = "YET_TO_SEND")),
        )
        assertTrue(
            SwipeMath.canReply(MessageDto(messageId = "tm-1", isFromMe = true, status = "OUTGOING_FAILED")),
        )
        assertTrue(
            SwipeMath.canReply(MessageDto(messageId = "tm-1", isFromMe = true, status = "OUTGOING_COMPLETE")),
        )
        assertTrue(
            SwipeMath.canReply(MessageDto(messageId = "tm-1", isFromMe = false, status = "OUTGOING_SENDING")),
        )
    }

    @Test
    fun replyText_matchesTheWebStripAndQuote() {
        val photo = MessageDto(messageId = "p", mediaId = "media", mimeType = "image/jpeg", body = "[Photo]")
        assertEquals(ReplyTexts.PHOTO, ReplyTexts.snippetSource(photo))
        val caption = photo.copy(body = "on the trail")
        assertEquals("on the trail", ReplyTexts.snippetSource(caption))
        val file = MessageDto(messageId = "f", mediaId = "media", mimeType = "application/octet-stream", body = "pic.jpg")
        assertEquals(ReplyTexts.PHOTO, ReplyTexts.snippetSource(file))
        val video = MessageDto(messageId = "v", mediaId = "media", mimeType = "video/mp4", body = "")
        assertEquals(ReplyTexts.MEDIA, ReplyTexts.snippetSource(video))
        val text = MessageDto(messageId = "t", body = "hello\n\nthere")
        assertEquals("hello there", ReplyTexts.barSnippet(ReplyTexts.snippetSource(text)))

        assertEquals(80, ReplyTexts.barSnippet("a".repeat(83)).length)
        assertFalse(ReplyTexts.barSnippet("b".repeat(90)).contains("…"))
        assertEquals("a".repeat(240), ReplyTexts.quoteText("a".repeat(240)))
        assertEquals("a".repeat(240) + "…", ReplyTexts.quoteText("a".repeat(241)))
        assertEquals(ReplyTexts.MEDIA, ReplyTexts.quoteText(""))
        assertEquals(ReplyTexts.MISSING, ReplyTexts.MISSING)

        val mine = MessageDto(messageId = "me", isFromMe = true, senderName = "Sam")
        assertEquals(ReplyTexts.YOU, ReplyTexts.author(mine, "Sam", isGroup = false))
        val named = MessageDto(messageId = "n", senderName = "Ada")
        assertEquals("Ada", ReplyTexts.author(named, "Chat", isGroup = true))
        val blank = MessageDto(messageId = "b", senderName = "")
        assertEquals("Sam", ReplyTexts.author(blank, "Sam", isGroup = false))
        assertEquals(ReplyTexts.THEM, ReplyTexts.author(blank, "Sam", isGroup = true))
    }
}
