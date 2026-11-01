package com.ubermicrostudios.messagesenhanced.util

/**
 * Names the native reconnect flow is allowed to send to POST /api/app/google/cookies.
 *
 * Cookie **values** must never be written to Log, files, SharedPreferences, or crash reports.
 * Callers should keep the map in a local and drop it after the POST.
 */
object GoogleCookies {
    val REQUIRED = listOf("SID", "HSID", "SSID", "OSID", "APISID", "SAPISID")

    val OPTIONAL = listOf(
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PSIDTS",
        "__Secure-3PSIDTS",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "NID",
        "SIDCC",
    )

    val ALLOWED = (REQUIRED + OPTIONAL).toSet()

    private val SIGNED_IN = listOf("SID", "HSID", "SAPISID")

    /** Parse a CookieManager `name=value; name2=value2` header. Values are kept as-is. */
    fun parseHeader(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')
            if (eq <= 0) continue
            val name = piece.substring(0, eq).trim()
            if (name.isEmpty()) continue
            out[name] = piece.substring(eq + 1)
        }
        return out
    }

    /** Keep required + optional names, in a stable order. Drops everything else. */
    fun filterAllowed(cookies: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(ALLOWED.size)
        for (name in REQUIRED) {
            val value = cookies[name] ?: continue
            if (value.isNotEmpty()) out[name] = value
        }
        for (name in OPTIONAL) {
            val value = cookies[name] ?: continue
            if (value.isNotEmpty()) out[name] = value
        }
        return out
    }

    fun mergeAndFilter(headers: List<String?>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for (header in headers) {
            merged.putAll(parseHeader(header))
        }
        return filterAllowed(merged)
    }

    fun hasRequired(cookies: Map<String, String>): Boolean =
        REQUIRED.all { name -> cookies[name].orEmpty().isNotEmpty() }

    /** SID + HSID + SAPISID; enough to treat the WebView as signed in. */
    fun looksSignedIn(cookies: Map<String, String>): Boolean =
        SIGNED_IN.all { name -> cookies[name].orEmpty().isNotEmpty() }

    fun looksLikeGoogleBlock(url: String?, title: String?, snippet: String? = null): Boolean {
        val u = url.orEmpty().lowercase()
        val t = title.orEmpty().lowercase()
        val s = snippet.orEmpty().lowercase()
        return u.contains("signin/rejected") ||
            u.contains("deniedsigninrejected") ||
            t.contains("may not be secure") ||
            t.contains("couldn't sign you in") ||
            t.contains("couldn’t sign you in") ||
            t.contains("this browser or app") ||
            s.contains("may not be secure") ||
            s.contains("this browser or app may not be secure")
    }
}
