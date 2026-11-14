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

    /** Vertical padding on each side of a suggestion row. Matches the picker row. */
    const val SUGGESTION_PAD_V_DP = 10

    /** Avatar diameter in a suggestion row. */
    const val SUGGESTION_AVATAR_DP = 32

    /** Suggestion label size, in sp. */
    const val SUGGESTION_TEXT_SP = 16f

    /** Rows kept on screen; further matches scroll. */
    const val SUGGESTION_MAX_ROWS = 5

    /**
     * Name at [position] in the list currently shown.
     * [position] indexes that filtered list, not the full member list.
     * Out of range is null so a stale index cannot insert a different person.
     */
    fun selectedSuggestion(visible: List<String>, position: Int): String? =
        if (position < 0) null else visible.getOrNull(position)

    /**
     * Replace the "@query" at the cursor with the suggestion the user tapped.
     * [visible] is the filtered rows on screen and [position] is the tapped row.
     */
    fun insertSuggestion(text: String, cursor: Int, visible: List<String>, position: Int): Pair<String, Int>? {
        val name = selectedSuggestion(visible, position) ?: return null
        return insert(text, cursor, name)
    }

    /**
     * Popup height for [matchCount] rows. Grows by one row per match and stops
     * at [SUGGESTION_MAX_ROWS], which is when the list scrolls.
     * [density] is px per dp. [fontScale] grows the row when the label is taller than the avatar.
     */
    fun suggestionPanelHeightPx(matchCount: Int, density: Float, fontScale: Float = 1f): Int {
        if (matchCount <= 0 || density <= 0f) return 0
        val row = suggestionRowHeightPx(density, fontScale)
        if (row <= 0) return 0
        return row * matchCount.coerceAtMost(SUGGESTION_MAX_ROWS)
    }

    /** One suggestion row: vertical padding plus the taller of the avatar and the label. */
    fun suggestionRowHeightPx(density: Float, fontScale: Float = 1f): Int {
        if (density <= 0f) return 0
        val pad = SUGGESTION_PAD_V_DP * 2f * density
        val avatar = SUGGESTION_AVATAR_DP * density
        val text = SUGGESTION_TEXT_SP * fontScale.coerceAtLeast(0f) * density * 1.4f
        return (pad + maxOf(avatar, text)).toInt().coerceAtLeast(1)
    }
}
