package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.util.ShareIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIndexTest {

    @Test
    fun contactGroups_sortsAzAndFiltersByNameOrNumber() {
        val contacts = listOf(
            ContactDto(name = "Casey", number = "15555550199"),
            ContactDto(name = "avery", number = "15555550100"),
            ContactDto(name = "Blair", number = "15555550101"),
            ContactDto(name = "2 Service", number = "15555550102"),
        )
        val groups = ShareIndex.contactGroups(contacts, "")
        assertEquals(listOf("A", "B", "C", "#"), groups.map { it.letter })
        assertEquals("avery", groups.first().contacts.single().name)
        assertEquals("2 Service", groups.last().contacts.single().name)

        val byName = ShareIndex.contactGroups(contacts, "bl")
        assertEquals(listOf("B"), byName.map { it.letter })
        assertEquals("Blair", byName.single().contacts.single().name)

        val byPhone = ShareIndex.contactGroups(contacts, "550199")
        assertEquals("Casey", byPhone.single().contacts.single().name)

        val rows = ShareIndex.rows(
            conversations = emptyList(),
            contacts = contacts,
            query = "av",
            expanded = false,
            selectedConversationIds = emptySet(),
            selectedContactKeys = emptySet(),
            recentTitle = "Recent conversations",
            emptyText = "No matches",
        )
        assertEquals("A", (rows[0] as ShareIndex.Row.Section).title)
        assertTrue(rows[1] is ShareIndex.Row.Contact)
        assertEquals(2, rows.size)
    }

    @Test
    fun recent_capsAtFiveUntilExpandedAndPrefixesYou() {
        val conversations = (1..7).map { index ->
            ConversationDto(
                conversationId = "c$index",
                name = "Chat $index",
                lastMessageTs = index.toLong(),
                lastMessagePreview = "m$index",
                lastFromMe = index == 2,
            )
        }
        val collapsed = ShareIndex.visibleRecent(conversations, "", expanded = false)
        assertEquals(5, collapsed.size)
        assertEquals("c7", collapsed.first().conversationId)
        assertEquals(7, ShareIndex.visibleRecent(conversations, "", expanded = true).size)
        assertEquals(listOf("c3"), ShareIndex.visibleRecent(conversations, "chat 3", expanded = false).map { it.conversationId })
        assertEquals("You: m2", ShareIndex.previewLine(conversations[1]))
        assertEquals("m3", ShareIndex.previewLine(conversations[2]))

        val rows = ShareIndex.rows(
            conversations = conversations,
            contacts = emptyList(),
            query = "",
            expanded = false,
            selectedConversationIds = setOf("c7"),
            selectedContactKeys = emptySet(),
            recentTitle = "Recent conversations",
            emptyText = "No matches",
        )
        assertTrue(rows.any { it is ShareIndex.Row.More })
        val first = rows.filterIsInstance<ShareIndex.Row.Conversation>().first()
        assertTrue(first.selected)
        assertTrue(first.first)
        assertFalse(first.last)
    }

    @Test
    fun groupNumbers_dedupesContactsAndDirectChats() {
        val contacts = listOf(
            ContactDto(name = "Avery", number = "+1 (555) 555-0100"),
            ContactDto(name = "Blair", number = "15555550101"),
        )
        val direct = ConversationDto(
            conversationId = "c1",
            name = "Casey",
            isGroup = false,
            participants = """[{"name":"Casey","number":"15555550199","is_me":false},{"name":"Me","number":"15555550100","is_me":true}]""",
        )
        val sameAsAvery = ConversationDto(
            conversationId = "c2",
            name = "Avery",
            participants = """[{"name":"Avery","phone":"5555550100"}]""",
        )
        val numbers = ShareIndex.groupNumbers(contacts, listOf(direct, sameAsAvery))
        assertEquals(3, numbers.size)
        assertTrue(numbers.any { it.contains("0100") })
        assertTrue(numbers.any { it.contains("0101") })
        assertTrue(numbers.any { it.contains("0199") })
    }
}
