package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto

/**
 * Rows for the Share screen: five recent conversations, then contacts grouped A to Z.
 * Filtering applies to both lists. A non-empty query shows every recent match.
 */
object ShareIndex {
    const val RECENT_CAP = 5

    data class ContactGroup(val letter: String, val contacts: List<ContactDto>)

    sealed class Row {
        data class Section(val title: String) : Row()
        data class Conversation(
            val conversation: ConversationDto,
            val preview: String,
            val selected: Boolean,
            val first: Boolean,
            val last: Boolean,
        ) : Row()
        data object More : Row()
        data class Contact(
            val contact: ContactDto,
            val selected: Boolean,
            val first: Boolean,
            val last: Boolean,
        ) : Row()
        data class Empty(val text: String) : Row()
    }

    fun letterOf(contact: ContactDto): String {
        val ch = contact.displayName.trim().firstOrNull() ?: return "#"
        return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
    }

    fun contactKey(contact: ContactDto): String {
        val phone = contact.phone
        if (phone.isNotBlank()) return NewChat.contactKey(phone)
        return "name:" + contact.displayName.trim().lowercase()
    }

    fun previewLine(conversation: ConversationDto): String {
        val preview = conversation.lastMessagePreview.trim()
        if (preview.isEmpty()) return ""
        if (conversation.lastFromMe && !preview.startsWith("You:")) return "You: $preview"
        return preview
    }

    fun matchesConversation(conversation: ConversationDto, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val folded = q.lowercase()
        if (Participants.convName(conversation).lowercase().contains(folded)) return true
        if (conversation.lastMessagePreview.lowercase().contains(folded)) return true
        val digits = NewChat.digitsOf(q)
        if (digits.length >= 3 && NewChat.digitsOf(Participants.convName(conversation)).contains(digits)) return true
        return false
    }

    /** A to Z, with non-letters under "#" after Z. [query] uses the new-chat name and number match. */
    fun contactGroups(contacts: List<ContactDto>, query: String): List<ContactGroup> {
        val grouped = contacts.filter { NewChat.matches(it, query) }.groupBy { letterOf(it) }
        val letters = grouped.keys.sortedWith(compareBy<String> { it == "#" }.thenBy { it })
        return letters.map { letter ->
            ContactGroup(
                letter = letter,
                contacts = grouped.getValue(letter).sortedBy { it.displayName.lowercase() },
            )
        }
    }

    fun visibleRecent(
        conversations: List<ConversationDto>,
        query: String,
        expanded: Boolean,
    ): List<ConversationDto> {
        val matched = conversations
            .asSequence()
            .filter { it.conversationId.isNotBlank() && matchesConversation(it, query) }
            .sortedByDescending { it.lastMessageTs }
            .toList()
        if (query.isNotBlank() || expanded) return matched
        return matched.take(RECENT_CAP)
    }

    /**
     * Phone numbers for one new group: selected contacts, plus the other person in a selected 1:1 chat.
     * Same number twice is kept once, compared on the last 10 digits.
     */
    fun groupNumbers(contacts: List<ContactDto>, conversations: List<ConversationDto>): List<String> {
        val raw = ArrayList<String>()
        for (contact in contacts) {
            if (contact.phone.isNotBlank()) raw.add(contact.phone)
        }
        for (conversation in conversations) {
            if (conversation.isGroup) continue
            val people = Participants.parse(conversation.participants)
            val person = people.firstOrNull { !it.me } ?: people.firstOrNull() ?: continue
            val number = person.number.ifBlank { person.phone }
            if (number.isNotBlank()) raw.add(number)
        }
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (number in raw) {
            val key = NewChat.contactKey(number)
            if (key.isBlank() || !seen.add(key)) continue
            out.add(number)
        }
        return out
    }

    fun rows(
        conversations: List<ConversationDto>,
        contacts: List<ContactDto>,
        query: String,
        expanded: Boolean,
        selectedConversationIds: Set<String>,
        selectedContactKeys: Set<String>,
        recentTitle: String,
        emptyText: String,
    ): List<Row> {
        val out = ArrayList<Row>()
        val recentAll = conversations
            .asSequence()
            .filter { it.conversationId.isNotBlank() && matchesConversation(it, query) }
            .sortedByDescending { it.lastMessageTs }
            .toList()
        val recent = if (query.isNotBlank() || expanded) recentAll else recentAll.take(RECENT_CAP)
        if (recent.isNotEmpty()) {
            out.add(Row.Section(recentTitle))
            recent.forEachIndexed { index, conversation ->
                out.add(
                    Row.Conversation(
                        conversation = conversation,
                        preview = previewLine(conversation),
                        selected = conversation.conversationId in selectedConversationIds,
                        first = index == 0,
                        last = index == recent.lastIndex,
                    ),
                )
            }
            if (recent.size < recentAll.size) out.add(Row.More)
        }
        val groups = contactGroups(contacts, query)
        for (group in groups) {
            out.add(Row.Section(group.letter))
            group.contacts.forEachIndexed { index, contact ->
                out.add(
                    Row.Contact(
                        contact = contact,
                        selected = contactKey(contact) in selectedContactKeys,
                        first = index == 0,
                        last = index == group.contacts.lastIndex,
                    ),
                )
            }
        }
        if (out.isEmpty()) out.add(Row.Empty(emptyText))
        return out
    }
}
