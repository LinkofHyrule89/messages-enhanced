package com.ubermicrostudios.messagesenhanced.share

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTargetsTest {
    @Test
    fun shortcutIdRoundTrips() {
        assertEquals("abc", ShareTargets.conversationId(ShareTargets.shortcutId("abc")))
        assertEquals("", ShareTargets.conversationId("other_abc"))
        assertEquals("", ShareTargets.conversationId(null))
    }

    @Test
    fun pickTakesNewestDistinctIncludingGroups() {
        val list = listOf(
            ConversationDto(conversationId = "a", name = "Alex Example", lastMessageTs = 10),
            ConversationDto(conversationId = "g", name = "Team Example", isGroup = true, lastMessageTs = 30),
            ConversationDto(conversationId = "", name = "Blank", lastMessageTs = 99),
            ConversationDto(conversationId = "a", name = "Dup", lastMessageTs = 5),
            ConversationDto(conversationId = "b", name = "Bo Sample", lastMessageTs = 20),
        )
        assertEquals(listOf("g", "b"), ShareTargets.pick(list, 2).map { it.conversationId })
    }
}
