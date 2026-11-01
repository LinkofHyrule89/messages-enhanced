package com.ubermicrostudios.messagesenhanced.update

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Pure update decisions. Android framework calls stay in the installer and the
 * receivers; these functions are what the unit tests lock in.
 */
object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    const val PACKAGE_REPLACED_ACTION = "android.intent.action.MY_PACKAGE_REPLACED"

    fun autoCheckDue(lastCheckAt: Long, now: Long, intervalMs: Long = CHECK_INTERVAL_MS): Boolean {
        return now - lastCheckAt >= intervalMs
    }

    /**
     * Android 12+ can commit a self-update with USER_ACTION_NOT_REQUIRED.
     * Older releases always confirm.
     */
    @ChecksSdkIntAtLeast(parameter = 0, api = Build.VERSION_CODES.S)
    fun requestSilentInstall(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

    /**
     * Android 14+ should label the session as a file this app downloaded, so the
     * installer does not treat it as a local package the user opened by hand.
     */
    @ChecksSdkIntAtLeast(parameter = 0, api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun markDownloadedFileSource(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Null when the length is unknown. Clamped to 0..100. */
    fun downloadPercent(read: Long, total: Long): Int? {
        if (total <= 0L) return null
        val percent = (read.coerceAtLeast(0L) * 100L) / total
        return percent.coerceIn(0L, 100L).toInt()
    }

    /**
     * Relaunch only for a package replace that this app's own installer started.
     * Any other update (adb, another installer) leaves the user where they were.
     */
    fun shouldRelaunch(action: String?, triggeredByApp: Boolean): Boolean {
        return triggeredByApp && action == PACKAGE_REPLACED_ACTION
    }

    /** Launch checks skip the offer dialog when auto-update is on. */
    fun installsWithoutOffer(autoUpdate: Boolean): Boolean = autoUpdate

    /**
     * WorkManager periodic check. Separate from [CHECK_INTERVAL_MS], which only
     * throttles checks started while the app is open.
     */
    const val BACKGROUND_CHECK_HOURS = 12L

    const val DOWNLOAD_WORK_NAME = "update_download_install"

    const val CHECK_WORK_NAME = "update_check"

    /**
     * A session callback is accepted when it belongs to the session this process
     * started. A fresh process (token still 0) accepts the callback, because the
     * process that committed the session may already be gone.
     */
    fun acceptSessionCallback(currentToken: Int, callbackToken: Int): Boolean {
        if (currentToken == 0) return true
        return callbackToken == currentToken
    }

    /**
     * Null when [archive] may be installed over [installed].
     * The archive package must match and its versionCode must be strictly newer.
     * A missing signer fails closed. When [allowSigningLineage] is true (API 28+),
     * a rotated key is accepted if the new APK's signing history includes every
     * current signer of the installed app.
     */
    fun rejectInstall(
        installed: ApkIdentity,
        archive: ApkIdentity,
        allowSigningLineage: Boolean = false,
    ): String? {
        if (archive.packageName.isBlank() || archive.packageName != installed.packageName) {
            return "This update is for a different app."
        }
        if (archive.versionCode <= installed.versionCode) {
            return "This update is not newer than the installed version."
        }
        if (installed.signers.isEmpty() || archive.signers.isEmpty()) {
            return "This update is signed with a different key."
        }
        if (installed.signers == archive.signers) return null
        if (allowSigningLineage && lineageIncludesCurrentSigner(installed, archive)) return null
        return "This update is signed with a different key."
    }

    /**
     * The new APK's PackageManager signing-certificate history includes every
     * current signer of the installed app. [ApkIdentity.hasPastSigningCertificates]
     * is SigningInfo.hasPastSigningCertificates on the archive.
     */
    fun lineageIncludesCurrentSigner(installed: ApkIdentity, archive: ApkIdentity): Boolean {
        if (!archive.hasPastSigningCertificates) return false
        val history = archive.signingHistory
        if (history.isEmpty()) return false
        return installed.signers.all { it in history }
    }
}

/** Package name, versionCode, and current signer hashes for an APK. */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val signingHistory: Set<String> = signers,
    val hasPastSigningCertificates: Boolean = false,
)

sealed class UpdateProgress {
    data class Downloading(val read: Long, val total: Long) : UpdateProgress() {
        val percent: Int? get() = UpdatePolicy.downloadPercent(read, total)
    }

    data object Installing : UpdateProgress()
}
