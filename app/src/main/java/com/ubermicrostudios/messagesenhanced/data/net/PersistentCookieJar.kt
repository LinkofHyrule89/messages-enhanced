package com.ubermicrostudios.messagesenhanced.data.net

import com.ubermicrostudios.messagesenhanced.data.model.StoredCookie
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersistentCookieJar @Inject constructor(
    private val prefs: AppPreferences,
) : CookieJar {

    private val lock = Any()
    private val cache: MutableList<StoredCookie> = mutableListOf()
    private var loaded = false

    /** Load the encrypted store on a background thread. Safe to call more than once. */
    fun warm() {
        ensureLoaded()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            cache.removeAll { it.expiresAt <= now }
            for (cookie in cookies) {
                cache.removeAll { sameIdentity(it, cookie) }
                if (cookie.expiresAt > now) {
                    cache.add(cookie.toStored())
                }
            }
            persistLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            val expired = cache.removeAll { it.expiresAt <= now }
            if (expired) persistLocked()
            return cache.mapNotNull { it.toOkHttp() }.filter { it.matches(url) }
        }
    }

    fun clear() {
        synchronized(lock) {
            ensureLoadedLocked()
            cache.clear()
            prefs.clearCookies()
        }
    }

    /**
     * Drops cookies that do not match [url], including a session for another host.
     * Expired cookies are dropped too.
     */
    fun retainFor(url: HttpUrl) {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            val kept = cache.filter { stored ->
                stored.expiresAt > now && stored.toOkHttp()?.matches(url) == true
            }
            if (kept.size == cache.size) return
            cache.clear()
            cache.addAll(kept)
            persistLocked()
        }
    }

    fun hasSessionCookie(url: HttpUrl): Boolean {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            return cache.any { stored ->
                stored.name == SESSION_COOKIE &&
                    stored.expiresAt > now &&
                    stored.value.isNotBlank() &&
                    stored.toOkHttp()?.matches(url) == true
            }
        }
    }

    private fun ensureLoaded() {
        synchronized(lock) { ensureLoadedLocked() }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        cache.clear()
        cache.addAll(prefs.loadCookies())
        loaded = true
    }

    private fun persistLocked() {
        prefs.saveCookies(cache.toList())
    }

    private fun sameIdentity(stored: StoredCookie, cookie: Cookie): Boolean =
        stored.name == cookie.name &&
            stored.domain.equals(cookie.domain, ignoreCase = true) &&
            stored.path == cookie.path

    private fun Cookie.toStored(): StoredCookie = StoredCookie(
        name = name,
        value = value,
        expiresAt = expiresAt,
        domain = domain,
        path = path,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
        persistent = persistent,
    )

    private fun StoredCookie.toOkHttp(): Cookie? {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
        try {
            if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }

    companion object {
        const val SESSION_COOKIE = "tm_session"

        fun parseServerUrl(raw: String): HttpUrl? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isBlank()) return null
            return trimmed.toHttpUrlOrNull() ?: "https://$trimmed".toHttpUrlOrNull()
        }
    }
}
