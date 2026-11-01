package com.ubermicrostudios.messagesenhanced.nativeapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import coil.target.ViewTarget
import coil.transform.CircleCropTransformation
import com.ubermicrostudios.messagesenhanced.nativeapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After Coil paints a cached avatar, ask the server with `If-None-Match`.
 * A 304 leaves the disk and memory entries alone. A changed 200 replaces them
 * and updates any image view still showing that URL.
 */
@Singleton
class AvatarRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) : coil.EventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val checkedAt = ConcurrentHashMap<String, Long>()
    private val watchers = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>>()

    override fun onStart(request: ImageRequest) {
        val url = request.data as? String ?: return
        if (!AvatarRevalidation.isAvatarUrl(url)) return
        imageView(request)?.setTag(R.id.tag_avatar_url, url)
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val url = request.data as? String ?: return
        if (!AvatarRevalidation.isAvatarUrl(url)) return
        if (result.dataSource == DataSource.NETWORK) return
        revalidate(url, imageView(request))
    }

    override fun onError(request: ImageRequest, result: ErrorResult) = Unit

    fun revalidate(url: String, view: ImageView?, nowMs: Long = System.currentTimeMillis()) {
        if (!AvatarRevalidation.isAvatarUrl(url)) return
        val key = ImageCacheKeys.diskKey(url)
        if (view != null) {
            view.setTag(R.id.tag_avatar_url, url)
            watchers.getOrPut(key) { CopyOnWriteArrayList() }.add(WeakReference(view))
        }
        if (!AvatarRevalidation.due(checkedAt[key] ?: 0L, nowMs)) return
        if (!inflight.add(key)) return
        scope.launch {
            try {
                check(url, key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                inflight.remove(key)
            }
        }
    }

    private fun imageView(request: ImageRequest): ImageView? =
        (request.target as? ViewTarget<*>)?.view as? ImageView

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun check(url: String, key: String) {
        val loader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        val disk = loader.diskCache ?: return
        val cached = readSnapshot(disk, key) ?: return
        val request = Request.Builder().url(url).apply {
            AvatarRevalidation.ifNoneMatch(cached.etag)?.let { header("If-None-Match", it) }
        }.build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return
        response.use { resp ->
            checkedAt[key] = System.currentTimeMillis()
            val body = if (resp.code == 200) resp.body?.bytes() else null
            val bytesMatch = body != null && body.contentEquals(cached.bytes)
            val decision = AvatarRevalidation.decide(
                storedEtag = cached.etag,
                code = resp.code,
                responseEtag = resp.header("ETag"),
                bytesMatch = bytesMatch,
            )
            if (!decision.storeBytes || body == null || body.size > MAX_AVATAR_BYTES) return
            val bitmap = if (decision.replaceImage) {
                BitmapFactory.decodeByteArray(body, 0, body.size) ?: return
            } else {
                null
            }
            val headers = buildList {
                for (i in 0 until resp.headers.size) {
                    add(resp.headers.name(i) to resp.headers.value(i))
                }
            }
            val metadata = AvatarRevalidation.coilMetadata(
                sentAtMillis = resp.sentRequestAtMillis,
                receivedAtMillis = resp.receivedResponseAtMillis,
                tls = resp.handshake != null,
                headers = headers,
            )
            if (!writeSnapshot(disk, key, metadata, body)) {
                bitmap?.recycle()
                return
            }
            if (bitmap == null) return
            val shown = circle(bitmap)
            if (shown !== bitmap) bitmap.recycle()
            val memoryKey = ImageCacheKeys.circleMemoryKey(url)
            loader.memoryCache?.remove(memoryKey)
            loader.memoryCache?.remove(MemoryCache.Key(key))
            loader.memoryCache?.set(memoryKey, MemoryCache.Value(shown))
            publish(key, url, shown)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun readSnapshot(disk: coil.disk.DiskCache, key: String): Cached? {
        val snapshot = disk.openSnapshot(key) ?: return null
        return snapshot.use { snap ->
            val meta = runCatching { disk.fileSystem.read(snap.metadata) { readUtf8() } }.getOrNull().orEmpty()
            val bytes = runCatching { disk.fileSystem.read(snap.data) { readByteArray() } }.getOrNull()
                ?: return null
            Cached(AvatarRevalidation.etagFromCoilMetadata(meta), bytes)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun writeSnapshot(disk: coil.disk.DiskCache, key: String, metadata: String, bytes: ByteArray): Boolean {
        val editor = disk.openEditor(key) ?: return false
        return try {
            disk.fileSystem.write(editor.metadata) { writeUtf8(metadata) }
            disk.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
            true
        } catch (_: Exception) {
            runCatching { editor.abort() }
            false
        }
    }

    private suspend fun circle(bitmap: Bitmap): Bitmap =
        CircleCropTransformation().transform(bitmap, Size(bitmap.width, bitmap.height))

    private fun publish(key: String, url: String, bitmap: Bitmap) {
        val refs = watchers[key] ?: return
        main.post {
            val alive = ArrayList<WeakReference<ImageView>>(refs.size)
            for (ref in refs) {
                val view = ref.get()
                if (view == null) continue
                alive.add(ref)
                if (view.getTag(R.id.tag_avatar_url) == url) view.setImageBitmap(bitmap)
            }
            if (alive.isEmpty()) watchers.remove(key) else refs.retainAll(alive.toSet())
        }
    }

    private data class Cached(val etag: String?, val bytes: ByteArray)

    companion object {
        private const val MAX_AVATAR_BYTES = 8 * 1024 * 1024
    }
}
