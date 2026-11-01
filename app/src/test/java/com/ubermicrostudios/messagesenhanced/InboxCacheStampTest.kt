package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.cache.InboxCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxCacheStampTest {
    @Test
    fun blankServerStampStillMatchesAndADifferentServerDoesNot() {
        assertTrue(InboxCache.matchesServer("", "https://messages.example"))
        assertTrue(InboxCache.matchesServer("   ", "https://messages.example"))
        assertTrue(InboxCache.matchesServer("https://messages.example", "https://messages.example"))
        assertFalse(InboxCache.matchesServer("https://other.example", "https://messages.example"))
    }
}
