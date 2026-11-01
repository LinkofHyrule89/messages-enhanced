package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ubermicrostudios.messagesenhanced.util.ThemeScreen
import java.io.File
import java.util.UUID

/** Copies a chosen photo into the cache and applies the web size and type checks. */
object ThemePhotoImport {
    sealed class Result {
        data class Ready(val path: String, val mime: String, val name: String) : Result()
        data class Rejected(val message: String) : Result()
    }

    fun import(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.substringBefore(';')?.trim().orEmpty()
        val name = displayName(context, uri)
        ThemeScreen.photoError(declaredSize(context, uri), mime)?.let { return Result.Rejected(it) }
        val dir = File(context.cacheDir, "theme-upload").apply { mkdirs() }
        val dest = File(dir, UUID.randomUUID().toString())
        try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) {
                    dest.delete()
                    return Result.Rejected(ThemeScreen.PHOTO_UNREADABLE)
                }
                dest.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > ThemeScreen.MAX_UPLOAD_BYTES) {
                            dest.delete()
                            return Result.Rejected(ThemeScreen.PHOTO_TOO_LARGE)
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (_: Exception) {
            dest.delete()
            return Result.Rejected(ThemeScreen.PHOTO_UNREADABLE)
        }
        ThemeScreen.photoError(dest.length(), mime)?.let {
            dest.delete()
            return Result.Rejected(it)
        }
        return Result.Ready(dest.absolutePath, mime, name)
    }

    private fun declaredSize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1L
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
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "photo.jpg"
    }
}
