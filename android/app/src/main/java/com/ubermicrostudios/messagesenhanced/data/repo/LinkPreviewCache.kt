package com.ubermicrostudios.messagesenhanced.data.repo

import com.ubermicrostudios.messagesenhanced.data.model.LinkPreviewDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.util.PreviewBackoff
import com.ubermicrostudios.messagesenhanced.util.PreviewFetch
import com.ubermicrostudios.messagesenhanced.util.PreviewFetchDecision
import com.ubermicrostudios.messagesenhanced.util.PreviewRetries
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
 *
 * A usable card stays cached. A failed fetch, a 502, or an empty 200 is a [PreviewRetries]
 * entry that expires on a backoff, so X, YouTube, and every other preview can recover
 * when the row is shown again. A YouTube fallback title is shown and asked again after 30s.
 */
@Singleton
class LinkPreviewCache @Inject constructor(
    private val api: MessagesApi,
    private val baseUrl: BaseUrlProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val clock: () -> Long = System::currentTimeMillis
    private val youtubeRetry = HashMap<String, Long>()
    private val memory = object : LinkedHashMap<String, LinkPreviewDto>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreviewDto>?): Boolean {
            val drop = size > 100
            if (drop && eldest != null) youtubeRetry.remove(eldest.key)
            return drop
        }
    }
    private val failures = PreviewRetries(clock)
    private val inflight = HashSet<String>()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun peek(url: String): LinkPreviewDto? = synchronized(lock) { memory[url] }

    /**
     * True when the URL has a cached card that is not due for a YouTube refresh,
     * or a failure that is still inside its backoff.
     */
    fun known(url: String): Boolean = synchronized(lock) {
        val now = clock()
        if (memory.containsKey(url) && !youtubeDue(url, now) && !failureDue(url, now)) return true
        failures.blocked(url, now)
    }

    fun imageUrl(preview: LinkPreviewDto): String? = preview.absoluteImage(baseUrl.origin())

    fun clear() {
        synchronized(lock) {
            memory.clear()
            failures.clearAll()
            inflight.clear()
            youtubeRetry.clear()
        }
        _revision.value = _revision.value + 1
    }

    /**
     * Make every waiting failure and YouTube fallback eligible on the next bind.
     * Also bumps [revision] so a visible thread rebinds and asks again. Successful
     * cards that are not a YouTube fallback stay cached.
     */
    fun releaseFailures() {
        synchronized(lock) {
            val now = clock()
            failures.releaseAll(now)
            for (url in youtubeRetry.keys.toList()) {
                val at = youtubeRetry[url] ?: continue
                if (at > now) youtubeRetry[url] = now
            }
        }
        _revision.value = _revision.value + 1
    }

    fun request(url: String) {
        if (url.isBlank()) return
        synchronized(lock) {
            if (!prepareFetch(url)) return
            inflight.add(url)
        }
        scope.launch {
            try {
                val outcome = runCatchingCancellable { api.linkPreview(url) }
                val error = outcome.exceptionOrNull()
                val preview = outcome.getOrNull()?.takeIf { it.usable() }
                val decision = PreviewFetchDecision.classify(preview, error)
                val card = preview
                synchronized(lock) {
                    when (decision) {
                        is PreviewFetch.Hit -> {
                            if (card != null) memory[url] = card
                            failures.clear(url)
                            if (decision.retryAfterMs != null) {
                                youtubeRetry[url] = clock() + decision.retryAfterMs
                            } else {
                                youtubeRetry.remove(url)
                            }
                        }
                        is PreviewFetch.Miss -> {
                            failures.fail(url, decision.status, decision.empty, clock())
                            // A 502 keeps the last card (often the YouTube fallback) on screen.
                            // An empty or rejected response drops it.
                            if (decision.empty || PreviewBackoff.isClientStatus(decision.status)) {
                                memory.remove(url)
                                youtubeRetry.remove(url)
                            }
                        }
                    }
                }
                _revision.value = _revision.value + 1
            } finally {
                synchronized(lock) { inflight.remove(url) }
            }
        }
    }

    /** Caller holds [lock]. */
    private fun prepareFetch(url: String): Boolean {
        if (url in inflight) return false
        val now = clock()
        val cached = memory[url]
        if (cached != null) {
            val refresh = youtubeDue(url, now) || failureDue(url, now)
            if (!refresh) return false
            if (youtubeDue(url, now)) youtubeRetry.remove(url)
            return true
        }
        return !failures.blocked(url, now)
    }

    private fun youtubeDue(url: String, now: Long): Boolean =
        youtubeRetry[url]?.let { now >= it } == true

    private fun failureDue(url: String, now: Long): Boolean {
        val entry = failures.entry(url) ?: return false
        return now >= entry.retryAt
    }
}
