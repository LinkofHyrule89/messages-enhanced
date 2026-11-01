package com.ubermicrostudios.messagesenhanced.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Periodic update check. Auto-update queues the download worker; otherwise the
 * user gets an Update available notification.
 *
 * Approach adapted from Neko's AppUpdateJob (Apache-2.0).
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val checker = EntryPointAccessors.fromApplication(
            applicationContext,
            UpdateWorkerEntryPoint::class.java,
        ).updateChecker()
        return try {
            when (val result = checker.check(force = true)) {
                is UpdateResult.Available -> {
                    if (UpdatePolicy.installsWithoutOffer(checker.autoUpdate) &&
                        checker.canInstallPackages()
                    ) {
                        checker.pendingOffer = null
                        UpdateDownloadInstallWorker.enqueue(applicationContext, result.release)
                    } else {
                        UpdateNotifier.showAvailable(applicationContext, result.release)
                    }
                    Result.success()
                }
                is UpdateResult.Failed -> Result.failure()
                UpdateResult.UpToDate, null -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                UpdatePolicy.BACKGROUND_CHECK_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UpdatePolicy.CHECK_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
