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
        val next = try {
            if (SecurePrefs(this).serverAddress.isBlank()) Intent(this, SetupActivity::class.java)
            else Intent(this, TwaActivity::class.java)
        } catch (t: Throwable) {
            LaunchLog.record(this, "Couldn't read the saved server address", t)
            Intent(this, SetupActivity::class.java)
        }
        startActivity(next)
        finish()
    }
}
