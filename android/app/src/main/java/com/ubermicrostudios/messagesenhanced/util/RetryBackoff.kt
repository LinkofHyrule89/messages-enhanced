package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.LinkPreviewDto
import retrofit2.HttpException
import java.net.URI

/**
 * How long to wait before asking again for a link preview that failed.
 *
 * Transient failures (network, 5xx, 408, 429) wait 30s, then 2m, then 10m, then 30m.
 * Other 4xx, and a 200 with nothing usable, wait longer (10m, then 30m) so a link that
 * simply has no card is not fetched on every bind. Nothing is kept for the life of the process.
 * X and YouTube use this same schedule when the fetch fails; a YouTube card whose title
 * is still the fallback "YouTube" is a hit that is asked again after 30s.
 */
object PreviewBackoff {
    val TRANSIENT_MS = longArrayOf(30_000L, 120_000L, 600_000L, 1_800_000L)
    val CLIENT_MS = longArrayOf(600_000L, 1_800_000L)
    const val CAP_MS = 1_800_000L
    const val YOUTUBE_FALLBACK_MS = 30_000L

    fun isClientStatus(status: Int?): Boolean =
        status != null && status in 400..499 && status != 408 && status != 429

    /** [attempt] is how many failures are already recorded (0 for the first miss). */
    fun delayMs(attempt: Int, status: Int?, empty: Boolean = false): Long {
        val table = if (empty || isClientStatus(status)) CLIENT_MS else TRANSIENT_MS
        val index = attempt.coerceAtLeast(0)
        return table[index.coerceAtMost(table.lastIndex)]
    }

    fun httpStatus(error: Throwable?): Int? {
        var current = error
        while (current != null) {
            if (current is HttpException) return current.code()
            current = current.cause
        }
        return null
    }
}

/** What to store after one link-preview response. A hit is never a null map entry. */
sealed class PreviewFetch {
    /** [retryAfterMs] is set only for the YouTube title fallback. Null means keep the card. */
    data class Hit(val retryAfterMs: Long?) : PreviewFetch()

    /** [empty] is a 200 with no title, description, image, or site name. */
    data class Miss(val status: Int?, val empty: Boolean) : PreviewFetch()
}

object PreviewFetchDecision {
    fun isYoutubeFallback(preview: LinkPreviewDto): Boolean =
        preview.isVideo && preview.title == "YouTube" && preview.description.isBlank()

    fun classify(preview: LinkPreviewDto?, error: Throwable?): PreviewFetch {
        if (preview != null && preview.usable()) {
            val again = if (isYoutubeFallback(preview)) PreviewBackoff.YOUTUBE_FALLBACK_MS else null
            return PreviewFetch.Hit(again)
        }
        return PreviewFetch.Miss(PreviewBackoff.httpStatus(error), empty = error == null)
    }
}

/**
 * Failure log for link previews. A URL is [blocked] until its backoff elapses, then the
 * next request may run. [releaseAll] makes every waiting URL eligible immediately
 * (reconnect, foreground, Google connected) without forgetting how many times it failed.
 * Callers may share one instance across threads; each method is synchronized.
 */
class PreviewRetries(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val attempts: Int, val retryAt: Long, val status: Int?)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    fun blocked(url: String, now: Long = clock()): Boolean = synchronized(lock) {
        val entry = entries[url] ?: return false
        now < entry.retryAt
    }

    fun entry(url: String): Entry? = synchronized(lock) { entries[url] }

    fun fail(url: String, status: Int?, empty: Boolean, now: Long = clock()): Entry = synchronized(lock) {
        val prev = entries[url]?.attempts ?: 0
        val entry = Entry(prev + 1, now + PreviewBackoff.delayMs(prev, status, empty), status)
        entries[url] = entry
        while (entries.size > MAX) {
            val eldest = entries.entries.firstOrNull()?.key ?: break
            entries.remove(eldest)
        }
        entry
    }

    fun clear(url: String) = synchronized(lock) { entries.remove(url) }

    fun clearAll() = synchronized(lock) { entries.clear() }

    fun releaseAll(now: Long = clock()) = synchronized(lock) {
        val urls = entries.keys.toList()
        for (url in urls) {
            val entry = entries[url] ?: continue
            if (entry.retryAt > now) entries[url] = entry.copy(retryAt = now)
        }
    }

    companion object {
        const val MAX = 100
    }
}

/**
 * Automatic retries for a message image or link-preview picture while it is on screen.
 * After 2s, 10s, and 30s, only a tap (or a connectivity release) tries again.
 */
object ImageRetryBackoff {
    val AUTO_MS = longArrayOf(2_000L, 10_000L, 30_000L)

    /** [failuresBeforeThisWait] is how many failures are already recorded. */
    fun autoDelayMs(failuresBeforeThisWait: Int): Long? =
        AUTO_MS.getOrNull(failuresBeforeThisWait.coerceAtLeast(0))

    /** Message media and proxied preview images must not keep a failed response. */
    fun forgetOnError(url: String): Boolean {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return false
        return path.startsWith("/api/media/") || path.startsWith("/api/link-preview-image")
    }
}

/**
 * Per-URL image failure clock. [onBind] is the check when a row is shown again:
 * fetch when there is no failure or the backoff has elapsed, otherwise wait.
 * [release] / [releaseAll] make a waiting URL eligible now.
 */
class ImageRetrySchedule(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(var attempt: Int, var nextAt: Long)

    data class Bind(
        val fetch: Boolean,
        val waitMs: Long,
        val failed: Boolean,
        val attempt: Int,
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    fun onBind(url: String, now: Long = clock()): Bind = synchronized(lock) {
        val entry = entries[url] ?: return Bind(fetch = true, waitMs = 0L, failed = false, attempt = 0)
        if (entry.attempt <= 0) return Bind(true, 0L, false, entry.attempt)
        if (entry.nextAt == Long.MAX_VALUE) return Bind(false, Long.MAX_VALUE, true, entry.attempt)
        val wait = entry.nextAt - now
        if (wait <= 0L) return Bind(true, 0L, true, entry.attempt)
        Bind(false, wait, true, entry.attempt)
    }

    /** Delay until the next automatic try, or null when only a tap should retry. */
    fun onFailure(url: String, now: Long = clock()): Long? = synchronized(lock) {
        val entry = entries.getOrPut(url) { Entry(0, 0L) }
        val delay = ImageRetryBackoff.autoDelayMs(entry.attempt)
        entry.attempt += 1
        entry.nextAt = if (delay == null) Long.MAX_VALUE else now + delay
        delay
    }

    fun onSuccess(url: String) {
        synchronized(lock) { entries.remove(url) }
    }

    fun release(url: String, now: Long = clock()) = synchronized(lock) {
        val entry = entries[url] ?: return
        if (entry.attempt > 0) entry.nextAt = now
    }

    fun releaseAll(now: Long = clock()) = synchronized(lock) {
        for (entry in entries.values) {
            if (entry.attempt > 0) entry.nextAt = now
        }
    }

    fun isFailed(url: String): Boolean = synchronized(lock) { (entries[url]?.attempt ?: 0) > 0 }
}
