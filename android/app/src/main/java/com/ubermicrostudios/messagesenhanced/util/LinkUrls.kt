package com.ubermicrostudios.messagesenhanced.util

/**
 * URL splitting copied from the web app's `splitLinks` / `firstURL`
 * (`app.js`): the same pattern, trailing-punctuation trim, and the rule that
 * a match stuck to an email or a word is not a link.
 */
object LinkUrls {
    private val pattern = Regex(
        """((?:https?://|www\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?::\d{2,5})?(?:[/?#][^\s<]*)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val wordBefore = Regex("""[@A-Za-z0-9_]""")

    data class Part(val text: String, val url: String? = null)

    fun parts(text: String): List<Part> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Part>()
        var last = 0
        for (match in pattern.findAll(text)) {
            val start = match.range.first
            if (start > 0 && wordBefore.matches(text.substring(start - 1, start))) continue
            val core = trimTrailing(match.value).first
            if (core.isEmpty()) continue
            if (start > last) out.add(Part(text.substring(last, start)))
            out.add(Part(text = core, url = normalize(core)))
            last = start + core.length
        }
        if (last < text.length) out.add(Part(text.substring(last)))
        if (out.isEmpty()) out.add(Part(text))
        return out
    }

    /** [text] without the first link whose normalized url is [url], tidied. */
    fun stripUrl(text: String, url: String): String {
        var done = false
        val sb = StringBuilder()
        for (part in parts(text)) {
            if (!done && part.url == url) { done = true; continue }
            sb.append(part.text)
        }
        return sb.toString().replace(Regex("[ \t]{2,}"), " ").trim()
    }

    /** First http(s) URL in [text], or null. Bare hosts are normalized to https. */
    fun firstHttpUrl(text: String): String? {
        for (part in parts(text)) {
            val url = part.url ?: continue
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                return url
            }
        }
        return null
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if (scheme.matches(trimmed)) trimmed else "https://$trimmed"
    }

    /** Core link text, and the punctuation that was peeled off the end. */
    fun trimTrailing(raw: String): Pair<String, String> {
        var core = raw
        var trailing = ""
        while (core.isNotEmpty()) {
            val last = core.last()
            if (last in ".,!?;:") {
                trailing = last + trailing
                core = core.dropLast(1)
                continue
            }
            if (last == ')') {
                val opens = core.count { it == '(' }
                val closes = core.count { it == ')' }
                if (closes > opens) {
                    trailing = last + trailing
                    core = core.dropLast(1)
                    continue
                }
            }
            break
        }
        return core to trailing
    }

    private val scheme = Regex("""^[a-z][a-z0-9+.-]*://.+""", RegexOption.IGNORE_CASE)
}
