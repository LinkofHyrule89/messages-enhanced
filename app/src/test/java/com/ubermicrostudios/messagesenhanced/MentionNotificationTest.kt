package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.push.MessageNotificationHistory
import com.ubermicrostudios.messagesenhanced.push.MessageNotificationPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class MentionNotificationTest {
    private val base = mapOf("conv" to "c1", "title" to "Alex Example · Test Group", "group" to "true", "ts" to "1000")

    @Test fun mentionShowsSenderAndText() {
        val n = MessageNotificationPayload.parse(base + mapOf("person" to "Alex Example", "body" to "hi @Sam", "mentions_me" to "true"), 0, "https://x.test", false, "New message")!!
        assertEquals("Alex Example", n.mentionedBy)
        assertEquals("Test Group", n.title)
        assertEquals("hi @Sam", n.line.text)
        assertEquals("Alex Example mentioned you", MessageNotificationPayload.mentionTitle(n.mentionedBy))
    }

    @Test fun hiddenMentionSaysOnlyWho() {
        val n = MessageNotificationPayload.parse(base + mapOf("body" to "New message", "mentions_me" to "true"), 0, "https://x.test", true, "New message")!!
        assertEquals("Alex Example", n.mentionedBy)
        assertEquals("Alex Example mentioned you", n.line.text)
    }

    @Test fun noMentionAndStickyAppend() {
        val n = MessageNotificationPayload.parse(base + mapOf("person" to "Alex Example", "body" to "hi"), 0, "https://x.test", false, "")!!
        assertEquals("", n.mentionedBy)
        var t = MessageNotificationHistory.append(emptyList(), "c1", "Test Group", true, n.line, mentionedBy = "Alex Example")
        t = MessageNotificationHistory.append(t, "c1", "Test Group", true, n.line)
        assertEquals("Alex Example", t.first().mentionedBy)
    }
}
