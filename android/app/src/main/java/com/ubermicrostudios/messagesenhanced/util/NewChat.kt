package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ContactsResponse

/**
 * Start-chat panel rules from the web app (`openNewChat` / `renderNewChat`).
 */
object NewChat {
    fun digitsOf(value: String): String = value.filter { it.isDigit() }

    fun looksLikeAddress(query: String): Boolean {
        val q = query.trim()
        if (q.matches(Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$"""))) return true
        return q.matches(Regex("""^\+?[\d\s().-]+$""")) && digitsOf(q).length >= 3
    }

    fun contactKey(number: String): String {
        val digits = digitsOf(number)
        return if (digits.length >= 10) digits.takeLast(10) else digits.ifBlank { number.trim().lowercase() }
    }

    fun matches(contact: ContactDto, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val ql = q.lowercase()
        val qd = digitsOf(q)
        if (contact.displayName.lowercase().contains(ql)) return true
        return qd.length >= 3 && digitsOf(contact.phone).contains(qd)
    }

    fun letterOf(contact: ContactDto): String {
        val ch = contact.displayName.ifBlank { contact.phone }.ifBlank { "#" }.trim().firstOrNull() ?: return "#"
        return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
    }

    fun isChip(contact: ContactDto, chips: List<ContactDto>): Boolean {
        val key = contactKey(contact.phone)
        return chips.any { contactKey(it.phone) == key }
    }

    sealed class Row {
        data class Section(val title: String) : Row()
        data class Letter(val letter: String) : Row()
        data class SendTo(val query: String, val group: Boolean) : Row()
        data class Contact(val contact: ContactDto, val selected: Boolean) : Row()
        data class Empty(val text: String) : Row()
    }

    fun rows(
        query: String,
        data: ContactsResponse?,
        group: Boolean,
        chips: List<ContactDto>,
        loading: Boolean,
        error: String,
    ): List<Row> {
        val q = query.trim()
        val out = ArrayList<Row>()
        if (q.isNotEmpty()) {
            if (looksLikeAddress(q)) out.add(Row.SendTo(q, group))
            val matches = (data?.all ?: emptyList()).filter { matches(it, q) }.take(60)
            matches.forEach { out.add(Row.Contact(it, group && isChip(it, chips))) }
            if (matches.isEmpty() && !looksLikeAddress(q)) {
                out.add(Row.Empty(if (loading) "Loading contacts…" else "No matching contacts"))
            }
            return out
        }
        if (loading && data == null) {
            out.add(Row.Empty("Loading contacts…"))
            return out
        }
        if (error.isNotBlank() && data == null) {
            out.add(Row.Empty("Couldn't load contacts: $error"))
            return out
        }
        val body = data ?: ContactsResponse()
        if (body.top.isNotEmpty()) {
            out.add(Row.Section("Top contacts"))
            body.top.forEach { out.add(Row.Contact(it, group && isChip(it, chips))) }
        }
        out.add(Row.Section("All contacts"))
        if (body.all.isEmpty()) {
            out.add(Row.Empty("No contacts yet. Type a phone number above."))
            return out
        }
        var last = ""
        body.all.forEach { contact ->
            val letter = letterOf(contact)
            if (letter != last) {
                out.add(Row.Letter(letter))
                last = letter
            }
            out.add(Row.Contact(contact, group && isChip(contact, chips)))
        }
        return out
    }
}
