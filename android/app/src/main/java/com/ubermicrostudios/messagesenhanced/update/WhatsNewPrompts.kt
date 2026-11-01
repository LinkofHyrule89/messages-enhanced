package com.ubermicrostudios.messagesenhanced.update

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ubermicrostudios.messagesenhanced.BuildConfig
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.util.ChangelogParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * After an app update, show that version's CHANGELOG section once.
 * Bundled notes first; GitHub release body if the section is missing. Failures stay quiet.
 */
@Singleton
class WhatsNewPrompts @Inject constructor(
    @ApplicationContext context: Context,
    private val prefs: AppPreferences,
    @Named("updates") private val http: OkHttpClient,
) {
    private val app = context.applicationContext
    @Volatile
    private var offeredThisProcess = false

    fun maybeShow(activity: AppCompatActivity) {
        if (offeredThisProcess || activity.isFinishing || activity.isDestroyed) return
        val current = BuildConfig.VERSION_CODE
        val stored = prefs.lastWhatsNewVersionCode()
        if (!WhatsNewPolicy.shouldShow(stored, current, stored == null && updatedInPlace())) {
            if (stored == null) prefs.markWhatsNewShown(current)
            return
        }
        offeredThisProcess = true
        activity.lifecycleScope.launch {
            val notes = notesFor(BuildConfig.VERSION_NAME)
            prefs.markWhatsNewShown(current)
            if (notes.isNullOrBlank() || activity.isFinishing || activity.isDestroyed) return@launch
            present(activity, notes)
        }
    }

    private suspend fun notesFor(versionName: String): String? {
        bundledSection(versionName)?.let { return it }
        return githubSection(versionName)
    }

    private fun bundledSection(versionName: String): String? {
        val markdown = runCatching {
            app.assets.open(CHANGELOG_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        val section = ChangelogParser.section(markdown, versionName) ?: return null
        return ChangelogParser.displayBody(section).takeIf { it.isNotBlank() }
    }

    private suspend fun githubSection(versionName: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val version = ChangelogParser.normalize(versionName) ?: return@withContext null
            val tag = "android-v$version"
            val url =
                "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/tags/$tag"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "messages-enhanced-android")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val parsed = ReleaseParser.parse(response.body?.string().orEmpty()) ?: return@withContext null
                val headed = ChangelogParser.section("## $tag\n\n${parsed.notes}", versionName)
                val raw = headed ?: parsed.notes.trim().takeIf { it.isNotEmpty() }
                raw?.let { ChangelogParser.displayBody(it) }?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun present(activity: AppCompatActivity, notes: String) {
        runCatching {
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.whats_new_title, BuildConfig.VERSION_NAME))
                .setMessage(notes)
                .setPositiveButton(R.string.whats_new_ok, null)
                .show()
        }
    }

    companion object {
        const val CHANGELOG_ASSET = "CHANGELOG.md"
    }

    private fun updatedInPlace(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)
}
