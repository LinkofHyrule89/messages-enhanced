package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto

/**
 * Tablet cold start puts the last opened conversation back in the split pane.
 * Phones, restored activities, and notification opens do not. The conversation
 * has to be in the inbox cache so the pane can paint before the network refresh.
 */
object LastThreadRestore {
    fun decide(
        twoPane: Boolean,
        hasSavedState: Boolean,
        openingFromIntent: Boolean,
        lastId: String?,
        cached: List<ConversationDto>?,
    ): Decision {
        if (!twoPane || hasSavedState || openingFromIntent) return Decision.Skip
        val id = lastId?.trim().orEmpty()
        if (id.isBlank()) return Decision.Skip
        if (cached == null) return Decision.WaitForCache
        val conversation = cached.firstOrNull { it.conversationId == id } ?: return Decision.Skip
        return Decision.Open(conversation)
    }

    sealed interface Decision {
        data object Skip : Decision
        /** Inbox cache has not been read yet. */
        data object WaitForCache : Decision
        data class Open(val conversation: ConversationDto) : Decision
    }
}
