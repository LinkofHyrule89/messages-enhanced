package com.ubermicrostudios.messagesenhanced.util

import java.io.File

/**
 * Canonical-path check so an intent extra cannot point at a file outside [root].
 * `..`, a sibling prefix such as `cache-evil`, and a symlink that leaves [root] are rejected.
 */
object SafeFiles {
    fun fileWithin(root: File, rawPath: String): File? {
        val path = rawPath.trim().removePrefix("file://")
        if (path.isBlank()) return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val rootPath = base.path
        val childPath = candidate.path
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        if (!childPath.startsWith(prefix)) return null
        return candidate
    }
}
