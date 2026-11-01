package com.ubermicrostudios.messagesenhanced.data.net

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites requests onto the saved server origin, strips Origin (native client),
 * and surfaces 401 / redirect-to-/login as a lost session.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val baseUrlProvider: BaseUrlProvider,
    private val sessionMonitor: SessionMonitor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = rewriteToBase(original.url.toString(), baseUrlProvider.origin())
        val request = original.newBuilder()
            .url(rewritten)
            .removeHeader("Origin")
            .build()
        val response = chain.proceed(request)
        if (isAuthFailure(response)) {
            sessionMonitor.notifyAuthLost()
        }
        return response
    }

    companion object {
        fun rewriteToBase(requestUrl: String, origin: String): String {
            val base = origin.trim().trimEnd('/')
            val marker = "://"
            val pathStart = requestUrl.indexOf('/', requestUrl.indexOf(marker).let { if (it < 0) 0 else it + marker.length })
            val pathAndQuery = if (pathStart >= 0) requestUrl.substring(pathStart) else "/"
            return base + pathAndQuery
        }

        fun isAuthFailure(response: Response): Boolean {
            if (response.code == 401) return true
            if (response.code in 300..399) {
                val loc = response.header("Location").orEmpty()
                if (loc.contains("/login")) return true
            }
            val path = response.request.url.encodedPath
            return path == "/login" || path.startsWith("/login?")
        }
    }
}
