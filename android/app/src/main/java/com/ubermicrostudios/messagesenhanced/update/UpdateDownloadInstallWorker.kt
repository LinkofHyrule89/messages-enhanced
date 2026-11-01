package com.ubermicrostudios.messagesenhanced.update

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ubermicrostudios.messagesenhanced.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads an update APK in the foreground, then installs it with the existing
 * package-installer session. The user can leave the app; progress stays on the
 * notification.
 *
 * Approach adapted from Neko's AppDownloadInstallJob (Apache-2.0).
 */
class UpdateDownloadInstallWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val total = inputData.getLong(UpdateDownloadSpec.KEY_SIZE, 0L).coerceAtLeast(0L)
        val notification = UpdateNotifier.progressNotification(
            applicationContext,
            UpdateProgress.Downloading(read = 0L, total = total),
        )
        return foregroundInfo(notification)
    }

    override suspend fun doWork(): Result {
        val spec = UpdateDownloadSpec.from(inputData)
        val release = spec?.toRelease()
        if (spec == null || release == null) {
            UpdateNotifier.showFailed(applicationContext, detail = null, spec = null)
            return Result.failure()
        }
        val app = applicationContext
        val checker = EntryPointAccessors.fromApplication(app, UpdateWorkerEntryPoint::class.java)
            .updateChecker()
        if (!checker.canInstallPackages()) {
            UpdateNotifier.showFailed(app, app.getString(R.string.update_install_permission), spec)
            return Result.failure()
        }
        UpdateNotifier.cancelInstallUi(app)
        UpdateNotifier.cancelOutcome(app)
        val initial = UpdateProgress.Downloading(read = 0L, total = release.size)
        var promoted = false
        try {
            setForeground(getForegroundInfo())
            promoted = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Background starts on newer Android can reject a foreground service.
            // Keep going; the notification is best-effort and the download is not.
            UpdateNotifier.show(app, initial)
        }
        checker.pendingOffer = null
        return try {
            checker.exclusiveTransfer {
                checker.prepareInstallSession()
                val file = checker.download(release) { read, total ->
                    UpdateNotifier.show(app, UpdateProgress.Downloading(read, total))
                }
                if (isStopped) throw CancellationException("Update download stopped")
                UpdateNotifier.show(app, UpdateProgress.Installing)
                UpdateNotifier.showInstalling(app)
                withContext(Dispatchers.IO) { checker.install(file) }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!promoted) UpdateNotifier.cancel(app)
            UpdateNotifier.cancelInstallUi(app)
            val detail = e.message?.takeIf { it.isNotBlank() }
            UpdateNotifier.showFailed(app, detail, spec)
            Result.failure()
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UpdateNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(UpdateNotifier.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /**
         * Queues one download. [ExistingWorkPolicy.KEEP] leaves a download that
         * is already queued or running alone.
         */
        fun enqueue(context: Context, release: AppRelease) {
            val spec = UpdateDownloadSpec.from(release)
            spec.persist(context)
            val request = OneTimeWorkRequestBuilder<UpdateDownloadInstallWorker>()
                .setInputData(spec.toWorkData())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UpdatePolicy.DOWNLOAD_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
