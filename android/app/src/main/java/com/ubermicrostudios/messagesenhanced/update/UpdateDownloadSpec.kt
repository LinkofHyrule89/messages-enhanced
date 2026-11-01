package com.ubermicrostudios.messagesenhanced.update

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.work.Data
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences

/**
 * Coordinates for one APK download. Small enough for WorkManager input and a
 * notification action, and persisted so Retry still works after the process
 * that committed the install session is gone.
 */
internal data class UpdateDownloadSpec(
    val version: String,
    val tag: String,
    val apkName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
) {
    fun toRelease(): AppRelease? {
        val parsed = SemVer.parse(version) ?: return null
        if (downloadUrl.isBlank() || apkName.isBlank()) return null
        return AppRelease(
            version = parsed,
            tag = tag.ifBlank { version },
            notes = "",
            apkName = apkName,
            downloadUrl = downloadUrl,
            size = size,
            sha256 = sha256?.takeIf { it.isNotBlank() },
        )
    }

    fun toWorkData(): Data = Data.Builder()
        .putString(KEY_VERSION, version)
        .putString(KEY_TAG, tag)
        .putString(KEY_NAME, apkName)
        .putString(KEY_URL, downloadUrl)
        .putLong(KEY_SIZE, size)
        .putString(KEY_SHA, sha256)
        .build()

    fun writeTo(intent: Intent) {
        intent.putExtra(KEY_VERSION, version)
        intent.putExtra(KEY_TAG, tag)
        intent.putExtra(KEY_NAME, apkName)
        intent.putExtra(KEY_URL, downloadUrl)
        intent.putExtra(KEY_SIZE, size)
        intent.putExtra(KEY_SHA, sha256)
    }

    fun persist(context: Context) {
        prefs(context).edit(commit = true) {
            putBoolean(KEY_PRESENT, true)
            putString(KEY_VERSION, version)
            putString(KEY_TAG, tag)
            putString(KEY_NAME, apkName)
            putString(KEY_URL, downloadUrl)
            putLong(KEY_SIZE, size)
            putString(KEY_SHA, sha256)
        }
    }

    companion object {
        const val KEY_VERSION = "update_dl_version"
        const val KEY_TAG = "update_dl_tag"
        const val KEY_NAME = "update_dl_apk"
        const val KEY_URL = "update_dl_url"
        const val KEY_SIZE = "update_dl_size"
        const val KEY_SHA = "update_dl_sha"
        private const val KEY_PRESENT = "update_dl_present"

        fun from(release: AppRelease) = UpdateDownloadSpec(
            version = release.version.toString(),
            tag = release.tag,
            apkName = release.apkName,
            downloadUrl = release.downloadUrl,
            size = release.size,
            sha256 = release.sha256,
        )

        fun from(data: Data): UpdateDownloadSpec? = readFields(
            version = data.getString(KEY_VERSION),
            tag = data.getString(KEY_TAG),
            apkName = data.getString(KEY_NAME),
            downloadUrl = data.getString(KEY_URL),
            size = data.getLong(KEY_SIZE, -1L),
            sha256 = data.getString(KEY_SHA),
        )

        fun from(intent: Intent): UpdateDownloadSpec? {
            if (!intent.hasExtra(KEY_URL)) return null
            return readFields(
                version = intent.getStringExtra(KEY_VERSION),
                tag = intent.getStringExtra(KEY_TAG),
                apkName = intent.getStringExtra(KEY_NAME),
                downloadUrl = intent.getStringExtra(KEY_URL),
                size = intent.getLongExtra(KEY_SIZE, -1L),
                sha256 = intent.getStringExtra(KEY_SHA),
            )
        }

        fun read(context: Context): UpdateDownloadSpec? {
            val stored = prefs(context)
            if (!stored.getBoolean(KEY_PRESENT, false)) return null
            return readFields(
                version = stored.getString(KEY_VERSION, null),
                tag = stored.getString(KEY_TAG, null),
                apkName = stored.getString(KEY_NAME, null),
                downloadUrl = stored.getString(KEY_URL, null),
                size = stored.getLong(KEY_SIZE, -1L),
                sha256 = stored.getString(KEY_SHA, null),
            )
        }

        fun clear(context: Context) {
            prefs(context).edit(commit = true) {
                remove(KEY_PRESENT)
                remove(KEY_VERSION)
                remove(KEY_TAG)
                remove(KEY_NAME)
                remove(KEY_URL)
                remove(KEY_SIZE)
                remove(KEY_SHA)
            }
        }

        private fun readFields(
            version: String?,
            tag: String?,
            apkName: String?,
            downloadUrl: String?,
            size: Long,
            sha256: String?,
        ): UpdateDownloadSpec? {
            if (version.isNullOrBlank() || apkName.isNullOrBlank() || downloadUrl.isNullOrBlank()) return null
            if (size < 0L) return null
            return UpdateDownloadSpec(
                version = version,
                tag = tag.orEmpty(),
                apkName = apkName,
                downloadUrl = downloadUrl,
                size = size,
                sha256 = sha256?.takeIf { it.isNotBlank() },
            )
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(
                AppPreferences.SETTINGS_FILE,
                Context.MODE_PRIVATE,
            )
    }
}
