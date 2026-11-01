package com.ubermicrostudios.messagesenhanced.data.cache

import java.security.MessageDigest

/**
 * On-disk names for per-conversation message files. The hashed name is the
 * lowercase SHA-256 of the conversation id so every id maps to one safe filename.
 * Blank ids are rejected. [legacyFileName] is the pre-0.6.7 scheme, used only
 * to read leftover files once.
 */
object ThreadCacheNames {
    private val HASHED = Regex("^[0-9a-f]{64}\\.json$")

    fun fileName(conversationId: String): String? {
        val id = conversationId.trim()
        if (id.isBlank()) return null
        return sha256Hex(id) + ".json"
    }

    fun isHashedFile(name: String): Boolean = HASHED.matches(name)

    fun legacyFileName(conversationId: String): String {
        val hash = conversationId.hashCode().toUInt().toString(16)
        val safe = buildString {
            for (ch in conversationId) {
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') append(ch)
                if (length >= 40) break
            }
        }.ifBlank { "chat" }
        return "$safe-$hash.json"
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val unsigned = b.toInt() and 0xff
                append("0123456789abcdef"[unsigned shr 4])
                append("0123456789abcdef"[unsigned and 0x0f])
            }
        }
    }
}
