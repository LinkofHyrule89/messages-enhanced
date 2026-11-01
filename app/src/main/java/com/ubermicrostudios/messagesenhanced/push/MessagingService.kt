package com.ubermicrostudios.messagesenhanced.push

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
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.repo.PushRepository
import com.ubermicrostudios.messagesenhanced.ui.conversations.ConversationsActivity
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MessagingService : FirebaseMessagingService() {

    @Inject lateinit var pushRepository: PushRepository
    @Inject lateinit var notifications: ConversationNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        if (Push.initSaved(this) == null) return
        val data = message.data
        when (data["type"]) {
            "clear" -> clear(data)
            "alert" -> showSimple(data)
            "message", "test" -> {
                val shown = runBlocking(Dispatchers.IO) { notifications.showIncoming(data) }
                if (!shown) showSimple(data)
            }
        }
    }

    private fun clear(data: Map<String, String>) {
        val key = MessageNotificationPayload.conversationKey(data)
        val tag = data["tag"].orEmpty()
        if (key.isNotEmpty()) notifications.dismiss(key)
        if (tag.isNotEmpty() && tag != key) {
            NotificationManagerCompat.from(this).cancel(tag, NOTIF_ID)
        }
    }

    override fun onNewToken(token: String) {
        Push.saveToken(this, token, markChanged = true)
        if (::pushRepository.isInitialized) {
            scope.launch { pushRepository.resubscribeIfEnabled(token) }
        }
    }

    /** Alerts, and message pushes that have no conversation id, stay a single text notification. */
    private fun showSimple(data: Map<String, String>) {
        if (!notificationsGranted(this)) return
        val tag = data["tag"]?.takeIf { it.isNotEmpty() } ?: data["conv"].orEmpty().ifBlank { "message" }
        ensureChannels(this)
        val channel = if (data["type"] == "alert") CHANNEL_ALERTS else CHANNEL_MESSAGES
        val rawPerson = data["person"].orEmpty().ifBlank { data["sender_name"].orEmpty() }.ifBlank { data["sender"].orEmpty() }
        val title = formatPhoneForDisplay(
            data["title"].orEmpty().ifBlank { rawPerson }.ifBlank { getString(R.string.app_name) },
        )
        val body = data["body"].orEmpty()
        val whenMs = data["ts"]?.toLongOrNull()?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val conv = data["conv"].orEmpty()
        val open = Intent(this, ConversationsActivity::class.java).apply {
            action = ConversationsActivity.ACTION_OPEN_THREAD
            putExtra(ConversationsActivity.EXTRA_CONV, conv)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            MessageNotificationIds.requestCode(tag, MessageNotificationIds.SLOT_CONTENT),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val color = ContextCompat.getColor(this, R.color.accent)
        val publicVersion = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(getString(R.string.notif_public_title))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(whenMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(tag, NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_MENTIONS = "mentions"
        const val NOTIF_ID = 1

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MENTIONS, context.getString(R.string.channel_mentions), NotificationManager.IMPORTANCE_HIGH),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        fun notificationsGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
