package com.ubermicrostudios.messagesenhanced.ui.thread

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.util.CircleAvatars
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto
import com.ubermicrostudios.messagesenhanced.databinding.ItemViewerReplyBinding
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.ViewerReplies

class ViewerReplyAdapter : RecyclerView.Adapter<ViewerReplyAdapter.Row>() {
    private var items: List<MessageDto> = emptyList()
    private var people: List<ParticipantDto> = emptyList()
    var title: String = ""
    var isGroup: Boolean = false
    var origin: String = ""

    var participantsJson: String = ""
        set(value) {
            field = value
            people = Participants.parse(value)
        }

    fun submit(next: List<MessageDto>) {
        items = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val binding = ItemViewerReplyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Row(binding)
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        val message = items[position]
        val name = ViewerReplies.senderLabel(message, title, isGroup)
        val binding = holder.binding
        binding.replySender.text = name
        binding.replyTime.text = Formatters.relativeTime(message.timestampMs)
        binding.replyBody.text = ViewerReplies.replyText(message)
        binding.replyAvatar.text = Formatters.initials(name)
        binding.replyAvatar.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Formatters.avatarColor(name))
        }
        holder.request?.dispose()
        holder.request = null
        val photo = binding.replyAvatarPhoto
        CircleAvatars.clip(photo)
        photo.setImageDrawable(null)
        photo.isVisible = false
        val person = ViewerReplies.senderParticipant(people, message)
        val url = person?.let { Participants.personAvatarUrl(origin, it) }
        if (url.isNullOrBlank()) {
            photo.setTag(R.id.reply_avatar_photo, null)
            return
        }
        val token = message.messageId + ":" + url
        photo.setTag(R.id.reply_avatar_photo, token)
        val request = ImageRequest.Builder(photo.context)
            .data(url)
            .target(photo)
            .circleAvatar(url)
            .listener(
                onSuccess = { _, _ ->
                    if (photo.getTag(R.id.reply_avatar_photo) == token) photo.isVisible = true
                },
                onError = { _, _ ->
                    if (photo.getTag(R.id.reply_avatar_photo) == token) {
                        photo.isVisible = false
                        photo.setImageDrawable(null)
                    }
                },
            )
            .build()
        holder.request = photo.context.imageLoader.enqueue(request)
    }

    override fun onViewRecycled(holder: Row) {
        holder.request?.dispose()
        holder.request = null
        holder.binding.replyAvatarPhoto.setTag(R.id.reply_avatar_photo, null)
        holder.binding.replyAvatarPhoto.setImageDrawable(null)
        holder.binding.replyAvatarPhoto.isVisible = false
    }

    class Row(val binding: ItemViewerReplyBinding) : RecyclerView.ViewHolder(binding.root) {
        var request: Disposable? = null
    }
}
