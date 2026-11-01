package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.ProfileDto
import com.ubermicrostudios.messagesenhanced.util.InboxSearch
import com.ubermicrostudios.messagesenhanced.util.Participants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {

    private fun conv(
        id: String,
        name: String = "",
        unified: String = "",
        participants: String = "",
        group: Boolean = false,
    ) = ConversationDto(
        conversationId = id,
        name = name,
        unifiedName = unified,
        participants = participants,
        isGroup = group,
    )

    @Test
    fun merge_localNameBeforeServer_andCapsAtTwelve() {
        val loaded = (1..3).map { conv("l$it", name = "Ada $it") }
        val server = (1..20).map { conv("s$it", name = "Ada server $it") }
        val merged = InboxSearch.merge("ada", loaded, server, emptyList())
        assertEquals(12, merged.conversations.size)
        assertEquals("l1", merged.conversations[0].conversationId)
        assertEquals("l3", merged.conversations[2].conversationId)
        assertEquals("s1", merged.conversations[3].conversationId)
    }

    @Test
    fun merge_serverUsesNameOrUnifiedNotBoth_andParticipants() {
        val serverOnly = conv("s", name = "Alice", unified = "Ally")
        val byParticipant = conv("p", name = "Other", participants = """[{"number":"+15551212"}]""")
        val merged = InboxSearch.merge("ally", emptyList(), listOf(serverOnly, byParticipant), emptyList())
        assertTrue(merged.conversations.none { it.conversationId == "s" })
        val byNumber = InboxSearch.merge("1555", emptyList(), listOf(byParticipant), emptyList())
        assertEquals("p", byNumber.conversations.single().conversationId)
    }

    @Test
    fun merge_localUnifiedNameMatchesEvenWhenServerNameDoesNot() {
        val loaded = conv("c", name = "Alice", unified = "Ally")
        val merged = InboxSearch.merge("ally", listOf(loaded), emptyList(), emptyList())
        assertEquals("c", merged.conversations.single().conversationId)
    }

    @Test
    fun merge_messagePrefixesAndSnippetWindow() {
        val group = conv("g", name = "Crew", group = true)
        val direct = conv("d", name = "Ada")
        val long = "x".repeat(80) + "lunch" + "y".repeat(10)
        val messages = listOf(
            MessageDto(messageId = "1", conversationId = "g", senderName = "Bea", body = "hi", isFromMe = false),
            MessageDto(messageId = "2", conversationId = "d", body = "lunch", isFromMe = true),
            MessageDto(messageId = "3", conversationId = "d", senderName = "Ada", body = long, isFromMe = false),
            MessageDto(messageId = "4", conversationId = "missing", senderName = "Zoe", body = "lunch"),
        )
        val merged = InboxSearch.merge("lunch", listOf(group, direct), emptyList(), messages)
        assertEquals("Bea: ", merged.messages[0].prefix)
        assertEquals("Crew", merged.messages[0].title)
        assertEquals("You: ", merged.messages[1].prefix)
        assertTrue(merged.messages[2].body.startsWith("…"))
        assertTrue(merged.messages[2].body.lowercase().contains("lunch"))
        assertEquals("Zoe", merged.messages[3].title)
        assertEquals("", merged.messages[3].prefix)
    }

    @Test
    fun shortQuery_mergesNothing() {
        val merged = InboxSearch.merge("a", listOf(conv("c", name = "Ada")), emptyList(), emptyList())
        assertTrue(merged.conversations.isEmpty())
    }

    @Test
    fun messageWindow_matchesWebClamp() {
        assertEquals(80, InboxSearch.messageWindow(0))
        assertEquals(120, InboxSearch.messageWindow(100))
        assertEquals(1000, InboxSearch.messageWindow(5000))
    }

    @Test
    fun profileJson_andSelfFallback() {
        val profile = JsonConfig.json.decodeFromString(ProfileDto.serializer(), """{"photo_hash":"abc","source":"google_account"}""")
        assertEquals("abc", profile.photoHash)
        val empty = JsonConfig.json.decodeFromString(ProfileDto.serializer(), """{"photo_hash":""}""")
        assertEquals("", empty.photoHash)
        assertNull(Participants.profilePhotoUrl("https://example.test", ""))
        assertEquals(
            "https://example.test/api/app/profile-photo?h=abc",
            Participants.profilePhotoUrl("https://example.test/", "abc"),
        )
        val me = """[{"name":"Sam Q","first_name":"Sam","number":"+1555","is_me":true,"id":"p1"}]"""
        val self = Participants.self(listOf(conv("c", participants = me, name = "Ada")))
        assertEquals("Sam", Participants.profileName(self))
        assertEquals("S", Participants.profileInitial("Sam Q"))
        assertEquals("+1555", Participants.profileSeed(self, "Sam"))
        assertTrue(Participants.selfAvatarUrl("https://example.test", self!!).orEmpty().contains("participant_id=p1"))
    }
}
