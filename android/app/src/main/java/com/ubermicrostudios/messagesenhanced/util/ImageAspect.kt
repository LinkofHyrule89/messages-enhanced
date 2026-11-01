package com.ubermicrostudios.messagesenhanced.util

import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Pixel size of a message image, GIF, sticker, or link-preview picture.
 * The row is measured from this before Coil paints, so a bitmap arrival
 * does not change the row height.
 */
object ImageAspects {
    data class Px(val width: Int, val height: Int) {
        val valid: Boolean get() = width > 0 && height > 0
    }

    /** DTO size wins. Otherwise the size remembered for this URL after a decode. */
    fun known(dtoWidth: Int, dtoHeight: Int, cached: Px?): Px? {
        if (dtoWidth > 0 && dtoHeight > 0) return Px(dtoWidth, dtoHeight)
        return cached?.takeIf { it.valid }
    }

    fun resolve(url: String, dtoWidth: Int, dtoHeight: Int): Px? =
        known(dtoWidth, dtoHeight, memory.get(ImageCacheKeys.diskKey(url)))

    fun remember(url: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        memory.put(ImageCacheKeys.diskKey(url), Px(width, height))
    }

    /**
     * Link-preview height for a frame of [frameWidthPx]. Videos are 16:9.
     * Returns null when the aspect is not known yet.
     */
    fun linkHeight(
        frameWidthPx: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        video: Boolean,
        minPx: Int,
        capPx: Int,
    ): Int? {
        if (frameWidthPx <= 0 || capPx <= 0) return null
        val raw = if (video) {
            frameWidthPx.toLong() * 9 / 16
        } else {
            if (sourceWidth <= 0 || sourceHeight <= 0) return null
            frameWidthPx.toLong() * sourceHeight / sourceWidth
        }
        val floor = minPx.coerceAtLeast(1)
        val ceil = capPx.coerceAtLeast(floor)
        return raw.toInt().coerceIn(floor, ceil)
    }

    /** Bubble width used for photos and link cards: 300dp, or 66% of the screen. */
    fun frameWidth(screenWidthPx: Int, density: Float): Int {
        if (density <= 0f) return 0
        val wide = (300f * density).toInt()
        val screen = (screenWidthPx * 0.66f).toInt()
        return minOf(wide, screen).coerceAtLeast(1)
    }

    fun isSticker(mimeType: String, body: String): Boolean =
        mimeType.contains("webp", ignoreCase = true) || body.trim().equals("[Sticker]", ignoreCase = true)

    private val memory = AspectMemory()

    /** Visible for tests. Production uses [remember] / [resolve]. */
    class AspectMemory {
        private val map = ConcurrentHashMap<String, Px>()

        fun get(key: String): Px? = map[key]

        fun put(key: String, px: Px) {
            if (key.isBlank() || !px.valid) return
            if (map.size > CAP && !map.containsKey(key)) map.clear()
            map[key] = px
        }

        companion object {
            private const val CAP = 800
        }
    }
}

/** Header-only decode of a local file so a pending send can reserve its box. */
object LocalImageBounds {
    fun of(path: String): ImageAspects.Px? {
        if (path.isBlank()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return ImageAspects.Px(opts.outWidth, opts.outHeight)
    }
}
