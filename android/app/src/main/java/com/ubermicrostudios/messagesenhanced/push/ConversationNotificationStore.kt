package com.ubermicrostudios.messagesenhanced.push

import android.annotation.SuppressLint
import android.content.Context
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-conversation unread lines. The list is kept in memory and written through
 * to preferences so a process restart can rebuild the same MessagingStyle history.
 */
@Singleton
class ConversationNotificationStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    init {
        MessageNotificationIds.bindPersistence(context)
    }

    @Volatile
    private var memory: List<NotifThread>? = null

    fun snapshot(): List<NotifThread> = synchronized(lock) { load() }

    fun update(block: (List<NotifThread>) -> List<NotifThread>): List<NotifThread> = synchronized(lock) {
        val next = block(load())
        memory = next
        prefs.edit().putString(KEY, JsonConfig.json.encodeToString(next)).apply()
        next
    }

    /**
     * Returns the history that was stored, then commits an empty list.
     * commit, not apply, so sign-out finishes the wipe before the process moves on.
     */
    @SuppressLint("ApplySharedPref")
    fun clear(): List<NotifThread> = synchronized(lock) {
        val previous = load()
        memory = emptyList()
        prefs.edit().remove(KEY).commit()
        previous
    }

    private fun load(): List<NotifThread> {
        memory?.let { return it }
        val raw = prefs.getString(KEY, null).orEmpty()
        val parsed = if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching { JsonConfig.json.decodeFromString<List<NotifThread>>(raw) }.getOrDefault(emptyList())
        }
        memory = parsed
        return parsed
    }

    private companion object {
        const val PREFS = "me_native_message_notifications"
        const val KEY = "history"
    }
}
