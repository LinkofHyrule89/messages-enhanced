package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.util.LastThreadRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastThreadRestoreTest {

    private val ada = ConversationDto(conversationId = "c1", name = "Ada")
    private val bob = ConversationDto(conversationId = "c2", name = "Bob")

    @Test
    fun tabletColdStart_opensCachedConversation() {
        val decision = LastThreadRestore.decide(
            twoPane = true,
            hasSavedState = false,
            openingFromIntent = false,
            lastId = "c1",
            cached = listOf(bob, ada),
        )
        assertEquals(ada, (decision as LastThreadRestore.Decision.Open).conversation)
    }

    @Test
    fun phone_doesNotAutoOpen() {
        val decision = LastThreadRestore.decide(
            twoPane = false,
            hasSavedState = false,
            openingFromIntent = false,
            lastId = "c1",
            cached = null,
        )
        assertEquals(LastThreadRestore.Decision.Skip, decision)
    }

    @Test
    fun restoredActivity_doesNotAutoOpen() {
        val decision = LastThreadRestore.decide(
            twoPane = true,
            hasSavedState = true,
            openingFromIntent = false,
            lastId = "c1",
            cached = listOf(ada),
        )
        assertEquals(LastThreadRestore.Decision.Skip, decision)
    }

    @Test
    fun notificationIntent_winsOverLastConversation() {
        val decision = LastThreadRestore.decide(
            twoPane = true,
            hasSavedState = false,
            openingFromIntent = true,
            lastId = "c1",
            cached = listOf(ada),
        )
        assertEquals(LastThreadRestore.Decision.Skip, decision)
    }

    @Test
    fun blankOrMissingId_skips() {
        assertEquals(
            LastThreadRestore.Decision.Skip,
            LastThreadRestore.decide(true, false, false, null, listOf(ada)),
        )
        assertEquals(
            LastThreadRestore.Decision.Skip,
            LastThreadRestore.decide(true, false, false, "  ", listOf(ada)),
        )
    }

    @Test
    fun idNotInCache_skips() {
        val decision = LastThreadRestore.decide(
            twoPane = true,
            hasSavedState = false,
            openingFromIntent = false,
            lastId = "gone",
            cached = listOf(ada),
        )
        assertEquals(LastThreadRestore.Decision.Skip, decision)
    }

    @Test
    fun cacheNotReady_waits() {
        val decision = LastThreadRestore.decide(
            twoPane = true,
            hasSavedState = false,
            openingFromIntent = false,
            lastId = " c1 ",
            cached = null,
        )
        assertTrue(decision is LastThreadRestore.Decision.WaitForCache)
    }
}
