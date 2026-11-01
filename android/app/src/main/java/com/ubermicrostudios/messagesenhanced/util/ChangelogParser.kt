package com.ubermicrostudios.messagesenhanced.util

/**
 * Reads one version section from CHANGELOG.md.
 * Headings are `## android-vX.Y.Z` (this repo's Android releases) or `## vX.Y.Z`.
 */
object ChangelogParser {
    private val heading = Regex(
        """^##\s+(?:android-)?v?(\d+\.\d+\.\d+)\s*$""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )

    fun section(markdown: String, versionName: String): String? {
        val want = normalize(versionName) ?: return null
        val matches = heading.findAll(markdown).toList()
        val hit = matches.firstOrNull { it.groupValues[1] == want } ?: return null
        val start = hit.range.last + 1
        val end = matches.firstOrNull { it.range.first > hit.range.first }?.range?.first ?: markdown.length
        return markdown.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }

    fun displayBody(section: String): String =
        section.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trimEnd()
            val start = trimmed.indexOfFirst { !it.isWhitespace() }
            if (start >= 0 && trimmed.startsWith("- ", startIndex = start)) {
                trimmed.replaceRange(start, start + 2, "• ")
            } else {
                trimmed
            }
        }.trim()

    fun normalize(versionName: String): String? {
        val match = Regex(
            """^(?:android-)?v?(\d+\.\d+\.\d+)$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(versionName.trim()) ?: return null
        return match.groupValues[1]
    }
}
