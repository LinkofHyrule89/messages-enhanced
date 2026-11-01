package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Long-press message sheet, matching the web app's `openMsgSheet`, reactions,
 * stars, forward, multi-select copy, and info dialog (`app.js`).
 */
object MessageActions {
    const val LONG_PRESS_MS = 480L
    const val LONG_PRESS_SLOP_DP = 12f
    const val LONG_PRESS_HAPTIC_MS = 15L

    /** Server `messaging.DefaultMaxMediaBytes` (128 MiB), same cap as the web app. */
    const val MAX_MEDIA_BYTES = 128L shl 20

    const val SHEET_PAD = 20f
    const val SHEET_GAP = 16f
    const val SHEET_MIN_CLONE = 60f

    val QUICK_REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22")

    private val videoExt = Regex("""\.(mp4|m4v|mov|webm|3gp|3g2|mkv)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val reactionUuid = Regex(
        """^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""",
        RegexOption.IGNORE_CASE,
    )
    private val reactionToken = Regex("""^:?[a-z0-9_-]{6,}:?$""", RegexOption.IGNORE_CASE)
    private val failStatus = Regex("FAIL|CANCELED")
    private val sendingStatus = Regex("SENDING|YET_TO_SEND|VALIDATING|PROCESSING|AWAITING_RETRY|REVOCATION")
    private val readStatus = Regex("READ|DISPLAYED", RegexOption.IGNORE_CASE)

    /** Web `SHEET` open rules: not while selecting, and not a local or in-flight row. */
    fun canOpenMenu(message: MessageDto, selecting: Boolean): Boolean {
        if (selecting) return false
        if (message.messageId.isBlank() || message.messageId.startsWith("local-")) return false
        if (SwipeMath.isSendPlaceholder(message)) return false
        return true
    }

    /** Web bubble tap: not while selecting, and not a local or in-flight row. */
    fun canShowDetail(message: MessageDto, selecting: Boolean): Boolean = canOpenMenu(message, selecting)

    fun hasMedia(message: MessageDto): Boolean =
        message.mediaId.isNotBlank() && message.mediaId != "local"

    fun isVideo(message: MessageDto): Boolean {
        if (!hasMedia(message)) return false
        val mime = message.mimeType.lowercase()
        if (mime.startsWith("video/")) return true
        if (mime.isNotEmpty() && mime != "application/octet-stream") return false
        return videoExt.containsMatchIn(message.body.trim())
    }

    data class SheetMenu(
        val showReactions: Boolean,
        val showReply: Boolean,
        val showForward: Boolean,
        val showCopyUrl: Boolean,
        val copyUrl: String?,
        val showCopy: Boolean,
        val starLabel: String,
        val showDelete: Boolean,
        val showSelect: Boolean,
        val showInfo: Boolean,
    )

    /**
     * Which rows the sheet shows. Readonly threads (a folder on the web) hide
     * react, reply, delete, and select. Copy needs body text. Forward needs
     * body text or a real attachment. Copy URL needs an http(s) link.
     */
    fun sheetMenu(message: MessageDto, starred: Boolean, readonly: Boolean = false): SheetMenu {
        val body = message.body.trim()
        val url = LinkUrls.firstHttpUrl(body)
        return SheetMenu(
            showReactions = !readonly,
            showReply = !readonly,
            showForward = body.isNotEmpty() || hasMedia(message),
            showCopyUrl = !url.isNullOrBlank(),
            copyUrl = url,
            showCopy = body.isNotEmpty(),
            starLabel = if (starred) "Unstar" else "Star",
            showDelete = !readonly,
            showSelect = !readonly,
            showInfo = true,
        )
    }

    fun applyStar(starred: Set<String>, messageId: String, on: Boolean): Set<String> =
        if (on) starred + messageId else starred - messageId

    @Serializable
    data class ReactionDto(
        val emoji: String = "",
        val count: Int = 1,
        val actors: List<String> = emptyList(),
        val custom: JsonElement? = null,
    )

    fun parseReactions(raw: String): List<ReactionDto> {
        if (raw.isBlank() || raw == "null" || raw == "[]") return emptyList()
        val list = runCatching {
            JsonConfig.json.decodeFromString<List<ReactionDto>>(raw)
        }.getOrDefault(emptyList())
        return list.filter { it.emoji.isNotBlank() }
    }

    fun isCustomReaction(reaction: ReactionDto): Boolean {
        val emoji = reaction.emoji.trim()
        val custom = reaction.custom != null && reaction.custom !is JsonNull
        return custom || reactionUuid.matches(emoji) || reactionToken.matches(emoji)
    }

    fun reactionGlyph(reaction: ReactionDto): String =
        if (isCustomReaction(reaction)) "Custom reaction" else reaction.emoji

    fun reactionCount(reaction: ReactionDto): Int = if (reaction.count <= 0) 1 else reaction.count

    /** Pill text on the bubble: emoji, or "Custom", with a count when it is above 1. */
    fun reactionLine(raw: String): String {
        val list = parseReactions(raw)
        if (list.isEmpty()) return ""
        return list.joinToString("  ") { reaction ->
            val glyph = if (isCustomReaction(reaction)) "Custom" else reaction.emoji
            val count = reactionCount(reaction)
            if (count > 1) "$glyph $count" else glyph
        }
    }

    fun reactorNames(reaction: ReactionDto, participants: List<ParticipantDto>): List<String> {
        if (reaction.actors.isEmpty()) return emptyList()
        return reaction.actors.map { actor ->
            val person = participants.firstOrNull { participant ->
                participant.id == actor ||
                    participant.participantId == actor ||
                    participant.participantIdCamel == actor
            } ?: return@map ""
            if (person.me) "You" else formatPhoneForDisplay(person.name.ifBlank { person.number })
        }.filter { it.isNotBlank() }
    }

    fun reactionSummary(raw: String, participants: List<ParticipantDto>): String {
        val list = parseReactions(raw)
        if (list.isEmpty()) return ""
        return list.joinToString(" · ") { reaction ->
            val names = reactorNames(reaction, participants)
            val count = reactionCount(reaction)
            reactionGlyph(reaction) + when {
                names.isNotEmpty() -> " (" + names.joinToString(", ") + ")"
                count > 1 -> " ×$count"
                else -> ""
            }
        }
    }

    /** Web `sentStatus`: the words under an outgoing message, null when the bubble already says Failed. */
    fun sentStatus(status: String): String? {
        val value = status.uppercase()
        if (value == "OUTGOING_DISPLAYED") return "Read"
        if (value == "OUTGOING_DELIVERED") return "Delivered"
        if (value == "OUTGOING_COMPLETE" || value == "OUTGOING_NOT_DELIVERED_YET") return "Sent"
        if (value == "OUTGOING_SCHEDULED") return "Scheduled"
        if (failStatus.containsMatchIn(value)) return null
        if (sendingStatus.containsMatchIn(value)) return "Sending…"
        return null
    }

    fun infoStatus(message: MessageDto): String {
        if (!message.isFromMe) {
            return if (readStatus.containsMatchIn(message.status)) "Read" else "Received"
        }
        val text = message.statusText.trim()
        if (text.isNotEmpty()) return text
        sentStatus(message.status)?.let { return it }
        val raw = message.status.replace(Regex("^OUTGOING_"), "").replace('_', ' ').lowercase()
        return raw.ifBlank { "unknown" }
    }

    fun messageType(message: MessageDto): String = when {
        isVideo(message) -> "Video"
        ReplyTexts.isImage(message) -> "Photo"
        hasMedia(message) -> message.mimeType.ifBlank { "Attachment" }
        else -> "Text"
    }

    /**
     * Web `protocolText`. Non-sms platforms are the platform name with a capital
     * first letter. SMS uses the conversation protocol, and a photo or file on
     * SMS is MMS. End-to-end encryption is appended when the meta says so.
     */
    fun protocolText(sourcePlatform: String, metaProtocol: String, e2ee: Boolean, hasMedia: Boolean): String {
        val platform = sourcePlatform.trim().lowercase().ifBlank { "sms" }
        if (platform != "sms") {
            return platform.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
        val proto = when (metaProtocol) {
            "RCS" -> "RCS"
            "SMS" -> if (hasMedia) "MMS" else "SMS"
            else -> "SMS/RCS (unknown)"
        }
        return if (e2ee) "$proto · end-to-end encrypted" else proto
    }

    fun infoWhen(
        timestampMs: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (timestampMs <= 0L) return ""
        val format = SimpleDateFormat("EEE, MMM d, yyyy, h:mm:ss a", locale)
        format.timeZone = zone
        return format.format(Date(timestampMs))
    }

    fun infoRows(
        message: MessageDto,
        conversationName: String,
        sourcePlatform: String,
        metaProtocol: String,
        e2ee: Boolean,
        starred: Boolean,
        participants: List<ParticipantDto>,
        whenText: String,
    ): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        fun add(label: String, value: String) {
            if (value.isNotBlank()) rows.add(label to value)
        }
        add("Type", messageType(message))
        if (message.isFromMe) {
            add("From", "You")
            add("To", formatPhoneForDisplay(conversationName))
            add("Sent", whenText)
        } else {
            val name = message.senderName.ifBlank { conversationName }
            val number = message.senderNumber
            val from = if (number.isNotBlank() && number != message.senderName) {
                "${formatPhoneForDisplay(name)} (${formatPhoneForDisplay(number)})"
            } else {
                formatPhoneForDisplay(name)
            }
            add("From", from)
            add("Received", whenText)
        }
        val status = infoStatus(message)
        val readBy = MessageStatus.readByNames(status)
        add("Status", if (readBy.isNullOrEmpty()) formatPhoneForDisplay(status) else formatPhonesInText(status, readBy))
        add("Sent via", protocolText(sourcePlatform, metaProtocol, e2ee, hasMedia(message)))
        add("Reactions", reactionSummary(message.reactions, participants))
        if (starred) add("Starred", "Yes (on this server)")
        return rows
    }

    fun infoText(rows: List<Pair<String, String>>): String =
        rows.joinToString("\n") { (label, value) -> "$label: $value" }

    data class DeletePrompt(val title: String, val body: String)

    fun deletePrompt(count: Int, sourcePlatform: String): DeletePrompt {
        val title = if (count > 1) "Delete $count messages?" else "Delete this message?"
        val google = sourcePlatform.trim().lowercase().ifBlank { "sms" } == "sms"
        val body = if (google) {
            "It's removed from your phone and Messages on the web. The other person still has it."
        } else {
            "It's removed from this server only. The other person still has it."
        }
        return DeletePrompt(title, body)
    }

    fun deleteToast(scope: String?, count: Int): String {
        val base = if (scope == "local") "Deleted from this server" else "Deleted"
        return if (count > 1) "$base ($count)" else base
    }

    fun deletable(messages: List<MessageDto>): List<MessageDto> =
        messages.filter { message ->
            !message.messageId.startsWith("local-") && !SwipeMath.isSendPlaceholder(message)
        }

    fun selectedSorted(messages: List<MessageDto>, selectedIds: Set<String>): List<MessageDto> =
        messages.filter { it.messageId in selectedIds }.sortedBy { it.timestampMs }

    fun selectionCopy(
        messages: List<MessageDto>,
        conversationTitle: String,
        isGroup: Boolean,
        clock: (Long) -> String = { Formatters.clockTime(it) },
    ): String {
        val multi = messages.size > 1
        return messages.joinToString("\n") { message ->
            val body = message.body.trim().ifEmpty { if (hasMedia(message)) "[attachment]" else "" }
            if (!multi) body else "[${clock(message.timestampMs)}] ${ReplyTexts.author(message, conversationTitle, isGroup)}: $body"
        }
    }

    sealed interface ForwardPiece {
        data class Text(val body: String) : ForwardPiece
        data class Media(
            val messageId: String,
            val mimeType: String,
            val filename: String,
            val caption: String?,
        ) : ForwardPiece
    }

    /** Oldest first. An image with a real caption is a file plus a follow-up text, as on the web. */
    fun forwardPlan(messages: List<MessageDto>): List<ForwardPiece> {
        val ready = messages
            .filter { it.body.trim().isNotEmpty() || hasMedia(it) }
            .sortedBy { it.timestampMs }
        val pieces = ArrayList<ForwardPiece>()
        for (message in ready) {
            if (hasMedia(message)) {
                val mime = message.mimeType.ifBlank { "application/octet-stream" }
                pieces.add(
                    ForwardPiece.Media(
                        messageId = message.messageId,
                        mimeType = mime,
                        filename = "forwarded.${mediaExtension(mime)}",
                        caption = imageFollowUp(message),
                    ),
                )
            } else {
                pieces.add(ForwardPiece.Text(message.body.trim()))
            }
        }
        return pieces
    }

    fun mediaExtension(mime: String): String {
        val sub = mime.substringAfter('/', "bin").substringBefore(';')
        val clean = sub.takeWhile { it.isLetterOrDigit() }
        return clean.ifBlank { "bin" }
    }

    /** Caption sent as its own text after an image. Empty for generic labels, file names, and non-images. */
    fun imageFollowUp(message: MessageDto): String? {
        if (!ReplyTexts.isImage(message)) return null
        val caption = ReplyTexts.imageCaption(message)
        return caption.ifBlank { null }
    }

    fun rememberRecent(current: List<String>, emoji: String, cap: Int = 32): List<String> {
        if (emoji.isBlank()) return current
        return (listOf(emoji) + current.filter { it != emoji }).take(cap)
    }

    data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    data class SheetPlacement(
        val react: Box,
        val clone: Box,
        val menu: Box,
        val beside: Boolean,
    )

    /**
     * Web `placeMsgSheet`. [pad] and [gap] use the same unit as the boxes
     * (pixels on screen). The menu sits under the bubble, aligned to the
     * sender's side, and moves beside it when stacking would leave the screen.
     */
    fun placeSheet(
        bubble: Box,
        mine: Boolean,
        viewWidth: Float,
        viewHeight: Float,
        reactWidth: Float,
        reactHeight: Float,
        reactHidden: Boolean,
        menuWidth: Float,
        menuHeight: Float,
        pad: Float = SHEET_PAD,
        gap: Float = SHEET_GAP,
        minClone: Float = SHEET_MIN_CLONE,
    ): SheetPlacement {
        val reactH = if (reactHidden) 0f else reactHeight
        val reactGap = if (reactHidden) 0f else gap
        fun align(width: Float, origin: Float, span: Float): Float {
            val raw = if (mine) origin + span - width else origin
            return raw.coerceIn(pad, (viewWidth - pad - width).coerceAtLeast(pad))
        }
        val stackHeight = reactH + reactGap + bubble.height + gap + menuHeight
        val besideX = if (mine) bubble.left - gap - menuWidth else bubble.left + bubble.width + gap
        val beside = stackHeight > viewHeight - 2 * pad &&
            besideX >= pad &&
            besideX + menuWidth <= viewWidth - pad
        val cloneHeight = if (beside) {
            bubble.height.coerceAtMost(viewHeight - 2 * pad - reactH - reactGap)
        } else {
            val room = viewHeight - 2 * pad - reactH - reactGap - gap - menuHeight
            bubble.height.coerceAtMost(room.coerceAtLeast(minClone))
        }
        val groupHeight = if (beside) {
            reactH + reactGap + maxOf(cloneHeight, menuHeight)
        } else {
            reactH + reactGap + cloneHeight + gap + menuHeight
        }
        val top = (bubble.top - reactH - reactGap).coerceIn(pad, (viewHeight - pad - groupHeight).coerceAtLeast(pad))
        val cloneTop = top + reactH + reactGap
        val menuTop = if (beside) {
            cloneTop.coerceIn(pad, (viewHeight - pad - menuHeight).coerceAtLeast(pad))
        } else {
            cloneTop + cloneHeight + gap
        }
        val menuLeft = if (beside) besideX else align(menuWidth, bubble.left, bubble.width)
        return SheetPlacement(
            react = Box(align(reactWidth, bubble.left, bubble.width), top, reactWidth, reactHeight),
            clone = Box(bubble.left, cloneTop, bubble.width, cloneHeight),
            menu = Box(menuLeft, menuTop, menuWidth, menuHeight),
            beside = beside,
        )
    }
}
