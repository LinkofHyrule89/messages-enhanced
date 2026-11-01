package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.view.View
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadMenu
import com.ubermicrostudios.messagesenhanced.util.ConversationMenu
import com.ubermicrostudios.messagesenhanced.util.Participants

/** Long-press menu for a conversation row. Same popup as the thread ⋮ menu. */
object ConversationRowMenu {
    fun show(anchor: View, conversation: ConversationDto, actions: Actions) {
        val context = anchor.context
        ThreadMenu.showPopup(anchor, Participants.displayName(conversation)) {
            for (entry in ConversationMenu.entries(conversation)) {
                when (entry) {
                    ConversationMenu.Entry.DetailsGroup ->
                        row(R.drawable.ic_info, context.getString(R.string.menu_group_details)) { actions.details() }
                    ConversationMenu.Entry.DetailsContact ->
                        row(R.drawable.ic_info, context.getString(R.string.menu_contact_details)) { actions.details() }
                    ConversationMenu.Entry.Refresh ->
                        row(
                            R.drawable.ic_refresh,
                            context.getString(R.string.menu_refresh),
                            context.getString(R.string.menu_refresh_note),
                        ) { actions.refresh() }
                    is ConversationMenu.Entry.Pin -> {
                        val label = context.getString(
                            if (entry.unpin) R.string.unpin_conversation else R.string.pin_conversation,
                        )
                        val note = if (entry.note) context.getString(R.string.menu_pin_note) else ""
                        row(R.drawable.ic_pin, label, note) { actions.pin() }
                    }
                    ConversationMenu.Entry.Archive ->
                        row(R.drawable.ic_archive, context.getString(R.string.menu_archive)) { actions.archive() }
                    ConversationMenu.Entry.Trash ->
                        row(R.drawable.ic_trash, context.getString(R.string.menu_trash), danger = true) { actions.trash() }
                }
            }
        }
    }

    data class Actions(
        val details: () -> Unit,
        val refresh: () -> Unit,
        val pin: () -> Unit,
        val archive: () -> Unit,
        val trash: () -> Unit,
    )
}
