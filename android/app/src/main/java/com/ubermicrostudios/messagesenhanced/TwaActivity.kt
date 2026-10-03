package com.ubermicrostudios.messagesenhanced

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.ubermicrostudios.messagesenhanced.net.ServerAddress

/**
 * The app's launcher (via the ".EntryActivity" alias): android-browser-helper's
 * stock LauncherActivity, the path Bubblewrap apps use, with the URL taken
 * from the saved server instead of the manifest.
 *
 * - No server saved: opens the setup screen.
 * - First launch with a server: asks for Android's notification permission,
 *   then launches.
 * - Otherwise launches the Trusted Web Activity at once. LauncherActivity
 *   gives each task its own Custom Tabs session (keyed by task id), so a new
 *   launch after the TWA was closed, or after Chrome or this app was killed,
 *   always starts a fresh TWA instead of reattaching to a dead one.
 * - When the TWA closes (Back on the conversation list), this activity
 *   finishes too, so nothing is left in the task.
 * - If no browser supports TWAs: a Custom Tab, then a plain browser, then the
 *   setup screen with the error.
 */
class TwaActivity : LauncherActivity() {
    private var hasServer = false
    private var askNotifications = false
    private var launchStarted = false
    private var coveredSinceLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        hasServer = try { ServerAddress.launchUrl(SecurePrefs(this).serverAddress) != null } catch (t: Throwable) {
            LaunchLog.record(this, "Couldn't read the saved server address", t); false
        }
        askNotifications = hasServer && Notifications.shouldAskOnLaunch(this)
        super.onCreate(savedInstanceState) // launches when shouldLaunchImmediately()
        if (isFinishing) return
        if (!hasServer) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        if (askNotifications) {
            // Web notifications go through this permission (notification delegation).
            Notifications.markAsked(this)
            requestPermissions(arrayOf(Notifications.PERMISSION), REQ_NOTIFICATIONS)
        }
    }

    override fun shouldLaunchImmediately(): Boolean = hasServer && !askNotifications

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && !launchStarted && !isFinishing) launchTwa()
    }

    override fun launchTwa() {
        launchStarted = true
        try {
            super.launchTwa()
        } catch (t: Throwable) {
            LaunchLog.record(this, "Full-screen launch failed; trying a Custom Tab", t)
            openFallback(launchingUrl, null)
        }
    }

    /** The saved server's start page, or a link for the same server from the launching intent. */
    override fun getLaunchingUrl(): Uri {
        val saved = SecurePrefs(this).serverAddress
        val start = ServerAddress.launchUrl(saved) ?: return Uri.EMPTY
        val origin = ServerAddress.normalize(saved).origin
        val data = intent?.data
        if (data != null && data.scheme == "https" &&
            "${data.scheme}://${data.authority}".equals(origin, ignoreCase = true)) return data
        return Uri.parse(start)
    }

    override fun getFallbackStrategy(): TwaLauncher.FallbackStrategy =
        TwaLauncher.FallbackStrategy { _, builder, provider, done ->
            openFallback(builder.uri, provider)
            done?.run()
        }

    /** Custom Tab, else a plain browser intent, else the setup screen with the error. */
    private fun openFallback(url: Uri, provider: String?) {
        try {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (provider != null) tab.intent.setPackage(provider)
            tab.launchUrl(this, url)
            return
        } catch (t: Throwable) {
            LaunchLog.record(this, "Custom Tab failed; trying the default browser", t)
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            return
        } catch (t: ActivityNotFoundException) {
            LaunchLog.record(this, "No browser is installed to open $url. Install or enable Chrome.", t)
        } catch (t: Throwable) {
            LaunchLog.record(this, "Opening the browser failed", t)
        }
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FROM_FAILURE, true))
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (launchStarted) coveredSinceLaunch = true
    }

    override fun onResume() {
        super.onResume()
        // Back on screen after the browser covered it: the TWA / Custom Tab
        // is gone (its window may be translucent, so onRestart doesn't
        // always run). Close so the next open starts fresh.
        if (coveredSinceLaunch) finish()
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 41
    }
}
