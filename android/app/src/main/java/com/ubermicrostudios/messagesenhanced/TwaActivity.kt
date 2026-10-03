package com.ubermicrostudios.messagesenhanced

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.trusted.TrustedWebActivityIntentBuilder
import androidx.core.content.ContextCompat
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.splashscreens.PwaWrapperSplashScreenStrategy

/**
 * Opens the web app at the user's saved server, in this order:
 * 1. Trusted Web Activity (full screen; Chrome checks the server's
 *    /.well-known/assetlinks.json for this app's signing key, and shows a
 *    small URL bar if it doesn't match),
 * 2. a Custom Tab, if no browser supports TWAs or the TWA doesn't start,
 * 3. a plain browser intent.
 * If all fail, the setup screen opens with the error, so the app never just
 * closes.
 *
 * This is a plain Activity driving TwaLauncher instead of the helper's
 * LauncherActivity: LauncherActivity relaunches itself in a new task when it
 * is started from inside the app, and the relaunched copy saw the first copy
 * still "alive" and finished at once, so nothing opened.
 */
class TwaActivity : Activity() {
    private var launcher: TwaLauncher? = null
    private var splash: PwaWrapperSplashScreenStrategy? = null
    private var launched = false
    private var fellBack = false
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        if (!launched) {
            LaunchLog.record(this, "The full-screen app didn't start within ${WATCHDOG_MS / 1000} s; opened as a Custom Tab instead")
            launcher?.destroy(); launcher = null
            openFallback(launchUrl ?: return@Runnable, null)
        }
    }
    private var launchUrl: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean(STATE_LAUNCHED) == true) { finish(); return }
        val url = try { urlToOpen() } catch (t: Throwable) {
            LaunchLog.record(this, "Couldn't read the saved server address", t); null
        }
        if (url == null) { toSetup(); return }
        launchUrl = url
        try {
            val dark = ContextCompat.getColor(this, R.color.dark)
            val colors = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(dark).setNavigationBarColor(dark).setNavigationBarDividerColor(dark).build()
            val builder = TrustedWebActivityIntentBuilder(url)
                .setDefaultColorSchemeParams(colors)
                .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, colors)
            splash = PwaWrapperSplashScreenStrategy(
                this, R.drawable.splash, ContextCompat.getColor(this, R.color.brand),
                ImageView.ScaleType.CENTER, null, 300, FILE_PROVIDER, false,
            )
            launcher = TwaLauncher(this).also {
                it.launch(builder, null, splash, { onLaunched() }) { ctx, b, provider, done ->
                    // No TWA-capable browser: Custom Tab, then a plain browser.
                    openFallback(b.uri, provider)
                    done?.run()
                }
            }
            handler.postDelayed(watchdog, WATCHDOG_MS)
        } catch (t: Throwable) {
            LaunchLog.record(this, "Full-screen launch failed; trying a Custom Tab", t)
            launcher?.destroy(); launcher = null
            openFallback(url, null)
        }
    }

    private fun onLaunched() {
        launched = true
        handler.removeCallbacks(watchdog)
    }

    /** Custom Tab, else a plain browser intent, else the setup screen with the error. */
    private fun openFallback(url: Uri, provider: String?) {
        if (fellBack) return
        fellBack = true
        handler.removeCallbacks(watchdog)
        try {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (provider != null) tab.intent.setPackage(provider)
            tab.launchUrl(this, url)
            launched = true
            return
        } catch (t: Throwable) {
            LaunchLog.record(this, "Custom Tab failed; trying the default browser", t)
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            launched = true
            return
        } catch (t: ActivityNotFoundException) {
            LaunchLog.record(this, "No browser is installed to open $url. Install or enable Chrome.", t)
        } catch (t: Throwable) {
            LaunchLog.record(this, "Opening the browser failed", t)
        }
        toSetup()
    }

    private fun toSetup() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_FROM_FAILURE, true))
        finish()
    }

    /** The saved server's /app/, or a link for the same server from the launching intent. */
    private fun urlToOpen(): Uri? {
        val origin = SecurePrefs(this).serverAddress.trimEnd('/')
        if (origin.isEmpty()) return null
        val data = intent?.data
        if (data != null && data.scheme == "https" &&
            "${data.scheme}://${data.authority}".equals(origin, ignoreCase = true)) return data
        return Uri.parse("$origin/app/")
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        splash?.onActivityEnterAnimationComplete()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the browser: this window has nothing to show, so close it.
        if (launched) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCHED, launched)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        launcher?.destroy(); launcher = null
        splash?.destroy(); splash = null
        super.onDestroy()
    }

    companion object {
        private const val STATE_LAUNCHED = "launched"
        private const val WATCHDOG_MS = 10_000L
        private const val FILE_PROVIDER = "com.ubermicrostudios.messagesenhanced.fileprovider"

        fun intent(context: Context) = Intent(context, TwaActivity::class.java)
    }
}
