package com.ubermicrostudios.messagesenhanced.util

/**
 * Google Messages encodes @mentions as plain text wrapped in no-break spaces:
 * "\u00A0@Full Name\u00A0" or "\u00A0@First\u00A0". No hidden metadata.
 */
object Mentions {
    const val NB = '\u00A0'
    private val RE = Regex("(^|\u00A0)@([^\u00A0\n@]{1,60})\u00A0")
    private val TRAILING = Regex("\u00A0@([^\u00A0\n@]{1,60})\u00A0 ?$")

    /** Ranges of "@Name" (without the no-break spaces) in [text]. */
    fun ranges(text: String): List<IntRange> =
        RE.findAll(text).map { m ->
            val start = m.range.first + m.groupValues[1].length
            start until (start + 1 + m.groupValues[2].length)
        }.toList()

    fun token(name: String): String = "$NB@${name.trim()}$NB"

    /** Active "@query" before the cursor, or null. Returns (atIndex, query). */
    fun query(text: String, cursor: Int): Pair<Int, String>? {
        if (cursor < 0 || cursor > text.length) return null
        val before = text.substring(0, cursor)
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        val q = before.substring(at + 1)
        if (q.length > 30 || q.any { it == NB || it == '\n' || it == '@' } || q.count { it == ' ' } > 1) return null
        if (at > 0 && !(before[at - 1].isWhitespace() || before[at - 1] == '(')) return null
        return at to q
    }

    /** Replace "@query" at the cursor with the full-name mention. Returns (text, cursor). */
    fun insert(text: String, cursor: Int, name: String): Pair<String, Int>? {
        val (at, _) = query(text, cursor) ?: return null
        val post = text.substring(cursor)
        val ins = token(name) + if (post.startsWith(" ")) "" else " "
        val out = text.substring(0, at) + ins + post
        return out to (at + ins.length)
    }

    /**
     * Google-style backspace right after a mention: "@First Last" becomes
     * "@First"; a one-word mention is removed. Null when not after a mention.
     */
    fun backspace(text: String, cursor: Int): Pair<String, Int>? {
        if (cursor <= 0 || cursor > text.length) return null
        val m = TRAILING.find(text.substring(0, cursor)) ?: return null
        val words = m.groupValues[1].split(' ')
        val repl = if (words.size > 1) token(words.first()) else ""
        val start = m.range.first
        return (text.substring(0, start) + repl + text.substring(cursor)) to (start + repl.length)
    }

    fun matches(names: List<String>, query: String): List<String> {
        val q = query.lowercase()
        return names.filter { n ->
            q.isEmpty() || n.lowercase().startsWith(q) || n.lowercase().split(' ').any { it.startsWith(q) }
        }.take(8)
    }
}
