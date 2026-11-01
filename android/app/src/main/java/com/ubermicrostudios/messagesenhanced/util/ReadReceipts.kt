package com.ubermicrostudios.messagesenhanced.util

/** Reader list for read-receipt avatars: one entry per person, at most [max]. */
object ReadReceipts {
    fun key(name: String): String {
        val digits = name.filter { it.isDigit() }
        return if (digits.length >= 7) digits.takeLast(10) else name.trim().lowercase()
    }

    fun dedupe(names: List<String>, max: Int = 4): List<String> =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::key).take(max)
}
