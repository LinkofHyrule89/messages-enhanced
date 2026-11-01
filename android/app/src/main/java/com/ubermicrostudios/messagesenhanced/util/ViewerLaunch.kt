package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import kotlinx.serialization.Serializable

/** One message handed to the photo viewer. [localPreview] is kept, unlike the transient field on [MessageDto]. */
@Serializable
data class ViewerSnap(
    val messageId: String = "",
    val conversationId: String = "",
    val senderName: String = "",
    val senderNumber: String = "",
    val body: String = "",
    val timestampMs: Long = 0L,
    val status: String = "",
    val isFromMe: Boolean = false,
    val mediaId: String = "",
    val mimeType: String = "",
    val replyToId: String = "",
    val sourcePlatform: String = "",
    val statusText: String = "",
    val localPreview: String = "",
)

@Serializable
data class ViewerBundle(
    val conversationId: String = "",
    val title: String = "",
    val isGroup: Boolean = false,
    val sourcePlatform: String = "",
    val protocol: String = "",
    val e2ee: Boolean = false,
    val e2eeSource: String = "",
    val e2eeIds: List<String> = emptyList(),
    val e2eeLoadedAt: Long = 0L,
    val startId: String = "",
    val messages: List<ViewerSnap> = emptyList(),
    val participantsJson: String = "",
    val origin: String = "",
) {
    fun encryption(): ConversationEncryption = ConversationEncryption(
        e2ee = e2ee,
        e2eeSource = e2eeSource,
        e2eeIds = e2eeIds.toSet(),
        loadedAt = e2eeLoadedAt,
    )
}

fun MessageDto.toViewerSnap(): ViewerSnap = ViewerSnap(
    messageId = messageId,
    conversationId = conversationId,
    senderName = senderName,
    senderNumber = senderNumber,
    body = body,
    timestampMs = timestampMs,
    status = status,
    isFromMe = isFromMe,
    mediaId = mediaId,
    mimeType = mimeType,
    replyToId = replyToId,
    sourcePlatform = sourcePlatform,
    statusText = statusText,
    localPreview = localPreview,
)

fun ViewerSnap.toMessage(): MessageDto = MessageDto(
    messageId = messageId,
    conversationId = conversationId,
    senderName = senderName,
    senderNumber = senderNumber,
    body = body,
    timestampMs = timestampMs,
    status = status,
    isFromMe = isFromMe,
    mediaId = mediaId,
    mimeType = mimeType,
    replyToId = replyToId,
    sourcePlatform = sourcePlatform,
    statusText = statusText,
    localPreview = localPreview,
)
