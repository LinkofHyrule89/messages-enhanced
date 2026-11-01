package com.ubermicrostudios.messagesenhanced.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification actions that start a download. Tap on the notification itself
 * opens the app; this receiver is only the Update and Retry actions.
 *
 * Approach adapted from Neko's update notification receiver (Apache-2.0).
 */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ENQUEUE) return
        val spec = UpdateDownloadSpec.from(intent) ?: UpdateDownloadSpec.read(context) ?: return
        val release = spec.toRelease() ?: return
        UpdateNotifier.cancelOutcome(context)
        UpdateDownloadInstallWorker.enqueue(context, release)
    }

    companion object {
        const val ACTION_ENQUEUE = "com.ubermicrostudios.messagesenhanced.UPDATE_ENQUEUE"

        internal fun pendingIntent(context: Context, spec: UpdateDownloadSpec, requestCode: Int): PendingIntent {
            val intent = Intent(context, UpdateActionReceiver::class.java).apply {
                action = ACTION_ENQUEUE
                spec.writeTo(this)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
