package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto
import com.ubermicrostudios.messagesenhanced.util.MessageActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class MessageActionsTest {

    private fun message(
        id: String = "m1",
        body: String = "Hello",
        fromMe: Boolean = true,
        status: String = "OUTGOING_COMPLETE",
        mediaId: String = "",
        mime: String = "",
        senderName: String = "",
        senderNumber: String = "",
        reactions: String = "",
        statusText: String = "",
        timestamp: Long = 1_700_000_000_000L,
    ) = MessageDto(
        messageId = id,
        body = body,
        isFromMe = fromMe,
        status = status,
        mediaId = mediaId,
        mimeType = mime,
        senderName = senderName,
        senderNumber = senderNumber,
        reactions = reactions,
        statusText = statusText,
        timestampMs = timestamp,
    )

    @Test
    fun sheetHidesCopyWithoutBody_andForwardWithoutAnything() {
        val empty = MessageActions.sheetMenu(message(body = "  "), starred = false)
        assertFalse(empty.showCopy)
        assertFalse(empty.showCopyUrl)
        assertFalse(empty.showForward)
        assertTrue(empty.showReply)
        assertTrue(empty.showDelete)
        assertEquals("Star", empty.starLabel)

        val photo = MessageActions.sheetMenu(message(body = "", mediaId = "media-1", mime = "image/jpeg"), starred = true)
        assertTrue(photo.showForward)
        assertFalse(photo.showCopy)
        assertEquals("Unstar", photo.starLabel)
    }

    @Test
    fun sheetShowsCopyUrl_andReadonlyHidesMutations() {
        val link = MessageActions.sheetMenu(message(body = "see https://example.com/a."), starred = false)
        assertTrue(link.showCopyUrl)
        assertEquals("https://example.com/a", link.copyUrl)
        assertTrue(link.showCopy)

        val locked = MessageActions.sheetMenu(message(), starred = false, readonly = true)
        assertFalse(locked.showReactions)
        assertFalse(locked.showReply)
        assertFalse(locked.showDelete)
        assertFalse(locked.showSelect)
        assertTrue(locked.showInfo)
        assertTrue(locked.showCopy)
    }

    @Test
    fun menuSkipsLocalAndInFlightRows() {
        assertFalse(MessageActions.canOpenMenu(message(id = "local-1"), selecting = false))
        assertFalse(
            MessageActions.canOpenMenu(
                message(id = "tm-1", status = "OUTGOING_SENDING"),
                selecting = false,
            ),
        )
        assertFalse(MessageActions.canOpenMenu(message(), selecting = true))
        assertTrue(MessageActions.canOpenMenu(message(), selecting = false))
    }

    @Test
    fun starToggleRollsBack() {
        val on = MessageActions.applyStar(emptySet(), "m1", true)
        assertEquals(setOf("m1"), on)
        assertEquals(emptySet<String>(), MessageActions.applyStar(on, "m1", false))
    }

    @Test
    fun reactionsParseCountsNamesAndCustom() {
        val raw = """[{"emoji":"👍","count":2,"actors":["p1","p2"]},{"emoji":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","count":1}]"""
        val people = listOf(
            ParticipantDto(id = "p1", name = "Ada", isMe = true),
            ParticipantDto(id = "p2", name = "Bea"),
        )
        assertEquals("👍 2  Custom", MessageActions.reactionLine(raw))
        assertEquals(
            "👍 (You, Bea) · Custom reaction",
            MessageActions.reactionSummary(raw, people),
        )
    }

    @Test
    fun infoRowsMatchWebFields() {
        val whenText = MessageActions.infoWhen(1_700_000_000_000L, Locale.US, TimeZone.getTimeZone("UTC"))
        assertTrue(whenText.contains("2023"))
        assertTrue(whenText.contains(":"))

        val mine = MessageActions.infoRows(
            message = message(status = "OUTGOING_DELIVERED", statusText = ""),
            conversationName = "Ada",
            sourcePlatform = "sms",
            metaProtocol = "SMS",
            e2ee = true,
            starred = true,
            participants = emptyList(),
            whenText = "Tue, Nov 14, 2023, 10:13:20 PM",
        )
        assertEquals("You", mine.first { it.first == "From" }.second)
        assertEquals("Ada", mine.first { it.first == "To" }.second)
        assertEquals("Delivered", mine.first { it.first == "Status" }.second)
        assertEquals("SMS · end-to-end encrypted", mine.first { it.first == "Sent via" }.second)
        assertEquals("Yes (on this server)", mine.first { it.first == "Starred" }.second)

        val photo = message(body = "", mediaId = "m", mime = "image/png", fromMe = true, status = "OUTGOING_COMPLETE")
        assertEquals("Photo", MessageActions.messageType(photo))
        assertEquals(
            "MMS",
            MessageActions.protocolText("sms", "SMS", e2ee = false, hasMedia = true),
        )
        assertEquals(
            "Whatsapp",
            MessageActions.protocolText("whatsapp", "RCS", e2ee = false, hasMedia = false),
        )

        val theirs = MessageActions.infoRows(
            message = message(
                fromMe = false,
                senderName = "Bea",
                senderNumber = "+1555",
                status = "INCOMING_READ",
                body = "hi",
            ),
            conversationName = "Bea",
            sourcePlatform = "sms",
            metaProtocol = "",
            e2ee = false,
            starred = false,
            participants = emptyList(),
            whenText = "then",
        )
        assertEquals("Bea (+1555)", theirs.first { it.first == "From" }.second)
        assertEquals("Read", theirs.first { it.first == "Status" }.second)
        assertEquals("then", theirs.first { it.first == "Received" }.second)
        assertTrue(theirs.none { it.first == "Starred" })
    }

    @Test
    fun sentStatusMatchesWebWords() {
        assertEquals("Read", MessageActions.sentStatus("OUTGOING_DISPLAYED"))
        assertEquals("Sent", MessageActions.sentStatus("OUTGOING_NOT_DELIVERED_YET"))
        assertEquals("Scheduled", MessageActions.sentStatus("OUTGOING_SCHEDULED"))
        assertNull(MessageActions.sentStatus("OUTGOING_FAILED"))
        assertEquals("Sending…", MessageActions.sentStatus("OUTGOING_SENDING"))
        assertEquals("failed", MessageActions.infoStatus(message(status = "OUTGOING_FAILED", statusText = "")))
        assertEquals("Delivered to phone", MessageActions.infoStatus(message(statusText = "Delivered to phone")))
    }

    @Test
    fun selectionCopyAndForwardOrder() {
        val older = message(id = "a", body = "one", timestamp = 10)
        val newer = message(id = "b", body = "", mediaId = "pic", mime = "image/jpeg", timestamp = 20, fromMe = false, senderName = "Bea")
        val captioned = message(id = "c", body = "look", mediaId = "pic", mime = "image/jpeg", timestamp = 5)
        val copy = MessageActions.selectionCopy(listOf(older, newer), "Ada", isGroup = true) { "3:00 PM" }
        assertEquals("[3:00 PM] You: one\n[3:00 PM] Bea: [attachment]", copy)
        assertEquals("one", MessageActions.selectionCopy(listOf(older), "Ada", false) { "" })

        val plan = MessageActions.forwardPlan(listOf(newer, captioned, older, message(id = "d", body = "  ", timestamp = 1)))
        assertEquals(3, plan.size)
        val first = plan[0] as MessageActions.ForwardPiece.Media
        assertEquals("c", first.messageId)
        assertEquals("look", first.caption)
        assertEquals("forwarded.jpeg", first.filename)
        assertEquals("one", (plan[1] as MessageActions.ForwardPiece.Text).body)
        assertTrue(plan[2] is MessageActions.ForwardPiece.Media)
        assertNull((plan[2] as MessageActions.ForwardPiece.Media).caption)
        assertEquals(128L * 1024 * 1024, MessageActions.MAX_MEDIA_BYTES)
    }

    @Test
    fun deleteCopyDependsOnPlatformAndCount() {
        val one = MessageActions.deletePrompt(1, "sms")
        assertEquals("Delete this message?", one.title)
        assertTrue(one.body.contains("your phone"))
        val many = MessageActions.deletePrompt(3, "whatsapp")
        assertEquals("Delete 3 messages?", many.title)
        assertTrue(many.body.contains("this server only"))
        assertEquals("Deleted", MessageActions.deleteToast("google", 1))
        assertEquals("Deleted from this server (2)", MessageActions.deleteToast("local", 2))
    }

    @Test
    fun placeSheetStacksUnderMine_andMovesBesideWhenTight() {
        val bubble = MessageActions.Box(left = 400f, top = 200f, width = 120f, height = 48f)
        val stacked = MessageActions.placeSheet(
            bubble = bubble,
            mine = true,
            viewWidth = 800f,
            viewHeight = 900f,
            reactWidth = 220f,
            reactHeight = 40f,
            reactHidden = false,
            menuWidth = 200f,
            menuHeight = 280f,
        )
        assertFalse(stacked.beside)
        assertEquals(400f + 120f - 220f, stacked.react.left, 0.1f)
        assertTrue(stacked.menu.top > stacked.clone.top)

        val tight = MessageActions.placeSheet(
            bubble = MessageActions.Box(400f, 40f, 100f, 180f),
            mine = true,
            viewWidth = 800f,
            viewHeight = 300f,
            reactWidth = 40f,
            reactHeight = 40f,
            reactHidden = true,
            menuWidth = 160f,
            menuHeight = 220f,
        )
        assertTrue(tight.beside)
        assertEquals(400f - 16f - 160f, tight.menu.left, 0.1f)
    }

    @Test
    fun recentsMoveToFrontAndCap() {
        val next = MessageActions.rememberRecent(listOf("😀", "😂"), "👍")
        assertEquals(listOf("👍", "😀", "😂"), next)
        assertEquals(listOf("😂", "👍", "😀"), MessageActions.rememberRecent(next, "😂"))
    }
}
