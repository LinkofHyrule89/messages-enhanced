package com.ubermicrostudios.messagesenhanced.util

import java.net.URI

/**
 * Avatars and group icons (`GET /api/avatar`, including `participant_id=conv:…`)
 * and the account photo (`GET /api/app/profile-photo`) are cached by Coil, then
 * rechecked with the ETag the server already sends.
 *
 * `/api/avatar` sets `ETag: "<imageHash>"` and `Cache-Control: private, no-cache`,
 * and answers 304 only when `If-None-Match` is exactly that quoted value.
 * Profile photos use the same ETag scheme and accept a header that contains it.
 * The web client's `av` query param is a global generation, not a per-photo
 * version, so a conditional GET is the cheap check.
 */
object AvatarRevalidation {
    const val RECHECK_MS = 60_000L

    data class Decision(val replaceImage: Boolean, val storeBytes: Boolean)

    fun isAvatarUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        return path == "/api/avatar" || path.endsWith("/api/avatar") ||
            path == "/api/app/profile-photo" || path.endsWith("/api/app/profile-photo")
    }

    /** Send the stored header value unchanged so the server's exact compare matches. */
    fun ifNoneMatch(storedEtag: String?): String? = storedEtag?.trim()?.takeIf { it.isNotEmpty() }

    fun due(lastCheckMs: Long, nowMs: Long, intervalMs: Long = RECHECK_MS): Boolean {
        if (lastCheckMs <= 0L) return true
        return nowMs - lastCheckMs >= intervalMs
    }

    /**
     * 304 and an unchanged body keep the cached bitmap. A 200 whose bytes differ
     * replaces it. A 200 with the same bytes only refreshes metadata when we
     * still need to remember the ETag.
     */
    fun decide(
        storedEtag: String?,
        code: Int,
        responseEtag: String?,
        bytesMatch: Boolean,
    ): Decision {
        if (code == 304) return Decision(replaceImage = false, storeBytes = false)
        if (code != 200) return Decision(replaceImage = false, storeBytes = false)
        if (etagEquals(storedEtag, responseEtag)) return Decision(replaceImage = false, storeBytes = false)
        if (bytesMatch) {
            val learnEtag = storedEtag.isNullOrBlank() && !responseEtag.isNullOrBlank()
            return Decision(replaceImage = false, storeBytes = learnEtag)
        }
        return Decision(replaceImage = true, storeBytes = true)
    }

    fun etagEquals(a: String?, b: String?): Boolean {
        val left = normalizeEtag(a)
        val right = normalizeEtag(b)
        return left.isNotEmpty() && left == right
    }

    fun normalizeEtag(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("W/").trim().trim('"').lowercase()
    }

    /**
     * Coil writes disk metadata as:
     * sentRequestAtMillis, receivedResponseAtMillis, tls flag, header count,
     * then `Name: Value` lines. The ETag is one of those headers.
     */
    fun etagFromCoilMetadata(text: String): String? {
        val lines = text.split('\n')
        if (lines.size < 4) return null
        val count = lines[3].trim().toIntOrNull() ?: return null
        if (count < 0) return null
        for (i in 0 until count) {
            val line = lines.getOrNull(4 + i) ?: return null
            val name = line.substringBefore(':').trim()
            if (name.equals("ETag", ignoreCase = true)) {
                val value = line.substringAfter(':', "").trim()
                return value.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun coilMetadata(
        sentAtMillis: Long,
        receivedAtMillis: Long,
        tls: Boolean,
        headers: List<Pair<String, String>>,
    ): String = buildString {
        append(sentAtMillis).append('\n')
        append(receivedAtMillis).append('\n')
        append(if (tls) 1 else 0).append('\n')
        append(headers.size).append('\n')
        for ((name, value) in headers) {
            append(name).append(": ").append(value).append('\n')
        }
    }
}
