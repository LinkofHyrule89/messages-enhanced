package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto

/**
 * Rows for the conversation-list long-press menu, in the web `openConvMenu` order
 * (details, refresh, pin, archive, trash). The name is the popup header, not a row.
 *
 * Refresh, Archive, and Move to trash are Google Messages only: a blank platform
 * counts as `sms`, matching web `sourcePlatformOf`. A phone pin hides Pin.
 */
object ConversationMenu {
    sealed interface Entry {
        data object DetailsGroup : Entry
        data object DetailsContact : Entry
        data object Refresh : Entry
        data class Pin(val unpin: Boolean, val note: Boolean) : Entry
        data object Archive : Entry
        data object Trash : Entry
    }

    fun entries(conversation: ConversationDto): List<Entry> {
        val google = isGoogle(conversation.sourcePlatform)
        val rows = ArrayList<Entry>(6)
        rows.add(if (conversation.isGroup) Entry.DetailsGroup else Entry.DetailsContact)
        if (google) rows.add(Entry.Refresh)
        if (!conversation.googlePinned) {
            val unpin = conversation.localPinnedAtMs > 0L
            rows.add(Entry.Pin(unpin = unpin, note = !unpin))
        }
        if (google) {
            rows.add(Entry.Archive)
            rows.add(Entry.Trash)
        }
        return rows
    }

    fun isGoogle(sourcePlatform: String): Boolean =
        sourcePlatform.trim().lowercase().ifBlank { "sms" } == "sms"
}
