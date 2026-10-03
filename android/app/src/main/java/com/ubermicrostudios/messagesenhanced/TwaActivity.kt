package com.ubermicrostudios.messagesenhanced

import android.net.Uri
import com.google.androidbrowserhelper.trusted.LauncherActivity

/**
 * The web app as a Trusted Web Activity, opened at the user's saved server.
 * Chrome checks that server's /.well-known/assetlinks.json for this app's
 * signing key; if it doesn't match, the page opens in a Custom Tab instead.
 */
class TwaActivity : LauncherActivity() {
    override fun getLaunchingUrl(): Uri {
        val data = intent?.data
        val origin = SecurePrefs(this).serverAddress.trimEnd('/')
        // A tapped notification or link for our own server keeps its path.
        if (data != null && data.scheme == "https" && origin.isNotEmpty() &&
            "${data.scheme}://${data.authority}".equals(origin, ignoreCase = true)) return data
        return Uri.parse("$origin/app/")
    }
}
