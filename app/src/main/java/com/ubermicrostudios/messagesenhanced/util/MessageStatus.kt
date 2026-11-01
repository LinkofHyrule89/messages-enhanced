package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto

/**
 * Google Messages receipt kinds, matching the web app's `statusKind` / `listStatusKind`.
 * Also accepts the shorter statuses stored on older rows (`delivered`, `sent`, `read`).
 */
enum class ReceiptKind(val label: String) {
    SENDING("Sending…"),
    SENT("Sent"),
    DELIVERED("Delivered"),
    READ("Read"),
    FAILED("Not sent"),
}

object MessageStatus {
    private val sending = Regex("SENDING|YET_TO_SEND|VALIDATING|PROCESSING|AWAITING_RETRY|REVOCATION")
    private val readBy = Regex("^(?:read|seen) by\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val readBySplit = Regex("\\s*,\\s*|\\s+and\\s+|\\s*&\\s*")

    fun statusKind(
        status: String,
        messageId: String = "",
        fromMe: Boolean = true,
        posted: Boolean = false,
    ): ReceiptKind? {
        val s = status.trim().uppercase()
        if (s == "OUTGOING_SCHEDULED" || s == "SCHEDULED") return null
        if (s == "OUTGOING_DISPLAYED" || s == "DISPLAYED" || s == "READ") return ReceiptKind.READ
        if (s == "OUTGOING_DELIVERED" || s == "DELIVERED") return ReceiptKind.DELIVERED
        if (s == "OUTGOING_COMPLETE" || s == "OUTGOING_NOT_DELIVERED_YET" || s == "SENT" || s == "COMPLETE") {
            return ReceiptKind.SENT
        }
        if (s.contains("FAIL") || s.contains("CANCELED") || s.contains("CANCELLED")) return ReceiptKind.FAILED
        val placeholder = fromMe && (
            messageId.startsWith("tm-") ||
                messageId.startsWith("tmp_") ||
                messageId.startsWith("local-")
            )
        if (sending.containsMatchIn(s) || (placeholder && s.isNotEmpty())) {
            return if (posted) ReceiptKind.SENT else ReceiptKind.SENDING
        }
        return null
    }

    const val DELETED_TEXT = "This message was deleted"
    const val REVOKING_TEXT = "Deleting for everyone…"

    /** Deleted for everyone by its sender (libgm MESSAGE_DELETED = 300). */
    fun isDeleted(status: String): Boolean = status.trim().uppercase() == "MESSAGE_DELETED"

    /** Deleted-for-everyone rows are hidden unless the placeholder setting is on. */
    fun visibleMessages(messages: List<MessageDto>, showDeleted: Boolean): List<MessageDto> =
        if (showDeleted) messages else messages.filterNot { isDeleted(it.status) }

    /** Our own "delete for everyone" still waiting on Google. */
    fun isRevoking(status: String): Boolean = status.trim().uppercase().contains("REVOCATION")

    /** Small label under a bubble: Failed / Scheduled / Deleting… / Edited. */
    fun bubbleLabel(message: MessageDto): String {
        val s = message.status
        return when {
            isDeleted(s) -> ""
            isRevoking(s) -> REVOKING_TEXT
            message.isFromMe && statusKind(message) == ReceiptKind.FAILED -> "Failed"
            s.contains("SCHEDULED", ignoreCase = true) -> "Scheduled"
            message.edited -> "Edited"
            else -> ""
        }
    }

    fun statusKind(message: MessageDto, posted: Boolean = false): ReceiptKind? =
        statusKind(message.status, message.messageId, message.isFromMe, posted)

    /** Names from "Read by Alice, Bob" / "Seen by Alice". Null when it is not that text. */
    fun readByNames(statusText: String): List<String>? {
        val match = readBy.matchEntire(statusText.trim()) ?: return null
        return match.groupValues[1].split(readBySplit).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * List icon for a "You:" preview. Groups that only some people have read
     * stay on the delivered icon until the read-by list covers every other member.
     */
    fun listStatusKind(conversation: ConversationDto): ReceiptKind? {
        if (!conversation.lastFromMe || conversation.lastStatus.isBlank()) return null
        var kind = statusKind(conversation.lastStatus, messageId = "") ?: return null
        if (kind == ReceiptKind.READ && conversation.isGroup) {
            val names = readByNames(conversation.lastStatusText)
            val others = Participants.otherMembers(conversation).size
            if (names != null && names.isNotEmpty() && others > 0 && names.size < others) {
                kind = ReceiptKind.DELIVERED
            }
        }
        return kind
    }

    /**
     * Web `msgDetail`: outgoing status in words, then the time
     * (`dayLabel, clock` when it is not today).
     */
    fun detailLine(message: MessageDto, nowMs: Long = System.currentTimeMillis()): String {
        val parts = ArrayList<String>()
        if (message.isFromMe) {
            val kind = statusKind(message)
            val label = message.statusText.trim().ifBlank { kind?.label.orEmpty() }
            if (label.isNotBlank()) parts.add(label)
        }
        val time = Formatters.detailTime(message.timestampMs, nowMs)
        if (time.isNotBlank()) parts.add(time)
        return parts.joinToString(" · ")
    }
}

data class ConversationEncryption(
    val e2ee: Boolean = false,
    val e2eeSource: String = "",
    val e2eeIds: Set<String> = emptySet(),
    val loadedAt: Long = 0L,
)

object Encryption {
    /** Per-message lock, matching the web app's `msgEncrypted`. */
    fun messageEncrypted(message: MessageDto, meta: ConversationEncryption): Boolean {
        val id = message.messageId
        if (!meta.e2ee && meta.e2eeIds.isEmpty()) return false
        if (id.startsWith("tm-") || id.startsWith("tmp_") || id.startsWith("local-")) return false
        if (meta.e2eeIds.contains(id)) return true
        if (meta.e2eeSource == "messages") {
            return meta.e2ee && message.timestampMs > meta.loadedAt - 5_000
        }
        return meta.e2ee
    }

    /** Row lock when the protocol string itself says the chat is encrypted. */
    fun protocolEncrypted(displayProtocol: String): Boolean {
        val p = displayProtocol.uppercase()
        return p.contains("E2EE") || p.contains("ENCRYPT")
    }

    fun isRcs(displayProtocol: String): Boolean = displayProtocol.trim().equals("RCS", ignoreCase = true)
}
