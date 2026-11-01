package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.util.BareImages
import com.ubermicrostudios.messagesenhanced.util.BubbleCorners
import com.ubermicrostudios.messagesenhanced.util.MessageRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BareImageTest {
    private fun image(mime: String, body: String) = MessageDto(
        messageId = "m",
        mimeType = mime,
        body = body,
        mediaId = "media",
    )

    @Test
    fun photoAndGifWithNoCaptionAreBare() {
        assertTrue(BareImages.solo(image("image/jpeg", "")))
        assertTrue(BareImages.solo(image("image/jpeg", "[Photo]")))
        assertTrue(BareImages.solo(image("image/png", "pic.png")))
        assertTrue(BareImages.solo(image("image/gif", "")))
        assertTrue(BareImages.solo(image("image/gif", "[Image]")))
        assertTrue(BareImages.solo(image("image/heic", "[Photo]")))
    }

    @Test
    fun captionStickerAndSeveralImagesKeepTheBubble() {
        assertFalse(BareImages.solo(image("image/jpeg", "on the trail")))
        assertFalse(BareImages.solo(image("image/gif", "wow")))
        assertFalse(BareImages.solo(image("image/webp", "")))
        assertFalse(BareImages.solo(image("image/png", "[Sticker]")))
        assertFalse(BareImages.solo(image("image/jpeg", ""), imageCount = 2))
        assertFalse(BareImages.solo(image("video/mp4", "")))
        assertFalse(BareImages.solo(MessageDto(messageId = "t", body = "hello")))
    }

    @Test
    fun bareCornersMatchTheBubble() {
        val solo = BubbleCorners.px(density = 2f, fromMe = true, pos = MessageRuns.Pos.SOLO)
        assertEquals(BubbleCorners.Px(40f, 40f, 40f, 40f), solo)
        val firstMine = BubbleCorners.px(2f, fromMe = true, pos = MessageRuns.Pos.FIRST)
        assertEquals(40f, firstMine.topLeft)
        assertEquals(8f, firstMine.bottomRight)
        val midTheirs = BubbleCorners.px(1f, fromMe = false, pos = MessageRuns.Pos.MID)
        assertEquals(BubbleCorners.Px(4f, 20f, 20f, 4f), midTheirs)
        assertEquals(
            floatArrayOf(4f, 4f, 20f, 20f, 20f, 20f, 4f, 4f).toList(),
            midTheirs.toArray().toList(),
        )
    }
}
