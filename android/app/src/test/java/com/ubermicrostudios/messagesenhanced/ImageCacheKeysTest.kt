package com.ubermicrostudios.messagesenhanced

import coil.request.CachePolicy
import com.ubermicrostudios.messagesenhanced.util.ImageCacheKeys
import com.ubermicrostudios.messagesenhanced.util.mediaNetworkCachePolicy
import okhttp3.CacheControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun mediaImageRequestsAreNotCacheOnly() {
        assertEquals(CachePolicy.ENABLED, mediaNetworkCachePolicy)
        val sent = coil27CacheControl(mediaNetworkCachePolicy, CachePolicy.ENABLED)
        assertTrue(sent == null || !sent.onlyIfCached)
        // Coil 2.7's disabled network policy, with the disk cache still readable,
        // is OkHttp only-if-cached: a miss is a local 504 and the server is never called.
        val disabled = coil27CacheControl(CachePolicy.DISABLED, CachePolicy.ENABLED)
        assertTrue(disabled != null && disabled.onlyIfCached)
        assertFalse(coil27CacheControl(CachePolicy.ENABLED, CachePolicy.DISABLED)?.onlyIfCached == true)
    }
}

/**
 * Cache-Control header Coil 2.7 attaches in HttpUriFetcher.newRequest.
 * Null means Coil adds no header, so the request is a normal network fetch.
 */
private fun coil27CacheControl(network: CachePolicy, disk: CachePolicy): CacheControl? {
    val networkRead = network.readEnabled
    val diskRead = disk.readEnabled
    return when {
        !networkRead && diskRead -> CacheControl.FORCE_CACHE
        networkRead && !diskRead -> if (disk.writeEnabled) {
            CacheControl.FORCE_NETWORK
        } else {
            CacheControl.Builder().noCache().noStore().build()
        }
        !networkRead && !diskRead -> CacheControl.Builder().noCache().onlyIfCached().build()
        else -> null
    }
}
