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

    fun loginUrl(origin: String) = origin.trimEnd('/') + "/login"
    fun cookiesEndpoint(origin: String) = origin.trimEnd('/') + "/admin/cookies"
}
