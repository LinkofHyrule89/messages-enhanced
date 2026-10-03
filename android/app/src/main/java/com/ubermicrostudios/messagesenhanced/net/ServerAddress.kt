package com.ubermicrostudios.messagesenhanced.net

/**
 * Normalizes the server address the user types and derives endpoints. Pure,
 * Android-free, unit-tested.
 */
object ServerAddress {

    /** No built-in server: the user enters their own on first launch. */
    const val DEFAULT = ""

    data class Normalized(
        val ok: Boolean,
        val origin: String = "",
        val isHttps: Boolean = false,
        val host: String = "",
        val error: String? = null,
    )

    /**
     * Accepts "192.168.1.20:7117", "http://host:7117", "https://tm.example.com".
     * Defaults a bare host/host:port to http://. Returns the scheme+host(+port)
     * origin with no trailing path.
     */
    fun normalize(input: String?): Normalized {
        var s = (input ?: "").trim()
        if (s.isEmpty()) return Normalized(false, error = "Enter the server address.")
        val schemeMatch = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(s)
        if (schemeMatch != null) {
            val scheme = schemeMatch.value.lowercase()
            if (scheme != "http://" && scheme != "https://") {
                return Normalized(false, error = "Address must start with http:// or https://.")
            }
        } else {
            s = "http://$s"
        }
        val isHttps = s.lowercase().startsWith("https://")
        val rest = s.substring(s.indexOf("://") + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return Normalized(false, error = "Address is missing a host.")
        if (authority.contains('@') || authority.contains(' ')) {
            return Normalized(false, error = "That does not look like a valid address.")
        }
        if (authority.startsWith(":")) return Normalized(false, error = "Address is missing a host.")
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]"
        else authority.substringBefore(':')
        val port = authority.removePrefix(host).removePrefix(":")
        if (port.isNotEmpty() && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) {
            return Normalized(false, error = "The port number is not valid.")
        }
        if (!isHttps && !WebPolicy.isCleartextAllowedHost(host)) {
            return Normalized(
                false,
                error = "Plain http:// is only allowed for a local network address " +
                    "(like 192.168.x.x). Use https:// for other addresses.",
            )
        }
        val scheme = if (isHttps) "https" else "http"
        // Drop default ports so the Origin header matches the Host header the
        // HTTP client sends (the server compares them exactly).
        val defaultPort = if (isHttps) "443" else "80"
        val auth = if (port.isEmpty() || port == defaultPort) host else "$host:$port"
        return Normalized(true, origin = "$scheme://${auth.lowercase()}", isHttps = isHttps, host = host.lowercase())
    }

    /** Everything after the host: "/messages/app/" for "https://h/messages/app/?x". */
    private fun pathOf(input: String): String {
        val s = input.trim()
        val rest = if (s.contains("://")) s.substring(s.indexOf("://") + 3) else s
        val slash = rest.indexOf('/')
        return if (slash < 0) "" else rest.substring(slash).substringBefore('?').substringBefore('#')
    }

    /**
     * The path prefix the server lives under, from what the user typed: ""
     * for the bare domain or ".../app...", "/messages" for
     * "https://h/messages" or "https://h/messages/app/". The web app's own
     * start path ("/app" and anything after it) is dropped; it's added back
     * by [startUrl].
     */
    fun basePath(input: String?): String {
        val segs = pathOf(input ?: "").split('/').filter { it.isNotEmpty() }
        val appIdx = segs.indexOf("app")
        var keep = if (appIdx >= 0) segs.take(appIdx) else segs
        if (keep.lastOrNull() == "login") keep = keep.dropLast(1)
        return if (keep.isEmpty()) "" else "/" + keep.joinToString("/")
    }

    /** The web app's start address for a server at [origin] + [basePath]. */
    fun startUrl(origin: String, basePath: String = "") = origin.trimEnd('/') + basePath + "/app/"

    /**
     * What to open for a saved address: a saved start URL as is, or (a bare
     * origin saved by an older version or the cookie screen) origin + "/app/".
     */
    fun launchUrl(saved: String?): String? {
        val n = normalize(saved)
        if (!n.ok) return null
        val p = pathOf(saved!!)
        return if (p.isEmpty() || p == "/") startUrl(n.origin) else n.origin + p
    }

    fun loginUrl(origin: String) = origin.trimEnd('/') + "/login"
    fun cookiesEndpoint(origin: String) = origin.trimEnd('/') + "/admin/cookies"
}
