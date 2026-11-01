package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ubermicrostudios.messagesenhanced.util.ComposerPaste
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan
import java.io.File
import java.util.UUID

/** Copies a picked content URI into the cache so the send still works after the permission ends. */
object AttachmentImport {
    fun copy(context: Context, uri: Uri): ComposerPlan.PendingFile? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.substringBefore(';')?.trim().orEmpty()
            .ifBlank { "application/octet-stream" }
        val name = if (ComposerPaste.isImageMime(mime)) {
            ComposerPaste.pastedName(mime, displayName(context, uri))
        } else {
            displayName(context, uri)
        }
        val dir = File(context.cacheDir, "pending").apply { mkdirs() }
        val dest = File(dir, UUID.randomUUID().toString())
        try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) {
                    dest.delete()
                    return null
                }
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
            dest.delete()
            return null
        }
        return ComposerPlan.PendingFile(
            id = dest.name,
            path = dest.absolutePath,
            name = name,
            mime = mime,
            size = dest.length(),
        )
    }

    fun cameraFile(context: Context): File {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        return File(dir, "shot-${System.currentTimeMillis()}.jpg")
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "attachment"
    }
}
