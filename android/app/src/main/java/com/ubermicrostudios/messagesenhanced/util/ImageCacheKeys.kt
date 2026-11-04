package com.ubermicrostudios.messagesenhanced.util

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import coil.transform.CircleCropTransformation
import java.net.URI
import java.net.URLDecoder

/**
 * Stable Coil keys for authenticated media.
 * `/api/avatar` is identified by its query, so only auth-token parameters are removed.
 * A rotating `token` must not miss the disk cache, and two people must not share one entry.
 *
 * Memory keys for transformed requests must include the transformation. Coil uses an
 * explicit [ImageRequest.memoryCacheKey] as-is, so overriding it with the URL alone
 * made circle-cropped avatars reuse uncropped bitmaps from a probe load.
 */
object ImageCacheKeys {
    /** Coil's extra name for the first transformation, from MemoryCacheService. */
    const val TRANSFORMATION_EXTRA_0 = "coil#transformation_0"

    val circleCropCacheKey: String = CircleCropTransformation().cacheKey

    private val AUTH_PARAMS = setOf(
        "token",
        "access_token",
        "auth",
        "auth_token",
        "authorization",
        "sig",
        "signature",
        "session",
        "expires",
        "exp",
        "api_key",
        "apikey",
    )

    fun of(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val kept = stableQuery(uri.rawQuery)
        val port = uri.port
        val root = buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != -1 && !((scheme == "https" && port == 443) || (scheme == "http" && port == 80))) {
                append(':')
                append(port)
            }
            append(path)
        }
        return if (kept.isEmpty()) root else "$root?$kept"
    }

    fun diskKey(url: String): String = of(url) ?: url

    /**
     * Memory cache key for a circle-cropped avatar. The extra matches Coil's
     * `coil#transformation_0` so an uncropped decode of the same URL cannot be reused.
     */
    fun circleMemoryKey(url: String): MemoryCache.Key =
        MemoryCache.Key(diskKey(url), mapOf(TRANSFORMATION_EXTRA_0 to circleCropCacheKey))

    private fun stableQuery(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.split('&')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val name = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
                if (name.lowercase() in AUTH_PARAMS) null else part
            }
            .sortedBy { it.substringBefore('=').lowercase() }
            .joinToString("&")
    }
}

fun ImageRequest.Builder.stableCacheKey(data: Any?): ImageRequest.Builder {
    val key = (data as? String)?.let(ImageCacheKeys::of) ?: return this
    // Disk only. Overriding memoryCacheKey drops Coil's transformation extras.
    diskCacheKey(key)
    return this
}

/** Neutral fill used before a bitmap arrives. Same tone as an incoming bubble. */
const val IMAGE_PLACEHOLDER_COLOR = 0xFF262B33.toInt()

/**
 * Network cache policy for message and preview images.
 *
 * Coil 2.7 maps [CachePolicy.DISABLED] to OkHttp `only-if-cached` whenever the disk
 * cache is still readable. An uncached URL then fails in-process with HTTP 504 and
 * never reaches the server. [CachePolicy.ENABLED] is a normal fetch. Successes stay
 * in Coil's disk cache ([coil.ImageLoader.Builder.respectCacheHeaders] is false).
 * Failed responses are not written there, and image loads drop those keys on error.
 */
val mediaNetworkCachePolicy: CachePolicy = CachePolicy.ENABLED

/**
 * Message and wallpaper loads: stable disk and memory keys, and a crossfade
 * only when [NetworkCrossfadeFactory] sees [coil.decode.DataSource.NETWORK].
 * The memory key is also the placeholder key, so a hit is set synchronously
 * inside Coil's enqueue on the main thread.
 */
fun ImageRequest.Builder.cachedMedia(url: String): ImageRequest.Builder {
    val disk = ImageCacheKeys.diskKey(url)
    val memory = MemoryCache.Key(disk)
    diskCacheKey(disk)
    memoryCacheKey(memory)
    placeholderMemoryCacheKey(memory)
    placeholder(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
    networkCachePolicy(mediaNetworkCachePolicy)
    transitionFactory(NetworkCrossfadeFactory())
    return this
}

/** Circle-cropped avatar: stable disk key, memory key that includes the crop, no crossfade. */
fun ImageRequest.Builder.circleAvatar(url: String): ImageRequest.Builder {
    val memory = ImageCacheKeys.circleMemoryKey(url)
    diskCacheKey(ImageCacheKeys.diskKey(url))
    memoryCacheKey(memory)
    placeholderMemoryCacheKey(memory)
    transformations(CircleCropTransformation())
    crossfade(false)
    return this
}

class UrlStringKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String? = ImageCacheKeys.of(data)
}

class UrlUriKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? = ImageCacheKeys.of(data.toString())
}
