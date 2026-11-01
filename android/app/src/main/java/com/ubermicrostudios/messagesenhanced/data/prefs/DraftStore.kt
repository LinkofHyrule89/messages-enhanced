package com.ubermicrostudios.messagesenhanced.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ubermicrostudios.messagesenhanced.util.Drafts
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-conversation composer drafts, stored only on this device (private
 * SharedPreferences keyed by conversation ID) so they survive restarts.
 * Never sent to the server. Mention chips live in the text itself, so they
 * come back with it.
 */
@Singleton
class DraftStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<() -> Unit>()

    fun get(conversationId: String): String =
        if (conversationId.isBlank()) "" else prefs.getString(KEY + conversationId, null).orEmpty()

    fun put(conversationId: String, text: String?) {
        if (conversationId.isBlank()) return
        val value = Drafts.normalize(text)
        if (value == prefs.getString(KEY + conversationId, null)) return
        prefs.edit { if (value == null) remove(KEY + conversationId) else putString(KEY + conversationId, value) }
        listeners.toList().forEach { it() }
    }

    fun clear(conversationId: String) = put(conversationId, null)

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private companion object {
        const val FILE = "composer_drafts"
        const val KEY = "draft:"
    }
}
