package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto
import java.net.URLEncoder

data class AvatarRequest(val url: String, val group: Boolean)

/**
 * Avatar URLs match the web app: `GET /api/avatar?v=2&source=sms&participant_id=&contact_id=&phone=`
 * for Google Messages (sms) only. Groups try `participant_id=conv:<id>` first, then member photos.
 */
object Participants {
    private const val memberCap = 5

    fun parse(raw: String): List<ParticipantDto> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            JsonConfig.json.decodeFromString<List<ParticipantDto>>(raw)
        }.getOrDefault(emptyList())
    }

    fun sourcePlatform(conversation: ConversationDto): String =
        conversation.sourcePlatform.trim().lowercase().ifBlank { "sms" }

    fun displayName(conversation: ConversationDto): String =
        conversation.unifiedName.ifBlank { conversation.name }.ifBlank { "Conversation" }

    /** Current non-me members. If every other entry is hidden, keep them all. */
    fun otherMembers(conversation: ConversationDto): List<ParticipantDto> {
        val people = parse(conversation.participants)
        val others = people.filter { !it.me }
        val current = others.filter { !it.hidden }
        return if (current.isNotEmpty()) current else others
    }

    fun avatarRequests(origin: String, conversation: ConversationDto): List<AvatarRequest> {
        if (sourcePlatform(conversation) != "sms") return emptyList()
        val base = origin.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val all = parse(conversation.participants)
        val others = if (conversation.isGroup) {
            otherMembers(conversation)
        } else {
            all.filter { !it.me }.ifEmpty { all }
        }
        val out = ArrayList<AvatarRequest>()
        if (conversation.isGroup && conversation.conversationId.isNotBlank()) {
            avatarUrl(base, participantId = "conv:${conversation.conversationId}")?.let {
                out.add(AvatarRequest(it, group = true))
            }
        }
        for (person in others) {
            if (out.size >= 1 + memberCap) break
            val url = avatarUrl(
                base,
                participantId = person.participantKey(),
                contactId = person.contactKey(),
                phone = person.phoneKey(),
            ) ?: continue
            if (out.any { it.url == url }) continue
            out.add(AvatarRequest(url, group = false))
        }
        if (!conversation.isGroup) {
            return out.take(1)
        }
        return out
    }

    fun avatarUrl(
        origin: String,
        participantId: String = "",
        contactId: String = "",
        phone: String = "",
        source: String = "sms",
    ): String? {
        if (participantId.isBlank() && contactId.isBlank() && phone.isBlank()) return null
        val q = StringBuilder()
        q.append(origin.trim().trimEnd('/'))
        q.append("/api/avatar?v=2&source=").append(enc(source))
        if (participantId.isNotBlank()) q.append("&participant_id=").append(enc(participantId))
        if (contactId.isNotBlank()) q.append("&contact_id=").append(enc(contactId))
        if (phone.isNotBlank()) q.append("&phone=").append(enc(phone))
        return q.toString()
    }

    /** Same hue as the web app's `hue()` (`hsl(h 45% 38%)`). */
    fun hue(seed: String): Int {
        var h = 0
        for (ch in seed) {
            h = ((h.toLong() * 31 + ch.code) % 360).toInt()
        }
        return h
    }

    fun avatarColor(seed: String): Int = hsl(hue(seed), 0.45f, 0.38f)

    /** Light fallback palette for sender names when the phone sent no color. */
    private val SENDER_PALETTE = intArrayOf(
        0xFFF28B82.toInt(), 0xFF81C995.toInt(), 0xFFFDD663.toInt(), 0xFF8AB4F8.toInt(),
        0xFFC58AF9.toInt(), 0xFFFCAD70.toInt(), 0xFFFF8BCB.toInt(), 0xFF78D9EC.toInt(),
    )

    /** The phone's participant color, parsed, or null. */
    fun phoneColor(person: ParticipantDto?): Int? {
        val c = person?.color?.trim().orEmpty()
        if (!Regex("^#[0-9A-Fa-f]{6}$").matches(c)) return null
        return (0xFF000000 or c.substring(1).toLong(16)).toInt()
    }

    /**
     * Group sender-name color, as Google Messages does it: each participant keeps a
     * color assigned on the phone (its avatar color); we use that. Without one, a
     * stable pick from a palette by number/name so names still differ.
     */
    fun senderColor(person: ParticipantDto?, seed: String): Int {
        phoneColor(person)?.let { return it }
        var h = 0
        for (ch in seed) h = h * 31 + ch.code
        return SENDER_PALETTE[Math.floorMod(h, SENDER_PALETTE.size)]
    }

    /** Display name the web search box uses: unified name, then Name, then the id. */
    fun convName(conversation: ConversationDto): String =
        conversation.unifiedName.ifBlank { conversation.name }.ifBlank { conversation.conversationId }

    /**
     * The signed-in person, from any conversation participant flagged `is_me`
     * that has an id or a number. Same walk the web header uses.
     */
    fun self(conversations: List<ConversationDto>): ParticipantDto? {
        for (conversation in conversations) {
            for (person in parse(conversation.participants)) {
                if (!person.me) continue
                val id = person.id.ifBlank { person.participantId }.ifBlank { person.participantIdCamel }
                val number = person.number.ifBlank { person.phone }
                if (id.isNotBlank() || number.isNotBlank()) return person
            }
        }
        return null
    }

    fun profileName(person: ParticipantDto?): String {
        if (person == null) return "Me"
        return person.firstName.ifBlank { person.name }.ifBlank { "Me" }
    }

    /** First character of the web `initials()` string (the profile button shows only that). */
    fun profileInitial(name: String): String {
        val cleaned = name.map { ch -> if (ch.isLetterOrDigit() || ch.isWhitespace()) ch else ' ' }
            .joinToString("")
            .trim()
        val parts = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val first = parts.firstOrNull()?.firstOrNull { it.isLetterOrDigit() } ?: return "?"
        return first.uppercaseChar().toString()
    }

    fun profileSeed(person: ParticipantDto?, name: String): String {
        val number = person?.number?.ifBlank { person.phone }.orEmpty()
        return number.ifBlank { name }
    }

    fun profilePhotoUrl(origin: String, hash: String): String? {
        if (hash.isBlank()) return null
        val base = origin.trim().trimEnd('/')
        if (base.isBlank()) return null
        return "$base/api/app/profile-photo?h=${enc(hash)}"
    }

    fun selfAvatarUrl(origin: String, person: ParticipantDto): String? = personAvatarUrl(origin, person)

    fun personAvatarUrl(origin: String, person: ParticipantDto): String? = avatarUrl(
        origin,
        participantId = person.id.ifBlank { person.participantId }.ifBlank { person.participantIdCamel },
        contactId = person.contactId.ifBlank { person.contactIdCamel },
        phone = person.number.ifBlank { person.phone },
    )

    /**
     * The group member Google means by a "Read by" name: first name, full name,
     * first word of the name, then the number. Same walk as web `memberByName`.
     */
    fun memberByName(conversation: ConversationDto, name: String): ParticipantDto? {
        val others = parse(conversation.participants).filter { !it.me }
        val lo = name.trim().lowercase()
        val num = name.filter { it.isDigit() }
        val tests = listOf<(ParticipantDto) -> Boolean>(
            { it.firstName.equals(name, ignoreCase = true) },
            { it.name.equals(name, ignoreCase = true) },
            { it.name.split(Regex("\\s+")).firstOrNull().equals(name, ignoreCase = true) },
            { num.length >= 7 && it.number.filter { ch -> ch.isDigit() }.takeLast(10) == num.takeLast(10) },
        )
        for (test in tests) {
            others.firstOrNull(test)?.let { return it }
        }
        if (lo.isBlank()) return null
        return others.firstOrNull { it.firstName.lowercase() == lo }
    }

    fun hsl(hDegrees: Int, s: Float, l: Float): Int {
        val h = ((hDegrees % 360) + 360) % 360
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

private fun ParticipantDto.participantKey(): String =
    id.ifBlank { participantId }.ifBlank { participantIdCamel }.trim()

private fun ParticipantDto.contactKey(): String =
    contactId.ifBlank { contactIdCamel }.trim()

private fun ParticipantDto.phoneKey(): String =
    number.ifBlank { phone }.trim()
