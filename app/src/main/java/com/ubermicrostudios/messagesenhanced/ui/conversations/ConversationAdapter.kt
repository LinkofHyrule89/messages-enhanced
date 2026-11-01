package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.net.TypingTracker
import com.ubermicrostudios.messagesenhanced.databinding.ItemConversationBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemConversationHeaderBinding
import com.ubermicrostudios.messagesenhanced.util.ConvListRow
import com.ubermicrostudios.messagesenhanced.util.Encryption
import com.ubermicrostudios.messagesenhanced.util.RecyclerIds
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.MessageStatus
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.ReceiptKind
import com.ubermicrostudios.messagesenhanced.util.ThemeColors
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay

class ConversationAdapter(
    private val origin: () -> String,
    private val onClick: (ConversationDto) -> Unit,
    private val onMenu: (ConversationDto, View) -> Unit,
    private val draftOf: (String) -> String = { "" },
) : ListAdapter<ConvListRow, RecyclerView.ViewHolder>(Diff) {

    init {
        setHasStableIds(true)
    }

    var selectedId: String? = null
        set(value) {
            val old = field
            field = value
            currentList.forEachIndexed { index, item ->
                val id = (item as? ConvListRow.Item)?.conversation?.conversationId
                if (id != null && (id == old || id == value)) notifyItemChanged(index)
            }
        }

    var typingNames: Map<String, List<String>> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var colors: ThemeColors = ThemeColors.Default
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    object Diff : DiffUtil.ItemCallback<ConvListRow>() {
        override fun areItemsTheSame(a: ConvListRow, b: ConvListRow): Boolean = when {
            a is ConvListRow.Header && b is ConvListRow.Header -> a.title == b.title
            a is ConvListRow.Item && b is ConvListRow.Item ->
                a.conversation.conversationId == b.conversation.conversationId
            else -> false
        }

        override fun areContentsTheSame(a: ConvListRow, b: ConvListRow) = a == b
    }

    override fun getItemId(position: Int): Long = when (val row = getItem(position)) {
        is ConvListRow.Header -> RecyclerIds.of(1, row.title)
        is ConvListRow.Item -> RecyclerIds.of(2, row.conversation.conversationId)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ConvListRow.Header -> VIEW_HEADER
        is ConvListRow.Item -> VIEW_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_HEADER) {
            HeaderHolder(ItemConversationHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemConversationBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ConvListRow.Header -> (holder as HeaderHolder).bind(row)
            is ConvListRow.Item -> (holder as ItemHolder).bind(row.conversation)
        }
    }

    private inner class HeaderHolder(val binding: ItemConversationHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ConvListRow.Header) {
            binding.title.text = row.title
            binding.pin.isVisible = row.pinned
            val muted = binding.root.context.getColor(R.color.muted)
            binding.pin.imageTintList = ColorStateList.valueOf(muted)
            binding.title.setTextColor(muted)
        }
    }

    private inner class ItemHolder(val binding: ItemConversationBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConversationDto) {
            val b = binding
            val context = b.root.context
            val text = context.getColor(R.color.text)
            val muted = context.getColor(R.color.muted)
            val name = Participants.displayName(item)
            b.name.text = formatPhoneForDisplay(name)
            val unread = item.unreadCount > 0
            b.name.setTextColor(text)
            b.name.typeface = nameWeight(unread)
            val typing = typingNames[item.conversationId].orEmpty()
            if (typing.isNotEmpty()) {
                b.preview.text = TypingTracker.labelFor(typing.map { formatPhoneForDisplay(it) }, item.isGroup)
                b.preview.setTextColor(colors.accent)
                b.preview.setTypeface(Typeface.SANS_SERIF, if (unread) Typeface.BOLD_ITALIC else Typeface.ITALIC)
                b.statusIcon.isVisible = false
            } else if (draftOf(item.conversationId).isNotBlank()) {
                // Google Messages: unsent text shows as "Draft: ..." in the list.
                b.preview.text = com.ubermicrostudios.messagesenhanced.util.Drafts.listPreview(draftOf(item.conversationId))
                b.preview.setTextColor(colors.accent)
                b.preview.setTypeface(Typeface.SANS_SERIF, Typeface.ITALIC)
                b.statusIcon.isVisible = false
            } else {
                val preview = item.lastMessagePreview
                b.preview.text = if (item.lastFromMe && preview.isNotBlank() && !preview.startsWith("You:")) {
                    "You: $preview"
                } else {
                    preview
                }
                b.preview.setTextColor(if (unread) text else muted)
                b.preview.setTypeface(Typeface.SANS_SERIF, if (unread) Typeface.BOLD else Typeface.NORMAL)
                val kind = MessageStatus.listStatusKind(item)
                if (kind == null) {
                    b.statusIcon.isVisible = false
                } else {
                    b.statusIcon.isVisible = true
                    b.statusIcon.setImageResource(kind.icon())
                    // Web list icons: rings in the muted text color; read = filled grey circles (own colors).
                    b.statusIcon.imageTintList = when (kind) {
                        ReceiptKind.READ -> null
                        ReceiptKind.SENT, ReceiptKind.DELIVERED -> ColorStateList.valueOf(muted)
                        else -> ColorStateList.valueOf(kind.tint(context))
                    }
                    b.statusIcon.contentDescription = kind.label
                }
            }
            b.time.text = Formatters.listTime(item.lastMessageTs)
            b.time.setTextColor(muted)
            b.unread.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            b.unread.isVisible = unread
            b.unread.backgroundTintList = ColorStateList.valueOf(colors.accent)
            b.muted.isVisible = item.notificationMode == "muted"
            b.favorite.isVisible = item.isFavorite
            b.e2ee.isVisible = Encryption.protocolEncrypted(item.displayProtocol)
            b.e2ee.imageTintList = ColorStateList.valueOf(muted)
            b.rcsBadge.isVisible = Encryption.isRcs(item.displayProtocol)
            b.rcsBadge.contentDescription = context.getString(R.string.composer_hint_rcs)
            val pinned = item.googlePinned || item.localPinnedAtMs > 0L
            b.pin.isVisible = pinned
            if (pinned) {
                val onPhone = item.googlePinned
                b.pin.imageTintList = ColorStateList.valueOf(if (onPhone) colors.accent else muted)
                b.pin.contentDescription = context.getString(if (onPhone) R.string.pinned_on_phone else R.string.pinned)
            }
            AvatarBinder.bind(b, origin(), item)
            val selected = item.conversationId == selectedId
            b.row.setBackgroundColor(if (selected) context.getColor(R.color.conv_active) else Color.TRANSPARENT)
            b.accentBar.isVisible = selected
            b.accentBar.setBackgroundColor(colors.accent)
            b.overflow.isVisible = false
            b.row.setOnClickListener { onClick(item) }
            b.row.setOnLongClickListener {
                onMenu(item, b.row)
                true
            }
        }
    }

    private fun nameWeight(unread: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.SANS_SERIF, if (unread) 700 else 600, false)
        } else if (unread) {
            Typeface.create("sans-serif", Typeface.BOLD)
        } else {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_ITEM = 1
    }
}

internal fun ReceiptKind.icon(): Int = when (this) {
    ReceiptKind.SENDING -> R.drawable.ic_status_sending
    ReceiptKind.SENT -> R.drawable.ic_status_sent
    ReceiptKind.DELIVERED -> R.drawable.ic_status_delivered
    ReceiptKind.READ -> R.drawable.ic_status_read
    ReceiptKind.FAILED -> R.drawable.ic_status_failed
}

internal fun ReceiptKind.tint(context: android.content.Context): Int = when (this) {
    ReceiptKind.READ -> context.getColor(R.color.accent)
    ReceiptKind.FAILED -> context.getColor(R.color.danger)
    else -> context.getColor(R.color.muted)
}
