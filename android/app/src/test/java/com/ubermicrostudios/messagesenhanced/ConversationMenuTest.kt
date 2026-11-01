package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.util.ConversationMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMenuTest {

    @Test
    fun googleOneToOne_unpinned_showsContactRefreshPinArchiveTrash() {
        val entries = ConversationMenu.entries(ConversationDto(name = "Ada", sourcePlatform = "sms"))
        assertEquals(
            listOf("contact", "refresh", "pin+note", "archive", "trash"),
            labels(entries),
        )
    }

    @Test
    fun googleGroup_localPin_showsGroupAndUnpinWithoutNote() {
        val entries = ConversationMenu.entries(
            ConversationDto(isGroup = true, sourcePlatform = "SMS", localPinnedAtMs = 5L),
        )
        assertEquals(
            listOf("group", "refresh", "unpin", "archive", "trash"),
            labels(entries),
        )
    }

    @Test
    fun phonePin_hidesPinRow_andKeepsGoogleActions() {
        val entries = ConversationMenu.entries(
            ConversationDto(sourcePlatform = " sms ", googlePinned = true, localPinnedAtMs = 9L),
        )
        assertEquals(listOf("contact", "refresh", "archive", "trash"), labels(entries))
        assertTrue(entries.none { it is ConversationMenu.Entry.Pin })
    }

    @Test
    fun nonGoogle_hidesRefreshArchiveAndTrash() {
        val imessage = ConversationMenu.entries(
            ConversationDto(sourcePlatform = "imessage", isGroup = false),
        )
        assertEquals(listOf("contact", "pin+note"), labels(imessage))

        val pinnedGroup = ConversationMenu.entries(
            ConversationDto(sourcePlatform = "Imessage", isGroup = true, googlePinned = true),
        )
        assertEquals(listOf("group"), labels(pinnedGroup))
    }

    @Test
    fun blankPlatform_countsAsGoogle_otherPlatformsDoNot() {
        assertTrue(ConversationMenu.isGoogle(""))
        assertTrue(ConversationMenu.isGoogle("   "))
        assertTrue(ConversationMenu.isGoogle("Sms"))
        assertFalse(ConversationMenu.isGoogle("whatsapp"))
        assertFalse(ConversationMenu.isGoogle("rcs"))
        assertEquals(
            listOf("contact", "refresh", "pin+note", "archive", "trash"),
            labels(ConversationMenu.entries(ConversationDto(sourcePlatform = ""))),
        )
    }

    private fun labels(entries: List<ConversationMenu.Entry>): List<String> = entries.map { entry ->
        when (entry) {
            ConversationMenu.Entry.DetailsGroup -> "group"
            ConversationMenu.Entry.DetailsContact -> "contact"
            ConversationMenu.Entry.Refresh -> "refresh"
            is ConversationMenu.Entry.Pin -> if (entry.unpin) "unpin" else "pin" + if (entry.note) "+note" else ""
            ConversationMenu.Entry.Archive -> "archive"
            ConversationMenu.Entry.Trash -> "trash"
        }
    }
}
