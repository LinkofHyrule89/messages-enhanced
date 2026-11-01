package com.ubermicrostudios.messagesenhanced.push

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.util.IdempotencyKeys
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Sends a notification reply, or marks a conversation read, without opening the app.
 * The idempotency key is fixed when the work is queued so a retry does not send twice.
 */
class NotificationActionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, MessagingService.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notif_sending_reply))
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return foregroundInfo(notification)
    }

    override suspend fun doWork(): Result {
        val conv = inputData.getString(KEY_CONV).orEmpty()
        if (conv.isBlank()) return Result.failure()
        val entry = EntryPointAccessors.fromApplication(applicationContext, NotificationWorkerEntryPoint::class.java)
        return when (inputData.getString(KEY_ACTION)) {
            ACTION_REPLY -> reply(entry, conv)
            ACTION_READ -> {
                entry.messagesRepository().markRead(conv)
                Result.success()
            }
            else -> Result.failure()
        }
    }

    private suspend fun reply(entry: NotificationWorkerEntryPoint, conv: String): Result {
        val text = inputData.getString(KEY_TEXT).orEmpty()
        if (text.isBlank()) return Result.failure()
        val base = inputData.getString(KEY_IDEMPOTENCY).orEmpty().ifBlank { IdempotencyKeys.newKey() }
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val group = inputData.getBoolean(KEY_GROUP, false)
        val timestamp = inputData.getLong(KEY_TS, System.currentTimeMillis())
        val notifications = entry.conversationNotifications()
        val gens = applicationContext.getSharedPreferences(GEN_PREFS, Context.MODE_PRIVATE)
        val generation = gens.getInt(base, 0)
        val key = ReplyPolicy.keyFor(base, generation)
        val outcome = try {
            val res = entry.messagesRepository().send(conv, text, idempotencyKey = key)
            ReplyPolicy.decide(200, res.success, res.status, runAttemptCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            ReplyPolicy.decide(e.code(), false, ReplyPolicy.parseStatus(body), runAttemptCount)
        } catch (_: Exception) {
            ReplyPolicy.decide(null, null, null, runAttemptCount)
        }
        return when (outcome) {
            ReplyPolicy.Outcome.SENT -> {
                gens.edit().remove(base).apply()
                notifications.appendSelfAndPost(conv, text, timestamp, title, group)
                Result.success()
            }
            ReplyPolicy.Outcome.RETRY_SAME_KEY -> Result.retry()
            ReplyPolicy.Outcome.RETRY_NEW_KEY -> {
                gens.edit().putInt(base, generation + 1).commit()
                Result.retry()
            }
            ReplyPolicy.Outcome.GIVE_UP -> {
                gens.edit().remove(base).apply()
                notifications.showReplyFailed(conv, title, text, group)
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                MessageNotificationIds.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(MessageNotificationIds.FOREGROUND_ID, notification)
        }
    }

    companion object {
        const val ACTION_REPLY = "reply"
        const val ACTION_READ = "read"

        private const val KEY_ACTION = "action"
        private const val KEY_CONV = "conv"
        private const val KEY_TEXT = "text"
        private const val KEY_TITLE = "title"
        private const val KEY_GROUP = "group"
        private const val KEY_TS = "ts"
        private const val KEY_IDEMPOTENCY = "idempotency"
        private const val GEN_PREFS = "notif_reply_generations"

        fun enqueueReply(
            context: Context,
            conversationId: String,
            text: String,
            title: String,
            isGroup: Boolean,
        ): UUID {
            val data = replyData(conversationId, text, title, isGroup)
            val request = OneTimeWorkRequestBuilder<NotificationActionWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val unique = "notif-reply:" + data.getString(KEY_IDEMPOTENCY)
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(unique, ExistingWorkPolicy.KEEP, request)
            return request.id
        }

        fun enqueueRead(context: Context, conversationId: String): UUID {
            val data = Data.Builder()
                .putString(KEY_ACTION, ACTION_READ)
                .putString(KEY_CONV, conversationId)
                .build()
            val request = OneTimeWorkRequestBuilder<NotificationActionWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
            return request.id
        }

        private fun replyData(conversationId: String, text: String, title: String, isGroup: Boolean): Data {
            val key = IdempotencyKeys.newKey()
            val timestamp = System.currentTimeMillis()
            var body = text
            var data = buildReply(conversationId, body, title, isGroup, timestamp, key)
            while (data.toByteArray().size > Data.MAX_DATA_BYTES - 256 && body.length > 20) {
                body = body.take(body.length * 3 / 4)
                data = buildReply(conversationId, body, title, isGroup, timestamp, key)
            }
            return data
        }

        private fun buildReply(
            conversationId: String,
            text: String,
            title: String,
            isGroup: Boolean,
            timestamp: Long,
            key: String,
        ): Data = Data.Builder()
            .putString(KEY_ACTION, ACTION_REPLY)
            .putString(KEY_CONV, conversationId)
            .putString(KEY_TEXT, text)
            .putString(KEY_TITLE, title.take(300))
            .putBoolean(KEY_GROUP, isGroup)
            .putLong(KEY_TS, timestamp)
            .putString(KEY_IDEMPOTENCY, key)
            .build()
    }
}
