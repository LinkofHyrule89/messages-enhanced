package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto

/**
 * Group / contact details sheet, matching web `openDetails` / `currentMembers`.
 */
object GroupDetails {
    data class Member(
        val name: String,
        val number: String,
        val me: Boolean,
        val person: ParticipantDto,
    )

    data class Content(
        val title: String,
        val name: String,
        val subtitle: String,
        val showMembers: Boolean,
        val members: List<Member>,
        val empty: String?,
    )

    fun content(
        isGroup: Boolean,
        title: String,
        people: List<ParticipantDto>,
        selfFallback: String = "",
    ): Content {
        val others = people.filter { !it.me }
        val current = others.filter { !it.hidden }.ifEmpty { others }
        val me = people.firstOrNull { it.me }
        val subtitle = if (isGroup) {
            if (people.isNotEmpty()) "${current.size + 1} people, including you" else "Group conversation"
        } else {
            val number = current.firstOrNull()?.number?.ifBlank { current.firstOrNull()?.phone }.orEmpty()
            when {
                number.isNotBlank() && number != title -> number
                number.isNotBlank() -> ""
                else -> "No number available"
            }
        }
        val members = if (!isGroup) {
            emptyList()
        } else {
            val sorted = current.sortedBy { it.name.ifBlank { it.number }.lowercase() }.toMutableList()
            if (me != null) sorted.add(0, me) else sorted.add(0, ParticipantDto(isMe = true))
            sorted.map { person ->
                val mine = person.me
                val myName = if (mine) person.name.ifBlank { person.firstName }.ifBlank { selfFallback } else ""
                val name = when {
                    mine && myName.isNotBlank() -> "$myName (you)"
                    mine -> "You"
                    else -> person.name.ifBlank { person.number }.ifBlank { person.phone }.ifBlank { "Unknown" }
                }
                val number = person.number.ifBlank { person.phone }
                Member(name = name, number = if (number != name) number else "", me = mine, person = person)
            }
        }
        val empty = if (isGroup && current.isEmpty()) {
            "Member list isn't available for this conversation."
        } else {
            null
        }
        return Content(
            title = if (isGroup) "Group details" else "Contact details",
            name = title,
            subtitle = subtitle,
            showMembers = isGroup,
            members = members,
            empty = empty,
        )
    }
}
