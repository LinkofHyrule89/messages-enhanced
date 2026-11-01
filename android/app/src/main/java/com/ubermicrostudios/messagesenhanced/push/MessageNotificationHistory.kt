package com.ubermicrostudios.messagesenhanced.push

import android.content.Context
import android.content.SharedPreferences
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.StableIdMap
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * One unread line kept for a conversation notification.
 * Names stay raw; [formatPhoneForDisplay] is applied when the notification is built.
 */
@Serializable
data class NotifLine(
    val senderKey: String = "",
    val senderName: String = "",
    val text: String = "",
    val timestampMs: Long = 0L,
    val fromMe: Boolean = false,
    val avatarUrl: String = "",
)

/** Unread messages still shown for one conversation. Newest conversation is first in the list. */
@Serializable
data class NotifThread(
    val conversationId: String = "",
    val title: String = "",
    val isGroup: Boolean = false,
    val lines: List<NotifLine> = emptyList(),
    val groupAvatarUrl: String = "",
    val source: String = "sms",
    val legacyTag: String = "",
    /** Who @mentioned the owner in the still-shown messages ("" = no mention). */
    val mentionedBy: String = "",
)

/** A push payload reduced to the fields the notification history stores. */
data class IncomingNotification(
    val conversationId: String,
    val title: String,
    val isGroup: Boolean,
    val source: String,
    val line: NotifLine,
    val groupAvatarUrl: String,
    val legacyTag: String,
    val mentionedBy: String = "",
)

/**
 * Pure history and id rules for message notifications.
 * Android notification ids are ints, so a conversation id is mapped into a stable range
 * that stays away from the app-update ids and the summary id.
 */
object MessageNotificationHistory {
    const val MAX_LINES = 10
    const val SELF_KEY = "self"
    const val SELF_NAME = "You"

    fun trim(lines: List<NotifLine>, max: Int = MAX_LINES): List<NotifLine> {
        if (max <= 0 || lines.isEmpty()) return emptyList()
        if (lines.size <= max) return lines
        return lines.takeLast(max)
    }

    /**
     * Adds [line] to [conversationId], keeps the newest [max] lines, and moves that
     * conversation to the front. A conversation that was already a group stays a group.
     */
    fun append(
        threads: List<NotifThread>,
        conversationId: String,
        title: String,
        isGroup: Boolean,
        line: NotifLine,
        groupAvatarUrl: String = "",
        source: String = "",
        legacyTag: String = "",
        mentionedBy: String = "",
        max: Int = MAX_LINES,
    ): List<NotifThread> {
        if (conversationId.isBlank()) return threads
        val existing = threads.firstOrNull { it.conversationId == conversationId }
        val updated = NotifThread(
            conversationId = conversationId,
            title = title.ifBlank { existing?.title.orEmpty() },
            isGroup = (existing?.isGroup == true) || isGroup,
            lines = trim(existing?.lines.orEmpty() + line, max),
            groupAvatarUrl = groupAvatarUrl.ifBlank { existing?.groupAvatarUrl.orEmpty() },
            source = source.ifBlank { existing?.source.orEmpty().ifBlank { "sms" } },
            legacyTag = legacyTag.ifBlank { existing?.legacyTag.orEmpty() },
            mentionedBy = mentionedBy.ifBlank { existing?.mentionedBy.orEmpty() },
        )
        return listOf(updated) + threads.filter { it.conversationId != conversationId }
    }

    /**
     * Appends the user's own reply. The same text and timestamp is ignored, so a
     * WorkManager retry does not duplicate the line.
     */
    fun appendSelf(
        threads: List<NotifThread>,
        conversationId: String,
        text: String,
        timestampMs: Long,
        title: String = "",
        isGroup: Boolean = false,
        selfName: String = SELF_NAME,
        max: Int = MAX_LINES,
    ): List<NotifThread> {
        if (conversationId.isBlank() || text.isBlank()) return threads
        val existing = threads.firstOrNull { it.conversationId == conversationId }
        val last = existing?.lines?.lastOrNull()
        if (last != null && last.fromMe && last.text == text && last.timestampMs == timestampMs) {
            return threads
        }
        return append(
            threads = threads,
            conversationId = conversationId,
            title = title.ifBlank { existing?.title.orEmpty() },
            isGroup = isGroup || existing?.isGroup == true,
            line = NotifLine(
                senderKey = SELF_KEY,
                senderName = selfName,
                text = text,
                timestampMs = timestampMs,
                fromMe = true,
            ),
            max = max,
        )
    }

    fun clear(threads: List<NotifThread>, conversationId: String): List<NotifThread> {
        if (conversationId.isBlank()) return threads
        return threads.filter { it.conversationId != conversationId }
    }

    /** One InboxStyle row: conversation, and the sender when the chat is a group or the line is ours. */
    fun summaryLine(thread: NotifThread, country: String? = null, selfName: String = SELF_NAME): String {
        fun show(raw: String): String {
            val formatted = if (country == null) formatPhoneForDisplay(raw) else formatPhoneForDisplay(raw, country)
            return formatted.ifBlank { raw }
        }
        val title = show(thread.title).ifBlank { thread.conversationId }
        val last = thread.lines.lastOrNull() ?: return title
        if (!thread.isGroup && !last.fromMe) return "$title: ${last.text}"
        val who = if (last.fromMe) selfName else show(last.senderName).ifBlank { title }
        return "$title: $who: ${last.text}"
    }
}

object MessageNotificationIds {
    const val GROUP_KEY = "me_messages"
    const val SUMMARY_ID = 9001
    const val FOREGROUND_ID = 9002

    const val SLOT_CONTENT = 1
    const val SLOT_REPLY = 2
    const val SLOT_READ = 3
    const val SLOT_DELETE = 4
    const val SLOT_ERROR = 5
    const val SLOT_SUMMARY = 6
    const val SLOT_RETRY = 7

    const val RANGE_START = 20_000
    const val RANGE_SPAN = 1_000_003

    private const val REQUEST_START = 1
    private const val REQUEST_SPAN = Int.MAX_VALUE - 1
    private const val PREFS = "me_native_notif_ids"
    private const val KEY_NOTIF = "notif"
    private const val KEY_REQUEST = "request"

    private val lock = Any()
    private val notifIds = StableIdMap(::isReserved, RANGE_START, RANGE_SPAN)
    private val requestIds = StableIdMap({ it == 0 }, REQUEST_START, REQUEST_SPAN)

    @Volatile
    private var persisted: SharedPreferences? = null

    fun bindPersistence(prefs: SharedPreferences) {
        synchronized(lock) {
            if (persisted != null) return
            persisted = prefs
            notifIds.restore(decodeMap(prefs.getString(KEY_NOTIF, null)))
            requestIds.restore(decodeMap(prefs.getString(KEY_REQUEST, null)))
        }
    }

    fun bindPersistence(context: Context) {
        bindPersistence(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        )
    }

    fun notificationId(conversationId: String): Int = allocateNotif("n:$conversationId")

    fun replyErrorId(conversationId: String): Int {
        val error = allocateNotif("e:$conversationId")
        return if (error == notificationId(conversationId)) allocateNotif("e2:$conversationId") else error
    }

    /** Stable pending-intent request code. Different slots for one conversation do not collide. */
    fun requestCode(conversationId: String, slot: Int): Int {
        val id = requestIds.get("$slot\u001f$conversationId")
        persist()
        return id
    }

    fun isReserved(id: Int): Boolean =
        id == 0 || id == 1 || id == SUMMARY_ID || id == FOREGROUND_ID || id in 7101..7105

    internal fun resetForTests() {
        synchronized(lock) {
            notifIds.clear()
            requestIds.clear()
            persisted = null
        }
    }

    private fun allocateNotif(key: String): Int {
        val id = notifIds.get(key)
        persist()
        return id
    }

    private fun persist() {
        val prefs = persisted ?: return
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_NOTIF, encodeMap(notifIds.snapshot()))
                .putString(KEY_REQUEST, encodeMap(requestIds.snapshot()))
                .apply()
        }
    }

    private fun encodeMap(map: Map<String, Int>): String =
        JsonConfig.json.encodeToString(
            MapSerializer(String.serializer(), Int.serializer()),
            map,
        )

    private fun decodeMap(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            JsonConfig.json.decodeFromString(
                MapSerializer(String.serializer(), Int.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }
}

object MessageNotificationPayload {
    fun conversationKey(data: Map<String, String>): String =
        first(data, "conv", "conversation_id", "tag")

    /**
     * Explicit group flag wins. Otherwise a title that differs from the sender is a group,
     * which is how a group name and a member name arrive in the same push.
     */
    fun isGroupConversation(flag: String, title: String, person: String): Boolean {
        when (flag.trim().lowercase()) {
            "1", "true", "yes" -> return true
            "0", "false", "no" -> return false
        }
        return title.isNotBlank() && person.isNotBlank() && !title.equals(person, ignoreCase = true)
    }

    fun parse(
        data: Map<String, String>,
        nowMs: Long,
        origin: String,
        hideBody: Boolean,
        hiddenBody: String,
    ): IncomingNotification? {
        val conversationId = conversationKey(data)
        if (conversationId.isBlank()) return null
        val fullTitle = first(data, "title")
        // Hidden-text pushes leave out "person"; the server title is still "Sender · Group".
        val personRaw = first(data, "person", "sender_name", "sender").ifBlank {
            if (fullTitle.contains(" · ")) fullTitle.substringBefore(" · ") else ""
        }
        val mentions = when (first(data, "mentions_me", "mentionsMe").trim().lowercase()) {
            "1", "true", "yes" -> true
            else -> false
        }
        val titleRaw = first(data, "title").ifBlank { personRaw }.let { t ->
            // Server group titles are "Sender · Group"; the conversation title is the group.
            val prefix = "$personRaw · "
            if (personRaw.isNotBlank() && t.startsWith(prefix) && t.length > prefix.length) t.removePrefix(prefix) else t
        }
        val placeholder = hiddenBody.ifBlank { "New message" }
        val rawBody = first(data, "body", "text", "message")
        val mentionedBy = if (mentions) personRaw.ifBlank { titleRaw }.ifBlank { "Someone" } else ""
        val text = when {
            hideBody && mentionedBy.isNotEmpty() -> mentionTitle(mentionedBy)
            hideBody -> placeholder
            rawBody.isBlank() -> placeholder
            else -> rawBody
        }
        val timestamp = first(data, "ts", "timestamp").toLongOrNull()?.takeIf { it > 0L } ?: nowMs
        val source = first(data, "source", "source_platform").ifBlank { "sms" }
        val group = isGroupConversation(first(data, "group", "is_group", "isGroup"), titleRaw, personRaw)
        val phone = first(data, "phone", "sender_number", "number")
        val participantId = first(data, "participant_id", "participantId")
        val contactId = first(data, "contact_id", "contactId")
        val avatar = senderAvatarUrl(
            origin = origin,
            source = source,
            direct = first(data, "avatar", "avatar_url", "icon"),
            participantId = participantId,
            contactId = contactId,
            phone = phone,
        )
        val senderKey = first(data, "participant_id", "participantId", "sender", "phone", "sender_number")
            .ifBlank { personRaw }
            .ifBlank { conversationId }
        return IncomingNotification(
            conversationId = conversationId,
            title = titleRaw,
            isGroup = group,
            source = source,
            line = NotifLine(
                senderKey = senderKey,
                senderName = personRaw.ifBlank { titleRaw },
                text = text,
                timestampMs = timestamp,
                fromMe = false,
                avatarUrl = avatar,
            ),
            groupAvatarUrl = if (group) groupAvatarUrl(origin, conversationId, source) else "",
            legacyTag = first(data, "tag"),
            mentionedBy = mentionedBy,
        )
    }

    fun mentionTitle(sender: String): String = "$sender mentioned you"

    /** Explicit http(s) or absolute path wins. Otherwise the sms `/api/avatar` URL the rest of the app uses. */
    fun senderAvatarUrl(
        origin: String,
        source: String,
        direct: String,
        participantId: String,
        contactId: String,
        phone: String,
    ): String {
        absolute(origin, direct)?.let { return it }
        if (source.trim().lowercase() != "sms") return ""
        return Participants.avatarUrl(
            origin = origin,
            participantId = participantId,
            contactId = contactId,
            phone = phone,
            source = "sms",
        ).orEmpty()
    }

    fun groupAvatarUrl(origin: String, conversationId: String, source: String): String {
        if (conversationId.isBlank() || source.trim().lowercase() != "sms") return ""
        return Participants.avatarUrl(origin, participantId = "conv:$conversationId", source = "sms").orEmpty()
    }

    private fun absolute(origin: String, direct: String): String? {
        val value = direct.trim()
        if (value.isBlank()) return null
        if (value.startsWith("https://") || value.startsWith("http://")) return value
        if (!value.startsWith("/")) return null
        val base = origin.trim().trimEnd('/')
        if (base.isBlank()) return null
        return base + value
    }

    private fun first(data: Map<String, String>, vararg keys: String): String {
        for (key in keys) {
            val value = data[key]?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return ""
    }
}
