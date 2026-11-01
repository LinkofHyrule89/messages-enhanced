package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import org.junit.Assert.assertEquals
import org.junit.Test

class DeletedEditedTest {
    @Test fun deletedShowsPlaceholderNoLabel() {
        val m = MessageDto(messageId = "s1", body = "", status = "MESSAGE_DELETED")
        assertEquals("", MessageStatus.bubbleLabel(m))
        assertEquals(MessageStatus.DELETED_TEXT, ReplyTexts.snippetSource(m))
    }

    @Test fun revocationAndEditedLabels() {
        assertEquals(MessageStatus.REVOKING_TEXT, MessageStatus.bubbleLabel(MessageDto(messageId = "s2", body = "x", status = "OUTGOING_REVOCATION_PENDING", isFromMe = true)))
        assertEquals("Edited", MessageStatus.bubbleLabel(MessageDto(messageId = "s3", body = "synthetic", status = "INCOMING_COMPLETE", edited = true)))
        assertEquals("", MessageStatus.bubbleLabel(MessageDto(messageId = "s4", body = "synthetic", status = "INCOMING_COMPLETE")))
    }

    @Test fun deletedHiddenUnlessSettingOn() {
        val list = listOf(MessageDto(messageId = "a", body = "synthetic"), MessageDto(messageId = "b", status = "MESSAGE_DELETED"))
        assertEquals(1, MessageStatus.visibleMessages(list, false).size)
        assertEquals(2, MessageStatus.visibleMessages(list, true).size)
    }
}
