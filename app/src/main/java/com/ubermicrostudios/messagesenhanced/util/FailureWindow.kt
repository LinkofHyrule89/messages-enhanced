package com.ubermicrostudios.messagesenhanced.util

/**
 * Shows an error only after [minFailures] consecutive failures spanning more than [minSpanMs],
 * all counted while the process is foregrounded with a validated network.
 * Failures while backgrounded or offline are ignored. A success, [onForeground], or
 * [onBackground] clears the streak. Callers keep the last good data either way.
 * After startup or returning to the foreground, nothing is shown for [quietMs].
 */
class FailureWindow(
    private val minFailures: Int = 5,
    private val minSpanMs: Long = 30_000,
    private val quietMs: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gate: NetworkAvailability = NetworkAvailability.AlwaysOn,
) {
    private val lock = Any()
    private var failures = 0
    private var firstAt = 0L
    private var quietUntil = 0L

    init {
        onForeground()
    }

    fun success() {
        synchronized(lock) {
            failures = 0
            firstAt = 0L
        }
    }

    /** Reset the streak and suppress the banner for [quietMs]. */
    fun onForeground() {
        synchronized(lock) {
            failures = 0
            firstAt = 0L
            quietUntil = clock() + quietMs
        }
    }

    /** Drop a streak built up before the process left the foreground. */
    fun onBackground() {
        synchronized(lock) {
            failures = 0
            firstAt = 0L
        }
    }

    fun inQuiet(): Boolean = synchronized(lock) { clock() < quietUntil }

    /** Message to surface, or null while the streak is still short, quiet, or not countable. */
    fun failure(message: String): String? {
        synchronized(lock) {
            if (!gate.isForeground || !gate.isNetworkValidated) return null
            val now = clock()
            if (failures == 0) firstAt = now
            failures++
            if (now < quietUntil) return null
            return if (failures >= minFailures && now - firstAt > minSpanMs) message else null
        }
    }
}

object NetErrors {
    const val UNREACHABLE = "Can’t reach the server. Will keep trying."

    fun friendly(error: Throwable): String {
        val raw = error.message.orEmpty()
        if (isNetworkMessage(raw)) return UNREACHABLE
        return raw.ifBlank { "Could not refresh." }
    }

    fun isNetworkMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        if (message == UNREACHABLE) return true
        return message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("No address associated with hostname", ignoreCase = true)
    }
}
