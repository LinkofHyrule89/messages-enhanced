package com.ubermicrostudios.messagesenhanced.update

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ubermicrostudios.messagesenhanced.R
import kotlinx.coroutines.launch

/**
 * Update dialogs for the activity that is currently in front.
 * Launch checks are quiet except when a newer release exists.
 * Tapping Update queues [UpdateDownloadInstallWorker] and returns, so the
 * download continues after the user leaves. Auto-update does the same with no dialog.
 */
class UpdatePrompts(
    private val activity: AppCompatActivity,
    private val checker: UpdateChecker,
) {
    private var launchChecked = false
    private var manualBusy = false
    private var offerDialog: AlertDialog? = null
    private var closingDialogs = false

    fun onHostResume() {
        if (activity.isFinishing || activity.isDestroyed) return
        val waiting = checker.awaitingPermission
        if (waiting != null) {
            if (!checker.canInstallPackages()) {
                checker.awaitingPermission = null
                toast(activity.getString(R.string.update_install_permission))
            } else {
                checker.awaitingPermission = null
                enqueue(waiting.release, waiting.fromUser)
            }
        }
    }

    fun checkOnLaunch() {
        if (activity.isFinishing) return
        if (checker.installInProgress()) {
            launchChecked = true
            return
        }
        val waiting = checker.pendingOffer
        if (waiting != null) {
            launchChecked = true
            presentFound(waiting)
            return
        }
        if (launchChecked) return
        launchChecked = true
        activity.lifecycleScope.launch {
            val result = checker.check(force = false)
            if (result is UpdateResult.Available) presentFound(result.release)
        }
    }

    fun checkFromSettings(button: View) {
        if (manualBusy || activity.isFinishing) return
        manualBusy = true
        button.isEnabled = false
        activity.lifecycleScope.launch {
            try {
                when (val result = checker.check(force = true)) {
                    is UpdateResult.Available -> showOffer(result.release)
                    is UpdateResult.Failed -> toast(activity.getString(R.string.update_failed, result.message))
                    UpdateResult.UpToDate, null -> toast(activity.getString(R.string.up_to_date))
                }
            } finally {
                manualBusy = false
                if (!activity.isDestroyed) button.isEnabled = true
            }
        }
    }

    fun dismiss() {
        closingDialogs = true
        offerDialog?.dismiss()
        offerDialog = null
    }

    private fun presentFound(release: AppRelease) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (UpdatePolicy.installsWithoutOffer(checker.autoUpdate)) {
            beginInstall(release, fromUserTap = false)
        } else {
            showOffer(release)
        }
    }

    private fun showOffer(release: AppRelease) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (offerDialog?.isShowing == true) return
        val notes = release.notes.ifBlank { activity.getString(R.string.update_no_notes) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(release.tag)
            .setMessage(notes)
            .setPositiveButton(R.string.update_action) { _, _ ->
                beginInstall(release, fromUserTap = true)
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                checker.pendingOffer = null
                UpdateNotifier.cancelAvailable(activity)
            }
            .setOnCancelListener {
                if (!closingDialogs) {
                    checker.pendingOffer = null
                    UpdateNotifier.cancelAvailable(activity)
                }
            }
            .create()
        offerDialog = dialog
        dialog.show()
    }

    private fun beginInstall(release: AppRelease, fromUserTap: Boolean) {
        checker.pendingOffer = null
        if (checker.installInProgress()) return
        if (!checker.canInstallPackages()) {
            checker.awaitingPermission = AwaitingInstall(release, fromUserTap)
            try {
                activity.startActivity(checker.manageUnknownSourcesIntent())
            } catch (_: ActivityNotFoundException) {
                checker.awaitingPermission = null
                toast(activity.getString(R.string.update_install_permission))
            }
            return
        }
        enqueue(release, fromUserTap)
    }

    private fun enqueue(release: AppRelease, fromUserTap: Boolean) {
        if (fromUserTap) requestPostNotifications()
        UpdateDownloadInstallWorker.enqueue(activity, release)
    }

    /** Android 13+ only. The download is queued either way. */
    private fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    private fun toast(message: String) {
        if (activity.isDestroyed) return
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 4813
    }
}
