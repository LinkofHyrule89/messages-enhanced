package com.ubermicrostudios.messagesenhanced.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
