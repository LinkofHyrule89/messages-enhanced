package com.ubermicrostudios.messagesenhanced.data.repo

import com.ubermicrostudios.messagesenhanced.data.cache.InboxCache
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.net.PersistentCookieJar
import com.ubermicrostudios.messagesenhanced.data.net.SseClient
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.push.ConversationNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

sealed class LoginResult {
    data object Success : LoginResult()
    data object WrongSecret : LoginResult()
    data object RateLimited : LoginResult()
    data class Failed(val message: String) : LoginResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val client: OkHttpClient,
    private val cookieJar: PersistentCookieJar,
    private val prefs: AppPreferences,
    private val baseUrl: BaseUrlProvider,
    private val sse: SseClient,
    private val inboxCache: InboxCache,
    private val notifications: ConversationNotifications,
    private val themes: ThemeRepository,
    private val health: HealthRepository,
    private val linkPreviews: LinkPreviewCache,
) {
    fun hasSession(): Boolean {
        val origin = prefs.serverUrl
        if (origin.isBlank()) return false
        val url = PersistentCookieJar.parseServerUrl(origin) ?: return false
        return cookieJar.hasSessionCookie(url)
    }

    fun serverUrl(): String = prefs.serverUrl

    fun saveServerUrl(url: String) {
        val next = url.trim().trimEnd('/')
        val previous = prefs.serverUrl
        prefs.serverUrl = next
        val parsed = PersistentCookieJar.parseServerUrl(next)
        if (parsed != null) cookieJar.retainFor(parsed) else cookieJar.clear()
        if (previous == next) return
        if (previous.isNotBlank()) {
            prefs.setLastSelectedConversationId(null)
            inboxCache.clear()
            notifications.clearAll()
            themes.clear()
            linkPreviews.clear()
            if (hasSession()) {
                inboxCache.openWrites()
            } else {
                sse.stop()
                health.stop()
            }
        }
    }

    suspend fun login(secret: String): LoginResult = withContext(Dispatchers.IO) {
        val origin = baseUrl.origin()
        val body = FormBody.Builder().add("secret", secret).build()
        val request = Request.Builder()
            .url("$origin/login")
            .post(body)
            .removeHeader("Origin")
            .build()
        val originUrl = PersistentCookieJar.parseServerUrl(origin)
        val result = try {
            client.newCall(request).execute().use { resp ->
                val location = resp.header("Location").orEmpty()
                val hasSession = originUrl != null && cookieJar.hasSessionCookie(originUrl)
                when {
                    resp.code == 401 -> LoginResult.WrongSecret
                    resp.code == 429 -> LoginResult.RateLimited
                    resp.code == 303 && hasSession -> LoginResult.Success
                    hasSession -> LoginResult.Success
                    resp.code in 300..399 && !location.contains("/login") -> LoginResult.Success
                    else -> LoginResult.Failed("Login failed (HTTP ${resp.code}).")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LoginResult.Failed("Could not reach $origin.")
        }
        if (result is LoginResult.Success) inboxCache.openWrites()
        result
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            sse.stop()
            health.stop()
            val origin = baseUrl.origin()
            val request = Request.Builder()
                .url("$origin/logout")
                .get()
                .removeHeader("Origin")
                .build()
            try {
                client.newCall(request).execute().close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        } finally {
            cookieJar.clear()
            prefs.setLastSelectedConversationId(null)
            inboxCache.clear()
            notifications.clearAll()
            themes.clear()
            linkPreviews.clear()
        }
    }
}
