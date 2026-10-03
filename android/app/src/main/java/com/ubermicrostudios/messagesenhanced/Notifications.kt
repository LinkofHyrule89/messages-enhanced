package com.ubermicrostudios.messagesenhanced

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** Android's notification permission, which the web app's notifications go through. */
object Notifications {
    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS
    private const val PREFS = "me_app_state"
    private const val KEY_ASKED = "notifications_asked"

    fun granted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 13+, not granted, and not asked by the app before. */
    fun shouldAskOnLaunch(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun openSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            )
        } catch (t: Throwable) {
            LaunchLog.record(activity, "Couldn't open the notification settings", t)
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + activity.packageName))
                )
            } catch (_: Throwable) {}
        }
    }
}

/**
 * messagesenhanced://notifications (from the web app's Settings): asks for
 * the notification permission if Android still can, otherwise opens this
 * app's notification settings. Then returns to the web app.
 */
class NotificationSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Notifications.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Notifications.markAsked(this)
            requestPermissions(arrayOf(Notifications.PERMISSION), 42)
        } else {
            // Permission granted but notifications (or a channel) switched off.
            Notifications.openSettings(this)
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Notifications allowed. Tap Turn on in the web app.", Toast.LENGTH_LONG).show()
        } else {
            // Denied, or Android no longer shows the prompt: let the user switch it on.
            Notifications.openSettings(this)
        }
        finish()
    }
}
