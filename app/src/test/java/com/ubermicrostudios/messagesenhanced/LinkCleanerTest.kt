package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.LinkCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic URLs only. */
class LinkCleanerTest {
    private fun c(u: String, r: String = LinkCleaner.DEFAULT_RULE) = LinkCleaner.cleanUrl(u, r)

    @Test fun stripsPrefixParams() {
        assertEquals("https://shop.example.com/p/123?id=9", c("https://shop.example.com/p/123?utm_source=x&utm_medium=y&id=9"))
        assertEquals("https://example.com/a", c("https://example.com/a?ref=abc&ref_=nav&pf_rd_p=1&pf_rd_r=2&psc=1"))
        assertEquals("https://example.net/x?q=cats#top", c("https://example.net/x?share_id=1&ShareTm=2&_ga=3&spm=a.b&q=cats#top"))
    }

    @Test fun shortRulesAndCase() {
        assertEquals("https://video.example.org/watch?v=abc123", c("https://video.example.org/watch?v=abc123&t=42&si=zzz"))
        assertEquals("https://example.com/x?keep=a%20b", c("https://example.com/x?UTM_Campaign=1&keep=a%20b"))
        assertEquals("https://example.com/x?Q=1&id=2", c("https://example.com/x?Q=1&id=2"))
        assertEquals("https://example.com/plain", c("https://example.com/plain"))
    }

    @Test fun customAndInvalidRules() {
        assertEquals("https://example.com/x?bar=2", c("https://example.com/x?foo=1&bar=2", "foo"))
        assertFalse(LinkCleaner.isValid("utm|("))
        assertTrue(LinkCleaner.isValid(LinkCleaner.DEFAULT_RULE))
        assertEquals("https://example.com/x?utm=1", c("https://example.com/x?utm=1", "("))
    }

    @Test fun cleansTextKeepingPunctuation() {
        val r = LinkCleaner.cleanText("See https://example.com/a?utm_source=x&id=1, and https://example.org/b?s=2. Also http://example.net/c.")
        assertEquals("See https://example.com/a?id=1, and https://example.org/b. Also http://example.net/c.", r.text)
        assertEquals(2, r.count)
    }
}
