package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto

/**
 * Reply strip and in-bubble quote text, matching the web app's
 * `msgSnippetSource`, `setReplyTo`, and `replyQuote`.
 */
object ReplyTexts {
    const val BAR_LIMIT = 80
    const val QUOTE_LIMIT = 240
    const val YOU = "You"
    const val THEM = "Them"
    const val PHOTO = "Photo"
    const val MEDIA = "Media"
    const val MISSING = "Original message"

    private val imageExt = Regex("""\.(jpe?g|png|gif|webp|bmp|avif|heic|heif)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val bareFilename = Regex("""^[^\s/\\]+\.[a-z0-9]{2,5}$""", RegexOption.IGNORE_CASE)
    private val genericMedia = setOf(
        "[Photo]",
        "[Video]",
        "[Audio]",
        "[Voice note]",
        "[Sticker]",
        "[Attachment]",
        "[Image]",
    )

    fun author(message: MessageDto, conversationTitle: String, isGroup: Boolean): String {
        if (message.isFromMe) return YOU
        if (message.senderName.isNotBlank()) return message.senderName
        if (!isGroup && conversationTitle.isNotBlank()) return conversationTitle
        return THEM
    }

    /** Body stored on the reply target before the 80-character strip trim. */
    fun snippetSource(message: MessageDto): String {
        if (MessageStatus.isDeleted(message.status)) return MessageStatus.DELETED_TEXT
        if (isImage(message)) return imageCaption(message).ifEmpty { PHOTO }
        if (message.body.isNotEmpty()) return message.body
        if (message.mediaId.isNotBlank() || message.mimeType.isNotBlank()) return MEDIA
        return ""
    }

    /** Composer strip: collapsed whitespace, at most [BAR_LIMIT] characters, no ellipsis. */
    fun barSnippet(source: String): String = collapse(source.ifEmpty { MEDIA }).take(BAR_LIMIT)

    /** Quote under a reply: collapsed whitespace, 60 characters, then an ellipsis. */
    fun quoteText(source: String): String {
        val body = collapse(source.ifEmpty { MEDIA })
        return if (body.length > QUOTE_LIMIT) body.substring(0, QUOTE_LIMIT) + "…" else body
    }

    fun collapse(text: String): String = Regex("""\s+""").replace(text, " ")

    fun isImage(message: MessageDto): Boolean {
        if (message.mediaId.isBlank()) return false
        val mime = message.mimeType.lowercase()
        if (mime.startsWith("image/")) return true
        if (mime.isNotEmpty() && mime != "application/octet-stream") return false
        return imageExt.containsMatchIn(message.body.trim())
    }

    fun imageCaption(message: MessageDto): String {
        val caption = message.body.trim()
        if (caption.isEmpty() || caption in genericMedia || bareFilename.matches(caption)) return ""
        return caption
    }
}
