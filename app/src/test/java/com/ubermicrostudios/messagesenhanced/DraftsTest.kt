package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.Drafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftsTest {
    @Test fun erasedTextDeletesDraft() {
        assertNull(Drafts.normalize(""))
        assertNull(Drafts.normalize("   \n"))
        assertNull(Drafts.normalize(null))
        assertEquals("hi there", Drafts.normalize("hi there"))
    }

    @Test fun mentionChipsKeptVerbatim() {
        val t = "hey \u00A0@Test Person\u00A0 see you"
        assertEquals(t, Drafts.normalize(t))
    }

    @Test fun longDraftIsCapped() {
        assertEquals(Drafts.MAX_CHARS, Drafts.normalize("a".repeat(Drafts.MAX_CHARS + 10))!!.length)
    }

    @Test fun sharedWinsWhenNoDraft() {
        assertEquals("https://example.com/x", Drafts.mergeShared("", "https://example.com/x"))
        assertEquals("https://example.com/x", Drafts.mergeShared(null, "https://example.com/x"))
    }

    @Test fun sharedAppendsToExistingDraft() {
        assertEquals("look at this\nhttps://example.com/x", Drafts.mergeShared("look at this ", "https://example.com/x"))
    }

    @Test fun sharedAlreadyInDraftNotDuplicated() {
        assertEquals("see https://example.com/x", Drafts.mergeShared("see https://example.com/x", "https://example.com/x"))
    }

    @Test fun blankShareKeepsDraft() {
        assertEquals("draft", Drafts.mergeShared("draft", " "))
    }

    @Test fun listPreview() {
        assertEquals("Draft: hi \u0040Sam there", Drafts.listPreview("hi\n\u00A0@Sam\u00A0there").replace("  ", " "))
    }
}
