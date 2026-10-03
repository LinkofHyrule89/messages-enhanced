package com.ubermicrostudios.messagesenhanced.net

/**
 * Pure helpers for the Google Messages WebView and the cleartext policy.
 */
object WebPolicy {

    const val MESSAGES_WEB_URL = "https://messages.google.com/web/"

    /**
     * Turn the WebView's default UA into a normal mobile Chrome UA: drop the
     * "; wv" token and the "Version/4.0 " marker that identify an embedded
     * WebView. Everything else (real Chrome version, Android version) is kept.
     */
    fun chromeLikeUserAgent(defaultUa: String): String =
        defaultUa
            .replace("; wv)", ")")
            .replace("; wv", "")
            .replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    /**
     * True when Google has shown its "This browser or app may not be secure" /
     * disallowed-user-agent page. Detected from the URL only (no script injection).
     */
    fun isGoogleSignInBlocked(url: String?): Boolean {
        val u = (url ?: "").lowercase()
        if (!u.contains("accounts.google.com")) return false
        return u.contains("/signin/rejected") ||
            u.contains("disallowed_useragent") ||
            u.contains("/deniedsigninrejected") ||
            u.contains("/signin/v2/deniedsignin")
    }

    /** Only https (and about:blank) may load inside the Google WebView. */
    fun isAllowedInWebView(url: String?): Boolean {
        val u = (url ?: "").lowercase()
        return u.startsWith("https://") || u == "about:blank"
    }

    /**
     * Cleartext (http://) is only used for the user's own LAN/loopback server:
     * RFC1918 (10/8, 172.16/12, 192.168/16), loopback, link-local 169.254/16,
     * CGNAT/Tailscale 100.64/10, localhost, and *.local / *.lan / *.home.arpa
     * names. Any other host must use https.
     */
    fun isCleartextAllowedHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1") return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (h.startsWith("fd") && h.contains(':')) return true // IPv6 ULA fd00::/8
        if (h.startsWith("fe80:")) return true                  // IPv6 link-local
        val parts = h.split('.')
        if (parts.size != 4) return false
        val o = parts.map { it.toIntOrNull() ?: return false }
        if (o.any { it !in 0..255 }) return false
        return when {
            o[0] == 10 -> true
            o[0] == 127 -> true
            o[0] == 192 && o[1] == 168 -> true
            o[0] == 172 && o[1] in 16..31 -> true
            o[0] == 169 && o[1] == 254 -> true
            o[0] == 100 && o[1] in 64..127 -> true
            else -> false
        }
    }
}
