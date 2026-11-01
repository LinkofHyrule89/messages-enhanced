package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.ImageCacheKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCacheKeysTest {

    @Test
    fun stripsAuthTokensAndKeepsAvatarIdentity() {
        val avatar = ImageCacheKeys.of(
            "https://messages.example/api/avatar?token=abc&phone=15555550100&source=sms&v=2",
        )
        assertEquals(
            "https://messages.example/api/avatar?phone=15555550100&source=sms&v=2",
            avatar,
        )
        assertEquals(
            "https://messages.example/api/media/m1",
            ImageCacheKeys.of("https://messages.example/api/media/m1?access_token=zzz"),
        )
        assertNull(ImageCacheKeys.of("/cache/pending/a.png"))
    }

    @Test
    fun circleMemoryKeyIncludesTheCropAndSharesTheStableUrl() {
        val url = "https://messages.example/api/avatar?token=abc&phone=15555550100&source=sms&v=2"
        val key = ImageCacheKeys.circleMemoryKey(url)
        assertEquals(
            "https://messages.example/api/avatar?phone=15555550100&source=sms&v=2",
            key.key,
        )
        assertEquals(
            ImageCacheKeys.circleCropCacheKey,
            key.extras[ImageCacheKeys.TRANSFORMATION_EXTRA_0],
        )
        assertEquals(key, ImageCacheKeys.circleMemoryKey(url.replace("token=abc&", "")))
        assertNotEquals(key.key, ImageCacheKeys.circleMemoryKey("https://messages.example/api/avatar?phone=other").key)
        assertTrue(ImageCacheKeys.circleCropCacheKey.contains("CircleCrop"))
    }
}
