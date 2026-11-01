package com.ubermicrostudios.messagesenhanced.data.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Retries idempotent requests (GET/HEAD) on DNS and connection failures.
 * The events stream is left to [SseClient], which reconnects itself.
 */
class RetryInterceptor(
    private val attempts: Int = 3,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Accept") == "text/event-stream") {
            return chain.proceed(request)
        }
        val idempotent = request.method == "GET" || request.method == "HEAD"
        val tries = if (idempotent) attempts.coerceAtLeast(1) else 1
        var last: IOException? = null
        for (attempt in 0 until tries) {
            try {
                val response = chain.proceed(request)
                if (idempotent && response.code in 500..599 && attempt < tries - 1) {
                    response.close()
                    sleep(backoff(attempt))
                    continue
                }
                return response
            } catch (e: IOException) {
                last = e
                if (!idempotent || !retryable(e) || attempt >= tries - 1) throw e
                sleep(backoff(attempt))
            }
        }
        throw last ?: IOException("request failed")
    }

    private fun retryable(error: IOException): Boolean =
        error is UnknownHostException || error is ConnectException || error is SocketTimeoutException

    private fun backoff(attempt: Int): Long = 300L * (1L shl attempt.coerceAtMost(3))
}
