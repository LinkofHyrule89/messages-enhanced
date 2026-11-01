package com.ubermicrostudios.messagesenhanced.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences, or null when the keystore cannot open.
 * Callers fall back to plaintext so a keystore failure does not drop data.
 */
object EncryptedPrefs {
    fun openOrNull(context: Context, fileName: String): SharedPreferences? {
        val app = context.applicationContext
        return try {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                fileName,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Throwable) {
            null
        }
    }
}
