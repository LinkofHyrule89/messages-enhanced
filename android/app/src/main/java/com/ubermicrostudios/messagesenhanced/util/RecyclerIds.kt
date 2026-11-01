package com.ubermicrostudios.messagesenhanced.util

/** Stable RecyclerView item ids. Never [androidx.recyclerview.widget.RecyclerView.NO_ID]. */
object RecyclerIds {
    fun of(kind: Long, key: String): Long {
        var h = 0xcbf29ce484222325UL
        h = h xor kind.toULong()
        h *= 0x100000001b3UL
        for (c in key) {
            h = h xor c.code.toULong()
            h *= 0x100000001b3UL
        }
        val v = h.toLong()
        return if (v == -1L) kind else v
    }
}
