package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto
import com.ubermicrostudios.messagesenhanced.util.ViewerGallery
import com.ubermicrostudios.messagesenhanced.util.ViewerReplies
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ViewerRepliesTest {

    private fun message(
        id: String,
        replyTo: String = "",
        body: String = "",
        at: Long = 0L,
        fromMe: Boolean = false,
        sender: String = "",
        mime: String = "",
        mediaId: String = "",
        local: String = "",
    ) = MessageDto(
        messageId = id,
        replyToId = replyTo,
        body = body,
        timestampMs = at,
        isFromMe = fromMe,
        senderName = sender,
        mimeType = mime,
        mediaId = mediaId,
        localPreview = local,
    )

    @Test
    fun directReplies_countOnlyMessagesQuotingThatImage() {
        val image = message(id = "img", mime = "image/jpeg", mediaId = "img", at = 1)
        val older = message(id = "c", replyTo = "img", body = "first", at = 10, sender = "Ada")
        val newer = message(id = "a", replyTo = "img", body = "second", at = 20, fromMe = true)
        val other = message(id = "b", replyTo = "other", body = "nope", at = 15)
        val self = message(id = "img", replyTo = "img", body = "not a reply", at = 30)
        val messages = listOf(newer, other, image, self, older)

        val replies = ViewerReplies.directReplies(messages, "img")
        assertEquals(listOf("c", "a"), replies.map { it.messageId })
        assertEquals(2, ViewerReplies.count(messages, "img"))
        assertEquals("2", ViewerReplies.badgeLabel(2))
        assertEquals(0, ViewerReplies.count(messages, "missing"))
        assertEquals(0, ViewerReplies.count(messages, ""))
        assertNull(ViewerReplies.badgeLabel(0))
        assertEquals("99+", ViewerReplies.badgeLabel(100))
    }

    @Test
    fun senderLabel_youOrFormattedName() {
        val mine = message(id = "m", fromMe = true, sender = "Pat")
        assertEquals("You", ViewerReplies.senderLabel(mine, "Pat", isGroup = false))
        val named = message(id = "t", sender = "Ada")
        assertEquals("Ada", ViewerReplies.senderLabel(named, "Ada", isGroup = true))
        val number = message(id = "n", sender = "+18015551234")
        assertEquals(
            formatPhoneForDisplay("+18015551234"),
            ViewerReplies.senderLabel(number, "Chat", isGroup = true),
        )
    }

    @Test
    fun mergeRemote_keepsAPendingCommentUntilTheServerCopyArrives() {
        val image = message(id = "img", at = 1)
        val pending = message(id = "local-comment-9", replyTo = "img", body = "nice", at = 50, fromMe = true)
        val localPhoto = message(id = "local-photo", mime = "image/jpeg", at = 40, local = "/cache/a.jpg")
        val remote = listOf(image, message(id = "old", body = "hi", at = 2))
        val merged = ViewerReplies.mergeRemote(listOf(image, localPhoto, pending), remote)
        assertEquals(listOf("img", "old", "local-photo", "local-comment-9"), merged.map { it.messageId })

        val landed = message(id = "server-1", replyTo = "img", body = "nice", at = 55, fromMe = true)
        val after = ViewerReplies.mergeRemote(merged, remote + landed, pending)
        assertEquals(false, after.any { it.messageId == "local-comment-9" })
        assertEquals(true, after.any { it.messageId == "server-1" })
    }

    @Test
    fun gallery_ordersOpenableImagesAndStartsAtTheTappedOne() {
        val text = message(id = "t", body = "hi", at = 1)
        val first = message(id = "a", mime = "image/jpeg", at = 2, local = "/cache/a.jpg")
        val video = message(id = "v", mime = "video/mp4", mediaId = "v", at = 3)
        val second = message(id = "b", mime = "image/png", at = 4)
        val gif = message(id = "g", mime = "image/gif", at = 5)
        val messages = listOf(second, text, video, gif, first)
        val url = { message: MessageDto -> "https://messages.example/api/media/${message.messageId}" }
        val images = ViewerGallery.images(messages, url)
        assertEquals(listOf("a", "b", "g"), images.map { it.messageId })
        assertEquals(1, ViewerGallery.startIndex(images, "b"))
        assertEquals(2, ViewerGallery.startIndex(images, "g"))
        assertEquals(0, ViewerGallery.startIndex(images, "missing"))
    }

    @Test
    fun senderParticipant_matchesMeThenDigitsThenName() {
        val me = ParticipantDto(name = "Me", number = "+15555550100", isMe = true)
        val byPhone = ParticipantDto(name = "Avery", phone = "+1 (555) 555-0199")
        val byName = ParticipantDto(name = "Blair")
        val people = listOf(byName, byPhone, me)

        val mine = message(id = "m", fromMe = true, sender = "Blair")
        assertSame(me, ViewerReplies.senderParticipant(people, mine))

        val digits = MessageDto(messageId = "d", senderNumber = "555-555-0199", senderName = "Someone Else")
        assertSame(byPhone, ViewerReplies.senderParticipant(people, digits))

        val named = message(id = "n", sender = "blair")
        assertSame(byName, ViewerReplies.senderParticipant(people, named))

        val unknown = message(id = "u", sender = "Casey")
        assertNull(ViewerReplies.senderParticipant(people, unknown))
        assertNull(ViewerReplies.senderParticipant(emptyList(), mine))
    }
}
