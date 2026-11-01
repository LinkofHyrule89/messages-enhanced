package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.update.WhatsNewPolicy
import com.ubermicrostudios.messagesenhanced.util.ChangelogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParserTest {

    private val markdown = """
        # Changelog

        ## v0.6.8

        - Round photos again.
        - Chats come back right away.

        ## v0.6.7

        - Encrypted credentials.
    """.trimIndent()

    @Test
    fun section_matchesVersionNameWithOrWithoutV() {
        val expected = "- Round photos again.\n- Chats come back right away."
        assertEquals(expected, ChangelogParser.section(markdown, "0.6.8"))
        assertEquals(expected, ChangelogParser.section(markdown, "v0.6.8"))
        assertEquals(expected, ChangelogParser.section(markdown, "  v0.6.8  "))
        assertEquals("- Encrypted credentials.", ChangelogParser.section(markdown, "0.6.7"))
    }

    @Test
    fun section_missingOrUnknownReturnsNull() {
        assertNull(ChangelogParser.section(markdown, "0.6.9"))
        assertNull(ChangelogParser.section("", "0.6.8"))
        assertNull(ChangelogParser.section(markdown, "not-a-version"))
        assertNull(ChangelogParser.section("## Notes\n- hi", "0.6.8"))
    }

    @Test
    fun displayBody_turnsDashesIntoBullets() {
        assertEquals(
            "• Round photos again.\n• Chats come back right away.",
            ChangelogParser.displayBody("- Round photos again.\n- Chats come back right away."),
        )
        assertEquals(
            "Keep this line\n• Next",
            ChangelogParser.displayBody("Keep this line\n- Next"),
        )
    }

    @Test
    fun normalize_stripsVAndRejectsJunk() {
        assertEquals("0.6.8", ChangelogParser.normalize("v0.6.8"))
        assertEquals("0.6.8", ChangelogParser.normalize("0.6.8"))
        assertNull(ChangelogParser.normalize("0.6"))
        assertNull(ChangelogParser.normalize(""))
    }

    @Test
    fun whatsNew_showsOnlyAfterAnUpdateNotAFreshInstall() {
        assertFalse(WhatsNewPolicy.shouldShow(null, 14))
        assertTrue(WhatsNewPolicy.shouldShow(13, 14))
        assertFalse(WhatsNewPolicy.shouldShow(14, 14))
        assertFalse(WhatsNewPolicy.shouldShow(15, 14))
        assertFalse(WhatsNewPolicy.shouldShow(13, 0))
    }
}
