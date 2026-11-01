package com.ubermicrostudios.messagesenhanced.util

/**
 * Clean links: removes tracking query parameters from URLs in text. A
 * parameter is removed when its name matches the rule regex at the start of
 * the name (so "utm" removes utm_source, "t" removes t and tag), any case.
 * Everything else in the URL stays exactly as it was.
 */
object LinkCleaner {
    const val DEFAULT_RULE = "ref|utm|source|clickfrom|sku|spm|pf_rd|_ga|share_|ShareTm|_encoding|psc|t|s|wprov|si|a"

    private val URL = Regex("""\bhttps?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex("""[.,;:!?)\]]+$""")

    fun compile(rule: String?): Regex? {
        val r = rule?.trim().orEmpty()
        if (r.isEmpty()) return null
        return runCatching { Regex("^(?:$r)", RegexOption.IGNORE_CASE) }.getOrNull()
    }

    fun isValid(rule: String?): Boolean = compile(rule) != null

    fun cleanUrl(url: String, rule: String? = DEFAULT_RULE): String {
        val re = compile(rule) ?: return url
        val hashAt = url.indexOf('#')
        val hash = if (hashAt >= 0) url.substring(hashAt) else ""
        val noHash = if (hashAt >= 0) url.substring(0, hashAt) else url
        val q = noHash.indexOf('?')
        if (q < 0) return url
        val keep = noHash.substring(q + 1).split('&').filter { part ->
            if (part.isEmpty()) return@filter false
            val raw = part.substringBefore('=')
            val name = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            !re.containsMatchIn(name)
        }
        return noHash.substring(0, q) + (if (keep.isEmpty()) "" else "?" + keep.joinToString("&")) + hash
    }

    data class Result(val text: String, val count: Int)

    fun cleanText(text: String, rule: String? = DEFAULT_RULE): Result {
        var n = 0
        val out = URL.replace(text) { m ->
            var u = m.value
            var tail = ""
            TRAILING.find(u)?.let { t -> tail = t.value; u = u.dropLast(tail.length) }
            val c = cleanUrl(u, rule)
            if (c != u) n++
            c + tail
        }
        return Result(out, n)
    }

    /** Ranges of URLs that would change, with their cleaned replacement (for in-place edits). */
    fun changes(text: String, rule: String? = DEFAULT_RULE): List<Pair<IntRange, String>> {
        val out = ArrayList<Pair<IntRange, String>>()
        for (m in URL.findAll(text)) {
            var u = m.value
            TRAILING.find(u)?.let { t -> u = u.dropLast(t.value.length) }
            val c = cleanUrl(u, rule)
            if (c != u) out += IntRange(m.range.first, m.range.first + u.length - 1) to c
        }
        return out
    }
}
