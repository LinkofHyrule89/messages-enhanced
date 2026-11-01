package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto

/**
 * Photos the full-screen viewer can page through, and the direct replies hanging off one of them.
 * A direct reply is any other message whose reply-to id is the photo's message id.
 */
object ViewerGallery {
    fun images(messages: List<MessageDto>, mediaUrl: (MessageDto) -> String): List<MessageDto> =
        messages
            .filter { ImageStore.openSource(it, mediaUrl(it)) != null }
            .sortedWith(compareBy({ it.timestampMs }, { it.messageId }))

    fun startIndex(images: List<MessageDto>, tappedId: String): Int {
        val index = images.indexOfFirst { it.messageId == tappedId }
        return if (index < 0) 0 else index
    }
}

object ViewerReplies {
    fun directReplies(messages: List<MessageDto>, imageMessageId: String): List<MessageDto> {
        if (imageMessageId.isBlank()) return emptyList()
        return messages
            .filter { it.messageId != imageMessageId && it.replyToId == imageMessageId }
            .sortedWith(compareBy({ it.timestampMs }, { it.messageId }))
    }

    fun count(messages: List<MessageDto>, imageMessageId: String): Int =
        directReplies(messages, imageMessageId).size

    /** Null hides the badge. Counts above 99 collapse so the bubble stays small. */
    fun badgeLabel(count: Int): String? = when {
        count <= 0 -> null
        count > 99 -> "99+"
        else -> count.toString()
    }

    /**
     * Who sent a viewer reply. Your own messages use the participant flagged `me`.
     * Everyone else matches the last 10 digits of the sender number, then the name.
     */
    fun senderParticipant(people: List<ParticipantDto>, message: MessageDto): ParticipantDto? {
        if (message.isFromMe) return people.firstOrNull { it.me }
        val senderDigits = message.senderNumber.filter { it.isDigit() }.takeLast(10)
        if (senderDigits.isNotEmpty()) {
            people.firstOrNull { person ->
                val theirs = person.number.ifBlank { person.phone }.filter { it.isDigit() }.takeLast(10)
                theirs.isNotEmpty() && theirs == senderDigits
            }?.let { return it }
        }
        val name = message.senderName.trim()
        if (name.isEmpty()) return null
        return people.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
    }

    /** "You" for your own messages. Everyone else is the formatted sender name. */
    fun senderLabel(message: MessageDto, conversationTitle: String, isGroup: Boolean): String {
        val raw = if (message.isFromMe) {
            ReplyTexts.YOU
        } else {
            message.senderName.ifBlank {
                if (!isGroup) conversationTitle else ""
            }.ifBlank { message.senderNumber }.ifBlank { ReplyTexts.THEM }
        }
        return formatPhoneForDisplay(raw)
    }

    fun replyText(message: MessageDto): String {
        val body = message.body.trim()
        if (body.isNotEmpty()) return body
        return ReplyTexts.snippetSource(message)
    }

    fun withReply(messages: List<MessageDto>, reply: MessageDto): List<MessageDto> {
        if (reply.messageId.isBlank()) return messages
        if (messages.any { it.messageId == reply.messageId }) return messages
        return (messages + reply).sortedWith(compareBy({ it.timestampMs }, { it.messageId }))
    }

    /**
     * Fold a server refresh into the open viewer.
     * Local files that have not landed stay. A pending comment stays until a matching sent row arrives.
     */
    fun mergeRemote(
        existing: List<MessageDto>,
        remote: List<MessageDto>,
        pending: MessageDto? = null,
    ): List<MessageDto> {
        val remoteIds = remote.map { it.messageId }.toSet()
        val kept = existing.filter { message ->
            if (message.messageId in remoteIds) return@filter false
            if (message.messageId.startsWith("local-comment-")) {
                return@filter remote.none { landed(it, message) }
            }
            message.localPreview.isNotBlank()
        }
        val pendingKept = pending?.takeIf { optimistic ->
            optimistic.messageId !in remoteIds && remote.none { landed(it, optimistic) }
        }
        return (kept + remote + listOfNotNull(pendingKept))
            .distinctBy { it.messageId }
            .sortedWith(compareBy({ it.timestampMs }, { it.messageId }))
    }

    private fun landed(remote: MessageDto, pending: MessageDto): Boolean =
        remote.isFromMe &&
            remote.replyToId == pending.replyToId &&
            remote.body.trim() == pending.body.trim() &&
            remote.timestampMs >= pending.timestampMs - 60_000L
}
