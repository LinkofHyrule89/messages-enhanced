package com.ubermicrostudios.messagesenhanced.util

import android.content.Context
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import kotlinx.serialization.encodeToString

/**
 * Emoji picker data. [load] reads the web `emoji-data.js` asset and the recent list
 * (`tm.emojiRecent`, 24 glyphs, newest first). Until that load, [bundled] is a small fallback.
 */
object EmojiCatalog {
    data class Named(val emoji: String, val name: String)
    data class Category(val id: String, val label: String, val items: List<Named>)

    private const val PREFS = "tm.emoji"
    private const val RECENT_KEY = "tm.emojiRecent"

    private var loaded = false
    private var source: List<Category> = bundled()
    private val recent = ArrayList<String>()
    private var persist: ((List<String>) -> Unit)? = null

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val parsed = runCatching {
            context.assets.open("emoji-data.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.mapCatching { EmojiData.parse(it) }.getOrNull()
        if (!parsed.isNullOrEmpty()) source = parsed
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = runCatching {
            val raw = prefs.getString(RECENT_KEY, null) ?: return@runCatching emptyList()
            JsonConfig.json.decodeFromString<List<String>>(raw)
        }.getOrDefault(emptyList())
        recent.clear()
        recent.addAll(saved.filter { it.isNotBlank() }.distinct().take(ComposerPlan.EMOJI_RECENT_MAX))
        persist = { list ->
            prefs.edit().putString(RECENT_KEY, JsonConfig.json.encodeToString(list)).apply()
        }
    }

    fun recents(): List<String> = recent.toList()

    fun remember(emoji: String) {
        val next = MessageActions.rememberRecent(recent, emoji, cap = ComposerPlan.EMOJI_RECENT_MAX)
        recent.clear()
        recent.addAll(next)
        runCatching { persist?.invoke(next) }
    }

    /** Recent first, then the catalog. Recent is always present so the tab matches the web picker. */
    fun categories(): List<Category> {
        val picked = recents().map { emoji -> Named(emoji, nameOf(emoji)) }
        return listOf(Category("recent", "Recent", picked)) + source
    }

    /** Case-insensitive name search, capped the way the web grid caps a query. */
    fun search(query: String): List<Named> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Named>(ComposerPlan.EMOJI_SEARCH_LIMIT)
        val seen = HashSet<String>()
        for (category in source) {
            for (item in category.items) {
                if (item.emoji in seen) continue
                if (!item.name.lowercase().contains(q)) continue
                seen.add(item.emoji)
                hits.add(item)
                if (hits.size >= ComposerPlan.EMOJI_SEARCH_LIMIT) return hits
            }
        }
        return hits
    }

    fun nameOf(emoji: String): String {
        for (category in source) {
            val found = category.items.firstOrNull { it.emoji == emoji } ?: continue
            return found.name
        }
        return ""
    }

    fun bundled(): List<Category> = listOf(
        named(
            "smileys",
            "Smileys",
            listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😉",
                "😊", "😇", "🥰", "😍", "😘", "😗", "😚", "😋", "😛", "😜",
                "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐",
                "😑", "😶", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥", "😌", "😔",
                "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🥵", "🥶",
                "🥴", "😵", "🤯", "🤠", "🥳", "😎", "🤓", "🧐", "😕", "😟",
                "🙁", "😮", "😯", "😲", "😳", "🥺", "😦", "😧", "😨", "😰",
                "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫",
                "🥱", "😤", "😡", "😠", "🤬", "👍", "👎", "👏", "🙌", "🤝",
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❣️",
                "💕", "💯", "✨", "🔥", "⭐", "✅", "❌",
            ),
        ),
        named("people", "People", listOf("👋", "🤚", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "☝️", "👊", "✊", "🙏")),
        named("animals", "Animals", listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🦄", "🐝")),
        named("food", "Food", listOf("🍎", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🍒", "🥑", "🍕", "🍔", "🍟", "🌮", "🍣", "🍪", "🎂", "☕", "🍺", "🥂", "🧊")),
        named("activities", "Activities", listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🎱", "🎮", "🎯", "🎲", "🎸", "🎹", "🎺", "🎨", "🎬", "🎤", "🎧", "🏆", "🥇", "🎪")),
        named("travel", "Travel", listOf("🚗", "🚕", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "✈️", "🚀", "🚁", "⛵", "🏠", "🏡", "🏢", "🏰", "🗼", "🗽", "⛰️", "🌋")),
        named("objects", "Objects", listOf("⌚", "📱", "💻", "⌨️", "📷", "📺", "💡", "🔦", "📚", "✏️", "📌", "📎", "✂️", "🔒", "🔑", "🎁", "🎈", "✉️", "📦", "🗑️")),
        named("symbols", "Symbols", listOf("💬", "👁️‍🗨️", "💤", "💢", "💥", "💫", "💦", "💨", "🕳️", "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚫", "⚪", "🔶", "🔷", "🏁")),
        named("flags", "Flags", listOf("🏁", "🚩", "🎌", "🏴", "🏳️", "🇺🇸", "🇬🇧", "🇨🇦", "🇲🇽", "🇧🇷", "🇫🇷", "🇩🇪", "🇮🇹", "🇪🇸", "🇯🇵", "🇰🇷", "🇨🇳", "🇮🇳", "🇦🇺", "🇪🇺")),
    )

    private fun named(id: String, label: String, emojis: List<String>): Category =
        Category(id, label, emojis.map { Named(it, "") })
}
