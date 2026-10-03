package com.ubermicrostudios.messagesenhanced

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ubermicrostudios.messagesenhanced.net.ServerAddress

/**
 * Settings storage. The server password is stored in EncryptedSharedPreferences
 * (AES-256-GCM, key held in the Android Keystore). It is never shown back in the
 * UI; the UI only knows whether one is saved.
 */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var serverAddress: String
        get() = prefs.getString(KEY_ADDRESS, null) ?: ServerAddress.DEFAULT
        set(v) = prefs.edit { putString(KEY_ADDRESS, v) }

    val hasPassword: Boolean get() = !prefs.getString(KEY_PASSWORD, null).isNullOrEmpty()

    /** Only read at send time; never displayed. */
    fun readPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun savePassword(value: String) = prefs.edit { putString(KEY_PASSWORD, value) }

    fun clearPassword() = prefs.edit { remove(KEY_PASSWORD) }

    companion object {
        private const val FILE = "me_secure_prefs"
        private const val KEY_ADDRESS = "server_address"
        private const val KEY_PASSWORD = "server_password"
    }
}
