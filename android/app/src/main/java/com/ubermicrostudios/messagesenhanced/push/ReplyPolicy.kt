package com.ubermicrostudios.messagesenhanced.push

/**
 * Decides what a notification reply does after one send attempt.
 * The server dedupes by idempotency key: a key that already failed answers 409 FAILED
 * forever, so a real retry after a server-side failure needs a fresh key, while a
 * network error (the request may have landed) must reuse the same key.
 */
object ReplyPolicy {
    const val MAX_ATTEMPTS = 4

    enum class Outcome { SENT, RETRY_SAME_KEY, RETRY_NEW_KEY, GIVE_UP }

    /**
     * @param httpCode null when the request threw before a response (I/O error).
     * @param success the response "success" flag, when a body was read.
     * @param status the response "status" string, upper-cased or not.
     */
    fun decide(httpCode: Int?, success: Boolean?, status: String?, attempt: Int): Outcome {
        val s = status.orEmpty().uppercase()
        val raw = when {
            httpCode == null -> Outcome.RETRY_SAME_KEY
            httpCode in 200..299 && success == true -> Outcome.SENT
            httpCode == 409 && s == "SENDING" -> Outcome.SENT
            httpCode == 409 -> Outcome.RETRY_NEW_KEY
            httpCode in 200..299 -> Outcome.RETRY_NEW_KEY
            httpCode == 401 || httpCode == 403 || httpCode == 400 || httpCode == 404 || httpCode == 413 -> Outcome.GIVE_UP
            httpCode == 408 || httpCode == 429 || httpCode >= 500 -> Outcome.RETRY_SAME_KEY
            else -> Outcome.GIVE_UP
        }
        if (raw == Outcome.SENT || raw == Outcome.GIVE_UP) return raw
        return if (attempt + 1 >= MAX_ATTEMPTS) Outcome.GIVE_UP else raw
    }

    /** Key for a given server-failure generation. Generation 0 is the original key. */
    fun keyFor(base: String, generation: Int): String = if (generation <= 0) base else "$base-r$generation"

    fun parseStatus(body: String?): String =
        Regex("\"status\"\\s*:\\s*\"([A-Za-z_]+)\"").find(body.orEmpty())?.groupValues?.get(1).orEmpty()
}
