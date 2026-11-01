package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.databinding.ItemConversationBinding
import com.ubermicrostudios.messagesenhanced.util.AvatarRequest
import com.ubermicrostudios.messagesenhanced.util.CircleAvatars
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.circleAvatar

/** The views [AvatarBinder] fills. The list row and the thread header share one implementation. */
class AvatarTarget(
    val box: View,
    val initials: TextView,
    val photo: ImageView,
    val duoA: ImageView,
    val duoB: ImageView,
)

/**
 * Loads `/api/avatar` through the app ImageLoader (session cookie client).
 * Group icon first; otherwise a two-photo collage; otherwise colored initials.
 */
object AvatarBinder {
    fun bind(binding: ItemConversationBinding, origin: String, conversation: ConversationDto) {
        bind(
            AvatarTarget(binding.avatarBox, binding.avatar, binding.avatarPhoto, binding.avatarDuoA, binding.avatarDuoB),
            origin,
            conversation,
        )
    }

    fun bind(target: AvatarTarget, origin: String, conversation: ConversationDto) {
        CircleAvatars.clip(target.photo)
        CircleAvatars.clip(target.duoA)
        CircleAvatars.clip(target.duoB)
        val name = Participants.displayName(conversation)
        val color = Participants.avatarColor(conversation.conversationId.ifBlank { name })
        val initials = Formatters.initials(name)
        val requests = Participants.avatarRequests(origin, conversation)
        val token = conversation.conversationId + ":" + requests.joinToString("|") { it.url }
        if (target.box.getTag(R.id.avatar_box) == token) return
        showInitials(target, initials, color)
        target.box.setTag(R.id.avatar_box, token)
        cancel(target)
        if (requests.isEmpty()) return
        val group = requests.firstOrNull { it.group }
        val members = requests.filter { !it.group }
        if (group != null) {
            enqueue(target, group.url, token) { ok ->
                if (!alive(target, token)) return@enqueue
                if (ok) showSingle(target, group.url, token) else loadCollage(target, members, token, color, initials)
            }
        } else {
            loadCollage(target, members, token, color, initials)
        }
    }

    private fun loadCollage(
        target: AvatarTarget,
        members: List<AvatarRequest>,
        token: String,
        color: Int,
        initials: String,
    ) {
        if (members.isEmpty()) {
            showInitials(target, initials, color)
            return
        }
        val found = ArrayList<String>()
        fun next(index: Int) {
            if (!alive(target, token)) return
            if (found.size >= 2 || index >= members.size) {
                when (found.size) {
                    0 -> showInitials(target, initials, color)
                    1 -> showSingle(target, found[0], token)
                    else -> showDuo(target, found[0], found[1], token)
                }
                return
            }
            val url = members[index].url
            enqueue(target, url, token) { ok ->
                if (ok) found.add(url)
                next(index + 1)
            }
        }
        next(0)
    }

    private fun showInitials(target: AvatarTarget, initials: String, color: Int) {
        target.initials.isVisible = true
        target.initials.text = initials
        target.initials.background = oval(color)
        target.photo.isVisible = false
        target.duoA.isVisible = false
        target.duoB.isVisible = false
        target.photo.setImageDrawable(null)
        target.duoA.setImageDrawable(null)
        target.duoB.setImageDrawable(null)
    }

    private fun showSingle(target: AvatarTarget, url: String, token: String) {
        target.initials.isVisible = false
        target.duoA.isVisible = false
        target.duoB.isVisible = false
        target.photo.isVisible = true
        target.photo.loadCircle(url, token, target) {
            if (alive(target, token)) {
                showInitials(target, target.initials.text?.toString().orEmpty().ifBlank { "?" }, Color.DKGRAY)
            }
        }
    }

    private fun showDuo(target: AvatarTarget, first: String, second: String, token: String) {
        target.initials.isVisible = false
        target.photo.isVisible = false
        target.duoA.isVisible = true
        target.duoB.isVisible = true
        target.duoA.loadCircle(first, token, target, null)
        target.duoB.loadCircle(second, token, target, null)
    }

    private fun ImageView.loadCircle(
        url: String,
        token: String,
        target: AvatarTarget,
        onError: (() -> Unit)?,
    ) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .target(this)
            .circleAvatar(url)
            .listener(
                onError = { _, _ -> if (alive(target, token)) onError?.invoke() },
            )
            .build()
        track(target, context.imageLoader.enqueue(request))
    }

    private fun enqueue(
        target: AvatarTarget,
        url: String,
        token: String,
        onResult: (Boolean) -> Unit,
    ) {
        val request = ImageRequest.Builder(target.box.context)
            .data(url)
            .circleAvatar(url)
            .listener(
                onSuccess = { _, _ -> if (alive(target, token)) onResult(true) },
                onError = { _, _ -> if (alive(target, token)) onResult(false) },
            )
            .build()
        track(target, target.box.context.imageLoader.enqueue(request))
    }

    private fun track(target: AvatarTarget, disposable: Disposable) {
        val key = R.id.avatar_photo
        @Suppress("UNCHECKED_CAST")
        val list = (target.box.getTag(key) as? MutableList<Disposable>) ?: mutableListOf()
        list.add(disposable)
        target.box.setTag(key, list)
    }

    private fun cancel(target: AvatarTarget) {
        @Suppress("UNCHECKED_CAST")
        val list = target.box.getTag(R.id.avatar_photo) as? MutableList<Disposable>
        list?.forEach { it.dispose() }
        list?.clear()
    }

    private fun alive(target: AvatarTarget, token: String): Boolean =
        target.box.getTag(R.id.avatar_box) == token

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
