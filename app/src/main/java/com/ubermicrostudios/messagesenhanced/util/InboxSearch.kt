package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Search merging from the web app's `runSearch`: local name matches first,
 * then server conversation hits, then message hits. A query shorter than
 * two characters never reaches the server.
 */
object InboxSearch {
    const val MIN_QUERY = 2
    private const val CONVERSATION_CAP = 12

    data class MessageHit(
        val message: MessageDto,
        val title: String,
        val prefix: String,
        val body: String,
    )

    data class Merged(
        val conversations: List<ConversationDto>,
        val messages: List<MessageHit>,
    )

    fun merge(
        query: String,
        loaded: List<ConversationDto>,
        serverConversations: List<ConversationDto>,
        serverMessages: List<MessageDto>,
    ): Merged {
        val q = query.trim()
        val ql = q.lowercase()
        if (ql.length < MIN_QUERY) return Merged(emptyList(), emptyList())
        val byId = LinkedHashMap<String, ConversationDto>()
        val named = ArrayList<ConversationDto>()
        for (conversation in loaded) {
            byId[conversation.conversationId] = conversation
            if (Participants.convName(conversation).lowercase().contains(ql)) named.add(conversation)
        }
        for (conversation in serverConversations) {
            if (named.any { it.conversationId == conversation.conversationId }) {
                if (!byId.containsKey(conversation.conversationId)) byId[conversation.conversationId] = conversation
                continue
            }
            val label = conversation.name.ifBlank { conversation.unifiedName }
            val nameHit = label.lowercase().contains(ql)
            val participantHit = conversation.participants.lowercase().contains(ql)
            if (nameHit || participantHit) named.add(conversation)
            if (!byId.containsKey(conversation.conversationId)) byId[conversation.conversationId] = conversation
        }
        val messages = serverMessages.map { message ->
            val conversation = byId[message.conversationId]
            val title = conversation?.let { Participants.convName(it) }
                ?: message.senderName.ifBlank { "Conversation" }
            val prefix = when {
                message.isFromMe -> "You: "
                conversation?.isGroup == true && message.senderName.isNotBlank() -> "${message.senderName}: "
                else -> ""
            }
            MessageHit(
                message = message,
                title = title,
                prefix = prefix,
                body = aroundMatch(message.body, q),
            )
        }
        return Merged(named.take(CONVERSATION_CAP), messages)
    }

    /** Show the part around the first hit when the match is far into a long message. */
    fun aroundMatch(text: String, query: String): String {
        if (query.isEmpty()) return text
        val lo = text.lowercase()
        val ql = query.lowercase()
        val first = lo.indexOf(ql)
        if (first > 60) {
            val start = (first - 40).coerceAtLeast(0)
            return "…" + text.substring(start)
        }
        return text
    }

    /**
     * How many messages to load so a search hit is on screen.
     * Web: `min(1000, max(80, afterCount + 20))`.
     */
    fun messageWindow(afterCount: Int): Int = min(1000, max(80, afterCount + 20))

    /** Web `dayLabel`: Today, Yesterday, or a long weekday with month and day. */
    fun dayLabel(timestampMs: Long, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        if (timestampMs <= 0L) return ""
        val zone = Calendar.getInstance().timeZone
        val then = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        if (sameDay(then, now)) return "Today"
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DATE, -1) }
        if (sameDay(then, yesterday)) return "Yesterday"
        return SimpleDateFormat("EEEE, MMM d", locale).format(Date(timestampMs))
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
