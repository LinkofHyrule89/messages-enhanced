package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.databinding.ItemConversationHeaderBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemSearchHitBinding
import com.ubermicrostudios.messagesenhanced.util.InboxSearch
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.RecyclerIds
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay

sealed class SearchRow {
    data class Header(val title: String) : SearchRow()
    data class Conversation(val conversation: ConversationDto) : SearchRow()
    data class Message(val hit: InboxSearch.MessageHit) : SearchRow()
}

class SearchAdapter(
    private val onConversation: (ConversationDto) -> Unit,
    private val onMessage: (InboxSearch.MessageHit) -> Unit,
) : ListAdapter<SearchRow, RecyclerView.ViewHolder>(Diff) {

    init {
        setHasStableIds(true)
    }

    var query: String = ""

    fun rows(conversationsTitle: String, messagesTitle: String, search: SearchUi): List<SearchRow> {
        val out = ArrayList<SearchRow>()
        if (search.conversations.isNotEmpty()) {
            out.add(SearchRow.Header(conversationsTitle))
            search.conversations.forEach { out.add(SearchRow.Conversation(it)) }
        }
        if (search.messages.isNotEmpty()) {
            out.add(SearchRow.Header(messagesTitle))
            search.messages.forEach { out.add(SearchRow.Message(it)) }
        }
        return out
    }

    override fun getItemId(position: Int): Long = when (val row = getItem(position)) {
        is SearchRow.Header -> RecyclerIds.of(1, row.title)
        is SearchRow.Conversation -> RecyclerIds.of(2, row.conversation.conversationId)
        is SearchRow.Message -> RecyclerIds.of(3, row.hit.message.messageId)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchRow.Header -> VIEW_HEADER
        is SearchRow.Conversation, is SearchRow.Message -> VIEW_HIT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_HEADER) {
            HeaderHolder(ItemConversationHeaderBinding.inflate(inflater, parent, false))
        } else {
            HitHolder(ItemSearchHitBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SearchRow.Header -> (holder as HeaderHolder).bind(row)
            is SearchRow.Conversation -> (holder as HitHolder).bindConversation(row.conversation)
            is SearchRow.Message -> (holder as HitHolder).bindMessage(row.hit)
        }
    }

    private class HeaderHolder(val binding: ItemConversationHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SearchRow.Header) {
            binding.pin.isVisible = false
            binding.title.isAllCaps = false
            binding.title.text = row.title
        }
    }

    private inner class HitHolder(val binding: ItemSearchHitBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindConversation(conversation: ConversationDto) {
            val name = formatPhoneForDisplay(Participants.convName(conversation).ifBlank { "Conversation" })
            binding.title.text = highlight(name, query, binding.root.context.getColor(R.color.search_mark))
            binding.preview.isVisible = false
            binding.time.isVisible = false
            binding.root.setOnClickListener { onConversation(conversation) }
        }

        fun bindMessage(hit: InboxSearch.MessageHit) {
            binding.title.text = formatPhoneForDisplay(hit.title)
            val mark = binding.root.context.getColor(R.color.search_mark)
            val shown = displayPrefix(hit.prefix) + hit.body
            val spanned = highlight(shown, query, mark)
            binding.preview.isVisible = shown.isNotBlank()
            binding.preview.text = spanned
            val whenLabel = InboxSearch.dayLabel(hit.message.timestampMs)
            binding.time.isVisible = whenLabel.isNotBlank()
            binding.time.text = whenLabel
            binding.root.setOnClickListener { onMessage(hit) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<SearchRow>() {
        override fun areItemsTheSame(a: SearchRow, b: SearchRow): Boolean = when {
            a is SearchRow.Header && b is SearchRow.Header -> a.title == b.title
            a is SearchRow.Conversation && b is SearchRow.Conversation ->
                a.conversation.conversationId == b.conversation.conversationId
            a is SearchRow.Message && b is SearchRow.Message ->
                a.hit.message.messageId == b.hit.message.messageId
            else -> false
        }

        override fun areContentsTheSame(a: SearchRow, b: SearchRow): Boolean = a == b
    }

    companion object {
        private const val VIEW_HEADER = 1
        private const val VIEW_HIT = 2

        private fun displayPrefix(prefix: String): String {
            if (!prefix.endsWith(": ")) return formatPhoneForDisplay(prefix)
            return formatPhoneForDisplay(prefix.removeSuffix(": ")) + ": "
        }

        fun highlight(text: String, query: String, color: Int): CharSequence {
            if (query.isBlank()) return text
            val value = SpannableString(text)
            val lo = text.lowercase()
            val ql = query.trim().lowercase()
            if (ql.isEmpty()) return value
            var index = 0
            while (index < lo.length) {
                val found = lo.indexOf(ql, index)
                if (found < 0) break
                value.setSpan(
                    BackgroundColorSpan(color),
                    found,
                    found + ql.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                index = found + ql.length
            }
            return value
        }
    }
}
