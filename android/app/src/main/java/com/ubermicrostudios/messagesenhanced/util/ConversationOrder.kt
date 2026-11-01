package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto

/**
 * Conversation list order from the web app: pinned rows first (phone pin or a
 * local pin), each group by latest message. Section titles match `renderConversations`.
 */
object ConversationOrder {
    fun isPinned(conversation: ConversationDto): Boolean =
        conversation.googlePinned || conversation.localPinnedAtMs > 0L

    fun rows(conversations: List<ConversationDto>): List<ConvListRow> {
        val pinned = conversations.filter { isPinned(it) }.sortedByDescending { it.lastMessageTs }
        val rest = conversations.filter { !isPinned(it) }.sortedByDescending { it.lastMessageTs }
        val out = ArrayList<ConvListRow>(conversations.size + 2)
        if (pinned.isNotEmpty()) {
            out.add(ConvListRow.Header("Pinned", pinned = true))
            pinned.forEach { out.add(ConvListRow.Item(it)) }
            if (rest.isNotEmpty()) out.add(ConvListRow.Header("Other conversations", pinned = false))
        }
        rest.forEach { out.add(ConvListRow.Item(it)) }
        return out
    }
}

sealed class ConvListRow {
    data class Header(val title: String, val pinned: Boolean) : ConvListRow()
    data class Item(val conversation: ConversationDto) : ConvListRow()
}
