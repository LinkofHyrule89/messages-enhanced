package com.ubermicrostudios.messagesenhanced.util

import kotlin.math.roundToLong
import kotlin.random.Random

/** Exponential backoff with ±20% jitter. Attempt 0 is about 1s, capped at 30s. */
object ReconnectDelay {
    fun millis(attempt: Int, random: Random = Random.Default): Long {
        val shift = attempt.coerceIn(0, 5)
        val base = (1_000L shl shift).coerceAtMost(30_000L)
        val swing = (base * 0.2).roundToLong()
        val jitter = if (swing == 0L) 0L else random.nextLong(-swing, swing + 1)
        return (base + jitter).coerceIn(500L, 30_000L)
    }
}
