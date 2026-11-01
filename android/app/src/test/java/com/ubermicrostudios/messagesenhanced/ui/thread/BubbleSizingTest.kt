package com.ubermicrostudios.messagesenhanced.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSizingTest {
    @Test fun shortReplyLongQuoteKeepsQuoteWidth() {
        // Reply "Oh nice" is ~60px; the quote's one-line text is ~420px.
        val reply = 60f
        val quote = QuoteWidth.intrinsic(nameWidth = 30f, textWidth = 420.4f, padding = 24)
        assertEquals(445, quote)
        assertTrue(quote > reply)
    }

    @Test fun nameWiderThanText() {
        assertEquals(124, QuoteWidth.intrinsic(100f, 40f, 24))
    }

    @Test fun photoFillsWidthAtAspect() {
        assertEquals(600 to 300, PhotoFit.fit(1200, 600, 600, 800, 120))
    }

    @Test fun tallPhotoShrinksUncropped() {
        assertEquals(400 to 800, PhotoFit.fit(1000, 2000, 600, 800, 120))
    }

    @Test fun unloadedPlaceholder() {
        assertEquals(600 to 120, PhotoFit.fit(-1, -1, 600, 800, 120))
    }
}
