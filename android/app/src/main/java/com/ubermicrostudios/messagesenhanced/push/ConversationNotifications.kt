package com.ubermicrostudios.messagesenhanced.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.ubermicrostudios.messagesenhanced.share.ShareShortcuts
import com.ubermicrostudios.messagesenhanced.share.ShareTargets
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ubermicrostudios.messagesenhanced.util.CircleBitmaps
import com.ubermicrostudios.messagesenhanced.util.InitialsBitmap
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.ui.conversations.ConversationsActivity
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One MessagingStyle notification per conversation, plus a summary while any remain.
 * Reply and mark-as-read are notification actions; they do not open the app.
 */
@Singleton
class ConversationNotifications @Inject constructor(
    @ApplicationContext context: Context,
    private val store: ConversationNotificationStore,
    private val prefs: AppPreferences,
    private val baseUrl: BaseUrlProvider,
    private val imageLoader: ImageLoader,
) {
    private val context = context.applicationContext
    private val postLock = Any()
    private val iconCache = ConcurrentHashMap<String, Bitmap>()

    suspend fun showIncoming(data: Map<String, String>): Boolean {
        if (MessageNotificationPayload.conversationKey(data).isBlank()) return false
        if (!MessagingService.notificationsGranted(context)) return true
        val incoming = MessageNotificationPayload.parse(
            data = data,
            nowMs = System.currentTimeMillis(),
            origin = baseUrl.origin(),
            hideBody = prefs.hideMessageText,
            hiddenBody = context.getString(R.string.notif_public_title),
        ) ?: return false
        store.update {
            MessageNotificationHistory.append(
                threads = it,
                conversationId = incoming.conversationId,
                title = incoming.title,
                isGroup = incoming.isGroup,
                line = incoming.line,
                groupAvatarUrl = incoming.groupAvatarUrl,
                source = incoming.source,
                legacyTag = incoming.legacyTag,
                mentionedBy = incoming.mentionedBy,
            )
        }
        val thread = store.snapshot().firstOrNull { it.conversationId == incoming.conversationId } ?: return true
        val icons = loadIcons(iconUrls(thread))
        synchronized(postLock) {
            val current = store.snapshot()
            val fresh = current.firstOrNull { it.conversationId == incoming.conversationId } ?: return@synchronized
            MessagingService.ensureChannels(context)
            cancelLegacy(fresh)
            postConversation(fresh, icons, silent = false)
            postSummary(current)
        }
        return true
    }

    /** Adds the sent reply onto the conversation notification without a new alert. */
    suspend fun appendSelfAndPost(
        conversationId: String,
        text: String,
        timestampMs: Long,
        title: String,
        isGroup: Boolean,
    ) {
        if (conversationId.isBlank() || text.isBlank()) return
        if (!MessagingService.notificationsGranted(context)) return
        store.update {
            MessageNotificationHistory.appendSelf(
                threads = it,
                conversationId = conversationId,
                text = text,
                timestampMs = timestampMs,
                title = title,
                isGroup = isGroup,
                selfName = context.getString(R.string.notif_you),
            )
        }
        val thread = store.snapshot().firstOrNull { it.conversationId == conversationId } ?: return
        val icons = loadIcons(iconUrls(thread))
        synchronized(postLock) {
            val current = store.snapshot()
            val fresh = current.firstOrNull { it.conversationId == conversationId } ?: return@synchronized
            MessagingService.ensureChannels(context)
            cancelLegacy(fresh)
            NotificationManagerCompat.from(context).cancel(MessageNotificationIds.replyErrorId(conversationId))
            postConversation(fresh, icons, silent = true)
            postSummary(current)
        }
    }

    /** Drops every conversation notification. Used when the account or server changes. */
    fun clearAll() {
        val existing = store.clear()
        val nm = NotificationManagerCompat.from(context)
        for (thread in existing) {
            nm.cancel(MessageNotificationIds.notificationId(thread.conversationId))
            nm.cancel(MessageNotificationIds.replyErrorId(thread.conversationId))
            cancelLegacy(thread)
        }
        nm.cancel(MessageNotificationIds.SUMMARY_ID)
    }

    /** Drops the conversation's history and its notification. Cancels the summary when nothing is left. */
    fun dismiss(conversationId: String) {
        if (conversationId.isBlank()) return
        val removed = store.snapshot().firstOrNull { it.conversationId == conversationId }
        val threads = store.update { MessageNotificationHistory.clear(it, conversationId) }
        synchronized(postLock) {
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(MessageNotificationIds.notificationId(conversationId))
            nm.cancel(MessageNotificationIds.replyErrorId(conversationId))
            if (removed != null) cancelLegacy(removed) else nm.cancel(conversationId, MessagingService.NOTIF_ID)
            if (threads.isEmpty()) {
                nm.cancel(MessageNotificationIds.SUMMARY_ID)
            } else {
                MessagingService.ensureChannels(context)
                postSummary(threads)
            }
        }
    }

    /**
     * Posts "Failed to send, tap to retry". Tapping re-queues the same text; the
     * conversation notification is re-posted so the shade's reply spinner stops.
     */
    suspend fun showReplyFailed(conversationId: String, title: String, text: String = "", isGroup: Boolean = false) {
        if (conversationId.isBlank() || !MessagingService.notificationsGranted(context)) return
        val name = title.ifBlank {
            store.snapshot().firstOrNull { it.conversationId == conversationId }?.title.orEmpty()
        }
        val where = formatPhoneForDisplay(name).ifBlank { context.getString(R.string.app_name) }
        MessagingService.ensureChannels(context)
        val tap = if (text.isNotBlank()) {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActions.ACTION_RETRY
                putExtra(NotificationActions.EXTRA_CONV, conversationId)
                putExtra(NotificationActions.EXTRA_TITLE, title)
                putExtra(NotificationActions.EXTRA_GROUP, isGroup)
                putExtra(NotificationActions.EXTRA_TEXT, text.take(4000))
            }
            PendingIntent.getBroadcast(
                context,
                MessageNotificationIds.requestCode(conversationId, MessageNotificationIds.SLOT_RETRY),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            openThread(conversationId, MessageNotificationIds.SLOT_ERROR)
        }
        val body = if (text.isNotBlank()) "$where: ${text.take(120)}" else where
        val builder = NotificationCompat.Builder(context, MessagingService.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(context.getString(if (text.isNotBlank()) R.string.notif_reply_failed_retry else R.string.notif_reply_failed))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
        if (text.isNotBlank()) {
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notif_open_chat),
                openThread(conversationId, MessageNotificationIds.SLOT_ERROR),
            )
        }
        notify(MessageNotificationIds.replyErrorId(conversationId), builder.build())
        repost(conversationId)
    }

    fun clearReplyFailed(conversationId: String) {
        NotificationManagerCompat.from(context).cancel(MessageNotificationIds.replyErrorId(conversationId))
    }

    /** Re-posts a conversation notification silently (clears a pending inline-reply spinner). */
    private suspend fun repost(conversationId: String) {
        val thread = store.snapshot().firstOrNull { it.conversationId == conversationId } ?: return
        val icons = loadIcons(iconUrls(thread))
        synchronized(postLock) {
            val fresh = store.snapshot().firstOrNull { it.conversationId == conversationId } ?: return@synchronized
            postConversation(fresh, icons, silent = true)
        }
    }

    private fun postConversation(thread: NotifThread, icons: Map<String, Bitmap>, silent: Boolean) {
        val selfName = context.getString(R.string.notif_you)
        val self = Person.Builder().setName(selfName).setKey(MessageNotificationHistory.SELF_KEY).build()
        val mention = thread.mentionedBy.isNotBlank()
        val channel = if (mention) MessagingService.CHANNEL_MENTIONS else MessagingService.CHANNEL_MESSAGES
        val style = NotificationCompat.MessagingStyle(self).setGroupConversation(thread.isGroup || mention)
        if (mention) {
            style.setConversationTitle(MessageNotificationPayload.mentionTitle(formatPhoneForDisplay(thread.mentionedBy)))
        } else if (thread.isGroup) {
            val title = formatPhoneForDisplay(thread.title).ifBlank { context.getString(R.string.app_name) }
            style.setConversationTitle(title)
        }
        for (line in thread.lines) {
            style.addMessage(line.text, line.timestampMs, personFor(line, self, selfName, icons))
        }
        val whenMs = thread.lines.lastOrNull()?.timestampMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        val color = ContextCompat.getColor(context, R.color.accent)
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setStyle(style)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openThread(thread.conversationId, MessageNotificationIds.SLOT_CONTENT))
            .setDeleteIntent(dismissPending(thread.conversationId))
            .setGroup(MessageNotificationIds.GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addAction(replyAction(thread))
            .addAction(markReadAction(thread.conversationId))
        if (mention && thread.isGroup) {
            formatPhoneForDisplay(thread.title).takeIf { it.isNotBlank() }?.let { builder.setSubText(it) }
        }
        if (silent) builder.setSilent(true)
        // Conversation photo (group icon or sender) or an initials circle, never the app logo.
        val convIcon = largeIcon(thread, icons)
        convIcon?.let { icon -> builder.setLargeIcon(icon.toIcon(context)) }
        // Conversation-style (Android 11+): tie to the long-lived conversation shortcut so the
        // system shows the person/group avatar with only a small app badge.
        conversationShortcut(thread, convIcon)?.let { sid ->
            builder.setShortcutId(sid)
            builder.setLocusId(LocusIdCompat(sid))
        }
        val posted = builder.build()
        val id = MessageNotificationIds.notificationId(thread.conversationId)
        try {
            NotificationManagerCompat.from(context).notify(id, posted)
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
            // A binder full of avatar bitmaps can fail the post. The text history still shows.
            if (icons.isEmpty()) return
            postConversation(thread, emptyMap(), silent)
        }
    }

    private fun postSummary(threads: List<NotifThread>) {
        if (threads.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(MessageNotificationIds.SUMMARY_ID)
            return
        }
        val selfName = context.getString(R.string.notif_you)
        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.app_name))
            .setSummaryText(summaryCount(threads.size))
        for (thread in threads.take(INBOX_LINES)) {
            inbox.addLine(MessageNotificationHistory.summaryLine(thread, selfName = selfName))
        }
        val open = PendingIntent.getActivity(
            context,
            MessageNotificationIds.requestCode("summary", MessageNotificationIds.SLOT_SUMMARY),
            Intent(context, ConversationsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MessagingService.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summaryCount(threads.size))
            .setStyle(inbox)
            .setGroup(MessageNotificationIds.GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
        notify(MessageNotificationIds.SUMMARY_ID, notification)
    }

    private fun personFor(line: NotifLine, self: Person, selfName: String, icons: Map<String, Bitmap>): Person {
        if (line.fromMe) return self
        val name = formatPhoneForDisplay(line.senderName).ifBlank { selfName }
        val builder = Person.Builder().setName(name).setKey(line.senderKey.ifBlank { name })
        val bitmap = icons[line.avatarUrl] ?: initialsBitmap(name, line.senderKey.ifBlank { name })
        builder.setIcon(IconCompat.createWithBitmap(bitmap))
        return builder.build()
    }

    private fun conversationShortcut(thread: NotifThread, icon: IconCompat?): String? = runCatching {
        val sid = ShareTargets.shortcutId(thread.conversationId)
        val exists = ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == sid }
        if (!exists) {
            val name = formatPhoneForDisplay(
                if (thread.isGroup) thread.title else thread.lines.lastOrNull { !it.fromMe }?.senderName ?: thread.title,
            ).ifBlank { "Conversation" }
            val open = Intent(context, ConversationsActivity::class.java).apply {
                action = ConversationsActivity.ACTION_OPEN_THREAD
                putExtra(ConversationsActivity.EXTRA_CONV, thread.conversationId)
            }
            val b = ShortcutInfoCompat.Builder(context, sid)
                .setShortLabel(name.take(24))
                .setLongLabel(name)
                .setIntent(open)
                .setLongLived(true)
                .setCategories(setOf(ShareShortcuts.CATEGORY))
            icon?.let { b.setIcon(it) }
            if (!thread.isGroup) b.setPerson(Person.Builder().setName(name).setKey(sid).also { p -> icon?.let { p.setIcon(it) } }.build())
            ShortcutManagerCompat.pushDynamicShortcut(context, b.build())
        }
        sid
    }.getOrNull()

    private fun largeIcon(thread: NotifThread, icons: Map<String, Bitmap>): IconCompat? {
        val bitmap = if (thread.isGroup) {
            icons[thread.groupAvatarUrl]
                ?: initialsBitmap(formatPhoneForDisplay(thread.title), "conv:" + thread.conversationId)
        } else {
            val line = thread.lines.lastOrNull { !it.fromMe }
            line?.avatarUrl?.takeIf { it.isNotBlank() }?.let { icons[it] }
                ?: line?.let { initialsBitmap(formatPhoneForDisplay(it.senderName), it.senderKey) }
        }
        return bitmap?.let { IconCompat.createWithBitmap(it) }
    }

    private fun replyAction(thread: NotifThread): NotificationCompat.Action {
        val remote = RemoteInput.Builder(NotificationActions.KEY_REPLY)
            .setLabel(context.getString(R.string.notif_reply))
            .build()
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActions.ACTION_REPLY
            putExtra(NotificationActions.EXTRA_CONV, thread.conversationId)
            putExtra(NotificationActions.EXTRA_TITLE, thread.title)
            putExtra(NotificationActions.EXTRA_GROUP, thread.isGroup)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            MessageNotificationIds.requestCode(thread.conversationId, MessageNotificationIds.SLOT_REPLY),
            intent,
            mutableFlags(),
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_reply, context.getString(R.string.notif_reply), pending)
            .addRemoteInput(remote)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(conversationId: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActions.ACTION_READ
            putExtra(NotificationActions.EXTRA_CONV, conversationId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            MessageNotificationIds.requestCode(conversationId, MessageNotificationIds.SLOT_READ),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_status_sent,
            context.getString(R.string.notif_mark_read),
            pending,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun dismissPending(conversationId: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActions.ACTION_DISMISSED
            putExtra(NotificationActions.EXTRA_CONV, conversationId)
        }
        return PendingIntent.getBroadcast(
            context,
            MessageNotificationIds.requestCode(conversationId, MessageNotificationIds.SLOT_DELETE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openThread(conversationId: String, slot: Int): PendingIntent {
        val open = Intent(context, ConversationsActivity::class.java).apply {
            action = ConversationsActivity.ACTION_OPEN_THREAD
            putExtra(ConversationsActivity.EXTRA_CONV, conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            MessageNotificationIds.requestCode(conversationId, slot),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun summaryCount(count: Int): String =
        if (count == 1) {
            context.getString(R.string.notif_summary_one)
        } else {
            context.getString(R.string.notif_summary_many, count)
        }

    private fun cancelLegacy(thread: NotifThread) {
        val nm = NotificationManagerCompat.from(context)
        for (tag in listOf(thread.conversationId, thread.legacyTag).filter { it.isNotBlank() }.distinct()) {
            nm.cancel(tag, MessagingService.NOTIF_ID)
        }
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun iconUrls(thread: NotifThread): List<String> {
        val urls = ArrayList<String>()
        if (thread.groupAvatarUrl.isNotBlank()) urls.add(thread.groupAvatarUrl)
        for (line in thread.lines) {
            if (line.avatarUrl.isNotBlank()) urls.add(line.avatarUrl)
        }
        return urls.distinct()
    }

    private suspend fun loadIcons(urls: List<String>): Map<String, Bitmap> {
        if (urls.isEmpty()) return emptyMap()
        val found = ConcurrentHashMap<String, Bitmap>()
        withTimeoutOrNull(ICON_TIMEOUT_MS) {
            coroutineScope {
                urls.map { url ->
                    async {
                        loadIcon(url)?.let { found[url] = it }
                    }
                }.awaitAll()
            }
        }
        return found.toMap()
    }

    private suspend fun loadIcon(url: String): Bitmap? {
        iconCache[url]?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ICON_PX)
            .allowHardware(false)
            .circleAvatar(url)
            .build()
        val result = runCatching { imageLoader.execute(request) }.getOrNull() as? SuccessResult ?: return null
        val bitmap = runCatching { result.drawable.toBitmap(ICON_PX, ICON_PX) }.getOrNull() ?: return null
        val circular = CircleBitmaps.of(bitmap)
        if (iconCache.size > ICON_CACHE) iconCache.clear()
        iconCache[url] = circular
        return circular
    }

    private fun initialsBitmap(name: String, key: String): Bitmap = InitialsBitmap.of(name, key, ICON_PX)

    private fun mutableFlags(): Int {
        val base = PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 31) base or PendingIntent.FLAG_MUTABLE else base
    }

    private companion object {
        const val ICON_PX = 128
        const val ICON_CACHE = 32
        const val ICON_TIMEOUT_MS = 2_000L
        const val INBOX_LINES = 5
    }
}
