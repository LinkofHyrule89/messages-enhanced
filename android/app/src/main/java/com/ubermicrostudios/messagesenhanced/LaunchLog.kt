package com.ubermicrostudios.messagesenhanced

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny on-device log of launch problems and crashes, shown on the setup
 * screen. Holds only error text (no cookies, passwords or page content) and
 * keeps the last few entries.
 */
object LaunchLog {
    private const val PREFS = "me_diagnostics"
    private const val KEY = "log"
    private const val MAX_CHARS = 6000

    fun record(context: Context, what: String, t: Throwable? = null) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val detail = t?.let { "\n" + Log.getStackTraceString(it).lineSequence().take(12).joinToString("\n") }.orEmpty()
        Log.w("MessagesEnhanced", what, t)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = ("$stamp  $what$detail\n\n" + prefs.getString(KEY, "").orEmpty()).take(MAX_CHARS)
        // commit(): this may run just before the process dies.
        prefs.edit().putString(KEY, next).commit()
    }

    fun read(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
