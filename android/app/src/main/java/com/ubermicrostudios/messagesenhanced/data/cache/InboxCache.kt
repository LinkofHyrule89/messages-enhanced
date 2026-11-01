package com.ubermicrostudios.messagesenhanced.data.cache

import android.content.Context
import android.util.AtomicFile
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversations and the latest messages of recently opened chats, as JSON under filesDir.
 * Reads and writes stay on [Dispatchers.IO]. Memory is filled by [warm] so the first frame
 * can paint without touching disk on the main thread.
 *
 * Files are stamped with the server URL. [clear] drops memory and disk after logout or a
 * server change so the next sign-in does not paint the previous account.
 */
@Singleton
class InboxCache @Inject constructor(
    @ApplicationContext context: Context,
    private val prefs: AppPreferences,
) {
    private val root = File(context.applicationContext.filesDir, "inbox_cache")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = JsonConfig.json
    private val fileLock = Any()
    private val jobLock = Any()

    @Volatile
    private var conversations: List<ConversationDto>? = null

    @Volatile
    private var warmDone = false

    @Volatile
    private var writesOpen = true

    private var epoch = 0

    private val messages = ConcurrentHashMap<String, List<MessageDto>>()
    private var warmJob: Job? = null
    private var conversationJob: Job? = null
    private val messageJobs = HashMap<String, Job>()

    /** Starts the disk read. Safe to call more than once. Does not block. */
    fun warm() {
        synchronized(jobLock) {
            if (warmJob != null) return
            val gen = synchronized(fileLock) { epoch }
            warmJob = scope.launch { warmBody(gen) }
        }
    }

    /** True after the first disk warm has finished (or been cancelled by [clear]). */
    fun isWarm(): Boolean = warmDone

    /** Null until the conversation file has been read or a save has landed in memory. */
    fun peekConversations(): List<ConversationDto>? = conversations

    /**
     * Null when this chat has not been read yet.
     * An empty list means the cache was checked and this chat has no saved messages.
     */
    fun peekMessages(conversationId: String): List<MessageDto>? {
        if (conversationId.isBlank()) return emptyList()
        messages[conversationId]?.let { return it }
        return if (warmDone) emptyList() else null
    }

    suspend fun readConversations(): List<ConversationDto> {
        warm()
        warmJob?.join()
        return conversations.orEmpty()
    }

    /**
     * Messages saved for [conversationId]. Waits for [warm], then reads this chat's
     * file if it was not in the recent preload. Empty when nothing is cached.
     */
    suspend fun readMessages(conversationId: String): List<MessageDto> {
        if (conversationId.isBlank()) return emptyList()
        warm()
        warmJob?.join()
        messages[conversationId]?.let { return it }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            synchronized(fileLock) {
                messages[conversationId] ?: run {
                    val loaded = readThreadFile(conversationId)
                    // A save can land in memory while the file is read. Keep that newer list.
                    messages.putIfAbsent(conversationId, loaded) ?: loaded
                }
            }
        }
    }

    /** Generation captured at the start of a fetch. Bumped by [clear]. */
    fun generation(): Int = synchronized(fileLock) { epoch }

    fun isCurrent(generation: Int): Boolean = synchronized(fileLock) {
        CacheGeneration.canCommit(generation, epoch, writesOpen)
    }

    fun saveConversations(list: List<ConversationDto>, generation: Int) {
        if (!writesOpen) return
        val server: String
        synchronized(fileLock) {
            if (!CacheGeneration.canCommit(generation, epoch, writesOpen)) return
            server = currentServer()
            conversations = list
        }
        synchronized(jobLock) {
            conversationJob?.cancel()
            conversationJob = scope.launch {
                delay(DEBOUNCE_MS)
                if (!writesOpen) return@launch
                val latest = synchronized(fileLock) {
                    if (!CacheGeneration.canCommit(generation, epoch, writesOpen)) return@launch
                    conversations
                } ?: return@launch
                synchronized(fileLock) {
                    if (!CacheGeneration.canCommit(generation, epoch, writesOpen)) return@synchronized
                    writeAtomic(
                        conversationFile(),
                        json.encodeToString(ConversationCacheFile(server = server, conversations = latest)),
                    )
                }
            }
        }
    }

    /** Keeps the newest [MAX_MESSAGES] and remembers this chat as recently opened. */
    fun saveMessages(conversationId: String, list: List<MessageDto>, generation: Int) {
        if (conversationId.isBlank() || !writesOpen) return
        val trimmed = list.filter { !it.messageId.startsWith("local-") }.takeLast(MAX_MESSAGES)
        val server: String
        synchronized(fileLock) {
            if (!CacheGeneration.canCommit(generation, epoch, writesOpen)) return
            server = currentServer()
            messages[conversationId] = trimmed
        }
        synchronized(jobLock) {
            messageJobs[conversationId]?.cancel()
            messageJobs[conversationId] = scope.launch {
                delay(DEBOUNCE_MS)
                if (!writesOpen) return@launch
                val latest = synchronized(fileLock) {
                    if (!CacheGeneration.canCommit(generation, epoch, writesOpen)) return@launch
                    messages[conversationId].orEmpty()
                }
                synchronized(fileLock) {
                    if (!CacheGeneration.canCommit(generation, epoch, writesOpen)) return@synchronized
                    writeThread(conversationId, latest, server)
                    updateRecentLocked(conversationId, server)
                }
            }
        }
    }

    fun touch(conversationId: String) {
        if (conversationId.isBlank() || !writesOpen) return
        val gen = synchronized(fileLock) { epoch }
        val server = currentServer()
        scope.launch {
            synchronized(fileLock) {
                if (!CacheGeneration.canCommit(gen, epoch, writesOpen)) return@synchronized
                updateRecentLocked(conversationId, server)
            }
        }
    }

    /**
     * Drops memory immediately and deletes disk on [Dispatchers.IO]. In-flight
     * writes from the cleared generation do not land. The directory is renamed
     * away first so a later save cannot be wiped by the async delete.
     * Saves are ignored until [openWrites], so a refresh that finishes during
     * logout cannot put the old inbox back.
     */
    fun clear() {
        writesOpen = false
        synchronized(jobLock) {
            conversationJob?.cancel()
            conversationJob = null
            messageJobs.values.forEach { job -> job.cancel() }
            messageJobs.clear()
            warmJob?.cancel()
            warmJob = null
        }
        val doomed: File?
        synchronized(fileLock) {
            epoch++
            conversations = null
            messages.clear()
            warmDone = false
            doomed = retireRootLocked()
        }
        scope.launch {
            runCatching { doomed?.deleteRecursively() }
        }
    }

    /** Allows saves again after [clear]. A fresh process starts open. */
    fun openWrites() {
        writesOpen = true
    }

    private fun warmBody(gen: Int) {
        synchronized(fileLock) {
            if (gen != epoch) return
            deleteWipingDirsLocked()
            if (conversations == null) conversations = readConversationFile()
            val ids = readRecentFile()
            for (id in ids) {
                if (!messages.containsKey(id)) messages[id] = readThreadFile(id)
            }
            dropUnkeptThreadFilesLocked(ids)
            if (gen != epoch) return
            warmDone = true
        }
    }

    private fun currentServer(): String = prefs.serverUrl

    private fun conversationFile(): File = File(root, "conversations.json")

    private fun recentFile(): File = File(root, "recent.json")

    private fun threadDir(): File = File(root, "threads")

    private fun threadFile(id: String): File? {
        val name = ThreadCacheNames.fileName(id) ?: return null
        return File(threadDir(), name)
    }

    private fun legacyThreadFile(id: String): File = File(threadDir(), ThreadCacheNames.legacyFileName(id))

    private fun readConversationFile(): List<ConversationDto> {
        val text = readText(conversationFile())
        if (text.isBlank()) return emptyList()
        val wrapped = runCatching { json.decodeFromString<ConversationCacheFile>(text) }.getOrNull()
        if (wrapped != null) {
            if (!matchesServer(wrapped.server, currentServer())) return emptyList()
            return wrapped.conversations
        }
        return runCatching { json.decodeFromString<List<ConversationDto>>(text) }.getOrDefault(emptyList())
    }

    private fun readRecentFile(): List<String> {
        val text = readText(recentFile())
        if (text.isBlank()) return emptyList()
        val wrapped = runCatching { json.decodeFromString<RecentCacheFile>(text) }.getOrNull() ?: return emptyList()
        if (!matchesServer(wrapped.server, currentServer())) return emptyList()
        return wrapped.ids.filter { it.isNotBlank() }.distinct().take(MAX_THREADS)
    }

    private fun readThreadFile(id: String): List<MessageDto> {
        if (id.isBlank()) return emptyList()
        val hashed = threadFile(id) ?: return emptyList()
        var text = readText(hashed)
        var fromLegacy = false
        val legacy = legacyThreadFile(id)
        if (text.isBlank() && legacy != hashed) {
            text = readText(legacy)
            fromLegacy = text.isNotBlank()
        }
        if (text.isBlank()) return emptyList()
        val wrapped = runCatching { json.decodeFromString<ThreadCacheFile>(text) }.getOrNull() ?: return emptyList()
        if (!matchesServer(wrapped.server, currentServer())) return emptyList()
        val list = if (wrapped.conversationId.isBlank() || wrapped.conversationId == id) {
            wrapped.messages.takeLast(MAX_MESSAGES)
        } else {
            return emptyList()
        }
        if (fromLegacy) {
            writeThread(id, list, wrapped.server.ifBlank { currentServer() })
            deleteFileAndBackup(legacy)
        }
        return list
    }

    private fun writeThread(id: String, list: List<MessageDto>, server: String) {
        val file = threadFile(id) ?: return
        writeAtomic(
            file,
            json.encodeToString(
                ThreadCacheFile(
                    server = server,
                    conversationId = id,
                    messages = list.takeLast(MAX_MESSAGES),
                ),
            ),
        )
        val legacy = legacyThreadFile(id)
        if (legacy != file) deleteFileAndBackup(legacy)
    }

    private fun updateRecentLocked(id: String, server: String) {
        val next = (listOf(id) + readRecentFile().filter { it.isNotBlank() && it != id }).distinct().take(MAX_THREADS)
        writeAtomic(recentFile(), json.encodeToString(RecentCacheFile(server = server, ids = next)))
        dropUnkeptThreadFilesLocked(next)
    }

    private fun dropUnkeptThreadFilesLocked(ids: List<String>) {
        val keep = ids.mapNotNull { threadFile(it)?.name }.toSet()
        threadDir().listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.endsWith(".bak") || name.endsWith(".tmp")) return@forEach
            if (name !in keep) deleteFileAndBackup(file)
        }
    }

    private fun deleteFileAndBackup(file: File) {
        file.delete()
        File(file.parentFile, file.name + ".bak").delete()
    }

    /**
     * Moves [root] aside so the async delete cannot wipe files written after
     * [openWrites]. Leftover wiping directories are removed on the next warm.
     */
    private fun retireRootLocked(): File? {
        if (!root.exists()) return null
        val parent = root.parentFile ?: return null
        val doomed = File(parent, "$WIPING_PREFIX$epoch-${System.nanoTime()}")
        return if (root.renameTo(doomed)) doomed else {
            val copy = File(parent, "$WIPING_PREFIX$epoch-fallback")
            if (root.renameTo(copy)) copy else null
        }
    }

    private fun deleteWipingDirsLocked() {
        val parent = root.parentFile ?: return
        parent.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith(WIPING_PREFIX)) {
                file.deleteRecursively()
            }
        }
    }

    /**
     * Writes via [AtomicFile] so a crash keeps the previous complete file.
     * The old direct [File.writeText] fallback truncated the good file first.
     */
    private fun writeAtomic(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
                stream = null
            } catch (error: Throwable) {
                stream?.let { atomic.failWrite(it) }
                throw error
            }
        }
    }

    private fun readText(file: File): String {
        val backup = File(file.parentFile, file.name + ".bak")
        if (!file.isFile && !backup.isFile) return ""
        return runCatching {
            AtomicFile(file).openRead().use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()
    }

    @Serializable
    private data class ConversationCacheFile(
        val version: Int = 1,
        val server: String = "",
        val conversations: List<ConversationDto> = emptyList(),
    )

    @Serializable
    private data class RecentCacheFile(
        val version: Int = 1,
        val server: String = "",
        val ids: List<String> = emptyList(),
    )

    @Serializable
    private data class ThreadCacheFile(
        val version: Int = 1,
        val server: String = "",
        val conversationId: String = "",
        val messages: List<MessageDto> = emptyList(),
    )

    companion object {
        const val MAX_MESSAGES = 50
        const val MAX_THREADS = 10
        private const val DEBOUNCE_MS = 400L
        private const val WIPING_PREFIX = "inbox_cache.wiping."

        /** A blank stamp is a cache written before server stamping and still belongs here. */
        fun matchesServer(stored: String, current: String): Boolean {
            if (stored.isBlank()) return true
            return stored == current
        }
    }
}
