package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.MessageDto

/**
 * Where a tapped photo comes from, and the file name used when sharing or saving it.
 * Server photos use the same `/api/media/{id}` URL the thread already loads.
 * A send that has not landed yet still has its local file.
 */
object ImageStore {
    const val ALBUM = "Messages Enhanced"
    const val RELATIVE_PATH = "Pictures/Messages Enhanced"
    const val SHARE_DIR = "share"

    sealed class Source {
        data class Remote(val url: String) : Source()
        data class Local(val path: String) : Source()
    }

    fun openSource(message: MessageDto, mediaUrl: String): Source? {
        if (!message.isImageMedia()) return null
        val local = message.localPreview.trim()
        if (local.isNotEmpty()) {
            if (local.startsWith("http://") || local.startsWith("https://")) return Source.Remote(local)
            val path = if (local.startsWith("file://")) local.removePrefix("file://") else local
            if (path.isNotEmpty()) return Source.Local(path)
        }
        if (message.messageId.isNotBlank() && !message.messageId.startsWith("local-") && mediaUrl.isNotBlank()) {
            return Source.Remote(mediaUrl)
        }
        return null
    }

    fun extension(mime: String): String {
        val type = mime.substringBefore(';').trim().lowercase()
        return when (type) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            else -> {
                val sub = type.substringAfter('/', "")
                val clean = sub.takeWhile { it.isLetterOrDigit() }
                if (type.startsWith("image/") && clean.isNotEmpty()) clean else "jpg"
            }
        }
    }

    fun fileName(id: String, mime: String, nowMs: Long): String {
        val clean = buildString {
            for (ch in id) {
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') append(ch)
            }
        }.take(48).ifBlank { "photo" }
        return "$clean-$nowMs.${extension(mime)}"
    }
}
