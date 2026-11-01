package com.ubermicrostudios.messagesenhanced.util

/**
 * Emoji-only messages (1–3 emoji, nothing else) show large and animated,
 * like Google Messages. Animations are Noto Animated Emoji by Google
 * (CC BY 4.0), loaded from Google Fonts' emoji CDN.
 */
object EmojiOnly {
    const val MAX = 3

    private fun pictographic(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF || cp in 0x25A0..0x25FF || cp in 0x2934..0x2935 ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp == 0x24C2 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    private fun modifier(cp: Int): Boolean =
        cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F

    /** The emoji in [text] when it is 1–[MAX] emoji only, else null. */
    fun split(text: String): List<String>? {
        val t = text.filterNot { it.isWhitespace() }
        if (t.isEmpty() || t.length > 40) return null
        val out = ArrayList<StringBuilder>()
        val cps = t.codePoints().toArray()
        var i = 0
        var joinNext = false
        while (i < cps.size) {
            val cp = cps[i]
            val s = String(Character.toChars(cp))
            when {
                cp == 0x200D -> { if (out.isEmpty()) return null; out.last().append(s); joinNext = true }
                modifier(cp) -> { if (out.isEmpty()) return null; out.last().append(s) }
                joinNext -> { if (!pictographic(cp)) return null; out.last().append(s); joinNext = false }
                cp in 0x1F1E6..0x1F1FF -> {
                    val prev = out.lastOrNull()
                    val prevCp = prev?.toString()?.codePoints()?.toArray()
                    if (prev != null && prevCp != null && prevCp.size == 1 && prevCp[0] in 0x1F1E6..0x1F1FF) prev.append(s) else out.add(StringBuilder(s))
                }
                (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) && cps.drop(i + 1).take(2).contains(0x20E3) -> out.add(StringBuilder(s))
                pictographic(cp) -> out.add(StringBuilder(s))
                else -> return null
            }
            i++
        }
        if (out.isEmpty() || out.size > MAX) return null
        return out.map { it.toString() }
    }

    /** Noto Animated Emoji URL (animated WebP) for one emoji. */
    fun notoUrl(emoji: String): String {
        val code = emoji.codePoints().toArray().filter { it != 0xFE0F }.joinToString("_") { Integer.toHexString(it) }
        return "https://fonts.gstatic.com/s/e/notoemoji/latest/$code/512.webp"
    }
}
