package com.ubermicrostudios.messagesenhanced.util

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.load
import coil.request.Disposable
import coil.request.ImageRequest
import com.ubermicrostudios.messagesenhanced.R
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loads a message image or link-preview picture and, on failure, retries while the view
 * is on screen: 2s, 10s, 30s, then a tap. Layout size is left alone; the reserved box
 * stays filled with [IMAGE_PLACEHOLDER_COLOR]. Error responses are dropped from Coil's
 * memory and disk caches so the next try hits the network.
 *
 * Touch this from the main thread. [retryVisible] is the connectivity / foreground pass.
 */
object RetryingImages {
    private val schedule = ImageRetrySchedule()
    private val watching = CopyOnWriteArrayList<WeakReference<ImageView>>()

    private class Load(
        val url: String,
        var token: Int,
        var runnable: Runnable?,
        var request: Disposable?,
        var failed: Boolean,
        val onSuccess: (Drawable) -> Unit,
        val onFailed: (attempt: Int, willAutoRetry: Boolean) -> Unit,
        val onStart: () -> Unit,
        val tapRetries: Boolean,
        val chrome: Boolean,
        var chromeOn: Boolean,
        val savedDesc: CharSequence?,
        val savedForeground: Drawable?,
        val applyCachedMedia: Boolean,
        val configure: ImageRequest.Builder.() -> Unit,
    )

    fun failed(image: ImageView): Boolean =
        (image.getTag(R.id.tag_media_load) as? Load)?.failed == true

    fun load(
        image: ImageView,
        url: String,
        onSuccess: (Drawable) -> Unit = {},
        onFailed: (attempt: Int, willAutoRetry: Boolean) -> Unit = { _, _ -> },
        onStart: () -> Unit = {},
        tapRetries: Boolean = false,
        chrome: Boolean = true,
        applyCachedMedia: Boolean = true,
        configure: ImageRequest.Builder.() -> Unit = {},
    ) {
        if (url.isBlank()) return
        val previous = image.getTag(R.id.tag_media_load) as? Load
        // Rebinding the same URL must not dispose a request that is still running.
        // Cancelling it fails the row, and the replacement starts over.
        if (previous != null &&
            previous.url == url &&
            !previous.failed &&
            previous.runnable == null &&
            previous.request?.isDisposed == false
        ) {
            return
        }
        previous?.runnable?.let { image.removeCallbacks(it) }
        previous?.request?.dispose()
        if (previous?.chromeOn == true) {
            image.foreground = previous.savedForeground
            image.contentDescription = previous.savedDesc
        }
        val load = Load(
            url = url,
            token = (previous?.token ?: 0) + 1,
            runnable = null,
            request = null,
            failed = false,
            onSuccess = onSuccess,
            onFailed = onFailed,
            onStart = onStart,
            tapRetries = tapRetries,
            chrome = chrome,
            chromeOn = false,
            savedDesc = image.contentDescription,
            savedForeground = image.foreground,
            applyCachedMedia = applyCachedMedia,
            configure = configure,
        )
        image.setTag(R.id.tag_media_load, load)
        val decision = schedule.onBind(url)
        if (!decision.fetch) {
            load.failed = true
            // A recycled row can still be showing the previous message's bitmap.
            // This URL is not being loaded, so fill the reserved box instead.
            if (previous == null || previous.url != url) {
                image.setImageDrawable(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
            }
            watch(image)
            showChrome(image, load)
            if (load.tapRetries) image.setOnClickListener { retryNow(image) }
            val auto = decision.waitMs != Long.MAX_VALUE
            load.onFailed(decision.attempt, auto)
            if (auto) post(image, load, load.token, decision.waitMs)
            return
        }
        enqueue(image, load, load.token)
    }

    fun retryNow(image: ImageView) {
        val load = image.getTag(R.id.tag_media_load) as? Load ?: return
        load.runnable?.let { image.removeCallbacks(it) }
        load.runnable = null
        schedule.release(load.url)
        load.token += 1
        load.failed = false
        hideChrome(image, load)
        enqueue(image, load, load.token)
    }

    /**
     * Connectivity, foreground, or Google connected again: waiting backoffs are due now,
     * and images still on screen load immediately.
     */
    fun retryAfterReconnect() {
        schedule.releaseAll()
        retryVisible()
    }

    /** Immediate retry of images that are on screen and currently failed. */
    fun retryVisible() {
        val snapshot = watching.toList()
        for (ref in snapshot) {
            val view = ref.get()
            if (view == null) {
                watching.remove(ref)
                continue
            }
            val load = view.getTag(R.id.tag_media_load) as? Load
            if (load == null || !load.failed) {
                watching.remove(ref)
                continue
            }
            if (!view.isAttachedToWindow || !view.isShown) continue
            retryNow(view)
        }
    }

    /** Drop callbacks and the retry chrome. Does not forget the URL's backoff. */
    fun cancel(image: ImageView) {
        val load = image.getTag(R.id.tag_media_load) as? Load ?: return
        load.runnable?.let { image.removeCallbacks(it) }
        load.runnable = null
        load.request?.dispose()
        load.request = null
        hideChrome(image, load)
        if (load.tapRetries) {
            image.setOnClickListener(null)
            image.isClickable = false
        }
        image.setTag(R.id.tag_media_load, null)
        unwatch(image)
    }

    private fun enqueue(image: ImageView, load: Load, token: Int) {
        val url = load.url
        load.request?.dispose()
        load.request = image.load(url) {
            if (load.applyCachedMedia) cachedMedia(url)
            error(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
            load.configure(this)
            // ENABLED on purpose. DISABLED is cache-only in Coil 2.7 and 504s a miss.
            // Failures are dropped below via forget(), not by disabling the network cache.
            networkCachePolicy(mediaNetworkCachePolicy)
            listener(
                onStart = {
                    if (current(image, url, token)) load.onStart()
                },
                onSuccess = { _, result ->
                    if (current(image, url, token)) {
                        schedule.onSuccess(url)
                        load.failed = false
                        hideChrome(image, load)
                        if (load.tapRetries) {
                            image.setOnClickListener(null)
                            image.isClickable = false
                        }
                        unwatch(image)
                        load.onSuccess(result.drawable)
                    }
                },
                onError = { request, _ ->
                    if (current(image, url, token)) {
                        if (ImageRetryBackoff.forgetOnError(url)) forget(image, request)
                        val delay = schedule.onFailure(url)
                        load.failed = true
                        watch(image)
                        if (image.drawable == null) {
                            image.setImageDrawable(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
                        }
                        showChrome(image, load)
                        if (load.tapRetries) image.setOnClickListener { retryNow(image) }
                        val auto = delay != null
                        load.onFailed(schedule.onBind(url).attempt, auto)
                        if (delay != null) post(image, load, token, delay)
                    }
                },
            )
        }
    }

    private fun post(image: ImageView, load: Load, token: Int, delayMs: Long) {
        val runnable = object : Runnable {
            override fun run() {
                if (load.runnable === this) load.runnable = null
                if (!current(image, load.url, token) || !visible(image)) return
                load.token += 1
                val next = load.token
                load.failed = false
                enqueue(image, load, next)
            }
        }
        load.runnable = runnable
        image.postDelayed(runnable, delayMs)
    }

    private fun current(image: ImageView, url: String, token: Int): Boolean {
        val load = image.getTag(R.id.tag_media_load) as? Load ?: return false
        return load.url == url && load.token == token
    }

    private fun visible(image: ImageView): Boolean = image.isAttachedToWindow && image.isShown

    private fun showChrome(image: ImageView, load: Load) {
        if (!load.chrome || load.chromeOn) return
        load.chromeOn = true
        image.foreground = ContextCompat.getDrawable(image.context, R.drawable.fg_media_retry)
        image.contentDescription = image.context.getString(R.string.image_tap_retry)
    }

    private fun hideChrome(image: ImageView, load: Load) {
        if (!load.chromeOn) return
        load.chromeOn = false
        image.foreground = load.savedForeground
        image.contentDescription = load.savedDesc
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun forget(image: ImageView, request: ImageRequest) {
        val loader = image.context.imageLoader
        request.memoryCacheKey?.let { loader.memoryCache?.remove(it) }
        val disk = request.diskCacheKey
        if (!disk.isNullOrBlank()) loader.diskCache?.remove(disk)
    }

    private fun watch(image: ImageView) {
        if (watching.any { it.get() === image }) return
        watching.add(WeakReference(image))
    }

    private fun unwatch(image: ImageView) {
        watching.removeAll { it.get() == null || it.get() === image }
    }
}
