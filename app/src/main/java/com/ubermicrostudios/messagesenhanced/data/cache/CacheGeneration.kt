package com.ubermicrostudios.messagesenhanced.data.cache

/**
 * A cache write from a fetch that started at [startedAt] may land only while
 * [current] is still that generation and writes are open. A server change or
 * sign-out bumps the generation so a slower older refresh cannot save the old list.
 */
object CacheGeneration {
    fun canCommit(startedAt: Int, current: Int, writesOpen: Boolean): Boolean =
        writesOpen && startedAt == current
}
