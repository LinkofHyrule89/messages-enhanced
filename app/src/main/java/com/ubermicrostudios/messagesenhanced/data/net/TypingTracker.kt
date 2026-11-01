package com.ubermicrostudios.messagesenhanced.data.net

import com.ubermicrostudios.messagesenhanced.data.model.StreamEvent
import com.ubermicrostudios.messagesenhanced.data.model.TypingDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is typing, from SSE `typing` events and `GET /api/app/typing`.
 * Entries expire (server TTL is 15s) so a missed stop cannot leave the dots up.
 */
@Singleton
class TypingTracker @Inject constructor() {
    private data class Entry(val name: String, val expiresAt: Long)

    private val lock = Any()
    private val byConversation = HashMap<String, HashMap<String, Entry>>()
    private val _names = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Conversation id -> display names of people currently typing (possibly blank). */
    val names: StateFlow<Map<String, List<String>>> = _names

    fun applyEvent(event: StreamEvent, now: Long = System.currentTimeMillis(), ttlMs: Long = DEFAULT_TTL) {
        if (event.type != "typing" || event.conversationId.isBlank()) return
        val key = event.senderNumber.ifBlank { event.senderName }.ifBlank { "?" }
        val on = event.typing == true
        synchronized(lock) {
            val map = byConversation.getOrPut(event.conversationId) { HashMap() }
            if (on) {
                map[key] = Entry(event.senderName, now + ttlMs.coerceAtLeast(1_000))
            } else {
                map.remove(key)
                if (map.isEmpty()) byConversation.remove(event.conversationId)
            }
            publishLocked(now)
        }
    }

    fun replace(states: List<TypingDto>, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            byConversation.clear()
            for (state in states) {
                if (state.conversationId.isBlank()) continue
                val key = state.senderNumber.ifBlank { state.senderName }.ifBlank { "?" }
                val ttl = state.expiresInMs.coerceAtLeast(0)
                if (ttl <= 0) continue
                val map = byConversation.getOrPut(state.conversationId) { HashMap() }
                map[key] = Entry(state.senderName, now + ttl)
            }
            publishLocked(now)
        }
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        synchronized(lock) { publishLocked(now) }
    }

    fun clear() {
        synchronized(lock) {
            byConversation.clear()
            _names.value = emptyMap()
        }
    }

    fun label(conversationId: String, isGroup: Boolean, now: Long = System.currentTimeMillis()): String {
        val current = synchronized(lock) { namesLocked(conversationId, now) }
        return labelFor(current, isGroup)
    }

    private fun publishLocked(now: Long) {
        val snapshot = HashMap<String, List<String>>()
        val ids = byConversation.keys.toList()
        for (id in ids) {
            val current = namesLocked(id, now)
            if (current.isEmpty()) continue
            snapshot[id] = current
        }
        _names.value = snapshot
    }

    private fun namesLocked(conversationId: String, now: Long): List<String> {
        val map = byConversation[conversationId] ?: return emptyList()
        val dead = map.filterValues { it.expiresAt <= now }.keys
        dead.forEach { map.remove(it) }
        if (map.isEmpty()) byConversation.remove(conversationId)
        return map.values.map { it.name }
    }

    companion object {
        const val DEFAULT_TTL = 15_000L

        fun labelFor(names: List<String>, isGroup: Boolean): String {
            if (names.isEmpty()) return ""
            val named = names.filter { it.isNotBlank() }
            if (!isGroup || named.isEmpty()) return "typing…"
            val who = if (named.size > 1) named[0] + " +" + (named.size - 1) else named[0]
            return "$who is typing…"
        }
    }
}
