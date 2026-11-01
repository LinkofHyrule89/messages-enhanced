package com.ubermicrostudios.messagesenhanced.util

import kotlinx.serialization.Serializable

/**
 * Composer rules from the web app.
 * Attachments wait in a tray and leave on Send (`takeFiles` / `sendMedia` / `sendMessage`).
 * WhatsApp non-audio and Signal media take the typed text as a caption and the reply.
 * A GIF sends immediately (`sendComposeGif`): WhatsApp and Signal caption it; other routes
 * send the typed text as the next message.
 */
object ComposerPlan {
    const val MAX_PENDING = 10
    const val GIF_PAGE_SIZE = 32
    const val GIF_FAVORITES_LIMIT = 72
    const val GIF_DEBOUNCE_MS = 500L
    const val EMOJI_SEARCH_LIMIT = 240
    const val EMOJI_RECENT_MAX = 24

    const val TOO_LARGE = "That file is too large to send (limit 128 MB)."
    const val TOO_MANY = "Up to 10 attachments at a time."
    const val GIF_PENDING_BLOCK = "Send or remove the current attachment before choosing a GIF."
    const val GIF_LOADING = "Loading GIFs..."
    const val GIF_EMPTY = "No GIFs found."
    const val GIF_FAILED = "Failed to send GIF"

    data class PendingFile(
        val id: String,
        val path: String,
        val name: String,
        val mime: String,
        val size: Long,
    )

    data class AddOutcome(val files: List<PendingFile>, val notices: List<String>)

    /** Skip empty files. One notice per reason, matching the web toasts. */
    fun addFiles(
        current: List<PendingFile>,
        incoming: List<PendingFile>,
        maxBytes: Long = MessageActions.MAX_MEDIA_BYTES,
    ): AddOutcome {
        val next = current.toMutableList()
        val notices = LinkedHashSet<String>()
        for (file in incoming) {
            if (file.size <= 0L) continue
            if (file.size > maxBytes) {
                notices.add(TOO_LARGE)
                continue
            }
            if (next.size >= MAX_PENDING) {
                notices.add(TOO_MANY)
                continue
            }
            next.add(file)
        }
        return AddOutcome(next, notices.toList())
    }

    fun supportsMediaCaption(platform: String, mime: String): Boolean {
        val p = platform.lowercase()
        val type = mime.lowercase()
        return (p == "whatsapp" && !type.startsWith("audio/")) || p == "signal"
    }

    fun supportsMediaReply(platform: String): Boolean {
        val p = platform.lowercase()
        return p == "whatsapp" || p == "signal"
    }

    data class MediaAction(val index: Int, val caption: String, val replyToId: String)
    data class TextAction(val text: String, val replyToId: String)

    data class SendPlan(
        val media: List<MediaAction>,
        val text: TextAction?,
        val clearDraft: Boolean,
        val clearReply: Boolean,
    )

    /**
     * Files go out first. The first captionable file consumes the draft. The first
     * WhatsApp or Signal file consumes the reply. Leftover text is its own message.
     */
    fun sendPlan(platform: String, draft: String, replyId: String?, files: List<PendingFile>): SendPlan {
        val text = draft.trim()
        val reply = replyId?.takeIf { it.isNotBlank() }.orEmpty()
        if (files.isEmpty()) {
            if (text.isEmpty()) return SendPlan(emptyList(), null, clearDraft = false, clearReply = false)
            return SendPlan(emptyList(), TextAction(text, reply), clearDraft = true, clearReply = reply.isNotEmpty())
        }
        var captionTaken = false
        var replyTaken = false
        val media = files.mapIndexed { index, file ->
            val caption = if (!captionTaken && text.isNotEmpty() && supportsMediaCaption(platform, file.mime)) {
                captionTaken = true
                text
            } else {
                ""
            }
            val mediaReply = if (!replyTaken && reply.isNotEmpty() && supportsMediaReply(platform)) {
                replyTaken = true
                reply
            } else {
                ""
            }
            MediaAction(index, caption, mediaReply)
        }
        val follow = if (!captionTaken && text.isNotEmpty()) {
            TextAction(text, if (replyTaken) "" else reply)
        } else {
            null
        }
        val clearReply = replyTaken || !follow?.replyToId.isNullOrBlank()
        return SendPlan(media, follow, clearDraft = captionTaken || follow != null, clearReply = clearReply)
    }

    data class GifPlan(
        val caption: String,
        val gifReply: String,
        val followUpText: String,
        val followUpReply: String,
    )

    /** Desktop `sendComposeGif`. The reply bar always clears once a GIF is chosen. */
    fun gifPlan(platform: String, draft: String, replyId: String?): GifPlan {
        val text = draft.trim()
        val reply = replyId?.takeIf { it.isNotBlank() }.orEmpty()
        return if (supportsMediaReply(platform)) {
            GifPlan(caption = text, gifReply = reply, followUpText = "", followUpReply = "")
        } else {
            GifPlan(
                caption = "",
                gifReply = "",
                followUpText = text,
                followUpReply = if (text.isNotEmpty()) reply else "",
            )
        }
    }

    data class Insert(val text: String, val cursor: Int)

    /** Web `insertAtCursor`: the glyph replaces the selection and the caret sits after it. */
    fun insertEmoji(text: String, start: Int, end: Int, emoji: String): Insert {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val next = text.substring(0, s) + emoji + text.substring(e)
        return Insert(next, (s + emoji.length).coerceIn(0, next.length))
    }

    @Serializable
    data class GifRef(
        val id: String = "",
        val title: String = "",
        val previewUrl: String = "",
        val url: String = "",
        val mimeType: String = "",
        val width: Int = 0,
        val height: Int = 0,
    )

    fun normalizeGif(
        id: String,
        title: String,
        previewUrl: String,
        url: String,
        mimeType: String,
        width: Int,
        height: Int,
    ): GifRef? {
        val full = url.trim()
        val preview = previewUrl.trim()
        if (full.isEmpty() || preview.isEmpty()) return null
        return GifRef(
            id = id.trim(),
            title = title.trim().ifBlank { "GIF" },
            previewUrl = preview,
            url = full,
            mimeType = mimeType.trim().ifBlank { "image/gif" },
            width = width,
            height = height,
        )
    }

    data class GifMerge(val items: List<GifRef>, val hasMore: Boolean)

    /** Web `loadComposeGifs`: drop duplicate URLs; another page exists only when this page was full. */
    fun mergeGifs(
        existing: List<GifRef>,
        incoming: List<GifRef>,
        pageSize: Int = GIF_PAGE_SIZE,
        reportedHasMore: Boolean = true,
    ): GifMerge {
        val seen = existing.map { it.url }.toMutableSet()
        val out = existing.toMutableList()
        var added = 0
        for (item in incoming) {
            if (item.url.isBlank() || item.url in seen) continue
            seen.add(item.url)
            out.add(item)
            added++
        }
        val hasMore = added > 0 && reportedHasMore && incoming.size >= pageSize
        return GifMerge(out, hasMore)
    }

    fun toggleFavorite(current: List<GifRef>, item: GifRef, limit: Int = GIF_FAVORITES_LIMIT): List<GifRef> {
        if (item.url.isBlank()) return current
        val index = current.indexOfFirst { it.url == item.url }
        if (index >= 0) return current.filterIndexed { i, _ -> i != index }
        return (listOf(item) + current).take(limit)
    }

    fun absoluteUrl(origin: String, url: String): String {
        val raw = url.trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("/")) return origin.trimEnd('/') + raw
        return raw
    }
}
