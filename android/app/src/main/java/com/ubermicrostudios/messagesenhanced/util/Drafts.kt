package com.ubermicrostudios.messagesenhanced.util

/** Pure rules for per-conversation composer drafts (local only, never synced). */
object Drafts {
    const val MAX_CHARS = 20_000

    /** What to store for typed text: blank (erased) means delete the draft. */
    fun normalize(text: String?): String? {
        val t = text.orEmpty()
        if (t.isBlank()) return null
        return if (t.length > MAX_CHARS) t.take(MAX_CHARS) else t
    }

    /**
     * Shared content into a chat that already has a draft: shared wins when there
     * is no draft; otherwise keep the draft and append the shared text on a new
     * line (skipping it if the draft already contains it).
     */
    fun mergeShared(saved: String?, shared: String?): String {
        val s = saved.orEmpty()
        val n = shared.orEmpty()
        if (n.isBlank()) return s
        if (s.isBlank()) return n
        if (s.contains(n.trim())) return s
        return s.trimEnd() + "\n" + n
    }

    /** Chat list preview text for a conversation with a draft. */
    fun listPreview(draft: String): String =
        "Draft: " + draft.replace('\n', ' ').replace('\u00A0', ' ').trim()
}
