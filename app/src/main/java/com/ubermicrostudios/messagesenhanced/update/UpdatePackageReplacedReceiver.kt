package com.ubermicrostudios.messagesenhanced.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences

/**
 * The package update kills this process, so STATUS_SUCCESS is not a reliable
 * place to reopen the app. The system delivers MY_PACKAGE_REPLACED to the new
 * process instead. The relaunch pref is set only when this app committed the
 * session, and it is cleared here so a later sideload does not reopen the app.
 */
class UpdatePackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val triggered = AppPreferences.readRelaunchAfterUpdate(context)
        if (!UpdatePolicy.shouldRelaunch(intent?.action, triggered)) return
        AppPreferences.writeRelaunchAfterUpdate(context, false)
        UpdateDownloadSpec.clear(context)
        UpdateNotifier.dismissProgress(context)
        UpdateNotifier.showInstalled(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(launch) }
    }
}
