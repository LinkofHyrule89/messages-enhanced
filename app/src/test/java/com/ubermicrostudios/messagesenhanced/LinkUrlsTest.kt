package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.LinkUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkUrlsTest {

    @Test
    fun extractsHttpUrlAndKeepsSurroundingText() {
        val parts = LinkUrls.parts("See https://example.com/posts/2112982970869170553 today")
        assertEquals("See ", parts[0].text)
        assertNull(parts[0].url)
        assertEquals("https://example.com/posts/2112982970869170553", parts[1].text)
        assertEquals("https://example.com/posts/2112982970869170553", parts[1].url)
        assertEquals(" today", parts[2].text)
    }

    @Test
    fun normalizesBareHostAndWww() {
        assertEquals("https://go.dev", LinkUrls.firstHttpUrl("go.dev"))
        assertEquals("https://www.example.com/a", LinkUrls.firstHttpUrl("www.example.com/a"))
        assertEquals("https://example.com:8080/path?q=1", LinkUrls.firstHttpUrl("example.com:8080/path?q=1"))
    }

    @Test
    fun stripsTrailingPunctuationButKeepsBalancedParens() {
        assertEquals("https://example.com/story", LinkUrls.firstHttpUrl("Look: https://example.com/story."))
        assertEquals("https://en.wikipedia.org/wiki/Chat_(software)", LinkUrls.firstHttpUrl("https://en.wikipedia.org/wiki/Chat_(software)"))
        assertEquals("https://example.com/a", LinkUrls.firstHttpUrl("(https://example.com/a)"))
        val peeled = LinkUrls.trimTrailing("example.com/a).")
        assertEquals("example.com/a", peeled.first)
        assertEquals(").", peeled.second)
    }

    @Test
    fun skipsEmailsAndReturnsFirstOfSeveral() {
        assertNull(LinkUrls.firstHttpUrl("write me at user@example.com please"))
        assertEquals(
            "https://a.example/one",
            LinkUrls.firstHttpUrl("https://a.example/one and then b.example/two"),
        )
        assertNull(LinkUrls.firstHttpUrl("no link here"))
        assertEquals(listOf(LinkUrls.Part("hello")), LinkUrls.parts("hello"))
    }
}
