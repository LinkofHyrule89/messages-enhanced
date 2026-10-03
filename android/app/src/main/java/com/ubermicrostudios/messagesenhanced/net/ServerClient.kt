package com.ubermicrostudios.messagesenhanced.net

import okhttp3.CookieJar
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the self-hosted Messages Enhanced server: logs in with the shared
 * secret (same-origin form POST), then posts the Google cookies.
 *
 * SECURITY: the server password and Google cookie values live only in memory
 * here and go straight into the request. Nothing is logged. No redirects are
 * auto-followed for the cookie POST so we can classify a 303 -> /login.
 */
class ServerClient {

    sealed class Outcome {
        data class Saved(val message: String) : Outcome()
        data class NeedLogin(val message: String) : Outcome()
        data class Error(val message: String) : Outcome()
        data class Unreachable(val message: String) : Outcome()
        data class WrongPassword(val message: String) : Outcome()
    }

    // In-memory cookie jar so the tm_session cookie from /login is sent on the
    // /admin/cookies POST. Not persisted.
    private val jar = object : CookieJar {
        private val store = HashMap<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.getOrPut(url.host) { mutableListOf() }.apply {
                for (c in cookies) {
                    removeAll { it.name == c.name }
                    add(c)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(jar)
        .build()

    /**
     * Log in, then post cookies. [origin] is a normalized origin (scheme://host[:port]).
     * [secret] is the server password. [cookieMap] is name->value.
     */
    fun send(origin: String, secret: String, cookieMap: Map<String, String>): Outcome {
        val loginUrl = ServerAddress.loginUrl(origin)
        val cookiesUrl = ServerAddress.cookiesEndpoint(origin)

        // 1) POST /login with field `secret`, Origin header = server origin
        //    (mimics a same-origin form post so the server's CSRF check passes).
        val loginBody = FormBody.Builder().add("secret", secret).build()
        val loginReq = Request.Builder()
            .url(loginUrl)
            .header("Origin", origin)
            .header("Referer", "$origin/login")
            .post(loginBody)
            .build()
        try {
            client.newCall(loginReq).execute().use { resp ->
                // Success is a 303 redirect that sets tm_session. A 200 with the
                // login form means wrong secret; a 401 also means wrong secret.
                val loc = resp.header("Location") ?: ""
                val hasSession = origin.toHttpUrlOrNull()
                    ?.let { jar.loadForRequest(it) }
                    .orEmpty()
                    .any { it.name == SESSION_COOKIE }
                when (ResponseInterpreter.classifyLogin(resp.code, loc, hasSession)) {
                    ResponseInterpreter.LoginResult.OK -> Unit
                    ResponseInterpreter.LoginResult.WRONG_PASSWORD ->
                        return Outcome.WrongPassword("Wrong server password.")
                    ResponseInterpreter.LoginResult.RATE_LIMITED ->
                        return Outcome.Error("Too many login attempts. Wait a few minutes and try again.")
                    ResponseInterpreter.LoginResult.CROSS_ORIGIN ->
                        return Outcome.Error("The server rejected the login as cross-origin (403).")
                    ResponseInterpreter.LoginResult.FAILED ->
                        return Outcome.Error("Login failed (HTTP ${resp.code}).")
                }
            }
        } catch (e: Exception) {
            return Outcome.Unreachable(unreachableMsg(origin))
        }

        // 2) POST /admin/cookies with body cookies=<json>, same Origin header.
        val body = CookieLogic.buildFormBody(cookieMap)
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val postReq = Request.Builder()
            .url(cookiesUrl)
            .header("Origin", origin)
            .header("Referer", "$origin/admin/cookies")
            .post(body)
            .build()
        try {
            client.newCall(postReq).execute().use { resp ->
                val loc = resp.header("Location")
                val finalUrl = if (resp.isRedirect && loc != null) absolutize(origin, loc) else cookiesUrl
                val text = resp.body?.string() ?: ""
                val res = ResponseInterpreter.interpret(resp.code, finalUrl, text)
                return when (res.kind) {
                    ResponseInterpreter.Kind.SAVED -> Outcome.Saved(res.message)
                    ResponseInterpreter.Kind.NEED_LOGIN -> Outcome.NeedLogin(res.message)
                    ResponseInterpreter.Kind.ERROR -> Outcome.Error(res.message)
                    ResponseInterpreter.Kind.UNKNOWN -> Outcome.Error(res.message)
                }
            }
        } catch (e: Exception) {
            return Outcome.Unreachable(unreachableMsg(origin))
        }
    }

    companion object {
        const val SESSION_COOKIE = "tm_session"
    }

    private fun absolutize(origin: String, location: String): String =
        if (location.startsWith("http")) location else origin.trimEnd('/') + "/" + location.trimStart('/')

    private fun unreachableMsg(origin: String) =
        "Could not reach $origin. Make sure the server is running and the address is right " +
            "(for a LAN address, use the same Wi-Fi as the server)."
}
