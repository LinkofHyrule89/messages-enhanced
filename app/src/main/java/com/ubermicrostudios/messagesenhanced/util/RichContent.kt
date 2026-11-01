package com.ubermicrostudios.messagesenhanced.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.Calendar
import java.util.TimeZone

/**
 * Google Messages special content: contacts (vCard), calendar invites (.ics),
 * RCS location pushes, RCS rich cards / carousels / suggestions, one-time
 * codes, payment amounts and system notices (tombstones). Pure parsing, no
 * Android types, so it is unit tested on the JVM. Mirrors the web's rich.js.
 */
object RichContent {
    enum class Kind { VCARD, ICS, PUSH_LOCATION, RICH_CARD, AUDIO, VIDEO, IMAGE, FILE }

    fun kind(mime: String, body: String = ""): Kind {
        val m = mime.lowercase()
        val b = body.trim().lowercase()
        return when {
            "vcard" in m || "directory" in m || b.endsWith(".vcf") -> Kind.VCARD
            "calendar" in m || b.endsWith(".ics") -> Kind.ICS
            "rcspushlocation" in m -> Kind.PUSH_LOCATION
            "botmessage" in m || "openrichcard" in m || "xbotmessage" in m || "botsuggestion" in m -> Kind.RICH_CARD
            m.startsWith("audio/") -> Kind.AUDIO
            m.startsWith("video/") -> Kind.VIDEO
            m.startsWith("image/") -> Kind.IMAGE
            else -> Kind.FILE
        }
    }

    data class Contact(val name: String, val phones: List<String>, val emails: List<String>, val org: String)
    data class Event(val title: String, val startMs: Long?, val endMs: Long?, val allDay: Boolean, val location: String, val description: String)
    data class Suggestion(val text: String, val reply: Boolean, val href: String)
    data class Card(val title: String, val description: String, val media: String, val suggestions: List<Suggestion>)
    data class Rich(val cards: List<Card>, val carousel: Boolean, val suggestions: List<Suggestion>)
    data class Notice(val icon: String, val text: String)

    private data class Prop(val name: String, val params: String, val value: String)

    private fun props(text: String): List<Prop> =
        text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n').mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val head = line.substring(0, i).split(';')
            Prop(head[0].replace(Regex("^item\\d+\\.", RegexOption.IGNORE_CASE), "").uppercase(), head.drop(1).joinToString(";").uppercase(), line.substring(i + 1))
        }

    private fun unescape(s: String) = s.replace(Regex("\\\\n", RegexOption.IGNORE_CASE), "\n").replace(Regex("\\\\([,;\\\\])"), "$1").trim()

    fun vcard(text: String): Contact {
        var name = ""
        var n = ""
        var org = ""
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        for (p in props(text)) when (p.name) {
            "FN" -> if (name.isEmpty()) name = unescape(p.value)
            "N" -> if (n.isEmpty()) n = unescape(p.value.split(';').take(2).reversed().joinToString(" "))
            "TEL" -> phones.add(unescape(p.value).removePrefix("tel:").removePrefix("TEL:"))
            "EMAIL" -> emails.add(unescape(p.value))
            "ORG" -> if (org.isEmpty()) org = unescape(p.value.split(';')[0])
        }
        name = name.ifBlank { n.trim() }.ifBlank { org }.ifBlank { phones.firstOrNull().orEmpty() }.ifBlank { "Contact" }
        return Contact(name, phones, emails, org)
    }

    private fun icsDate(v: String, tz: TimeZone): Pair<Long, Boolean>? {
        val m = Regex("^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})?(Z)?)?$").find(v.trim()) ?: return null
        val g = m.groupValues
        val allDay = g[4].isEmpty()
        val cal = Calendar.getInstance(if (g[7] == "Z") TimeZone.getTimeZone("UTC") else tz)
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt())
        return cal.timeInMillis to allDay
    }

    fun ics(text: String, tz: TimeZone = TimeZone.getDefault()): Event? {
        var inEvent = false
        var seen = false
        var title = ""; var location = ""; var desc = ""
        var start: Pair<Long, Boolean>? = null
        var end: Pair<Long, Boolean>? = null
        for (p in props(text)) {
            if (p.name == "BEGIN" && p.value.trim().equals("VEVENT", true)) { inEvent = true; seen = true; continue }
            if (p.name == "END" && p.value.trim().equals("VEVENT", true)) { if (inEvent) inEvent = false; continue }
            if (!inEvent) continue
            when (p.name) {
                "SUMMARY" -> if (title.isEmpty()) title = unescape(p.value)
                "LOCATION" -> if (location.isEmpty()) location = unescape(p.value)
                "DESCRIPTION" -> if (desc.isEmpty()) desc = unescape(p.value)
                "DTSTART" -> if (start == null) start = icsDate(p.value, tz)
                "DTEND" -> if (end == null) end = icsDate(p.value, tz)
            }
        }
        if (!seen) return null
        return Event(title.ifBlank { "Event" }, start?.first, end?.first, start?.second ?: false, location, desc)
    }

    private val POS = Regex("<(?:[A-Za-z0-9]+:)?pos[^>]*>\\s*(-?\\d+(?:\\.\\d+)?)[\\s,]+(-?\\d+(?:\\.\\d+)?)\\s*</")
    private val LABEL_ATTR = Regex("\\blabel=\"([^\"]*)\"")
    private val LABEL_EL = Regex("<(?:[A-Za-z0-9]+:)?(?:note|label)[^>]*>([\\s\\S]*?)</")

    /** RCS location push (the older location encoding): Maps link + label, or null. */
    fun pushLocation(xml: String): SharedLocation.Info? {
        val m = POS.find(xml) ?: return null
        val label = (LABEL_ATTR.find(xml) ?: LABEL_EL.find(xml))?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")?.trim().orEmpty()
        return SharedLocation.Info("https://maps.google.com/?q=${m.groupValues[1]},${m.groupValues[2]}", label)
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject?.str(key: String): String = (this?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

    private fun suggestions(arr: JsonElement?): List<Suggestion> = (arr as? JsonArray).orEmpty().mapNotNull { s ->
        val so = s.obj() ?: return@mapNotNull null
        val reply = so["reply"].obj()
        val r = reply ?: so["action"].obj() ?: return@mapNotNull null
        val text = r.str("displayText").ifBlank { return@mapNotNull null }
        val url = r["urlAction"].obj()?.get("openUrl").obj().str("url")
        val dial = r["dialerAction"].obj()?.get("dialPhoneNumber").obj().str("phoneNumber")
        val loc = r["mapAction"].obj()?.get("showLocation").obj()?.get("location").obj()
        val lat = loc.str("latitude"); val lon = loc.str("longitude")
        val href = when {
            url.isNotBlank() -> url
            dial.isNotBlank() -> "tel:$dial"
            lat.isNotBlank() && lon.isNotBlank() -> "https://maps.google.com/?q=$lat,$lon"
            else -> ""
        }
        Suggestion(text, reply != null, if (href.startsWith("https://", true) || href.startsWith("http://", true) || href.startsWith("tel:", true)) href else "")
    }.take(11)

    private fun card(c: JsonObject?): Card {
        val ct = c?.get("content").obj() ?: c
        val media = ct?.get("media").obj()
        val url = media.str("mediaUrl").ifBlank { media.str("thumbnailUrl") }
        return Card(ct.str("title"), ct.str("description"), if (url.startsWith("https://", true)) url else "", suggestions(ct?.get("suggestions")))
    }

    fun richCard(json: String): Rich? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull().obj() ?: return null
        val msg = root["message"].obj() ?: root
        val cards = ArrayList<Card>()
        msg["generalPurposeCard"].obj()?.let { cards.add(card(it)) }
        val car = msg["generalPurposeCardCarousel"].obj()
        (car?.get("content") as? JsonArray)?.forEach { cards.add(card(it.obj())) }
        val sugg = suggestions(root["suggestions"] ?: msg["suggestions"])
        if (cards.isEmpty() && sugg.isEmpty()) return null
        return Rich(cards, car != null, sugg)
    }

    private val OTP_WORD = Regex("(code|otp|passcode|pin|verification|verify|one[- ]time|2fa|security|login|sign[- ]in|c[oó]digo|token)", RegexOption.IGNORE_CASE)
    private val OTP = Regex("(?:^|[^\\d$€£.,:/])(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\d%]|[.,]\\d|\\s?(?:am|pm)\\b)", RegexOption.IGNORE_CASE)
    private val PAY_WORD = Regex("(paid|payment|sent you|you sent|requested|request(?:ed)? from|received|refund|charged|due|balance|venmo|zelle|cash app|paypal|google pay|apple cash)", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("(?:[$€£]\\s?\\d[\\d,]*(?:\\.\\d{2})?|\\d[\\d,]*(?:\\.\\d{2})?\\s?(?:USD|EUR|GBP))")

    fun otp(text: String): String {
        if (text.length > 400 || !OTP_WORD.containsMatchIn(text)) return ""
        val m = OTP.find(text) ?: return ""
        val code = m.groupValues[1].replace("-", "").replace(" ", "")
        if (Regex("^(19|20)\\d{2}$").matches(code)) {
            val near = text.substring(maxOf(0, m.range.first - 20), minOf(text.length, m.range.first + 30))
            if (!Regex("code|otp", RegexOption.IGNORE_CASE).containsMatchIn(near)) return ""
        }
        return code
    }

    fun payment(text: String): String {
        if (text.length > 400 || !PAY_WORD.containsMatchIn(text)) return ""
        return AMOUNT.find(text)?.value?.replace(Regex("\\s+"), " ").orEmpty()
    }

    fun isNotice(status: String): Boolean = status.contains("TOMBSTONE", ignoreCase = true)

    private val NOTICES = listOf(
        Regex("MISSED_VIDEO_CALL") to ("call" to "Missed video call"),
        Regex("MISSED.*CALL|CALL.*MISSED") to ("call" to "Missed call"),
        Regex("E2EE_TO|SWITCH_TO_TEXT|TO_TEXT|TO_RCS$|TO_MMS") to ("unlock" to "Messages are no longer end-to-end encrypted"),
        Regex("ENCRYPTED|E2EE") to ("lock" to "Messages are end-to-end encrypted"),
        Regex("IDENTITY_CHANGED") to ("shield" to "Security info changed"),
        Regex("VERIFIED") to ("shield" to "Verified sender"),
        Regex("SCAM|SPAM") to ("warn" to "This may be a scam"),
        Regex("JOINED") to ("group" to "Someone joined"),
        Regex("LEFT|REMOVED") to ("group" to "Someone left"),
        Regex("RENAMED|NAME_CLEARED|ICON_CHANGED|THEME_CHANGE") to ("edit" to "Group details changed"),
        Regex("GROUP_CREATED|BROADCAST_CREATED") to ("group" to "Group created"),
        Regex("SATELLITE") to ("info" to "Satellite messaging"),
        Regex("CHATBOT") to ("info" to "Business chat"),
        Regex("PROTOCOL_SWITCH_TO_RCS|ONE_ON_ONE_RCS") to ("info" to "Chatting with RCS"),
        Regex("SMS_CREATED|SWITCH_TO_GROUP_MMS|SMS") to ("info" to "Chatting with SMS/MMS"),
    )

    fun notice(status: String, body: String): Notice? {
        val s = status.uppercase()
        val hit = NOTICES.firstOrNull { it.first.containsMatchIn(s) }?.second
        val text = body.trim().ifBlank { hit?.second.orEmpty() }
        if (text.isBlank()) return null
        return Notice(hit?.first ?: "info", text)
    }

    /** Safe cache file name for an attachment (extension from the MIME type). */
    fun fileName(messageId: String, mime: String, body: String): String {
        val fromBody = Regex("\\.([A-Za-z0-9]{1,5})$").find(body.trim())?.groupValues?.get(1)
        val ext = fromBody ?: when (kind(mime)) {
            Kind.VCARD -> "vcf"; Kind.ICS -> "ics"; Kind.PUSH_LOCATION -> "xml"; Kind.RICH_CARD -> "json"
            else -> mime.substringAfter('/', "bin").substringAfterLast('.').substringBefore(';').take(5).ifBlank { "bin" }
        }
        return "m-" + messageId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60) + "." + ext.lowercase()
    }
}
