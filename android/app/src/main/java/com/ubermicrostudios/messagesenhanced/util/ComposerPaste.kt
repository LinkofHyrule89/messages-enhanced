package com.ubermicrostudios.messagesenhanced.util

/**
 * Composer paste / keyboard-insert rules from the web app (`initPaste` and
 * OnReceiveContent for image MIME types). Images wait in the attach tray and
 * leave through the same send-media path as the + menu.
 */
object ComposerPaste {
    val MIME_TYPES = arrayOf("image/*")

    fun isImageMime(mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return type == "image/*" || type.startsWith("image/")
    }

    /** Web `addPending`: rename a generic "image.png" when the type is not PNG. */
    fun pastedName(mime: String, given: String = ""): String {
        val type = mime.substringBefore(';').trim().lowercase()
        if (given.isNotBlank() && !(given == "image.png" && type != "image/png")) return given
        val ext = when {
            type.endsWith("jpeg") || type.endsWith("jpg") -> "jpg"
            type.endsWith("png") -> "png"
            type.endsWith("gif") -> "gif"
            type.endsWith("webp") -> "webp"
            type.endsWith("heic") || type.endsWith("heif") -> "heic"
            else -> type.substringAfter('/', "bin").replace(Regex("\\+.*$"), "").ifBlank { "bin" }
        }
        return "pasted.$ext"
    }
}
