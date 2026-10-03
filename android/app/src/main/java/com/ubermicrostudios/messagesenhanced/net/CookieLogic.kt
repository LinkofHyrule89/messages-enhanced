package com.ubermicrostudios.messagesenhanced.net

/**
 * Pure, Android-free helpers for selecting Google auth cookies and building the
 * request body the Messages Enhanced server expects. Kept free of android.* imports
 * so it can be unit-tested on the JVM.
 *
 * SECURITY: nothing here logs or exposes a cookie VALUE. Only cookie names are
 * ever surfaced to the UI (see [requiredStatus] / [presentNames]).
 */
object CookieLogic {

    // Must match internal/webapp/cookies.go on the server.
    val REQUIRED = listOf("SID", "HSID", "SSID", "OSID", "APISID", "SAPISID")
    val OPTIONAL = listOf(
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PSIDTS", "__Secure-3PSIDTS",
        "__Secure-1PAPISID", "__Secure-3PAPISID", "NID", "SIDCC",
    )
    val WANTED = REQUIRED + OPTIONAL

    /** One cookie as read from a host: which URL/host it came from + name/value. */
    data class HostCookie(val host: String, val name: String, val value: String)

    // For a required cookie, which source host should win when the same name
    // exists on several. OSID lives on many Google hosts (mail/drive/messages…)
    // but the app needs the messages.google.com one; the rest are on google.com.
    private val HOST_PREF = mapOf("OSID" to listOf("messages.google.com"))
    private val STRICT_HOST = setOf("OSID")
    private val DEFAULT_PREF = listOf("www.google.com", "google.com", "accounts.google.com")

    /**
     * Parse a CookieManager.getCookie() string ("a=1; b=2") into name/value pairs
     * tagged with the [host] the cookie string was read for.
     */
    fun parseCookieHeader(host: String, header: String?): List<HostCookie> {
        if (header.isNullOrBlank()) return emptyList()
        val out = ArrayList<HostCookie>()
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) continue
            out.add(HostCookie(host, name, value))
        }
        return out
    }

    /**
     * Reduce cookies read from several hosts into a { name -> value } map for only
     * the wanted names. For each wanted name the value is chosen by host
     * preference; OSID is taken ONLY from messages.google.com. Empty values are ignored.
     * The input order is preserved as the final tiebreaker.
     */
    fun selectCookies(cookies: List<HostCookie>): Map<String, String> {
        val wanted = WANTED.toSet()
        val candidates = cookies.filter { it.name in wanted && it.value.isNotBlank() }
        val out = LinkedHashMap<String, String>()
        for (name in WANTED) {
            val cands = candidates.filter { it.name == name }
            if (cands.isEmpty()) continue
            val prefs = HOST_PREF[name] ?: DEFAULT_PREF
            var chosen: String? = null
            for (h in prefs) {
                val hit = cands.firstOrNull { hostEquals(it.host, h) }
                if (hit != null) { chosen = hit.value; break }
            }
            if (chosen == null) {
                // OSID is strict: an OSID from mail/drive/accounts is useless to
                // the server, so report it missing instead of sending a wrong one.
                if (name in STRICT_HOST) continue
                chosen = cands.first().value
            }
            out[name] = chosen
        }
        return out
    }

    private fun hostEquals(host: String, want: String): Boolean {
        val h = normalizeHost(host)
        return h == want
    }

    /** Extract a bare host from a full URL or a host string. */
    fun normalizeHost(hostOrUrl: String): String {
        var s = hostOrUrl.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/')
        s = s.substringBefore(':')
        return s.removePrefix(".").lowercase()
    }

    data class RequiredStatus(val found: List<String>, val missing: List<String>)

    /** Which REQUIRED names are present / missing in a name->value map. */
    fun requiredStatus(cookieMap: Map<String, String>): RequiredStatus {
        val found = ArrayList<String>()
        val missing = ArrayList<String>()
        for (name in REQUIRED) {
            if (cookieMap.containsKey(name)) found.add(name) else missing.add(name)
        }
        return RequiredStatus(found, missing)
    }

    /** Names present, sorted, for display. NEVER returns values. */
    fun presentNames(cookieMap: Map<String, String>): List<String> = cookieMap.keys.sorted()

    /**
     * Encode a name->value map as the x-www-form-urlencoded body the server
     * expects: cookies=<url-encoded JSON object>.
     */
    fun buildFormBody(cookieMap: Map<String, String>): String =
        "cookies=" + urlEncode(toJsonObject(cookieMap))

    /** Minimal JSON object encoder (string -> string), deterministic order. */
    fun toJsonObject(cookieMap: Map<String, String>): String {
        val ordered = LinkedHashMap<String, String>()
        for (name in WANTED) cookieMap[name]?.let { ordered[name] = it }
        for ((k, v) in cookieMap) if (!ordered.containsKey(k)) ordered[k] = v
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in ordered) {
            if (!first) sb.append(',')
            first = false
            jsonString(sb, k); sb.append(':'); jsonString(sb, v)
        }
        return sb.append('}').toString()
    }

    private fun jsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private fun urlEncode(s: String): String {
        val sb = StringBuilder()
        val bytes = s.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xff
            when {
                c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code ->
                    sb.append(c.toChar())
                else -> sb.append('%').append("0123456789ABCDEF"[c ushr 4]).append("0123456789ABCDEF"[c and 0xf])
            }
        }
        return sb.toString()
    }
}
