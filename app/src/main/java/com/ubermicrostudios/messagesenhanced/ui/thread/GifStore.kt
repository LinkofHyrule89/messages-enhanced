package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import kotlinx.serialization.encodeToString
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan

/** Favorite GIFs, same storage key the desktop web client uses. */
object GifStore {
    private const val PREFS = "tm.gif"
    private const val KEY = "openmessage.gifFavorites.v1"

    fun load(context: Context): List<ComposerPlan.GifRef> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching { JsonConfig.json.decodeFromString<List<ComposerPlan.GifRef>>(raw) }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<ComposerPlan.GifRef>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, JsonConfig.json.encodeToString(items))
            .apply()
    }
}
