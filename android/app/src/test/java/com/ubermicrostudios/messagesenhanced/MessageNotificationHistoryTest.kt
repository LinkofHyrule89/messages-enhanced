package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.push.MessageNotificationHistory
import com.ubermicrostudios.messagesenhanced.push.MessageNotificationIds
import com.ubermicrostudios.messagesenhanced.push.MessageNotificationPayload
import com.ubermicrostudios.messagesenhanced.push.NotifLine
import com.ubermicrostudios.messagesenhanced.push.NotifThread
import com.ubermicrostudios.messagesenhanced.util.Participants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageNotificationHistoryTest {

    @Before
    fun resetNotificationIds() {
        MessageNotificationIds.resetForTests()
    }

    @Test
    fun trimKeepsTheNewestLines() {
        val lines = (1..12).map { line("m$it", "Ada", "n$it", it.toLong()) }
        val kept = MessageNotificationHistory.trim(lines, max = 3)
        assertEquals(listOf("n10", "n11", "n12"), kept.map { it.text })
    }

    @Test
    fun trimLeavesAShortListAloneAndDropsEverythingWhenMaxIsZero() {
        val lines = listOf(line("a", "Ada", "hi", 1L))
        assertEquals(lines, MessageNotificationHistory.trim(lines, max = 10))
        assertTrue(MessageNotificationHistory.trim(lines, max = 0).isEmpty())
        assertTrue(MessageNotificationHistory.trim(emptyList()).isEmpty())
    }

    @Test
    fun appendTrimsHistoryAndMovesThatConversationFirst() {
        val older = NotifThread("old", "Old chat", false, listOf(line("o", "Bea", "earlier", 1L)))
        val existingLines = (1..10).map { line("a$it", "Ada", "m$it", it.toLong()) }
        val start = listOf(NotifThread("c1", "Ada", false, existingLines), older)
        val next = MessageNotificationHistory.append(
            threads = start,
            conversationId = "c1",
            title = "Ada",
            isGroup = false,
            line = line("a11", "Ada", "newest", 11L),
            max = 10,
        )
        assertEquals(listOf("c1", "old"), next.map { it.conversationId })
        assertEquals(10, next[0].lines.size)
        assertEquals("m2", next[0].lines.first().text)
        assertEquals("newest", next[0].lines.last().text)
        assertEquals("earlier", next[1].lines.single().text)
    }

    @Test
    fun appendKeepsGroupOnceSetAndKeepsTheFirstTitleWhenTheNextIsBlank() {
        val grouped = MessageNotificationHistory.append(
            threads = emptyList(),
            conversationId = "g",
            title = "Family",
            isGroup = true,
            line = line("ada", "Ada", "hi", 1L),
            groupAvatarUrl = "https://cdn.example/g.png",
            legacyTag = "tag-g",
        )
        val stayed = MessageNotificationHistory.append(
            threads = grouped,
            conversationId = "g",
            title = "",
            isGroup = false,
            line = line("bea", "Bea", "yo", 2L),
        )
        assertTrue(stayed.single().isGroup)
        assertEquals("Family", stayed.single().title)
        assertEquals("https://cdn.example/g.png", stayed.single().groupAvatarUrl)
        assertEquals("tag-g", stayed.single().legacyTag)
        assertEquals(listOf("hi", "yo"), stayed.single().lines.map { it.text })

        val promoted = MessageNotificationHistory.append(
            threads = MessageNotificationHistory.append(
                emptyList(),
                "c",
                "Ada",
                false,
                line("ada", "Ada", "one", 1L),
            ),
            conversationId = "c",
            title = "Family",
            isGroup = true,
            line = line("bea", "Bea", "two", 2L),
        )
        assertTrue(promoted.single().isGroup)
        assertEquals("Family", promoted.single().title)
    }

    @Test
    fun appendIgnoresABlankConversationId() {
        val start = listOf(NotifThread("c", "Ada"))
        assertEquals(start, MessageNotificationHistory.append(start, "  ", "X", false, line("a", "A", "hi", 1L)))
    }

    @Test
    fun clearDropsOnlyThatConversation() {
        val threads = listOf(
            NotifThread("a", "Ada", lines = listOf(line("a", "Ada", "hi", 1L))),
            NotifThread("b", "Bea", lines = listOf(line("b", "Bea", "yo", 2L))),
        )
        assertEquals(listOf("b"), MessageNotificationHistory.clear(threads, "a").map { it.conversationId })
        assertEquals(threads, MessageNotificationHistory.clear(threads, ""))
        assertEquals(threads, MessageNotificationHistory.clear(threads, "missing"))
        assertTrue(MessageNotificationHistory.clear(threads, "a").let { MessageNotificationHistory.clear(it, "b") }.isEmpty())
    }

    @Test
    fun selfReplyAppendsAndARetryWithTheSameTimestampDoesNotDuplicate() {
        val start = MessageNotificationHistory.append(
            emptyList(),
            "c1",
            "Ada Quinn",
            false,
            line("ada", "+18015551234", "Ping", 10L),
        )
        val once = MessageNotificationHistory.appendSelf(start, "c1", "On my way", 20L)
        val twice = MessageNotificationHistory.appendSelf(once, "c1", "On my way", 20L)
        assertEquals(once, twice)
        val self = once.single().lines.last()
        assertTrue(self.fromMe)
        assertEquals(MessageNotificationHistory.SELF_KEY, self.senderKey)
        assertEquals("On my way", self.text)
        assertEquals("Ping", once.single().lines.first().text)
        assertEquals("Ada Quinn", once.single().title)
    }

    @Test
    fun selfReplyCanStartAThreadWhenHistoryWasEmpty() {
        val threads = MessageNotificationHistory.appendSelf(
            emptyList(),
            "c9",
            "Hello",
            5L,
            title = "Family",
            isGroup = true,
        )
        val thread = threads.single()
        assertEquals("c9", thread.conversationId)
        assertEquals("Family", thread.title)
        assertTrue(thread.isGroup)
        assertTrue(thread.lines.single().fromMe)
    }

    @Test
    fun selfReplyIgnoresBlankText() {
        val start = listOf(NotifThread("c", "Ada"))
        assertEquals(start, MessageNotificationHistory.appendSelf(start, "c", "  ", 1L))
    }

    @Test
    fun summaryLineUsesTheTitleAndNamesTheSenderForGroups() {
        val direct = NotifThread(
            "c",
            "Ada Quinn",
            false,
            listOf(line("ada", "Ada Quinn", "On my way", 1L)),
        )
        assertEquals("Ada Quinn: On my way", MessageNotificationHistory.summaryLine(direct, country = "US"))

        val group = NotifThread(
            "g",
            "Family",
            true,
            listOf(line("bea", "Bea Ruiz", "Dinner?", 2L)),
        )
        assertEquals("Family: Bea Ruiz: Dinner?", MessageNotificationHistory.summaryLine(group, country = "US"))

        val mine = MessageNotificationHistory.appendSelf(
            listOf(direct),
            "c",
            "Leaving now",
            3L,
        ).single()
        assertEquals(
            "Ada Quinn: You: Leaving now",
            MessageNotificationHistory.summaryLine(mine, country = "US"),
        )
    }

    @Test
    fun summaryLineFormatsAPhoneTitle() {
        val thread = NotifThread(
            "c",
            "+18015551234",
            false,
            listOf(line("p", "+18015551234", "Hi", 1L)),
        )
        val shown = MessageNotificationHistory.summaryLine(thread, country = "US")
        assertTrue(shown == "+18015551234: Hi" || shown == "(801) 555-1234: Hi")
    }

    @Test
    fun notificationIdsAreStableAndStayOutOfTheReservedRange() {
        val samples = listOf(
            "sms:+18015551234",
            "group-1",
            "conv/with spaces",
            "a",
            "b",
            "family",
            "conv:99",
        )
        val ids = samples.map { MessageNotificationIds.notificationId(it) }
        assertEquals(ids, samples.map { MessageNotificationIds.notificationId(it) })
        assertEquals(ids.size, ids.toSet().size)
        val errors = samples.map { MessageNotificationIds.replyErrorId(it) }
        assertEquals(errors.size, errors.toSet().size)
        samples.forEach { id ->
            val message = MessageNotificationIds.notificationId(id)
            val error = MessageNotificationIds.replyErrorId(id)
            assertNotEquals(message, error)
            assertFalse(MessageNotificationIds.isReserved(message))
            assertFalse(MessageNotificationIds.isReserved(error))
            assertTrue(message >= 20_000)
            assertTrue(error >= 20_000)
        }
        assertTrue(MessageNotificationIds.isReserved(MessageNotificationIds.SUMMARY_ID))
        assertTrue(MessageNotificationIds.isReserved(1))
        assertTrue(MessageNotificationIds.isReserved(7104))
    }

    @Test
    fun requestCodesDifferBySlotAndStayStable() {
        val first = MessageNotificationIds.requestCode("sms:+18015551234", MessageNotificationIds.SLOT_REPLY)
        val again = MessageNotificationIds.requestCode("sms:+18015551234", MessageNotificationIds.SLOT_REPLY)
        val read = MessageNotificationIds.requestCode("sms:+18015551234", MessageNotificationIds.SLOT_READ)
        val other = MessageNotificationIds.requestCode("group-1", MessageNotificationIds.SLOT_REPLY)
        assertEquals(first, again)
        assertNotEquals(first, read)
        assertNotEquals(first, other)
        assertTrue(first > 0)
    }

    @Test
    fun parseReadsTheMessageAndPrefersTheConversationIdOverTheTag() {
        val data = mapOf(
            "type" to "message",
            "conv" to "c1",
            "tag" to "other",
            "title" to "Family",
            "person" to "Ada Quinn",
            "body" to "Hello",
            "ts" to "1700000000000",
            "phone" to "+18015551234",
            "participant_id" to "p1",
            "source" to "sms",
        )
        val incoming = MessageNotificationPayload.parse(
            data,
            nowMs = 5L,
            origin = "https://example.test",
            hideBody = false,
            hiddenBody = "New message",
        )
        requireNotNull(incoming)
        assertEquals("c1", incoming.conversationId)
        assertTrue(incoming.isGroup)
        assertEquals("Hello", incoming.line.text)
        assertEquals(1_700_000_000_000L, incoming.line.timestampMs)
        assertEquals("p1", incoming.line.senderKey)
        assertEquals("Ada Quinn", incoming.line.senderName)
        assertEquals("other", incoming.legacyTag)
        assertEquals(
            Participants.avatarUrl(
                "https://example.test",
                participantId = "p1",
                phone = "+18015551234",
            ),
            incoming.line.avatarUrl,
        )
        assertEquals(
            Participants.avatarUrl("https://example.test", participantId = "conv:c1"),
            incoming.groupAvatarUrl,
        )
    }

    @Test
    fun parseHidesTheBodyAndFallsBackWhenTheTimestampIsMissing() {
        val hidden = MessageNotificationPayload.parse(
            mapOf("conv" to "c", "person" to "Ada", "body" to "secret", "title" to "Ada"),
            nowMs = 42L,
            origin = "",
            hideBody = true,
            hiddenBody = "New message",
        )
        requireNotNull(hidden)
        assertEquals("New message", hidden.line.text)
        assertEquals(42L, hidden.line.timestampMs)
        assertFalse(hidden.isGroup)
        assertEquals("", hidden.line.avatarUrl)
    }

    @Test
    fun explicitGroupFlagWinsOverTheTitleHeuristic() {
        assertFalse(
            MessageNotificationPayload.isGroupConversation("false", "Family", "Ada"),
        )
        assertTrue(
            MessageNotificationPayload.isGroupConversation("", "Family", "Ada"),
        )
        assertFalse(
            MessageNotificationPayload.isGroupConversation("", "Ada Quinn", "ada quinn"),
        )
        val direct = MessageNotificationPayload.parse(
            mapOf("tag" to "only-tag", "title" to "Family", "person" to "Ada", "is_group" to "0", "body" to "Hi"),
            nowMs = 1L,
            origin = "https://example.test",
            hideBody = false,
            hiddenBody = "New message",
        )
        requireNotNull(direct)
        assertEquals("only-tag", direct.conversationId)
        assertFalse(direct.isGroup)
        assertEquals("", direct.groupAvatarUrl)
    }

    @Test
    fun directAvatarWinsAndANonSmsPushDoesNotInventAnAvatar() {
        val pictured = MessageNotificationPayload.parse(
            mapOf(
                "conv" to "c",
                "person" to "Ada",
                "title" to "Ada",
                "body" to "Hi",
                "source" to "rcs",
                "avatar" to "https://cdn.example/a.png",
            ),
            nowMs = 1L,
            origin = "https://example.test",
            hideBody = false,
            hiddenBody = "New message",
        )
        requireNotNull(pictured)
        assertEquals("https://cdn.example/a.png", pictured.line.avatarUrl)

        val relative = MessageNotificationPayload.senderAvatarUrl(
            origin = "https://example.test",
            source = "rcs",
            direct = "/api/avatar?x=1",
            participantId = "p",
            contactId = "",
            phone = "",
        )
        assertEquals("https://example.test/api/avatar?x=1", relative)

        val none = MessageNotificationPayload.senderAvatarUrl(
            origin = "https://example.test",
            source = "rcs",
            direct = "",
            participantId = "p",
            contactId = "",
            phone = "+18015551234",
        )
        assertEquals("", none)
        assertEquals("", MessageNotificationPayload.groupAvatarUrl("https://example.test", "c", "rcs"))
    }

    @Test
    fun blankPayloadIsIgnored() {
        assertEquals("", MessageNotificationPayload.conversationKey(emptyMap()))
        assertNull(
            MessageNotificationPayload.parse(
                mapOf("type" to "message", "body" to "Hi"),
                nowMs = 1L,
                origin = "https://example.test",
                hideBody = false,
                hiddenBody = "New message",
            ),
        )
    }

    @Test
    fun emptyBodyUsesThePlaceholder() {
        val incoming = MessageNotificationPayload.parse(
            mapOf("conv" to "c", "person" to "Ada", "title" to "Ada", "body" to "  "),
            nowMs = 7L,
            origin = "",
            hideBody = false,
            hiddenBody = "New message",
        )
        requireNotNull(incoming)
        assertEquals("New message", incoming.line.text)
        assertEquals(7L, incoming.line.timestampMs)
    }

    private fun line(key: String, name: String, text: String, timestamp: Long) = NotifLine(
        senderKey = key,
        senderName = name,
        text = text,
        timestampMs = timestamp,
    )
}
