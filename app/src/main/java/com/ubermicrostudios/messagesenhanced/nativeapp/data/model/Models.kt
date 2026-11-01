package com.ubermicrostudios.messagesenhanced.nativeapp.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

/**
 * Conversation DTO matching the Go `db.Conversation` JSON from GET /api/conversations.
 * Untagged fields keep Go's exported names (ConversationID, Name, …); tagged fields are snake_case.
 */
@Serializable
data class ConversationDto(
    @SerialName("ConversationID") val conversationId: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("IsGroup") val isGroup: Boolean = false,
    @SerialName("Participants") val participants: String = "",
    @SerialName("LastMessageTS") val lastMessageTs: Long = 0L,
    @SerialName("UnreadCount") val unreadCount: Int = 0,
    @SerialName("source_platform") val sourcePlatform: String = "",
    @SerialName("display_protocol") val displayProtocol: String = "",
    @SerialName("last_message_preview") val lastMessagePreview: String = "",
    @SerialName("unified_id") val unifiedId: String = "",
    @SerialName("unified_name") val unifiedName: String = "",
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("notification_mode") val notificationMode: String = "",
    @SerialName("tab") val tab: String = "",
    @SerialName("google_pinned") val googlePinned: Boolean = false,
    @SerialName("local_pinned_at_ms") val localPinnedAtMs: Long = 0L,
    @SerialName("last_from_me") val lastFromMe: Boolean = false,
    @SerialName("last_status") val lastStatus: String = "",
    @SerialName("last_status_text") val lastStatusText: String = "",
)

/**
 * Message DTO matching the Go `db.Message` JSON from GET /api/conversations/{id}/messages.
 */
@Serializable
data class MessageDto(
    @SerialName("MessageID") val messageId: String = "",
    @SerialName("ConversationID") val conversationId: String = "",
    @SerialName("SenderName") val senderName: String = "",
    @SerialName("SenderNumber") val senderNumber: String = "",
    @SerialName("Body") val body: String = "",
    @SerialName("TimestampMS") val timestampMs: Long = 0L,
    @SerialName("Status") val status: String = "",
    @SerialName("IsFromMe") val isFromMe: Boolean = false,
    @SerialName("mentions_me") val mentionsMe: Boolean = false,
    @SerialName("MediaID") val mediaId: String = "",
    @SerialName("MimeType") val mimeType: String = "",
    @SerialName("Reactions") val reactions: String = "",
    @SerialName("ReplyToID") val replyToId: String = "",
    @SerialName("source_platform") val sourcePlatform: String = "",
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("transcript") val transcript: String = "",
    @SerialName("transcribed_at_ms") val transcribedAtMs: Long = 0L,
    @SerialName("transcript_model") val transcriptModel: String = "",
    @SerialName("status_text") val statusText: String = "",
    @SerialName("edited") val edited: Boolean = false,
    /**
     * Pixel size of the attachment when the server sends it. Absent today on
     * `db.Message`; alternate names are accepted so a later field does not
     * need a client update. Zero means unknown (size comes from the URL cache).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @SerialName("Width")
    @JsonNames("width", "media_width", "image_width")
    val mediaWidth: Int = 0,
    @OptIn(ExperimentalSerializationApi::class)
    @SerialName("Height")
    @JsonNames("height", "media_height", "image_height")
    val mediaHeight: Int = 0,
    /** Local file path or absolute preview URL for a send that has not landed yet. */
    @kotlinx.serialization.Transient val localPreview: String = "",
) {
    fun isImageMedia(): Boolean = mimeType.startsWith("image/", ignoreCase = true)
    fun hasAttachment(): Boolean = mediaId.isNotBlank() || mimeType.isNotBlank()
}

@Serializable
data class SendRequest(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message") val message: String,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
data class SendResponse(
    @SerialName("message_id") val messageId: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("success") val success: Boolean = false,
)

@Serializable
data class SendGifRequest(
    @SerialName("conversation_id") val conversationId: String,
    val url: String,
    val caption: String? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
data class GifResult(
    val id: String = "",
    val title: String = "",
    @SerialName("preview_url") val previewUrl: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("mime_type") val mimeType: String = "",
)

@Serializable
data class GifPage(
    val results: List<GifResult> = emptyList(),
    val page: Int = 1,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class ReactRequest(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val emoji: String,
    val action: String = "add",
)

@Serializable
data class StarEntry(
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("starred_ms") val starredMs: Long = 0L,
)

@Serializable
data class StarsResponse(
    val stars: Map<String, StarEntry> = emptyMap(),
    val scope: String = "",
)

@Serializable
data class StarRequest(
    @SerialName("message_id") val messageId: String,
    @SerialName("conversation_id") val conversationId: String,
    val starred: Boolean,
)

@Serializable
data class DeleteMessageRequest(
    @SerialName("message_id") val messageId: String,
)

@Serializable
data class DeleteMessageResponse(
    @SerialName("message_id") val messageId: String = "",
    @SerialName("conversation_id") val conversationId: String = "",
    val scope: String = "",
)

@Serializable
data class ReadRequest(
    @SerialName("conversation_id") val conversationId: String,
)

@Serializable
data class NotificationModeRequest(
    @SerialName("notification_mode") val notificationMode: String,
)

@Serializable
data class ParticipantDto(
    val name: String = "",
    val number: String = "",
    val phone: String = "",
    val id: String = "",
    @SerialName("participant_id") val participantId: String = "",
    @SerialName("participantId") val participantIdCamel: String = "",
    @SerialName("contact_id") val contactId: String = "",
    @SerialName("contactId") val contactIdCamel: String = "",
    @SerialName("is_me") val isMe: Boolean = false,
    @SerialName("isMe") val isMeCamel: Boolean = false,
    val hidden: Boolean = false,
    @SerialName("first_name") val firstName: String = "",
    /** Phone-assigned participant color ("#RRGGBB"), used for the avatar and group sender name. */
    val color: String = "",
) {
    val me: Boolean get() = isMe || isMeCamel
}

@Serializable
data class StreamEvent(
    @SerialName("type") val type: String = "",
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("connected") val connected: Boolean? = null,
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("sender_number") val senderNumber: String = "",
    @SerialName("timestamp") val timestamp: Long = 0L,
    @SerialName("typing") val typing: Boolean? = null,
)

@Serializable
data class PushConfig(
    @SerialName("available") val available: Boolean = false,
    @SerialName("public_key") val publicKey: String = "",
    @SerialName("devices") val devices: Int = 0,
    @SerialName("fcm") val fcm: FcmClientOptions? = null,
)

@Serializable
data class FcmClientOptions(
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("app_id") val appId: String = "",
    @SerialName("project_id") val projectId: String = "",
    @SerialName("sender_id") val senderId: String = "",
)

@Serializable
data class PushSubscribeRequest(
    @SerialName("endpoint") val endpoint: String,
    @SerialName("label") val label: String? = null,
    @SerialName("hide_text") val hideText: Boolean? = null,
)

@Serializable
data class PushUnsubscribeRequest(
    @SerialName("endpoint") val endpoint: String,
)

@Serializable
data class PushSettingsRequest(
    @SerialName("endpoint") val endpoint: String,
    @SerialName("hide_text") val hideText: Boolean,
)

@Serializable
data class PushTestRequest(
    @SerialName("endpoint") val endpoint: String,
)

@Serializable
data class TypingResponse(
    val typing: List<TypingDto> = emptyList(),
    @SerialName("ttl_ms") val ttlMs: Long = 15_000,
)

@Serializable
data class TypingDto(
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("sender_number") val senderNumber: String = "",
    @SerialName("expires_in_ms") val expiresInMs: Long = 0,
)

@Serializable
data class ConversationMetaDto(
    @SerialName("conversation_id") val conversationId: String = "",
    val protocol: String = "",
    val e2ee: Boolean = false,
    @SerialName("e2ee_source") val e2eeSource: String = "",
    @SerialName("e2ee_ids") val e2eeIds: List<String> = emptyList(),
    val muted: Boolean = false,
)

@Serializable
data class ThemesResponse(
    val themes: Map<String, ChatThemeDto> = emptyMap(),
    val palettes: List<String> = emptyList(),
)

@Serializable
data class ChatThemeDto(
    val palette: String = "",
    val color: String = "",
    val wallpaper: String = "",
    val custom: String = "",
    @SerialName("updated_ms") val updatedMs: Long = 0,
)

@Serializable
data class ThemePutRequest(
    @SerialName("conversation_id") val conversationId: String,
    val theme: ThemePutBody,
)

@Serializable
data class ThemePutBody(
    val palette: String = "",
    val color: String = "",
    val wallpaper: String = "",
    val custom: Boolean = false,
)

@Serializable
data class ThemeWriteResponse(
    @SerialName("conversation_id") val conversationId: String = "",
    val theme: ChatThemeDto = ChatThemeDto(),
    val set: Boolean = false,
)

@Serializable
data class WallpapersResponse(
    val categories: List<WallpaperCategoryDto> = emptyList(),
)

@Serializable
data class WallpaperCategoryDto(
    val id: String = "",
    val name: String = "",
    val items: List<WallpaperItemDto> = emptyList(),
)

@Serializable
data class WallpaperItemDto(
    val id: String = "",
    val file: String = "",
    val title: String = "",
    val author: String = "",
    val license: String = "",
    @SerialName("full_url") val fullUrl: String = "",
    @SerialName("thumb_url") val thumbUrl: String = "",
) {
    fun thumbPath(): String = thumbUrl.ifBlank { fullUrl }.ifBlank {
        if (file.isBlank()) "" else "/wallpapers/$file"
    }
}

@Serializable
data class SettingsEnvelope(
    val settings: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PinRequest(
    @SerialName("conversation_id") val conversationId: String,
    val pinned: Boolean,
)

@Serializable
data class PinResponse(
    @SerialName("conversation_id") val conversationId: String = "",
    val pinned: Boolean = false,
    @SerialName("google_pinned") val googlePinned: Boolean = false,
    @SerialName("local_pinned_at_ms") val localPinnedAtMs: Long = 0L,
)

@Serializable
data class RefreshRequest(
    val scope: String,
)

@Serializable
data class MuteRequest(
    @SerialName("conversation_id") val conversationId: String,
    val muted: Boolean,
)

@Serializable
data class ArchiveRequest(
    @SerialName("conversation_id") val conversationId: String,
    val archived: Boolean,
)

@Serializable
data class ContactDto(
    val name: String = "",
    val number: String = "",
    @SerialName("Name") val nameAlt: String = "",
    @SerialName("Number") val numberAlt: String = "",
    @SerialName("contact_id") val contactId: String = "",
    @SerialName("ContactID") val contactIdAlt: String = "",
    @SerialName("participant_id") val participantId: String = "",
    @SerialName("conversation_id") val conversationId: String = "",
    @SerialName("last_message_ts") val lastMessageTs: Long = 0L,
) {
    val displayName: String get() = name.ifBlank { nameAlt }.ifBlank { phone }
    val phone: String get() = number.ifBlank { numberAlt }
    val contactKey: String get() = contactId.ifBlank { contactIdAlt }
}

@Serializable
data class ContactsResponse(
    val top: List<ContactDto> = emptyList(),
    val all: List<ContactDto> = emptyList(),
)

@Serializable
data class StartConversationRequest(
    val numbers: List<String>,
    @SerialName("group_name") val groupName: String = "",
)

@Serializable
data class StartConversationResponse(
    @SerialName("conversation_id") val conversationId: String = "",
    val name: String = "",
    @SerialName("is_group") val isGroup: Boolean = false,
    val existing: Boolean = false,
)

@Serializable
data class ConversationIdRequest(
    @SerialName("conversation_id") val conversationId: String,
)

/** GET /api/app/profile. An empty hash means there is no account photo. */
@Serializable
data class ProfileDto(
    @SerialName("photo_hash") val photoHash: String = "",
    val source: String = "",
)

@Serializable
data class LinkPreviewDto(
    val url: String = "",
    val title: String = "",
    val description: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("site_name") val siteName: String = "",
    @SerialName("icon_url") val iconUrl: String = "",
    val domain: String = "",
    @SerialName("is_video") val isVideo: Boolean = false,
    @SerialName("image_width") val imageWidth: Int = 0,
    @SerialName("image_height") val imageHeight: Int = 0,
) {
    fun usable(): Boolean =
        title.isNotBlank() || description.isNotBlank() || imageUrl.isNotBlank() || siteName.isNotBlank()

    fun absoluteImage(origin: String): String? {
        val raw = imageUrl.trim()
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (!raw.startsWith("/")) return null
        return origin.trimEnd('/') + raw
    }

    fun siteLabel(): String = siteName.ifBlank { domain }

    /** Footer label, Google Messages style: the bare domain ("x.com"), else the site name. */
    fun footerLabel(): String = domain.trim().removePrefix("www.").ifBlank { siteName.trim() }
}

@Serializable
data class GrokStatus(
    val enabled: Boolean = false,
    val trigger: String = "me",
    @SerialName("groq_enabled") val groqEnabled: Boolean = false,
    @SerialName("key_configured") val keyConfigured: Boolean = false,
    @SerialName("groq_key_configured") val groqKeyConfigured: Boolean = false,
    @SerialName("replies_today") val repliesToday: Int = 0,
    @SerialName("daily_limit") val dailyLimit: Int = 0,
    @SerialName("groq_replies_today") val groqRepliesToday: Int = 0,
    @SerialName("groq_daily_limit") val groqDailyLimit: Int = 0,
    val error: String = "",
)

@Serializable
data class GrokUpdate(
    val enabled: Boolean,
    val trigger: String,
    @SerialName("groq_enabled") val groqEnabled: Boolean? = null,
)

/** GET /api/app/health. `checks` is the server monitor's current look at Google and the VPN. */
@Serializable
data class HealthCheckDto(
    val name: String = "",
    val ok: Boolean = false,
    val detail: String = "",
    @SerialName("down_since_ms") val downSinceMs: Long = 0L,
    val alerted: Boolean = false,
    @SerialName("checked_ms") val checkedMs: Long = 0L,
)

@Serializable
data class HealthResponse(
    val enabled: Boolean = false,
    val checks: List<HealthCheckDto> = emptyList(),
    @SerialName("alert_after_ms") val alertAfterMs: Long = 0L,
)

/**
 * `google` object on GET /api/app/pairing.
 * [phoneResponding] stays null when the field is absent: only an explicit false means the phone is offline.
 */
@Serializable
data class GoogleConnectionDto(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    @SerialName("needs_pairing") val needsPairing: Boolean = false,
    @SerialName("needs_repair") val needsRepair: Boolean = false,
    @SerialName("phone_responding") val phoneResponding: Boolean? = null,
)

@Serializable
data class PairingStateDto(
    val state: String = "",
    @SerialName("emoji") val emoji: String = "",
    @SerialName("message") val message: String = "",
)

/** GET /api/app/pairing. Unknown keys (cookies, timestamps) are ignored. */
@Serializable
data class PairingResponse(
    val google: GoogleConnectionDto? = null,
    val pairing: PairingStateDto? = null,
    @SerialName("auto_repair_enabled") val autoRepairEnabled: Boolean = true,
    @SerialName("session_saved") val sessionSaved: Boolean = false,
    @SerialName("sign_in_needed") val signInNeeded: Boolean = false,
)

/** POST /api/app/google/cookies. Cookie values are never stored or logged on the phone. */
@Serializable
data class GoogleCookiesRequest(
    val cookies: Map<String, String>,
)

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean,
)

@Serializable
data class BlockRequest(
    @SerialName("conversation_id") val conversationId: String,
    val blocked: Boolean,
    val report: Boolean,
)

/** action: resend, download (MMS), or fullsize (image). */
@Serializable
data class MessageActionRequest(
    @SerialName("message_id") val messageId: String,
    val action: String,
)
