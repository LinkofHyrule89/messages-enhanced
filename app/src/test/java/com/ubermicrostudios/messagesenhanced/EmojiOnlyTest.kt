package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.EmojiOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiOnlyTest {
    @Test fun detects() {
        assertEquals(listOf("\uD83D\uDE02"), EmojiOnly.split("\uD83D\uDE02"))
        assertEquals(3, EmojiOnly.split("\u2764\uFE0F \uD83D\uDE02\uD83D\uDC4D")?.size)
        assertEquals(1, EmojiOnly.split("\uD83D\uDC4D\uD83C\uDFFD")?.size) // skin tone
        assertEquals(1, EmojiOnly.split("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67")?.size) // ZWJ family
        assertEquals(1, EmojiOnly.split("\uD83C\uDDFA\uD83C\uDDF8")?.size) // flag
        assertNull(EmojiOnly.split("hi \uD83D\uDE02"))
        assertNull(EmojiOnly.split("\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02"))
        assertNull(EmojiOnly.split("123"))
        assertNull(EmojiOnly.split(""))
    }

    @Test fun url() {
        assertEquals("https://fonts.gstatic.com/s/e/notoemoji/latest/2764/512.webp", EmojiOnly.notoUrl("\u2764\uFE0F"))
        assertEquals("https://fonts.gstatic.com/s/e/notoemoji/latest/1f602/512.webp", EmojiOnly.notoUrl("\uD83D\uDE02"))
    }
}
