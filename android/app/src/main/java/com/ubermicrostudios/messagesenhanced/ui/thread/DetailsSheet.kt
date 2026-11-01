package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import coil.load
import com.ubermicrostudios.messagesenhanced.util.CircleAvatars
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto
import com.ubermicrostudios.messagesenhanced.databinding.DialogGroupDetailsBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemDetailsMemberBinding
import com.ubermicrostudios.messagesenhanced.ui.conversations.AvatarBinder
import com.ubermicrostudios.messagesenhanced.ui.conversations.AvatarTarget
import com.ubermicrostudios.messagesenhanced.ui.conversations.ConversationsViewModel
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.GroupDetails
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay

/** Group / contact details sheet matching web `openDetails` and screenshot F. */
object DetailsSheet {
    fun show(
        context: Context,
        conversation: ConversationDto,
        origin: String,
        selfFallback: String = "",
    ) {
        val people = Participants.parse(conversation.participants)
        val content = GroupDetails.content(
            isGroup = conversation.isGroup,
            title = Participants.displayName(conversation).ifBlank { conversation.name },
            people = people,
            selfFallback = selfFallback,
        )
        val binding = DialogGroupDetailsBinding.inflate(LayoutInflater.from(context))
        binding.detailsTitle.text = content.title
        binding.detailsName.text = formatPhoneForDisplay(content.name)
        binding.detailsSub.text = formatPhoneForDisplay(content.subtitle)
        binding.detailsSub.isVisible = content.subtitle.isNotBlank()
        binding.detailsMembersLabel.isVisible = content.showMembers
        binding.detailsMembers.isVisible = content.showMembers
        AvatarBinder.bind(
            AvatarTarget(
                binding.detailsAvatarBox,
                binding.detailsAvatar,
                binding.detailsAvatarPhoto,
                binding.detailsAvatarDuoA,
                binding.detailsAvatarDuoB,
            ),
            origin,
            conversation,
        )
        if (content.showMembers) {
            val inflater = LayoutInflater.from(context)
            content.members.forEach { member ->
                val row = ItemDetailsMemberBinding.inflate(inflater, binding.detailsMembers, true)
                row.name.text = memberLabel(member.name)
                row.number.isVisible = member.number.isNotBlank()
                row.number.text = formatPhoneForDisplay(member.number)
                paintMember(row, origin, member.person, member.name)
            }
            content.empty?.let { message ->
                val empty = TextView(context).apply {
                    text = message
                    setTextColor(context.getColor(com.ubermicrostudios.messagesenhanced.R.color.muted))
                    textSize = 14f
                    setPadding(8, 8, 8, 8)
                }
                binding.detailsMembers.addView(empty)
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()
        binding.detailsClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(
            (420 * context.resources.displayMetrics.density).toInt(),
        )
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun paintMember(
        row: ItemDetailsMemberBinding,
        origin: String,
        person: ParticipantDto,
        name: String,
    ) {
        val seed = person.number.ifBlank { person.phone }.ifBlank { name }
        row.avatar.text = Formatters.initials(person.name.ifBlank { name })
        row.avatar.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Participants.avatarColor(seed))
        }
        val profileUrl = if (person.me) ConversationsViewModel.publishedProfilePhotoUrl else null
        val fallback = Participants.personAvatarUrl(origin, person)
        val url = profileUrl?.takeIf { it.isNotBlank() } ?: fallback
        CircleAvatars.clip(row.avatarPhoto)
        if (url.isNullOrBlank()) {
            row.avatarPhoto.isVisible = false
            row.avatarPhoto.setImageDrawable(null)
            return
        }
        row.avatarPhoto.isVisible = true
        row.avatarPhoto.load(url) {
            circleAvatar(url)
            listener(
                onError = { _, _ ->
                    val next = fallback?.takeIf { it.isNotBlank() && it != url }
                    if (next == null) {
                        row.avatarPhoto.isVisible = false
                    } else {
                        row.avatarPhoto.load(next) {
                            circleAvatar(next)
                            listener(onError = { _, _ -> row.avatarPhoto.isVisible = false })
                        }
                    }
                },
            )
        }
    }

    /** "Ada (you)" keeps the suffix; a phone number in that name is formatted. */
    private fun memberLabel(name: String): String {
        val suffix = " (you)"
        if (!name.endsWith(suffix)) return formatPhoneForDisplay(name)
        return formatPhoneForDisplay(name.removeSuffix(suffix)) + suffix
    }
}
