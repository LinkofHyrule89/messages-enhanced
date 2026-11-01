package com.ubermicrostudios.messagesenhanced.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadActivity
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadFragment
import com.ubermicrostudios.messagesenhanced.util.CircleBitmaps
import com.ubermicrostudios.messagesenhanced.util.InitialsBitmap
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct Share: long-lived dynamic shortcuts for recent conversations (1:1 and groups),
 * in the share-target category declared in res/xml/shortcuts.xml.
 */
@Singleton
class ShareShortcuts @Inject constructor(
    @ApplicationContext context: Context,
    private val imageLoader: ImageLoader,
    private val baseUrl: BaseUrlProvider,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastKey = ""
    private val icons = HashMap<String, Bitmap>()

    /** Called whenever the inbox list changes; debounced, only republishes when the top set changes. */
    fun update(conversations: List<ConversationDto>) {
        val top = ShareTargets.pick(conversations, MAX)
        val key = ShareTargets.key(top)
        if (key == lastKey || top.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            delay(1_500)
            runCatching { publish(top) }.onSuccess { lastKey = key }
        }
    }

    private suspend fun publish(top: List<ConversationDto>) {
        val origin = baseUrl.origin()
        val keep = HashSet<String>()
        top.forEachIndexed { rank, conv ->
            val id = ShareTargets.shortcutId(conv.conversationId)
            keep.add(id)
            val name = formatPhoneForDisplay(Participants.displayName(conv)).ifBlank { "Conversation" }
            val bitmap = icon(origin, conv) ?: InitialsBitmap.of(name, conv.conversationId, ICON_PX)
            val icon = IconCompat.createWithBitmap(bitmap)
            val intent = Intent(context, ThreadActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                ThreadFragment.writeIntent(this, conv)
            }
            val builder = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(name.take(24))
                .setLongLabel(name)
                .setIcon(icon)
                .setIntent(intent)
                .setLongLived(true)
                .setRank(rank)
                .setCategories(setOf(CATEGORY))
            if (!conv.isGroup) {
                builder.setPerson(Person.Builder().setName(name).setKey(id).setIcon(icon).build())
            }
            ShortcutManagerCompat.pushDynamicShortcut(context, builder.build())
        }
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context)
            .map { it.id }
            .filter { it.startsWith(ShareTargets.PREFIX) && it !in keep }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
    }

    private suspend fun icon(origin: String, conv: ConversationDto): Bitmap? {
        val requests = Participants.avatarRequests(origin, conv)
        val ordered = requests.filter { it.group } + requests.filter { !it.group }.take(if (conv.isGroup) 0 else 1)
        for (request in ordered) {
            icons[request.url]?.let { return it }
            val loaded = withTimeoutOrNull(3_000) {
                val result = imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(request.url)
                        .size(ICON_PX)
                        .allowHardware(false)
                        .circleAvatar(request.url)
                        .build(),
                ) as? SuccessResult
                result?.drawable?.let { runCatching { CircleBitmaps.of(it.toBitmap(ICON_PX, ICON_PX)) }.getOrNull() }
            }
            if (loaded != null) {
                if (icons.size > 64) icons.clear()
                icons[request.url] = loaded
                return loaded
            }
        }
        return null
    }

    companion object {
        const val CATEGORY = "com.ubermicrostudios.messagesenhanced.SHARE_TARGET"
        private const val MAX = 8
        private const val ICON_PX = 192
    }
}

/** Pure helpers (unit-tested). */
object ShareTargets {
    const val PREFIX = "conv_"

    fun shortcutId(conversationId: String) = PREFIX + conversationId

    fun conversationId(shortcutId: String?): String =
        shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX).orEmpty()

    /** Most recent conversations with an id, in list order (server returns newest first). */
    fun pick(conversations: List<ConversationDto>, max: Int): List<ConversationDto> =
        conversations.filter { it.conversationId.isNotBlank() }.distinctBy { it.conversationId }
            .sortedByDescending { it.lastMessageTs }.take(max)

    fun key(top: List<ConversationDto>): String =
        top.joinToString("|") { it.conversationId + ":" + Participants.displayName(it) }
}
