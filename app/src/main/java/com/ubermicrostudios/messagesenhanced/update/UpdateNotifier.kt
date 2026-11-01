package com.ubermicrostudios.messagesenhanced.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ubermicrostudios.messagesenhanced.BuildConfig
import com.ubermicrostudios.messagesenhanced.R

/**
 * Update notifications: download progress, the install prompt, and the
 * available / installed / failed results.
 *
 * Approach adapted from Neko's AppUpdateNotifier (Apache-2.0).
 */
internal object UpdateNotifier {
    const val NOTIFICATION_ID = 7101
    private const val INSTALLING_ID = 7102
    private const val AVAILABLE_ID = 7103
    private const val RESULT_ID = 7104
    private const val PROMPT_ID = 7105

    private const val CHANNEL_ID = "app_updates"
    private const val ALERT_CHANNEL_ID = "app_update_alerts"

    private const val REQUEST_OPEN = 7110
    private const val REQUEST_UPDATE_ACTION = 7201
    private const val REQUEST_RETRY_ACTION = 7202
    private const val REQUEST_INSTALL_ACTION = 7203

    fun progressNotification(context: Context, progress: UpdateProgress): Notification {
        val app = context.applicationContext
        ensureChannels(app)
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(app, R.color.accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        openApp(app)?.let { builder.setContentIntent(it) }
        when (progress) {
            is UpdateProgress.Downloading -> {
                val percent = progress.percent
                builder.setContentTitle(app.getString(R.string.update_downloading))
                if (percent == null) {
                    builder.setContentText(app.getString(R.string.update_progress_unknown))
                    builder.setProgress(100, 0, true)
                } else {
                    builder.setContentText(app.getString(R.string.update_downloading_percent, percent))
                    builder.setProgress(100, percent, false)
                }
            }
            UpdateProgress.Installing -> {
                val title = app.getString(R.string.update_installing)
                builder.setContentTitle(title)
                    .setContentText(title)
                    .setProgress(100, 0, true)
            }
        }
        return builder.build()
    }

    fun show(context: Context, progress: UpdateProgress) {
        post(context, NOTIFICATION_ID, progressNotification(context, progress))
    }

    /** Ongoing install notice that outlives the foreground download notification. */
    fun showInstalling(context: Context) {
        val app = context.applicationContext
        ensureChannels(app)
        val title = app.getString(R.string.update_installing)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(app, R.color.accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentTitle(title)
            .setContentText(title)
            .setProgress(100, 0, true)
            .build()
        post(context, INSTALLING_ID, notification)
    }

    /**
     * Silent install was not allowed. The system confirm dialog is the primary
     * prompt; this notification is the Install action if that dialog cannot show.
     */
    fun showInstallPrompt(context: Context, confirm: Intent) {
        val app = context.applicationContext
        ensureChannels(app)
        val launch = PendingIntent.getActivity(
            app,
            REQUEST_INSTALL_ACTION,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = alert(app)
            .setContentTitle(app.getString(R.string.update_install_ready))
            .setContentText(app.getString(R.string.update_install_prompt))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .addAction(0, app.getString(R.string.update_install_action), launch)
            .build()
        try {
            NotificationManagerCompat.from(app).cancel(INSTALLING_ID)
        } catch (_: SecurityException) {
        }
        post(context, PROMPT_ID, notification)
    }

    fun showAvailable(context: Context, release: AppRelease) {
        val app = context.applicationContext
        ensureChannels(app)
        val spec = UpdateDownloadSpec.from(release)
        val builder = alert(app)
            .setContentTitle(app.getString(R.string.update_available))
            .setContentText(release.tag.ifBlank { release.version.toString() })
            .setAutoCancel(true)
            .addAction(
                0,
                app.getString(R.string.update_action),
                UpdateActionReceiver.pendingIntent(app, spec, REQUEST_UPDATE_ACTION),
            )
        openApp(app)?.let { builder.setContentIntent(it) }
        post(context, AVAILABLE_ID, builder.build())
    }

    fun showInstalled(context: Context) {
        cancelInstallUi(context)
        val app = context.applicationContext
        ensureChannels(app)
        val builder = alert(app)
            .setContentTitle(app.getString(R.string.update_installed))
            .setContentText(app.getString(R.string.update_installed_version, BuildConfig.VERSION_NAME))
            .setAutoCancel(true)
        openApp(app)?.let { builder.setContentIntent(it) }
        post(context, RESULT_ID, builder.build())
    }

    fun showFailed(context: Context, detail: String?, spec: UpdateDownloadSpec?) {
        // Leave the foreground notification id alone. WorkManager removes it when
        // the worker returns; cancelling it early crashes the foreground service.
        cancelInstallUi(context)
        val app = context.applicationContext
        ensureChannels(app)
        val text = detail?.takeIf { it.isNotBlank() } ?: app.getString(R.string.update_failed_generic)
        val builder = alert(app)
            .setContentTitle(app.getString(R.string.update_failed_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        openApp(app)?.let { builder.setContentIntent(it) }
        if (spec != null) {
            builder.addAction(
                0,
                app.getString(R.string.update_retry),
                UpdateActionReceiver.pendingIntent(app, spec, REQUEST_RETRY_ACTION),
            )
        }
        post(context, RESULT_ID, builder.build())
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        } catch (_: SecurityException) {
        }
    }

    fun cancelInstallUi(context: Context) {
        val app = context.applicationContext
        try {
            val nm = NotificationManagerCompat.from(app)
            nm.cancel(INSTALLING_ID)
            nm.cancel(PROMPT_ID)
        } catch (_: SecurityException) {
        }
    }

    fun cancelAvailable(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(AVAILABLE_ID)
        } catch (_: SecurityException) {
        }
    }

    fun cancelOutcome(context: Context) {
        val app = context.applicationContext
        try {
            val nm = NotificationManagerCompat.from(app)
            nm.cancel(AVAILABLE_ID)
            nm.cancel(RESULT_ID)
        } catch (_: SecurityException) {
        }
    }

    fun dismissProgress(context: Context) {
        cancel(context)
        cancelInstallUi(context)
    }

    private fun alert(context: Context): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        val app = context.applicationContext
        if (!notificationsAllowed(app)) return
        try {
            NotificationManagerCompat.from(app).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val progress = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_updates),
            NotificationManager.IMPORTANCE_LOW,
        )
        progress.setShowBadge(false)
        nm.createNotificationChannel(progress)
        val alerts = NotificationChannel(
            ALERT_CHANNEL_ID,
            context.getString(R.string.channel_update_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(alerts)
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
