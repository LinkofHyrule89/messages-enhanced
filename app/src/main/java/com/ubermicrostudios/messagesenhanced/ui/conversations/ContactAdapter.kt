package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.util.CircleAvatars
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.databinding.ItemContactBinding
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.NewChat
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay

class ContactAdapter(
    private val origin: () -> String,
    private val onContact: (ContactDto) -> Unit,
    private val onSendTo: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<NewChat.Row> = emptyList()

    fun submit(next: List<NewChat.Row>) {
        rows = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is NewChat.Row.Section, is NewChat.Row.Letter, is NewChat.Row.Empty -> TYPE_HEADER
        is NewChat.Row.SendTo, is NewChat.Row.Contact -> TYPE_CONTACT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(
                com.ubermicrostudios.messagesenhanced.R.layout.item_contact_header,
                parent,
                false,
            ) as TextView
            HeaderHolder(view)
        } else {
            ContactHolder(ItemContactBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is NewChat.Row.Section -> (holder as HeaderHolder).bind(row.title)
            is NewChat.Row.Letter -> (holder as HeaderHolder).bind(row.letter)
            is NewChat.Row.Empty -> (holder as HeaderHolder).bind(row.text)
            is NewChat.Row.SendTo -> (holder as ContactHolder).bindSendTo(row)
            is NewChat.Row.Contact -> (holder as ContactHolder).bind(row)
        }
    }

    private class HeaderHolder(val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(text: String) {
            view.text = text
        }
    }

    private inner class ContactHolder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: NewChat.Row.Contact) {
            val contact = row.contact
            binding.name.text = formatPhoneForDisplay(contact.displayName)
            binding.number.isVisible = contact.displayName != contact.phone && contact.phone.isNotBlank()
            binding.number.text = formatPhoneForDisplay(contact.phone)
            paintAvatar(contact.displayName, contact.phone, contact)
            binding.root.setBackgroundColor(if (row.selected) 0x332A6FE0 else Color.TRANSPARENT)
            binding.root.setOnClickListener { onContact(contact) }
        }

        fun bindSendTo(row: NewChat.Row.SendTo) {
            val prefix = if (row.group) "Add " else "Send to "
            binding.name.text = prefix + formatPhoneForDisplay(row.query)
            binding.number.isVisible = false
            paintAvatar(row.query, row.query, null)
            binding.root.setBackgroundColor(Color.TRANSPARENT)
            binding.root.setOnClickListener { onSendTo(row.query) }
        }

        private fun paintAvatar(name: String, number: String, contact: ContactDto?) {
            val color = Participants.avatarColor(number.ifBlank { name })
            binding.avatar.text = Formatters.initials(name)
            binding.avatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val url = contact?.let {
                Participants.avatarUrl(
                    origin(),
                    participantId = it.participantId,
                    contactId = it.contactKey,
                    phone = it.phone,
                )
            }
            CircleAvatars.clip(binding.avatarPhoto)
            if (url.isNullOrBlank()) {
                binding.avatarPhoto.isVisible = false
                binding.avatarPhoto.setImageDrawable(null)
            } else {
                binding.avatarPhoto.isVisible = true
                binding.avatarPhoto.load(url) {
                    circleAvatar(url)
                    listener(onError = { _, _ -> binding.avatarPhoto.isVisible = false })
                }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CONTACT = 1
    }
}
