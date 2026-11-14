package com.ubermicrostudios.messagesenhanced.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    private val nb = "\u00A0"

    @Test fun rangesFullAndFirst() {
        val t = "hi $nb@Alex Example$nb and $nb@Sam$nb!"
        val r = Mentions.ranges(t)
        assertEquals(listOf("@Alex Example", "@Sam"), r.map { t.substring(it) })
        assertEquals(emptyList<IntRange>(), Mentions.ranges("mail a@b.com"))
    }

    @Test fun insertThenBackspaceTwice() {
        val (t1, c1) = Mentions.insert("hey @Al", 7, "Alex Example")!!
        assertEquals("hey $nb@Alex Example$nb ", t1)
        val (t2, c2) = Mentions.backspace(t1, c1)!!
        assertEquals("hey $nb@Alex$nb", t2)
        assertEquals(t2.length, c2)
        val (t3, _) = Mentions.backspace(t2, c2)!!
        assertEquals("hey ", t3)
        assertNull(Mentions.backspace("plain", 5))
    }

    @Test fun query() {
        assertEquals(4 to "Al", Mentions.query("hey @Al", 7))
        assertNull(Mentions.query("a@b", 3))
    }

    @Test fun tapUsesTheFilteredRowNotTheFullMemberList() {
        val members = listOf("Alex Example", "Sam Taylor", "Riley Stone")
        val opened = Mentions.matches(members, "")
        val typed = Mentions.matches(members, "st")
        // "@" shows everyone. "@st" keeps Riley Stone, whose last word starts with "st".
        assertEquals(members, opened)
        assertEquals(listOf("Riley Stone"), typed)
        // The same row index is a different person before and after the filter.
        assertEquals("Alex Example", Mentions.selectedSuggestion(opened, 0))
        assertEquals("Riley Stone", Mentions.selectedSuggestion(typed, 0))
        val (text, cursor) = Mentions.insertSuggestion("@st", 3, typed, 0)!!
        assertEquals("$nb@Riley Stone$nb ", text)
        assertEquals(text.length, cursor)
        // A position that only existed in the unfiltered list inserts nothing.
        assertNull(Mentions.insertSuggestion("@st", 3, typed, opened.indexOf("Sam Taylor")))
    }

    @Test fun tapInsertsThatRowAndReplacesOnlyTheQuery() {
        val visible = listOf("Sam Taylor", "Riley Stone")
        assertEquals("Sam Taylor", Mentions.selectedSuggestion(visible, 0))
        val (text, cursor) = Mentions.insertSuggestion("hey @s there", 6, visible, 1)!!
        val mention = "$nb@Riley Stone$nb"
        assertEquals("hey $mention there", text)
        // The space after @s was already there, so the cursor sits just after the mention.
        assertEquals("hey $mention".length, cursor)
    }

    @Test fun outOfRangeTapDoesNotInsert() {
        val visible = listOf("Sam Taylor")
        assertNull(Mentions.selectedSuggestion(visible, 1))
        assertNull(Mentions.selectedSuggestion(visible, -1))
        assertNull(Mentions.insertSuggestion("@s", 2, visible, 1))
        assertNull(Mentions.insertSuggestion("@s", 2, visible, -1))
        assertNull(Mentions.insertSuggestion("@s", 2, emptyList(), 0))
    }

    @Test fun panelGrowsWithMatchesThenScrolls() {
        assertEquals(0, Mentions.suggestionPanelHeightPx(0, 2f))
        assertEquals(0, Mentions.suggestionPanelHeightPx(3, 0f))
        val one = Mentions.suggestionPanelHeightPx(1, 1f)
        assertEquals(52, one)
        assertEquals(one, Mentions.suggestionRowHeightPx(1f))
        assertEquals(one * 3, Mentions.suggestionPanelHeightPx(3, 1f))
        val capped = Mentions.suggestionPanelHeightPx(8, 1f)
        assertEquals(one * Mentions.SUGGESTION_MAX_ROWS, capped)
        assertTrue(capped < one * 8)
        val dense = Mentions.suggestionPanelHeightPx(1, 2.5f)
        assertEquals(Mentions.suggestionRowHeightPx(2.5f), dense)
        assertEquals(dense * Mentions.SUGGESTION_MAX_ROWS, Mentions.suggestionPanelHeightPx(6, 2.5f))
        // A large font makes the label taller than the avatar, so the row grows.
        assertTrue(Mentions.suggestionRowHeightPx(1f, fontScale = 3f) > one)
    }
}
