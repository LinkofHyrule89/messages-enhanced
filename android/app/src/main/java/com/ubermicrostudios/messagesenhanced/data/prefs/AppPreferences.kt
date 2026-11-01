package com.ubermicrostudios.messagesenhanced.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.core.content.edit
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.StoredCookie
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.CompletableFuture
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    private val settings: SharedPreferences =
        appContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    private val lock = Any()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var lastSelectedLoaded = false
    private var lastSelectedId = ""

    @Volatile
    private var secureHolder: SharedPreferences? = null

    /**
     * Opens the encrypted cookie store. First creation is keystore I/O; call from
     * a background thread at process start. A later main-thread read waits on IO
     * instead of creating the store on main.
     */
    fun warmSecure() {
        ensureSecure()
    }

    private fun ensureSecure(): SharedPreferences {
        secureHolder?.let { return it }
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            val future = CompletableFuture<SharedPreferences>()
            writer.launch {
                try {
                    future.complete(createSecure())
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
            return future.get()
        }
        return createSecure()
    }

    private fun createSecure(): SharedPreferences {
        synchronized(lock) {
            secureHolder?.let { return it }
            val created = EncryptedPrefs.openOrNull(appContext, SECURE_FILE)
                ?: appContext.getSharedPreferences(SECURE_PLAIN_FILE, Context.MODE_PRIVATE)
            secureHolder = created
            return created
        }
    }

    var serverUrl: String
        get() = settings.getString(KEY_SERVER_URL, null).orEmpty().trim().trimEnd('/')
        set(value) = settings.edit { putString(KEY_SERVER_URL, value.trim().trimEnd('/')) }

    var lastUpdateCheckAt: Long
        get() = settings.getLong(KEY_UPDATE_CHECK_AT, 0L)
        set(value) = settings.edit { putLong(KEY_UPDATE_CHECK_AT, value) }

    /** Off until the user turns it on in Settings. */
    var autoUpdate: Boolean
        get() = settings.getBoolean(KEY_AUTO_UPDATE, false)
        set(value) = settings.edit { putBoolean(KEY_AUTO_UPDATE, value) }

    /**
     * Written with commit before the install session is committed. The update
     * kills the process, so apply() could be lost before it reaches disk.
     */
    var relaunchAfterUpdate: Boolean
        get() = readRelaunchAfterUpdate(appContext)
        set(value) = writeRelaunchAfterUpdate(appContext, value)

    fun lastWhatsNewVersionCode(): Int? {
        if (!settings.contains(KEY_WHATS_NEW_CODE)) return null
        return settings.getInt(KEY_WHATS_NEW_CODE, 0)
    }

    fun markWhatsNewShown(code: Int) {
        settings.edit { putInt(KEY_WHATS_NEW_CODE, code) }
    }

    /** Show "This message was deleted" where a sender deleted for everyone. Off by default. */
    var showDeletedPlaceholders: Boolean
        get() = settings.getBoolean(KEY_SHOW_DELETED, false)
        set(value) = settings.edit { putBoolean(KEY_SHOW_DELETED, value) }

    /** Full-screen holiday/keyword effects and reaction bursts. On by default. */
    var holidayAnimations: Boolean
        get() = settings.getBoolean(KEY_HOLIDAY_FX, true)
        set(value) = settings.edit { putBoolean(KEY_HOLIDAY_FX, value) }

    /** Large animated Noto emoji for emoji-only messages. On by default. */
    var animatedEmojis: Boolean
        get() = settings.getBoolean(KEY_ANIM_EMOJI, true)
        set(value) = settings.edit { putBoolean(KEY_ANIM_EMOJI, value) }

    /** Clean tracking parameters from links shared into the app. On by default. */
    var autoCleanSharedLinks: Boolean
        get() = settings.getBoolean(KEY_AUTO_CLEAN_SHARED, true)
        set(value) = settings.edit { putBoolean(KEY_AUTO_CLEAN_SHARED, value) }

    /** Clean links rule (regex); invalid or blank falls back to the default. */
    var cleanLinksRule: String
        get() = settings.getString(KEY_CLEAN_RULE, null)
            ?.takeIf { com.ubermicrostudios.messagesenhanced.util.LinkCleaner.isValid(it) }
            ?: com.ubermicrostudios.messagesenhanced.util.LinkCleaner.DEFAULT_RULE
        set(value) = settings.edit { putString(KEY_CLEAN_RULE, value.trim()) }

    fun resetCleanLinksRule() {
        settings.edit { remove(KEY_CLEAN_RULE) }
    }

    var notificationsEnabled: Boolean
        get() = settings.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) = settings.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    var hideMessageText: Boolean
        get() = settings.getBoolean(KEY_HIDE_TEXT, false)
        set(value) = settings.edit { putBoolean(KEY_HIDE_TEXT, value) }

    fun loadCookies(): List<StoredCookie> {
        val raw = runCatching { ensureSecure().getString(KEY_COOKIES, null) }.getOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            JsonConfig.json.decodeFromString<List<StoredCookie>>(raw)
        }.getOrDefault(emptyList())
    }

    fun saveCookies(cookies: List<StoredCookie>) {
        val raw = JsonConfig.json.encodeToString(cookies)
        ensureSecure().edit { putString(KEY_COOKIES, raw) }
    }

    fun clearCookies() {
        ensureSecure().edit { remove(KEY_COOKIES) }
    }

    /**
     * Conversation last opened in the list. The disk write runs off the main thread.
     * Tablet cold start reads this to put that chat back in the split pane.
     */
    fun lastSelectedConversationId(): String = synchronized(lock) {
        ensureLastSelectedLocked()
        lastSelectedId
    }

    fun setLastSelectedConversationId(conversationId: String?) {
        val value = conversationId?.trim().orEmpty()
        synchronized(lock) {
            lastSelectedId = value
            lastSelectedLoaded = true
        }
        enqueueLastSelectedWrite()
    }

    /** Drops the saved id when that conversation is archived or moved to trash. */
    fun clearLastSelectedConversationId(conversationId: String) {
        val id = conversationId.trim()
        if (id.isBlank()) return
        synchronized(lock) {
            ensureLastSelectedLocked()
            if (lastSelectedId != id) return
            lastSelectedId = ""
        }
        enqueueLastSelectedWrite()
    }

    private fun ensureLastSelectedLocked() {
        if (lastSelectedLoaded) return
        lastSelectedId = settings.getString(KEY_LAST_CONVERSATION, null).orEmpty()
        lastSelectedLoaded = true
    }

    private fun enqueueLastSelectedWrite() {
        writer.launch {
            val value = synchronized(lock) { lastSelectedId }
            runCatching {
                settings.edit(commit = true) {
                    if (value.isBlank()) remove(KEY_LAST_CONVERSATION) else putString(KEY_LAST_CONVERSATION, value)
                }
            }
        }
    }

    companion object {
        internal const val SETTINGS_FILE = "me_native_settings"
        private const val SECURE_FILE = "me_native_secure"
        private const val SECURE_PLAIN_FILE = "me_native_secure_plain"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_HIDE_TEXT = "hide_message_text"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_UPDATE_CHECK_AT = "update_check_at"
        internal const val KEY_AUTO_UPDATE = "auto_update"
        internal const val KEY_SHOW_DELETED = "show_deleted_placeholders"
        internal const val KEY_HOLIDAY_FX = "holiday_animations"
        internal const val KEY_ANIM_EMOJI = "animated_emojis"
        internal const val KEY_AUTO_CLEAN_SHARED = "auto_clean_shared_links"
        internal const val KEY_CLEAN_RULE = "clean_links_rule"
        internal const val KEY_RELAUNCH_AFTER_UPDATE = "relaunch_after_update"
        private const val KEY_LAST_CONVERSATION = "last_selected_conversation_id"
        private const val KEY_WHATS_NEW_CODE = "whats_new_version_code"

        fun readRelaunchAfterUpdate(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_RELAUNCH_AFTER_UPDATE, false)
        }

        fun writeRelaunchAfterUpdate(context: Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                .edit(commit = true) { putBoolean(KEY_RELAUNCH_AFTER_UPDATE, enabled) }
        }
    }
}
