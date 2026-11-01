package com.ubermicrostudios.messagesenhanced.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Retries the first requests after returning to the foreground.
 *
 * Up to [MAX_ATTEMPTS] tries, waiting 1s then 3s between them (7s is the next
 * step if the cap is raised). Only [IOException] (including UnknownHost) is
 * retried, only while the process is foregrounded, and only during the
 * FailureWindow quiet period. Cancellation stops the loop.
 */
object ResumeRetry {
    const val MAX_ATTEMPTS = 3
    val BACKOFF_MS = longArrayOf(1_000L, 3_000L, 7_000L)

    fun retryable(error: Throwable): Boolean =
        error is IOException && error !is CancellationException

    suspend fun <T> run(
        gate: NetworkAvailability,
        inQuiet: () -> Boolean,
        delayMs: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        var last: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0 && (!gate.isForeground || !inQuiet())) {
                throw last ?: IOException("request failed")
            }
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                val more = attempt < MAX_ATTEMPTS - 1
                if (!more || !retryable(e) || !gate.isForeground || !inQuiet()) throw e
                delayMs(BACKOFF_MS[attempt])
            }
        }
        throw last ?: IOException("request failed")
    }
}
