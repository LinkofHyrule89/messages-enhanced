package com.ubermicrostudios.messagesenhanced.data.repo

import com.ubermicrostudios.messagesenhanced.data.model.LinkPreviewDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory link preview cache. The card is fetched from `GET /api/link-preview?url=`
 * and its image (already rewritten to `/api/link-preview-image`) loads through Coil.
 */
@Singleton
class LinkPreviewCache @Inject constructor(
    private val api: MessagesApi,
    private val baseUrl: BaseUrlProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = object : LinkedHashMap<String, LinkPreviewDto?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreviewDto?>?): Boolean =
            size > 100
    }
    private val inflight = HashSet<String>()
    private val retryAt = HashMap<String, Long>()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun peek(url: String): LinkPreviewDto? = synchronized(memory) { memory[url] }

    /** True once a request has finished, including a preview the server could not build. */
    fun known(url: String): Boolean = synchronized(memory) { memory.containsKey(url) }

    fun imageUrl(preview: LinkPreviewDto): String? = preview.absoluteImage(baseUrl.origin())

    fun clear() {
        synchronized(memory) {
            memory.clear()
            inflight.clear()
            retryAt.clear()
        }
        _revision.value = _revision.value + 1
    }

    fun request(url: String) {
        if (url.isBlank()) return
        synchronized(memory) {
            val due = retryAt[url]?.let { System.currentTimeMillis() >= it } == true
            if (due) retryAt.remove(url)
            if ((memory.containsKey(url) && !due) || !inflight.add(url)) return
        }
        scope.launch {
            try {
                val preview = runCatchingCancellable { api.linkPreview(url) }.getOrNull()?.takeIf { it.usable() }
                // A YouTube fallback (oEmbed failed: title "YouTube", no author) is shown but
                // not memoised, so the next bind asks the server again for the real title.
                val fallback = preview != null && preview.isVideo && preview.title == "YouTube" && preview.description.isBlank()
                synchronized(memory) {
                    memory[url] = preview
                    if (fallback) retryAt[url] = System.currentTimeMillis() + 30_000
                }
                _revision.value = _revision.value + 1
            } finally {
                synchronized(memory) { inflight.remove(url) }
            }
        }
    }
}
