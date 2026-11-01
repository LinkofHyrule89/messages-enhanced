package com.ubermicrostudios.messagesenhanced.push

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.ubermicrostudios.messagesenhanced.data.model.FcmClientOptions
import com.ubermicrostudios.messagesenhanced.data.prefs.EncryptedPrefs
import org.json.JSONObject

/**
 * Firebase Cloud Messaging, configured at runtime from the server (no google-services.json).
 * Mirrors the WebView app's Push.kt.
 */
object Push {
    const val PREFS = "me_native_push"
    const val SECURE_PREFS = "me_native_push_secure"
    private const val KEY_CONFIG = "config"
    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_CHANGED = "token_changed"

    private val lock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /** Opens the encrypted FCM store (and migrates plaintext) off the main thread. */
    fun warm(context: Context) {
        prefs(context)
    }

    fun init(context: Context, cfg: FcmConfig): FirebaseApp {
        val appContext = context.applicationContext
        val options = cfg.toOptions()
        synchronized(lock) {
            val existing = FirebaseApp.getApps(appContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            if (existing != null && sameOptions(existing.options, options)) return existing
            if (existing != null) {
                try {
                    existing.delete()
                } catch (_: Throwable) {
                }
            }
            return try {
                FirebaseApp.initializeApp(appContext, options) ?: FirebaseApp.getInstance()
            } catch (e: IllegalStateException) {
                val again = FirebaseApp.getApps(appContext)
                    .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                if (again != null && sameOptions(again.options, options)) again else throw e
            }
        }
    }

    fun initSaved(context: Context): FirebaseApp? {
        val cfg = loadConfig(context) ?: return null
        return try {
            init(context, cfg)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveConfig(context: Context, cfg: FcmConfig) {
        prefs(context).edit().putString(KEY_CONFIG, cfg.toJson()).commit()
    }

    fun loadConfig(context: Context): FcmConfig? =
        prefs(context).getString(KEY_CONFIG, null)?.let { FcmConfig.parse(it) }

    fun saveToken(context: Context, token: String, markChanged: Boolean) {
        val edit = prefs(context).edit().putString(KEY_TOKEN, token)
        if (markChanged) edit.putBoolean(KEY_TOKEN_CHANGED, true)
        edit.commit()
    }

    fun savedToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun consumeTokenChanged(context: Context): Boolean {
        val p = prefs(context)
        val changed = p.getBoolean(KEY_TOKEN_CHANGED, false)
        if (changed) p.edit().putBoolean(KEY_TOKEN_CHANGED, false).commit()
        return changed
    }

    fun clearToken(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_TOKEN_CHANGED).commit()
    }

    fun fcmEndpoint(token: String): String = "fcm:$token"

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(lock) {
            cachedPrefs?.let { return it }
            val app = context.applicationContext
            val plain = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encrypted = EncryptedPrefs.openOrNull(app, SECURE_PREFS)
            val store = if (encrypted != null) {
                migratePlainToEncrypted(plain, encrypted)
                encrypted
            } else {
                plain
            }
            cachedPrefs = store
            return store
        }
    }

    /**
     * Read the old plaintext file, write the encrypted file, then delete the old
     * keys. A failed encrypted write leaves the plaintext file in place.
     */
    private fun migratePlainToEncrypted(plain: SharedPreferences, encrypted: SharedPreferences) {
        val config = plain.getString(KEY_CONFIG, null)
        val token = plain.getString(KEY_TOKEN, null)
        val hasChanged = plain.contains(KEY_TOKEN_CHANGED)
        if (config == null && token == null && !hasChanged) return
        val encryptedHas = encrypted.contains(KEY_CONFIG) || encrypted.contains(KEY_TOKEN)
        if (!encryptedHas) {
            val edit = encrypted.edit()
            if (config != null) edit.putString(KEY_CONFIG, config)
            if (token != null) edit.putString(KEY_TOKEN, token)
            if (hasChanged) edit.putBoolean(KEY_TOKEN_CHANGED, plain.getBoolean(KEY_TOKEN_CHANGED, false))
            if (!edit.commit()) return
        }
        plain.edit().clear().commit()
    }

    private fun sameOptions(a: FirebaseOptions, b: FirebaseOptions): Boolean =
        a.apiKey == b.apiKey &&
            a.applicationId == b.applicationId &&
            a.projectId == b.projectId &&
            a.gcmSenderId == b.gcmSenderId
}

data class FcmConfig(
    val apiKey: String,
    val appId: String,
    val projectId: String,
    val senderId: String,
) {
    fun toJson(): String = JSONObject()
        .put("api_key", apiKey)
        .put("app_id", appId)
        .put("project_id", projectId)
        .put("sender_id", senderId)
        .toString()

    fun toOptions(): FirebaseOptions = FirebaseOptions.Builder()
        .setApiKey(apiKey)
        .setApplicationId(appId)
        .setProjectId(projectId)
        .setGcmSenderId(senderId)
        .build()

    companion object {
        fun from(options: FcmClientOptions): FcmConfig? {
            val apiKey = options.apiKey.trim()
            val appId = options.appId.trim()
            val projectId = options.projectId.trim()
            val senderId = options.senderId.trim()
            if (apiKey.isEmpty() || appId.isEmpty() || projectId.isEmpty() || senderId.isEmpty()) return null
            return FcmConfig(apiKey, appId, projectId, senderId)
        }

        fun parse(json: String?): FcmConfig? {
            if (json.isNullOrBlank()) return null
            val o = try {
                JSONObject(json)
            } catch (_: Throwable) {
                return null
            }
            val apiKey = stringField(o, "api_key") ?: return null
            val appId = stringField(o, "app_id") ?: return null
            val projectId = stringField(o, "project_id") ?: return null
            val senderId = stringField(o, "sender_id") ?: return null
            return FcmConfig(apiKey, appId, projectId, senderId)
        }

        private fun stringField(o: JSONObject, key: String): String? {
            val v = o.opt(key)
            if (v !is String) return null
            return v.trim().ifEmpty { null }
        }
    }
}
