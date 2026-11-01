package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Rows for the message Details screen.
 * [MessageDto] only has one timestamp, no media-size field, and no C2PA / content-credentials field.
 * Missing values are omitted rather than filled in.
 */
object MessageDetails {
    data class Row(val label: String, val value: String)

    /**
     * Google Messages status time: "Today, 9:05 PM", "Yesterday, 9:05 PM",
     * or a date plus the clock. The clock uses a normal space before AM/PM.
     */
    fun whenLabel(
        timestampMs: Long,
        nowMs: Long,
        zone: TimeZone,
        locale: Locale,
    ): String {
        if (timestampMs <= 0L) return ""
        val then = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val clock = SimpleDateFormat("h:mm a", locale).apply { timeZone = zone }.format(Date(timestampMs))
            .replace('\u202f', ' ')
            .replace('\u00a0', ' ')
        val day = when {
            sameDay(then, now) -> "Today"
            sameDay(then, (now.clone() as Calendar).apply { add(Calendar.DATE, -1) }) -> "Yesterday"
            then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                SimpleDateFormat("MMM d", locale).apply { timeZone = zone }.format(Date(timestampMs))
            else ->
                SimpleDateFormat("MMM d, yyyy", locale).apply { timeZone = zone }.format(Date(timestampMs))
        }
        return "$day, $clock"
    }

    /**
     * Received when the message is incoming, Sent when it is outgoing.
     * Explicit [sentAtMs] / [receivedAtMs] win when the caller actually has them.
     * A zero timestamp is omitted.
     */
    fun statusRows(
        fromMe: Boolean,
        timestampMs: Long,
        nowMs: Long,
        zone: TimeZone,
        locale: Locale,
        sentAtMs: Long = 0L,
        receivedAtMs: Long = 0L,
    ): List<Row> {
        var sent = sentAtMs.takeIf { it > 0L }
        var received = receivedAtMs.takeIf { it > 0L }
        if (sent == null && received == null && timestampMs > 0L) {
            if (fromMe) sent = timestampMs else received = timestampMs
        }
        val rows = ArrayList<Row>()
        if (received != null) {
            val label = whenLabel(received, nowMs, zone, locale)
            if (label.isNotBlank()) rows += Row("Received", label)
        }
        if (sent != null) {
            val label = whenLabel(sent, nowMs, zone, locale)
            if (label.isNotBlank()) rows += Row("Sent", label)
        }
        return rows
    }

    /**
     * The four Google Messages type labels, from the conversation protocol and the message platform.
     * Anything else (a blank protocol, or a platform this app does not label) is omitted.
     */
    fun transportLabel(
        sourcePlatform: String,
        protocol: String,
        encrypted: Boolean,
        hasMedia: Boolean,
    ): String? {
        val platform = sourcePlatform.trim().lowercase(Locale.US)
        val upper = protocol.trim().uppercase(Locale.US)
        if (upper.contains("UNKNOWN")) return null
        // WhatsApp, iMessage, and other apps are not one of the four Google Messages types.
        if (platform.isNotEmpty() && platform != "sms" && platform != "mms" && platform != "rcs") return null
        val e2ee = encrypted || upper.contains("E2EE") || upper.contains("ENCRYPT")
        val rcs = platform == "rcs" ||
            upper == "RCS" ||
            upper.startsWith("RCS ") ||
            upper.startsWith("RCS·") ||
            upper.startsWith("RCS-")
        if (rcs) {
            return if (e2ee) "End-to-End Encrypted RCS message" else "RCS message"
        }
        val sms = platform == "sms" || platform == "mms" || upper == "SMS" || upper == "MMS"
        if (!sms) return null
        if (platform == "mms" || upper == "MMS" || hasMedia) return "MMS"
        return "SMS"
    }

    fun hasTransportMedia(message: MessageDto): Boolean =
        message.mediaId.isNotBlank() || message.mimeType.isNotBlank()

    /** MessagesApi's message payload has no media-size field. */
    fun mediaSizeBytes(message: MessageDto): Long? = null

    /** MessagesApi's message payload has no C2PA / content-credentials field. */
    fun contentCredentials(message: MessageDto): String? = null

    /**
     * Media-details rows in Google's order. [formatSize] is
     * [android.text.format.Formatter.formatShortFileSize] at the call site.
     * A null size or blank credentials row is left out.
     */
    fun mediaRows(
        type: String?,
        sizeBytes: Long?,
        contentCredentials: String?,
        formatSize: (Long) -> String,
    ): List<Row> {
        val rows = ArrayList<Row>()
        val credentials = contentCredentials?.trim().orEmpty()
        if (credentials.isNotEmpty()) rows += Row("Content Credentials", credentials)
        if (!type.isNullOrBlank()) rows += Row("Type", type)
        if (sizeBytes != null && sizeBytes > 0L) {
            val size = formatSize(sizeBytes).trim()
            if (size.isNotEmpty()) rows += Row("Size", size)
        }
        return rows
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
