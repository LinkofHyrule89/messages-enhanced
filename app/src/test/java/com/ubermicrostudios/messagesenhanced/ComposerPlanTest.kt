package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.ComposerPaste
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan
import com.ubermicrostudios.messagesenhanced.util.EmojiData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposerPlanTest {
    private fun file(id: String, mime: String, size: Long = 10) =
        ComposerPlan.PendingFile(id, "/tmp/$id", id, mime, size)

    @Test
    fun smsKeepsTextAndReplyOffTheFile() {
        val plan = ComposerPlan.sendPlan(
            platform = "sms",
            draft = " hello ",
            replyId = "r1",
            files = listOf(file("a", "image/jpeg"), file("b", "video/mp4")),
        )
        assertEquals(2, plan.media.size)
        assertEquals("", plan.media[0].caption)
        assertEquals("", plan.media[0].replyToId)
        assertEquals("", plan.media[1].replyToId)
        assertEquals("hello", plan.text?.text)
        assertEquals("r1", plan.text?.replyToId)
        assertTrue(plan.clearDraft)
        assertTrue(plan.clearReply)
    }

    @Test
    fun smsFilesAloneLeaveTheReplyBar() {
        val plan = ComposerPlan.sendPlan("SMS", "", "r1", listOf(file("a", "image/jpeg")))
        assertNull(plan.text)
        assertEquals("", plan.media[0].replyToId)
        assertFalse(plan.clearDraft)
        assertFalse(plan.clearReply)
    }

    @Test
    fun whatsappImageTakesCaptionAndReplyOnce() {
        val plan = ComposerPlan.sendPlan(
            "whatsapp",
            "cap",
            "r1",
            listOf(file("a", "image/jpeg"), file("b", "image/png")),
        )
        assertEquals("cap", plan.media[0].caption)
        assertEquals("r1", plan.media[0].replyToId)
        assertEquals("", plan.media[1].caption)
        assertEquals("", plan.media[1].replyToId)
        assertNull(plan.text)
        assertTrue(plan.clearDraft)
        assertTrue(plan.clearReply)
    }

    @Test
    fun whatsappAudioLeavesTheTextAndStillTakesTheReply() {
        val plan = ComposerPlan.sendPlan(
            "whatsapp",
            "later",
            "r1",
            listOf(file("a", "audio/mp4"), file("b", "image/jpeg")),
        )
        assertEquals("", plan.media[0].caption)
        assertEquals("r1", plan.media[0].replyToId)
        assertEquals("later", plan.media[1].caption)
        assertEquals("", plan.media[1].replyToId)
        assertNull(plan.text)
    }

    @Test
    fun whatsappAudioOnlySendsTheTextAfterwardsWithoutTheReply() {
        val plan = ComposerPlan.sendPlan("whatsapp", "later", "r1", listOf(file("a", "audio/ogg")))
        assertEquals("r1", plan.media[0].replyToId)
        assertEquals("later", plan.text?.text)
        assertEquals("", plan.text?.replyToId)
    }

    @Test
    fun signalCaptionsAudio() {
        val plan = ComposerPlan.sendPlan("signal", "voice", "r1", listOf(file("a", "audio/mp4")))
        assertEquals("voice", plan.media[0].caption)
        assertEquals("r1", plan.media[0].replyToId)
        assertNull(plan.text)
    }

    @Test
    fun blankDraftDoesNotSend() {
        val plan = ComposerPlan.sendPlan("sms", "  ", null, emptyList())
        assertTrue(plan.media.isEmpty())
        assertNull(plan.text)
        assertFalse(plan.clearDraft)
    }

    @Test
    fun pendingLimitsSkipEmptyTooLargeAndExtras() {
        val current = (1..9).map { file("k$it", "image/jpeg") }
        val incoming = listOf(
            file("empty", "image/jpeg", 0),
            file("big", "image/jpeg", MessageActionsBytes.tooBig),
            file("ok", "image/png"),
            file("extra", "image/gif"),
        )
        val outcome = ComposerPlan.addFiles(current, incoming)
        assertEquals(10, outcome.files.size)
        assertEquals("ok", outcome.files.last().id)
        assertEquals(listOf(ComposerPlan.TOO_LARGE, ComposerPlan.TOO_MANY), outcome.notices)
    }

    @Test
    fun fileAtTheLimitIsKept() {
        val outcome = ComposerPlan.addFiles(emptyList(), listOf(file("edge", "image/jpeg", MessageActionsBytes.limit)))
        assertEquals(1, outcome.files.size)
        assertTrue(outcome.notices.isEmpty())
    }

    @Test
    fun emojiInsertsAtTheSelection() {
        val inserted = ComposerPlan.insertEmoji("ab", 1, 1, "😀")
        assertEquals("a😀b", inserted.text)
        assertEquals(3, inserted.cursor) // "a" + one emoji (2 chars) 
        val replaced = ComposerPlan.insertEmoji("hello", 1, 4, "👍")
        assertEquals("h👍o", replaced.text)
        assertEquals(3, replaced.cursor)
    }

    @Test
    fun gifPlanCaptionsWhatsappAndFollowsUpOnSms() {
        val wa = ComposerPlan.gifPlan("whatsapp", " hi ", "r1")
        assertEquals("hi", wa.caption)
        assertEquals("r1", wa.gifReply)
        assertEquals("", wa.followUpText)

        val sms = ComposerPlan.gifPlan("sms", "hi", "r1")
        assertEquals("", sms.caption)
        assertEquals("", sms.gifReply)
        assertEquals("hi", sms.followUpText)
        assertEquals("r1", sms.followUpReply)

        val bare = ComposerPlan.gifPlan("sms", "", "r1")
        assertEquals("", bare.followUpText)
        assertEquals("", bare.followUpReply)
    }

    @Test
    fun gifMergeDropsDuplicatesAndStopsOnAShortPage() {
        val first = ComposerPlan.GifRef(url = "https://g/1", previewUrl = "https://p/1", title = "One")
        val again = first.copy(title = "Again")
        val second = ComposerPlan.GifRef(url = "https://g/2", previewUrl = "https://p/2")
        val page = List(ComposerPlan.GIF_PAGE_SIZE) { index ->
            ComposerPlan.GifRef(url = "https://g/$index", previewUrl = "https://p/$index")
        }
        val more = ComposerPlan.mergeGifs(listOf(first), listOf(again) + page.drop(1), reportedHasMore = true)
        assertEquals(31, more.items.size)
        assertEquals("One", more.items.first().title)
        assertTrue(more.hasMore)

        val done = ComposerPlan.mergeGifs(emptyList(), listOf(first, second), reportedHasMore = true)
        assertFalse(done.hasMore)
        assertEquals(2, done.items.size)

        assertNull(ComposerPlan.normalizeGif("1", "t", "", "https://g", "image/gif", 1, 1))
        assertEquals("GIF", ComposerPlan.normalizeGif("1", " ", "/p", "/u", "", 0, 0)?.title)
    }

    @Test
    fun favoritesMoveToFrontAndUnstar() {
        val a = ComposerPlan.GifRef(url = "a", previewUrl = "pa", title = "A")
        val b = ComposerPlan.GifRef(url = "b", previewUrl = "pb", title = "B")
        val added = ComposerPlan.toggleFavorite(listOf(a), b)
        assertEquals(listOf("b", "a"), added.map { it.url })
        assertEquals(listOf("a"), ComposerPlan.toggleFavorite(added, b).map { it.url })
        val many = (1..ComposerPlan.GIF_FAVORITES_LIMIT).map {
            ComposerPlan.GifRef(url = "u$it", previewUrl = "p$it")
        }
        val capped = ComposerPlan.toggleFavorite(many, ComposerPlan.GifRef(url = "new", previewUrl = "pn"))
        assertEquals(ComposerPlan.GIF_FAVORITES_LIMIT, capped.size)
        assertEquals("new", capped.first().url)
    }

    @Test
    fun absolutePreviewKeepsHostAndJoinsAPath() {
        assertEquals("https://h/api/gifs/preview?url=a", ComposerPlan.absoluteUrl("https://h/", "/api/gifs/preview?url=a"))
        assertEquals("https://cdn/x", ComposerPlan.absoluteUrl("https://h", "https://cdn/x"))
    }

    @Test
    fun emojiDataParsesNamesAndTheBundledFile() {
        val sample = """
            window.TM_EMOJI = [{"id":"smileys","label":"Smileys","e":"😀\u001fgrinning face\u001e😂\u001fface with tears of joy"}];
        """.trimIndent()
        val parsed = EmojiData.parse(sample)
        assertEquals("smileys", parsed[0].id)
        assertEquals("grinning face", parsed[0].items[0].name)
        assertEquals("😂", parsed[0].items[1].emoji)

        val file = listOf(File("src/main/assets/emoji-data.js"), File("app/src/main/assets/emoji-data.js"))
            .first { it.exists() }
        val cats = EmojiData.parse(file.readText())
        assertEquals(
            listOf("smileys", "people", "animals", "food", "activities", "travel", "objects", "symbols", "flags"),
            cats.map { it.id },
        )
        assertEquals("grinning face", cats.first().items.first().name)
        assertTrue(cats.sumOf { it.items.size } > 1000)
    }

    @Test
    fun pasteAcceptsImageMimeAndRenamesGenericPng() {
        assertTrue(ComposerPaste.MIME_TYPES.contentEquals(arrayOf("image/*")))
        assertTrue(ComposerPaste.isImageMime("image/jpeg"))
        assertTrue(ComposerPaste.isImageMime("image/png; charset=binary"))
        assertTrue(ComposerPaste.isImageMime("image/*"))
        assertFalse(ComposerPaste.isImageMime("text/plain"))
        assertFalse(ComposerPaste.isImageMime("video/mp4"))
        assertEquals("photo.jpg", ComposerPaste.pastedName("image/jpeg", "photo.jpg"))
        assertEquals("pasted.jpg", ComposerPaste.pastedName("image/jpeg", "image.png"))
        assertEquals("image.png", ComposerPaste.pastedName("image/png", "image.png"))
        assertEquals("pasted.gif", ComposerPaste.pastedName("image/gif", ""))
    }
}

private object MessageActionsBytes {
    val limit = com.ubermicrostudios.messagesenhanced.util.MessageActions.MAX_MEDIA_BYTES
    val tooBig = limit + 1
}
