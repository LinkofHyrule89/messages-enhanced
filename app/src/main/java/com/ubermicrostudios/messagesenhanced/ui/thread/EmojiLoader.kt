package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

/**
 * Plain Coil loader for Noto Animated Emoji on fonts.gstatic.com. Deliberately
 * separate from the app loader so the auth interceptor never rewrites these
 * requests and the session cookie is never sent to a third party.
 */
object EmojiLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                }
                .crossfade(false)
                .build()
                .also { loader = it }
        }
}
