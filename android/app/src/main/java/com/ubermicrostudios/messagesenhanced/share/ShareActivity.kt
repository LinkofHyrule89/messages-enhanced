package com.ubermicrostudios.messagesenhanced.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.ui.thread.AttachmentImport
import com.ubermicrostudios.messagesenhanced.ui.thread.ForwardPicker
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadActivity
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadFragment
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan
import com.ubermicrostudios.messagesenhanced.util.ShareIndex
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Android share sheet entry (ACTION_SEND / SEND_MULTIPLE) and Direct Share target.
 * Shared files are copied to the app cache off the main thread, then the chosen conversation's
 * composer opens with them attached. Nothing is sent until the user taps Send there.
 */
@AndroidEntryPoint
class ShareActivity : SessionActivity() {
    @Inject lateinit var messagesRepo: MessagesRepository
    @Inject lateinit var baseUrl: BaseUrlProvider
    @Inject lateinit var appPrefs: com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeAuthLoss()
        if (savedInstanceState != null) return
        val raw = sharedText(intent)
        // Auto-clean shared links (share sheet and direct share) before prefilling the composer.
        val text = if (appPrefs.autoCleanSharedLinks) {
            com.ubermicrostudios.messagesenhanced.util.LinkCleaner.cleanText(raw, appPrefs.cleanLinksRule).text
        } else raw
        val uris = sharedUris(intent)
        if (text.isBlank() && uris.isEmpty()) {
            finish()
            return
        }
        val shortcutConv = ShareTargets.conversationId(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> runCatching { AttachmentImport.copy(applicationContext, uri) }.getOrNull() }
            }
            if (files.size < uris.size) toast("Some items could not be attached")
            val conversations = runCatchingCancellable { messagesRepo.conversations() }.getOrDefault(emptyList())
            if (isFinishing) return@launch
            if (shortcutConv.isNotBlank()) {
                val conv = conversations.firstOrNull { it.conversationId == shortcutConv }
                    ?: ConversationDto(conversationId = shortcutConv)
                openComposer(conv, text, files)
                return@launch
            }
            val contacts = runCatchingCancellable { messagesRepo.contacts().all }.getOrDefault(emptyList())
            if (isFinishing) return@launch
            val thumb = files.firstOrNull { it.mime.startsWith("image/") }?.path.orEmpty()
            ForwardPicker.share(
                context = this@ShareActivity,
                origin = baseUrl.origin(),
                conversations = conversations,
                contacts = contacts,
                thumbnail = thumb,
                caption = text,
                onSend = { selection -> lifecycleScope.launch { route(selection, files, asGroup = false) } },
                onSendGroup = { selection -> lifecycleScope.launch { route(selection, files, asGroup = true) } },
                onCancel = {
                    cleanup(files)
                    finish()
                },
            )
        }
    }

    /** One target: open its composer prefilled. Several (separate): the user tapped Send, so deliver to each. */
    private suspend fun route(selection: ForwardPicker.Selection, files: List<ComposerPlan.PendingFile>, asGroup: Boolean) {
        val caption = selection.caption.trim()
        if (asGroup) {
            val numbers = ShareIndex.groupNumbers(selection.contacts, selection.conversations)
            val started = if (numbers.size >= 2) {
                runCatchingCancellable { messagesRepo.startConversation(numbers) }.getOrNull()
            } else {
                null
            }
            val id = started?.conversationId.orEmpty()
            if (id.isNotBlank()) {
                openComposer(ConversationDto(conversationId = id, isGroup = true), caption, files)
                return
            }
        }
        val targets = LinkedHashMap<String, ConversationDto>()
        for (c in selection.conversations) if (c.conversationId.isNotBlank()) targets.putIfAbsent(c.conversationId, c)
        for (contact in selection.contacts) {
            val id = resolveContact(contact)
            if (id.isNotBlank()) targets.putIfAbsent(id, ConversationDto(conversationId = id, name = contact.displayName))
        }
        when {
            targets.isEmpty() -> {
                toast("Nothing shared")
                cleanup(files)
                finish()
            }
            targets.size == 1 -> openComposer(targets.values.first(), caption, files)
            else -> {
                var sent = 0
                for (id in targets.keys) {
                    if (runCatchingCancellable { deliver(id, caption, files) }.isSuccess) sent++
                }
                toast(if (sent == 0) "Share failed" else "Shared to $sent")
                cleanup(files)
                finish()
            }
        }
    }

    private suspend fun deliver(conversationId: String, caption: String, files: List<ComposerPlan.PendingFile>) {
        for (file in files) {
            val bytes = withContext(Dispatchers.IO) { File(file.path).readBytes() }
            messagesRepo.sendMedia(conversationId = conversationId, bytes = bytes, filename = file.name, mime = file.mime)
        }
        if (caption.isNotBlank()) messagesRepo.send(conversationId, caption)
    }

    private suspend fun resolveContact(contact: ContactDto): String {
        if (contact.conversationId.isNotBlank()) return contact.conversationId
        if (contact.phone.isBlank()) return ""
        return runCatchingCancellable { messagesRepo.startConversation(listOf(contact.phone)) }
            .getOrNull()?.conversationId.orEmpty()
    }

    private fun openComposer(conv: ConversationDto, text: String, files: List<ComposerPlan.PendingFile>) {
        val open = Intent(this, ThreadActivity::class.java).apply {
            ThreadFragment.writeIntent(this, conv)
            ThreadFragment.writeShare(this, text, files)
        }
        startActivity(open)
        finish()
    }

    private fun cleanup(files: List<ComposerPlan.PendingFile>) {
        files.forEach { runCatching { File(it.path).delete() } }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        fun sharedText(intent: Intent): String {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
            return if (subject.isNotBlank() && text.isNotBlank() && !text.contains(subject)) "$subject\n$text" else text
        }

        fun sharedUris(intent: Intent): List<Uri> {
            val out = ArrayList<Uri>()
            when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out.add(it) }
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out.addAll(it) }
            }
            if (out.isEmpty()) {
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) } }
            }
            return out.distinct().take(MAX_ITEMS)
        }

        private const val MAX_ITEMS = 20
    }
}
