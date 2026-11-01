package com.ubermicrostudios.messagesenhanced

import coil.decode.DataSource
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.ui.thread.PhotoFit
import com.ubermicrostudios.messagesenhanced.util.AvatarRevalidation
import com.ubermicrostudios.messagesenhanced.util.CacheTransitions
import com.ubermicrostudios.messagesenhanced.util.ImageAspects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStabilityTest {
    @Test
    fun knownSizePrefersDtoThenCache() {
        val cached = ImageAspects.Px(400, 200)
        assertEquals(ImageAspects.Px(800, 600), ImageAspects.known(800, 600, cached))
        assertEquals(cached, ImageAspects.known(0, 0, cached))
        assertNull(ImageAspects.known(0, 10, cached.copy(width = 0, height = 0)))
        assertNull(ImageAspects.known(0, 0, null))
    }

    @Test
    fun rememberedUrlSizeIsReusedAcrossBinds() {
        val url = "https://messages.example/api/media/stable-1?token=abc"
        ImageAspects.remember(url, 1200, 600)
        assertEquals(ImageAspects.Px(1200, 600), ImageAspects.resolve(url, 0, 0))
        assertEquals(
            ImageAspects.Px(1200, 600),
            ImageAspects.resolve(url.replace("token=abc", "token=other"), 0, 0),
        )
    }

    @Test
    fun linkHeightUsesAspectAndSixteenByNineForVideo() {
        assertEquals(180, ImageAspects.linkHeight(320, 1600, 900, video = false, minPx = 60, capPx = 400))
        assertEquals(180, ImageAspects.linkHeight(320, 16, 9, video = true, minPx = 60, capPx = 400))
        assertEquals(400, ImageAspects.linkHeight(320, 100, 800, video = false, minPx = 60, capPx = 400))
        assertNull(ImageAspects.linkHeight(320, 0, 0, video = false, minPx = 60, capPx = 400))
    }

    @Test
    fun reservedPhotoAspectIgnoresPlaceholderHeight() {
        assertEquals(600 to 300, PhotoFit.fit(1200, 600, 600, 800, 0))
        assertEquals(600 to 120, PhotoFit.fit(0, 0, 600, 800, 120))
    }

    @Test
    fun frameWidthCapsAtThreeHundredDp() {
        assertEquals(300, ImageAspects.frameWidth(screenWidthPx = 1080, density = 1f))
        assertEquals(198, ImageAspects.frameWidth(screenWidthPx = 300, density = 1f))
    }

    @Test
    fun stickersAreWebpOrStickerLabel() {
        assertTrue(ImageAspects.isSticker("image/webp", "hi"))
        assertTrue(ImageAspects.isSticker("image/png", "[Sticker]"))
        assertFalse(ImageAspects.isSticker("image/gif", "hello"))
    }

    @Test
    fun crossfadeOnlyForNetwork() {
        assertTrue(CacheTransitions.shouldCrossfade(DataSource.NETWORK))
        assertFalse(CacheTransitions.shouldCrossfade(DataSource.DISK))
        assertFalse(CacheTransitions.shouldCrossfade(DataSource.MEMORY))
        assertFalse(CacheTransitions.shouldCrossfade(DataSource.MEMORY_CACHE))
    }

    @Test
    fun avatarUrlsAreTheOnesTheServerEtags() {
        assertTrue(AvatarRevalidation.isAvatarUrl("https://h/api/avatar?v=2&participant_id=conv%3Aabc"))
        assertTrue(AvatarRevalidation.isAvatarUrl("https://h/api/app/profile-photo?h=abc"))
        assertFalse(AvatarRevalidation.isAvatarUrl("https://h/api/media/m1"))
        assertFalse(AvatarRevalidation.isAvatarUrl("https://h/api/link-preview-image?url=x"))
    }

    @Test
    fun conditionalHeaderIsTheStoredEtagVerbatim() {
        assertEquals("\"abc\"", AvatarRevalidation.ifNoneMatch("\"abc\""))
        assertNull(AvatarRevalidation.ifNoneMatch("  "))
        assertNull(AvatarRevalidation.ifNoneMatch(null))
    }

    @Test
    fun notModifiedDoesNotReplace() {
        val decision = AvatarRevalidation.decide("\"abc\"", 304, null, bytesMatch = false)
        assertFalse(decision.replaceImage)
        assertFalse(decision.storeBytes)
    }

    @Test
    fun sameEtagOrSameBytesDoNotReplaceTheBitmap() {
        val same = AvatarRevalidation.decide("\"ABC\"", 200, "W/\"abc\"", bytesMatch = false)
        assertFalse(same.replaceImage)
        val bytes = AvatarRevalidation.decide(null, 200, "\"def\"", bytesMatch = true)
        assertFalse(bytes.replaceImage)
        assertTrue(bytes.storeBytes)
        val unchanged = AvatarRevalidation.decide("\"def\"", 200, null, bytesMatch = true)
        assertFalse(unchanged.storeBytes)
    }

    @Test
    fun changedBodyReplacesTheCache() {
        val decision = AvatarRevalidation.decide("\"old\"", 200, "\"new\"", bytesMatch = false)
        assertTrue(decision.replaceImage)
        assertTrue(decision.storeBytes)
        assertFalse(AvatarRevalidation.decide("\"old\"", 500, "\"new\"", bytesMatch = false).replaceImage)
    }

    @Test
    fun coilMetadataRoundTripFindsEtag() {
        val text = AvatarRevalidation.coilMetadata(
            sentAtMillis = 10,
            receivedAtMillis = 20,
            tls = true,
            headers = listOf("Content-Type" to "image/jpeg", "ETag" to "\"hash\""),
        )
        assertEquals("\"hash\"", AvatarRevalidation.etagFromCoilMetadata(text))
        assertNull(AvatarRevalidation.etagFromCoilMetadata("nope"))
    }

    @Test
    fun recheckWaitsForTheInterval() {
        assertTrue(AvatarRevalidation.due(0, 1_000))
        assertFalse(AvatarRevalidation.due(1_000, 30_000))
        assertTrue(AvatarRevalidation.due(1_000, 61_000))
    }

    @Test
    fun messageJsonReadsWidthWhenPresent() {
        val go = JsonConfig.json.decodeFromString(
            MessageDto.serializer(),
            """{"MessageID":"m","Width":640,"Height":480,"MimeType":"image/jpeg"}""",
        )
        assertEquals(640, go.mediaWidth)
        assertEquals(480, go.mediaHeight)
        val snake = JsonConfig.json.decodeFromString(
            MessageDto.serializer(),
            """{"MessageID":"m2","image_width":10,"image_height":20}""",
        )
        assertEquals(10, snake.mediaWidth)
        assertEquals(20, snake.mediaHeight)
        val absent = JsonConfig.json.decodeFromString(MessageDto.serializer(), """{"MessageID":"m3"}""")
        assertEquals(0, absent.mediaWidth)
        assertEquals(0, absent.mediaHeight)
    }
}
