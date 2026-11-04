package com.ubermicrostudios.messagesenhanced.ui.thread

import android.app.Dialog
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.imageLoader
import coil.load
import coil.request.Disposable
import coil.request.ImageRequest
import com.ubermicrostudios.messagesenhanced.util.CircleAvatars
import com.ubermicrostudios.messagesenhanced.util.RetryingImages
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.google.android.material.color.MaterialColors
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.databinding.DialogForwardBinding
import com.ubermicrostudios.messagesenhanced.databinding.DialogShareBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemShareEmptyBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemShareMoreBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemSharePersonBinding
import com.ubermicrostudios.messagesenhanced.databinding.ItemShareSectionBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.conversations.AvatarBinder
import com.ubermicrostudios.messagesenhanced.ui.conversations.AvatarTarget
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.ShareIndex
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import java.io.File

/** Pick a conversation, matching the web forward sheet's name filter (first 80). */
object ForwardPicker {
    data class Selection(
        val conversations: List<ConversationDto>,
        val contacts: List<ContactDto>,
        val caption: String,
    )

    fun show(
        context: android.content.Context,
        title: String,
        conversations: List<ConversationDto>,
        onPick: (ConversationDto) -> Unit,
    ) {
        val binding = DialogForwardBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun render(query: String) {
            val q = query.trim().lowercase()
            val list = conversations
                .filter { q.isEmpty() || Participants.convName(it).lowercase().contains(q) }
                .take(80)
            val labels = if (list.isEmpty()) {
                listOf(context.getString(R.string.forward_empty))
            } else {
                list.map { formatPhoneForDisplay(Participants.convName(it)) }
            }
            binding.forwardList.adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(0xFFF2F4F7.toInt())
                    return view
                }
            }
            binding.forwardList.setOnItemClickListener { _, _, position, _ ->
                val conversation = list.getOrNull(position) ?: return@setOnItemClickListener
                dialog.dismiss()
                onPick(conversation)
            }
        }
        binding.forwardSearch.doAfterTextChanged { render(it?.toString().orEmpty()) }
        render("")
        dialog.show()
    }

    /**
     * Full-screen Share sheet used from the photo viewer.
     * [thumbnail] is a remote URL or a local file path. The X removes it and closes the sheet.
     */
    fun share(
        context: android.content.Context,
        origin: String,
        conversations: List<ConversationDto>,
        contacts: List<ContactDto>,
        thumbnail: String,
        caption: String,
        onSend: (Selection) -> Unit,
        onSendGroup: (Selection) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        var chosen = false
        val binding = DialogShareBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context, R.style.Theme_MessagesEnhancedNative_Share)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(binding.root)
        dialog.setCancelable(true)
        val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val selectedConversations = HashSet<String>()
        val selectedContacts = HashSet<String>()
        var query = ""
        var expanded = false
        var searchOpen = false
        val rows = ArrayList<ShareIndex.Row>()
        var renderList: () -> Unit = {}
        val adapter = ShareAdapter(
            origin = origin,
            onConversation = { id ->
                if (!selectedConversations.add(id)) selectedConversations.remove(id)
                renderList()
            },
            onContact = { key ->
                if (!selectedContacts.add(key)) selectedContacts.remove(key)
                renderList()
            },
            onMore = {
                expanded = true
                renderList()
            },
        )

        fun current(): Selection {
            val convos = conversations.filter { it.conversationId in selectedConversations }
            val people = contacts.filter { ShareIndex.contactKey(it) in selectedContacts }
            return Selection(convos, people, binding.caption.text?.toString().orEmpty())
        }

        renderList = {
            val next = ShareIndex.rows(
                conversations = conversations,
                contacts = contacts,
                query = query,
                expanded = expanded,
                selectedConversationIds = selectedConversations,
                selectedContactKeys = selectedContacts,
                recentTitle = context.getString(R.string.share_recent),
                emptyText = context.getString(R.string.share_no_matches),
            )
            rows.clear()
            rows.addAll(next)
            adapter.submit(rows)
            binding.send.isEnabled = selectedConversations.isNotEmpty() || selectedContacts.isNotEmpty()
        }

        fun closeSearch() {
            searchOpen = false
            query = ""
            binding.search.setText("")
            binding.search.isVisible = false
            binding.title.isVisible = true
            binding.searchButton.isVisible = true
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.search.windowToken, 0)
            renderList()
        }

        binding.shareList.layoutManager = LinearLayoutManager(context)
        binding.shareList.adapter = adapter
        binding.caption.setText(caption)
        val radius = 12f * context.resources.displayMetrics.density
        binding.thumb.clipToOutline = true
        binding.thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        if (thumbnail.isBlank()) {
            binding.thumb.setImageDrawable(null)
        } else if (thumbnail.startsWith("http://") || thumbnail.startsWith("https://")) {
            RetryingImages.load(binding.thumb, thumbnail)
        } else {
            val path = if (thumbnail.startsWith("file://")) thumbnail.removePrefix("file://") else thumbnail
            binding.thumb.load(File(path))
        }
        binding.removeAttachment.setOnClickListener {
            binding.thumb.dispose()
            dialog.dismiss()
        }
        binding.back.setOnClickListener {
            if (searchOpen) closeSearch() else dialog.dismiss()
        }
        binding.searchButton.setOnClickListener {
            searchOpen = true
            binding.title.isVisible = false
            binding.searchButton.isVisible = false
            binding.search.isVisible = true
            binding.search.requestFocus()
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(binding.search, InputMethodManager.SHOW_IMPLICIT)
        }
        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            renderList()
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && searchOpen) {
                closeSearch()
                true
            } else {
                false
            }
        }
        binding.send.setOnClickListener {
            val selection = current()
            chosen = true
            dialog.dismiss()
            onSend(selection)
        }
        binding.sendGroup.setOnClickListener {
            val selection = current()
            if (selection.conversations.isEmpty() && selection.contacts.isEmpty()) return@setOnClickListener
            chosen = true
            dialog.dismiss()
            onSendGroup(selection)
        }
        dialog.setOnShowListener { Insets.apply(binding.shareRoot, includeIme = true) }
        dialog.setOnDismissListener {
            binding.thumb.dispose()
            if (!chosen) onCancel()
        }
        renderList()
        dialog.show()
    }

    private class ShareAdapter(
        private val origin: String,
        private val onConversation: (String) -> Unit,
        private val onContact: (String) -> Unit,
        private val onMore: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<ShareIndex.Row> = emptyList()

        fun submit(next: List<ShareIndex.Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is ShareIndex.Row.Section -> TYPE_SECTION
            is ShareIndex.Row.More -> TYPE_MORE
            is ShareIndex.Row.Empty -> TYPE_EMPTY
            is ShareIndex.Row.Conversation, is ShareIndex.Row.Contact -> TYPE_PERSON
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION -> SectionHolder(ItemShareSectionBinding.inflate(inflater, parent, false))
                TYPE_MORE -> MoreHolder(ItemShareMoreBinding.inflate(inflater, parent, false), onMore)
                TYPE_EMPTY -> EmptyHolder(ItemShareEmptyBinding.inflate(inflater, parent, false))
                else -> PersonHolder(ItemSharePersonBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is ShareIndex.Row.Section -> (holder as SectionHolder).binding.title.text = row.title
                is ShareIndex.Row.More -> Unit
                is ShareIndex.Row.Empty -> (holder as EmptyHolder).binding.empty.text = row.text
                is ShareIndex.Row.Conversation -> (holder as PersonHolder).bindConversation(row, origin, onConversation)
                is ShareIndex.Row.Contact -> (holder as PersonHolder).bindContact(row, origin, onContact)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is PersonHolder) holder.clear()
        }

        class SectionHolder(val binding: ItemShareSectionBinding) : RecyclerView.ViewHolder(binding.root)
        class EmptyHolder(val binding: ItemShareEmptyBinding) : RecyclerView.ViewHolder(binding.root)
        class MoreHolder(binding: ItemShareMoreBinding, onMore: () -> Unit) : RecyclerView.ViewHolder(binding.root) {
            init {
                binding.more.setOnClickListener { onMore() }
            }
        }

        class PersonHolder(val binding: ItemSharePersonBinding) : RecyclerView.ViewHolder(binding.root) {
            private var contactRequest: Disposable? = null

            fun bindConversation(row: ShareIndex.Row.Conversation, origin: String, onClick: (String) -> Unit) {
                clearContactPhoto()
                val conversation = row.conversation
                val name = formatPhoneForDisplay(Participants.displayName(conversation))
                binding.name.text = name
                binding.subtitle.isVisible = row.preview.isNotBlank()
                binding.subtitle.text = row.preview
                binding.radio.isSelected = row.selected
                paintCard(row.first, row.last)
                binding.divider.isVisible = !row.last
                AvatarBinder.bind(
                    AvatarTarget(binding.avatarBox, binding.avatar, binding.avatarPhoto, binding.avatarDuoA, binding.avatarDuoB),
                    origin,
                    conversation,
                )
                binding.root.setOnClickListener { onClick(conversation.conversationId) }
            }

            fun bindContact(row: ShareIndex.Row.Contact, origin: String, onClick: (String) -> Unit) {
                val contact = row.contact
                val name = formatPhoneForDisplay(contact.displayName)
                val phone = formatPhoneForDisplay(contact.phone)
                binding.name.text = name
                binding.subtitle.isVisible = phone.isNotBlank() && phone != name
                binding.subtitle.text = phone
                binding.radio.isSelected = row.selected
                paintCard(row.first, row.last)
                binding.divider.isVisible = !row.last
                binding.avatarDuoA.isVisible = false
                binding.avatarDuoB.isVisible = false
                paintContact(origin, contact)
                binding.root.setOnClickListener { onClick(ShareIndex.contactKey(contact)) }
            }

            fun clear() {
                clearContactPhoto()
                binding.avatarPhoto.dispose()
            }

            private fun clearContactPhoto() {
                contactRequest?.dispose()
                contactRequest = null
                binding.avatarPhoto.setTag(R.id.avatar_photo, null)
            }

            private fun paintContact(origin: String, contact: ContactDto) {
                val name = contact.displayName
                val color = Participants.avatarColor(contact.phone.ifBlank { name })
                binding.avatar.text = Formatters.initials(name)
                binding.avatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                CircleAvatars.clip(binding.avatarPhoto)
                binding.avatarPhoto.setImageDrawable(null)
                binding.avatarPhoto.isVisible = false
                contactRequest?.dispose()
                val url = Participants.avatarUrl(
                    origin,
                    participantId = contact.participantId,
                    contactId = contact.contactKey,
                    phone = contact.phone,
                )
                if (url.isNullOrBlank()) {
                    contactRequest = null
                    return
                }
                val token = url
                binding.avatarPhoto.setTag(R.id.avatar_photo, token)
                val request = ImageRequest.Builder(binding.avatarPhoto.context)
                    .data(url)
                    .target(binding.avatarPhoto)
                    .circleAvatar(url)
                    .listener(
                        onSuccess = { _, _ ->
                            if (binding.avatarPhoto.getTag(R.id.avatar_photo) == token) {
                                binding.avatarPhoto.isVisible = true
                            }
                        },
                        onError = { _, _ ->
                            if (binding.avatarPhoto.getTag(R.id.avatar_photo) == token) {
                                binding.avatarPhoto.isVisible = false
                                binding.avatarPhoto.setImageDrawable(null)
                            }
                        },
                    )
                    .build()
                contactRequest = binding.avatarPhoto.context.imageLoader.enqueue(request)
            }

            private fun paintCard(first: Boolean, last: Boolean) {
                val color = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainer)
                val radius = 16f * binding.root.resources.displayMetrics.density
                binding.root.background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadii = floatArrayOf(
                        if (first) radius else 0f, if (first) radius else 0f,
                        if (first) radius else 0f, if (first) radius else 0f,
                        if (last) radius else 0f, if (last) radius else 0f,
                        if (last) radius else 0f, if (last) radius else 0f,
                    )
                }
            }
        }

        companion object {
            private const val TYPE_SECTION = 0
            private const val TYPE_PERSON = 1
            private const val TYPE_MORE = 2
            private const val TYPE_EMPTY = 3
        }
    }
}
