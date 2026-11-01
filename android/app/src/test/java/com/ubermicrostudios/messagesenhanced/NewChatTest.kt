package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ContactsResponse
import com.ubermicrostudios.messagesenhanced.util.NewChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatTest {
    @Test
    fun addressAndKeyMatchTheWeb() {
        assertTrue(NewChat.looksLikeAddress("+15551212"))
        assertTrue(NewChat.looksLikeAddress("ada@example.com"))
        assertFalse(NewChat.looksLikeAddress("Ada"))
        assertEquals("5551212345", NewChat.contactKey("+1 (555) 121-2345"))
        assertTrue(NewChat.matches(ContactDto(name = "Ada Example", number = "+1555"), "ada"))
        assertTrue(NewChat.matches(ContactDto(name = "Ada", number = "+15551212"), "1212"))
        assertFalse(NewChat.matches(ContactDto(name = "Ada", number = "+15551212"), "zzz"))
        assertEquals("A", NewChat.letterOf(ContactDto(name = "ada")))
        assertEquals("#", NewChat.letterOf(ContactDto(name = "12")))
    }

    @Test
    fun rowsShowTopThenAllAndSendTo() {
        val ada = ContactDto(name = "Ada", number = "+1")
        val bob = ContactDto(name = "Bob", number = "+2")
        val data = ContactsResponse(top = listOf(ada), all = listOf(ada, bob))
        val empty = NewChat.rows("", data, group = false, chips = emptyList(), loading = false, error = "")
        assertEquals("Top contacts", (empty[0] as NewChat.Row.Section).title)
        assertEquals("Ada", (empty[1] as NewChat.Row.Contact).contact.displayName)
        assertEquals("All contacts", (empty[2] as NewChat.Row.Section).title)
        assertEquals("A", (empty[3] as NewChat.Row.Letter).letter)
        val send = NewChat.rows("+1555", data, group = false, chips = emptyList(), loading = false, error = "")
        assertTrue(send[0] is NewChat.Row.SendTo)
        assertEquals("+1555", (send[0] as NewChat.Row.SendTo).query)
        val loading = NewChat.rows("", null, group = false, chips = emptyList(), loading = true, error = "")
        assertEquals("Loading contacts…", (loading[0] as NewChat.Row.Empty).text)
    }
}

class GroupDetailsTest {
    @Test
    fun groupSheetListsYouFirstThenMembers() {
        val people = listOf(
            com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto(
                name = "Ada Quinn",
                number = "+15551110001",
            ),
            com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto(
                name = "Bea Ruiz",
                number = "+15551110002",
                isMe = true,
            ),
            com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto(
                name = "Cy Nolan",
                number = "+15551110003",
            ),
        )
        val content = com.ubermicrostudios.messagesenhanced.util.GroupDetails.content(
            isGroup = true,
            title = "Sample Group",
            people = people,
        )
        assertEquals("Group details", content.title)
        assertEquals("Sample Group", content.name)
        assertEquals("3 people, including you", content.subtitle)
        assertEquals("Bea Ruiz (you)", content.members[0].name)
        assertEquals("+15551110002", content.members[0].number)
        assertEquals(listOf("Bea Ruiz (you)", "Ada Quinn", "Cy Nolan"), content.members.map { it.name })
    }
}
