package com.ubermicrostudios.messagesenhanced

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Launcher entry: open the web app (TWA) for the saved server, or the setup
 * screen on first launch. Has no UI of its own.
 */
class EntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = SecurePrefs(this).serverAddress
        val next = if (saved.isBlank()) Intent(this, SetupActivity::class.java)
        else Intent(this, TwaActivity::class.java)
        startActivity(next)
        finish()
    }
}
