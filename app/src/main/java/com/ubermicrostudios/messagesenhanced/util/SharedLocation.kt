package com.ubermicrostudios.messagesenhanced.util

import android.net.Uri

/** A shared location in message text: a geo: URI or a Google/Apple Maps link. */
object SharedLocation {
    data class Info(val url: String, val label: String)

    private val RE = Regex(
        "(geo:\\S+|https?://(?:maps\\.google\\.[^\\s/]+|(?:www\\.)?google\\.[^\\s/]+/maps|goo\\.gl/maps|maps\\.app\\.goo\\.gl|maps\\.apple\\.com)\\S*)",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String?): Info? {
        val t = text.orEmpty()
        val m = RE.find(t) ?: return null
        var url = m.value
        if (url.startsWith("geo:", ignoreCase = true)) {
            val q = Regex("[?&]q=([^&]+)").find(url)?.groupValues?.get(1)
            val ll = url.substring(4).split('?', ';').first()
            url = "https://maps.google.com/?q=" + (q ?: Uri.encode(ll))
        }
        val label = t.replace(m.value, "").replace(Regex("\\s+"), " ").trim()
        return Info(url, label)
    }
}
