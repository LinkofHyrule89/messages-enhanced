package com.ubermicrostudios.messagesenhanced.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import com.ubermicrostudios.messagesenhanced.util.IdempotencyKeys
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Reply and mark-as-read from the notification shade. The reply text is read here,
 * then a WorkManager job sends it. goAsync keeps the process up until that job finishes.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var notifications: ConversationNotifications
    @Inject lateinit var messages: MessagesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val conv = intent.getStringExtra(NotificationActions.EXTRA_CONV).orEmpty()
        if (conv.isBlank()) return
        when (intent.action) {
            NotificationActions.ACTION_DISMISSED -> notifications.dismiss(conv)
            NotificationActions.ACTION_READ -> {
                notifications.dismiss(conv)
                val pending = goAsync()
                val workId = runCatching { NotificationActionWorker.enqueueRead(context, conv) }.getOrNull()
                if (workId == null) {
                    scope.launch {
                        try {
                            messages.markRead(conv)
                        } finally {
                            pending.finish()
                        }
                    }
                } else {
                    await(context.applicationContext, pending, workId)
                }
            }
            NotificationActions.ACTION_REPLY, NotificationActions.ACTION_RETRY -> {
                val text = (
                    if (intent.action == NotificationActions.ACTION_RETRY) {
                        intent.getStringExtra(NotificationActions.EXTRA_TEXT)
                    } else {
                        RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(NotificationActions.KEY_REPLY)
                            ?.toString()
                    }
                    )?.trim().orEmpty()
                if (intent.action == NotificationActions.ACTION_RETRY) notifications.clearReplyFailed(conv)
                if (text.isBlank()) return
                val title = intent.getStringExtra(NotificationActions.EXTRA_TITLE).orEmpty()
                val group = intent.getBooleanExtra(NotificationActions.EXTRA_GROUP, false)
                val pending = goAsync()
                val workId = runCatching {
                    NotificationActionWorker.enqueueReply(context, conv, text, title, group)
                }.getOrNull()
                if (workId == null) {
                    scope.launch {
                        try {
                            val key = IdempotencyKeys.newKey()
                            val timestamp = System.currentTimeMillis()
                            messages.send(conv, text, idempotencyKey = key)
                            notifications.appendSelfAndPost(conv, text, timestamp, title, group)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            notifications.showReplyFailed(conv, title, text, group)
                        } finally {
                            pending.finish()
                        }
                    }
                } else {
                    await(context.applicationContext, pending, workId)
                }
            }
        }
    }

    private fun await(context: Context, pending: PendingResult, workId: UUID) {
        val finished = AtomicBoolean(false)
        fun done() {
            if (finished.compareAndSet(false, true)) pending.finish()
        }
        val wm = WorkManager.getInstance(context)
        val live = wm.getWorkInfoByIdLiveData(workId)
        lateinit var observer: Observer<WorkInfo?>
        observer = Observer { info ->
            if (info != null && info.state.isFinished) {
                live.removeObserver(observer)
                done()
            }
        }
        live.observeForever(observer)
        Handler(Looper.getMainLooper()).postDelayed({
            live.removeObserver(observer)
            done()
        }, WAIT_MS)
    }

    private companion object {
        // Stay under the broadcast ANR budget; the queued work survives without us.
        const val WAIT_MS = 9_000L
    }
}
