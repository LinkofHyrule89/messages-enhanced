package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto

/**
 * Default inbox, matching the web app's load of `GET /api/conversations?limit=100`:
 * drop `tab == "archive"` and `tab == "spam"` (spam/blocked). Trash is removed on
 * the server; a `trash` tab is excluded too so a trashed row cannot linger in the list.
 * Custom tabs stay, the same as the web app's unfiltered Recent list.
 */
object InboxFilter {
    val hiddenTabs = setOf("archive", "spam", "trash")

    fun isInbox(conversation: ConversationDto): Boolean = isInboxTab(conversation.tab)

    fun isInboxTab(tab: String): Boolean = tab.trim().lowercase() !in hiddenTabs

    fun inbox(conversations: List<ConversationDto>): List<ConversationDto> =
        conversations.filter(::isInbox)
}
