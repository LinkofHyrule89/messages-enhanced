package com.ubermicrostudios.messagesenhanced.ui.thread

import com.ubermicrostudios.messagesenhanced.util.Mentions
import com.ubermicrostudios.messagesenhanced.util.MessageStatus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.MediaStore
import android.view.MotionEvent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import android.graphics.drawable.ColorDrawable
import androidx.core.graphics.drawable.toDrawable
import coil.dispose
import coil.imageLoader
import coil.load
import coil.memory.MemoryCache
import com.ubermicrostudios.messagesenhanced.util.IMAGE_PLACEHOLDER_COLOR
import com.ubermicrostudios.messagesenhanced.util.ImageCacheKeys
import com.ubermicrostudios.messagesenhanced.util.cachedMedia
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.repo.LinkPreviewCache
import com.ubermicrostudios.messagesenhanced.databinding.FragmentThreadBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.conversations.AvatarBinder
import com.ubermicrostudios.messagesenhanced.ui.conversations.AvatarTarget
import com.ubermicrostudios.messagesenhanced.ui.settings.SettingsActivity
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.databinding.ItemAttachChipBinding
import com.ubermicrostudios.messagesenhanced.util.ComposerPaste
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan
import com.ubermicrostudios.messagesenhanced.util.EmojiCatalog
import com.ubermicrostudios.messagesenhanced.util.Encryption
import com.ubermicrostudios.messagesenhanced.util.LinkUrls
import com.ubermicrostudios.messagesenhanced.util.LinkCleaner
import com.ubermicrostudios.messagesenhanced.ui.effects.ScreenEffects
import com.ubermicrostudios.messagesenhanced.util.MessageActions
import com.ubermicrostudios.messagesenhanced.util.MessageRuns
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import com.ubermicrostudios.messagesenhanced.util.RichContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ThreadFragment : Fragment() {

    @Inject lateinit var previews: LinkPreviewCache
    @Inject lateinit var http: okhttp3.OkHttpClient
    private var voicePlayer: android.media.MediaPlayer? = null
    private var voiceJob: kotlinx.coroutines.Job? = null
    @Inject lateinit var appPrefs: com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences

    private var _binding: FragmentThreadBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ThreadViewModel by viewModels()
    private lateinit var adapter: MessageAdapter
    private var gestures: ThreadGestures? = null
    private var messagePress: MessagePress? = null
    private var messageSheet: MessageSheet? = null
    private var plusOpen = false
    private var pendingCursor: Int? = null
    private var pendingKey = ""
    private var cameraFile: File? = null
    private val pickGallery = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ComposerPlan.MAX_PENDING)) { uris ->
        importUris(uris)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importUris(uris)
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        cameraFile = null
        if (ok && file != null && file.exists() && file.length() > 0L) {
            val notices = viewModel.stagePending(
                listOf(
                    ComposerPlan.PendingFile(
                        id = file.name,
                        path = file.absolutePath,
                        name = file.name,
                        mime = "image/jpeg",
                        size = file.length(),
                    ),
                ),
            )
            notices.forEach { text -> toast(text) }
        } else {
            file?.delete()
        }
    }
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                messageSheet?.isOpen() == true -> messageSheet?.close()
                plusOpen -> setPlus(false)
                viewModel.state.value.selecting -> viewModel.stopSelect()
            }
        }
    }
    private var shownChrome: ThreadChrome? = null
    private var serverErrorShown: Boolean? = null
    private var wallpaperUrl: String? = null
    private var messageSignature = ""
    private var shownReplyId: String? = null
    private var stickToBottom = false
    private var messageScrollRestored = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentThreadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        viewModel.bind(
            conversationId = args.getString(ThreadViewModel.ARG_ID).orEmpty(),
            title = args.getString(ThreadViewModel.ARG_TITLE).orEmpty(),
            isGroup = args.getBoolean(ThreadViewModel.ARG_GROUP),
            displayProtocol = args.getString(ThreadViewModel.ARG_PROTOCOL).orEmpty(),
            participants = args.getString(ThreadViewModel.ARG_PARTICIPANTS).orEmpty(),
            sourcePlatform = args.getString(ThreadViewModel.ARG_SOURCE).orEmpty(),
            googlePinned = args.getBoolean(ThreadViewModel.ARG_GOOGLE_PINNED),
            localPinnedAtMs = args.getLong(ThreadViewModel.ARG_LOCAL_PINNED),
            notificationMode = args.getString(ThreadViewModel.ARG_NOTIFICATION).orEmpty(),
        )
        if (savedInstanceState == null) consumeShare(args)
        val twoPane = resources.getBoolean(R.bool.two_pane)
        Insets.applyThreadChrome(binding.threadRoot, binding.header, binding.composerDock, twoPane) {
            updateListPadding()
        }
        binding.header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListPadding() }
        binding.composerDock.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            updateListPadding()
            if (stickToBottom && bottom - top != oldBottom - oldTop) {
                stickToBottom = false
                val last = adapter.itemCount - 1
                if (last >= 0) binding.messages.scrollToPosition(last)
            }
        }

        val muted = ColorStateList.valueOf(requireContext().getColor(R.color.muted))
        val ink = ColorStateList.valueOf(requireContext().getColor(R.color.text))
        binding.back.imageTintList = ink
        binding.overflow.imageTintList = ink
        binding.attach.imageTintList = muted
        binding.emoji.imageTintList = muted
        binding.gallery.imageTintList = muted
        binding.muteToggle.imageTintList = ColorStateList.valueOf(requireContext().getColor(R.color.text))
        binding.muteToggle.setOnClickListener { viewModel.toggleMute() }
        binding.back.isVisible = !twoPane
        binding.back.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.overflow.setOnClickListener { showMenu() }
        binding.attach.setOnClickListener { setPlus(!plusOpen) }
        binding.emoji.setOnClickListener {
            setPlus(false)
            EmojiPicker.show(requireContext(), keepOpen = true) { emoji -> insertEmoji(emoji) }
        }
        binding.gallery.setOnClickListener {
            setPlus(false)
            openGallery()
        }
        binding.plusGallery.setOnClickListener {
            setPlus(false)
            openGallery()
        }
        binding.plusCamera.setOnClickListener {
            setPlus(false)
            openCamera()
        }
        binding.plusFiles.setOnClickListener {
            setPlus(false)
            openFiles()
        }
        binding.messages.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && plusOpen) setPlus(false)
            false
        }

        adapter = MessageAdapter(
            mediaUrl = { viewModel.baseUrl.mediaUrl(it.messageId) },
            previewOf = { previews.peek(it) },
            imageOf = { previews.imageUrl(it) },
            requestPreview = { previews.request(it) },
            chrome = { threadChrome(viewModel.state.value) },
            conversation = { snapshot(viewModel.state.value) },
            origin = { viewModel.baseUrl.origin() },
            messageById = { id -> viewModel.state.value.messages.firstOrNull { it.messageId == id } },
            onQuote = { id -> scrollToQuote(id) },
            syncGesture = { view -> gestures?.sync(view) },
            onRecycled = { view -> gestures?.onRecycled(view) },
            onImage = { message ->
                val state = viewModel.state.value
                ImageViewerActivity.open(
                    context = requireContext(),
                    messages = state.messages,
                    tapped = message,
                    conversationId = state.conversationId,
                    title = state.title,
                    isGroup = state.isGroup,
                    sourcePlatform = state.sourcePlatform,
                    protocol = state.protocol.ifBlank { state.displayProtocol },
                    encryption = state.encryption,
                    origin = viewModel.baseUrl.origin(),
                    participantsJson = state.participantsJson,
                )
                // Ask the phone for the full-resolution original (best effort, quiet).
                if (message.mimeType.startsWith("image/")) viewModel.messageAction(message.messageId, "fullsize", quiet = true)
            },
            onMessageAction = { message, action ->
                if (action == "resend") {
                    AlertDialog.Builder(requireContext())
                        .setMessage(R.string.resend_confirm)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.messageAction(message.messageId, "resend") }
                        .show()
                } else if (!richAction(message, action)) {
                    viewModel.messageAction(message.messageId, action)
                }
            },
            animatedEmoji = { appPrefs.animatedEmojis },
            richText = { message, cb -> fetchRichText(message, cb) },
        )
        gestures = ThreadGestures(
            recyclerView = binding.messages,
            messageFor = { id -> viewModel.state.value.messages.firstOrNull { it.messageId == id } },
            onReply = { message -> viewModel.startReply(message) },
        ).also { it.attach() }
        messageSheet = MessageSheet(
            binding = binding,
            host = sheetHost(),
            starred = { id -> id in viewModel.state.value.starredIds },
            onVisibility = { syncBack() },
        )
        messagePress = MessagePress(
            recyclerView = binding.messages,
            messageFor = { id -> viewModel.state.value.messages.firstOrNull { it.messageId == id } },
            claimed = { gestures?.horizontalClaimed() == true },
            selecting = { viewModel.state.value.selecting },
            sheetOpen = { messageSheet?.isOpen() == true },
            onToggle = { viewModel.toggleSelected(it) },
            onTap = { message ->
                adapter.detailId = if (adapter.detailId == message.messageId) null else message.messageId
            },
            onLongPress = { bubble, message -> messageSheet?.open(bubble, message) },
        ).also { it.attach() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        binding.selectCancel.imageTintList = ink
        binding.selectCancel.setOnClickListener { viewModel.stopSelect() }
        binding.selectCopy.setOnClickListener { copySelection() }
        binding.selectForward.setOnClickListener { forwardSelection() }
        binding.selectDelete.setOnClickListener { deleteSelection() }
        binding.messages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messages.adapter = adapter
        binding.messages.clipToPadding = false
        binding.messages.clipChildren = false
        binding.messages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) = updateJumpButton()
        })
        binding.jumpNewestButton.setOnClickListener { jumpToNewest() }
        val initial = viewModel.state.value
        adapter.submitList(MessageRuns.rows(initial.messages, initial.isGroup)) {
            restoreMessageScroll(initial)
        }
        if (initial.ready) (activity as? ThreadActivity)?.onThreadReady()
        binding.replyBarIcon.imageTintList = ColorStateList.valueOf(requireContext().getColor(R.color.accent))
        binding.replyCancel.imageTintList = muted
        binding.replyCancel.setOnClickListener { viewModel.clearReply() }

        bindComposerPaste()
        bindCleanLinks()
        bindMentions()
        binding.composer.doAfterTextChanged { viewModel.onDraft(it?.toString().orEmpty()) }
        binding.composer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                setPlus(false)
                viewModel.send()
                true
            } else {
                false
            }
        }
        binding.send.setOnClickListener {
            setPlus(false)
            viewModel.send()
        }
        renderReply(viewModel.state.value)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is ThreadEffect.Toast ->
                            Toast.makeText(requireContext(), effect.text, Toast.LENGTH_SHORT).show()
                        ThreadEffect.Close -> closeThread()
                        ThreadEffect.ListChanged -> setFragmentResult(RESULT_LIST, bundleOf())
                        is ThreadEffect.ScrollToMessage ->
                            binding.messages.postDelayed({ if (_binding != null) scrollToQuote(effect.id, findIfMissing = false) }, 150)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                previews.revision.collect {
                    if (_binding != null && ::adapter.isInitialized) adapter.notifyDataSetChanged()
                }
            }
        }
    }

    override fun onPause() {
        if (_binding != null) {
            viewModel.saveMessageScroll(binding.messages.layoutManager?.onSaveInstanceState())
        }
        viewModel.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) viewModel.onResume()
    }

    private fun render(state: ThreadUiState) {
        binding.title.text = formatPhoneForDisplay(state.title.ifBlank { getString(R.string.thread_title) })
        val shown = MessageStatus.visibleMessages(state.messages, appPrefs.showDeletedPlaceholders)
        val rows = MessageRuns.rows(shown, state.isGroup)
        val signature = state.messages.joinToString("\n") {
            "${it.messageId}\u0000${it.status}\u0000${it.statusText}\u0000${it.reactions}\u0000${it.edited}\u0000${it.body.hashCode()}"
        }
        val jump = signature != messageSignature
        maybeScreenEffect(state)
        messageSignature = signature
        val jumpId = state.jumpMessageId
        adapter.submitList(rows) {
            if (_binding == null) return@submitList
            if (jumpId != null) {
                val index = rows.indexOfFirst { it.message.messageId == jumpId }
                if (index >= 0) {
                    binding.messages.scrollToPosition(index)
                    viewModel.consumeJump(found = true)
                } else if (state.loadedOnce && !state.loading) {
                    viewModel.consumeJump(found = false)
                }
            } else {
                restoreMessageScroll(state, jump, rows.isNotEmpty())
            }
        }
        for (message in state.messages) {
            val url = LinkUrls.firstHttpUrl(message.body) ?: continue
            previews.request(url)
        }
        binding.empty.isVisible = state.ready && !state.loading && state.messages.isEmpty()
        binding.progress.isVisible = state.loading && state.messages.isEmpty()
        if (state.ready) (activity as? ThreadActivity)?.onThreadReady()
        val serverError = !state.error.isNullOrBlank()
        binding.error.isVisible = serverError
        binding.error.text = state.error.orEmpty()
        publishServerError(serverError)
        if (binding.composer.text?.toString() != state.draft) {
            binding.composer.setText(state.draft)
            val cursor = (pendingCursor ?: state.draft.length).coerceIn(0, state.draft.length)
            pendingCursor = null
            binding.composer.setSelection(cursor)
        }
        val canSend = !state.sending && (state.draft.isNotBlank() || state.pending.isNotEmpty())
        binding.send.isEnabled = canSend
        binding.attach.isEnabled = !state.sending
        binding.gallery.isEnabled = !state.sending
        binding.emoji.isEnabled = !state.sending
        renderPending(state.pending)
        paintSend(canSend)
        binding.composer.isEnabled = !state.sending
        binding.typing.isVisible = state.typingLabel.isNotBlank()
        binding.typing.text = state.typingLabel
        val isMuted = state.notificationMode == "muted"
        binding.muteToggle.setImageResource(if (isMuted) R.drawable.ic_bell_off else R.drawable.ic_bell)
        binding.muteToggle.contentDescription = getString(if (isMuted) R.string.menu_unmute else R.string.menu_mute)
        binding.muteToggle.alpha = if (isMuted) 0.7f else 1f
        val proto = state.protocol.ifBlank { state.displayProtocol }.trim()
        binding.composer.hint = when {
            proto.equals("RCS", ignoreCase = true) -> getString(R.string.composer_hint_rcs)
            proto.equals("SMS", ignoreCase = true) -> getString(R.string.composer_hint_sms)
            else -> getString(R.string.composer_hint)
        }
        showWallpaper(state.backgroundUrl)
        renderReply(state)
        renderSelection(state)
        AvatarBinder.bind(headerTarget(), viewModel.baseUrl.origin(), snapshot(state))
        val chrome = threadChrome(state)
        if (chrome != shownChrome) {
            shownChrome = chrome
            adapter.notifyDataSetChanged()
        }
    }

    private fun renderReply(state: ThreadUiState) {
        val reply = state.reply
        val id = reply?.messageId
        if (reply == null) {
            shownReplyId = null
            binding.replyBar.isVisible = false
            return
        }
        val opened = id != shownReplyId
        if (opened && isNearBottom()) stickToBottom = true
        binding.replyBar.isVisible = true
        binding.replyLabel.text = replyLabel(formatPhoneForDisplay(reply.author))
        binding.replySnippet.text = reply.snippet
        if (!opened) return
        shownReplyId = id
        binding.composer.requestFocus()
        val input = requireContext().getSystemService(InputMethodManager::class.java)
        binding.composer.post {
            if (_binding == null) return@post
            @Suppress("DEPRECATION")
            input?.showSoftInput(binding.composer, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun replyLabel(author: String): CharSequence {
        val prefix = getString(R.string.replying_to_prefix)
        val text = SpannableString(prefix + author)
        val start = prefix.length
        if (text.length > start) {
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(
                ForegroundColorSpan(requireContext().getColor(R.color.text)),
                start,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return text
    }

    /** Web `scrollHeight - scrollTop - clientHeight < 200` before opening the reply strip. */
    private fun isNearBottom(): Boolean {
        val list = binding.messages
        val range = list.computeVerticalScrollRange()
        val extent = list.computeVerticalScrollExtent()
        val offset = list.computeVerticalScrollOffset()
        if (range <= extent) return true
        return range - offset - extent < (200f * list.resources.displayMetrics.density).toInt()
    }

    private fun scrollToQuote(id: String, findIfMissing: Boolean = true) {
        val index = adapter.currentList.indexOfFirst { it.message.messageId == id }
        if (index < 0) {
            if (findIfMissing) viewModel.findOriginal(id)
            else Toast.makeText(requireContext(), "Message not available", Toast.LENGTH_SHORT).show()
            return
        }
        val lm = binding.messages.layoutManager as? LinearLayoutManager
        // Far away: jump near it first so the smooth scroll is short and actually lands.
        if (lm != null) {
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first >= 0 && (index < first - 30 || index > last + 30)) {
                lm.scrollToPositionWithOffset(if (index < first) minOf(index + 6, adapter.itemCount - 1) else maxOf(index - 6, 0), 0)
            }
        }
        val layout = binding.messages.layoutManager as? LinearLayoutManager ?: return
        val scroller = object : LinearSmoothScroller(requireContext()) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int,
            ): Int = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)

            override fun onStop() {
                super.onStop()
                if (_binding == null) return
                binding.messages.post { flashQuote(id, 3) }
                updateJumpButton()
            }
        }
        scroller.targetPosition = index
        layout.startSmoothScroll(scroller)
    }

    private fun flashQuote(id: String, tries: Int) {
        val current = _binding ?: return
        val index = adapter.currentList.indexOfFirst { it.message.messageId == id }
        if (index < 0) return
        val bubble = current.messages.findViewHolderForAdapterPosition(index)?.itemView?.findViewById<View>(R.id.bubble)
        if (bubble == null) {
            if (tries > 0) current.messages.postDelayed({ flashQuote(id, tries - 1) }, 120)
            return
        }
        val density = bubble.resources.displayMetrics.density
        val highlight = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setStroke((2 * density).toInt(), 0x80FFFFFF.toInt())
        }
        bubble.foreground = highlight
        bubble.postDelayed({
            if (bubble.foreground === highlight) bubble.foreground = null
        }, 1_200)
    }

    private fun showMenu() {
        val state = viewModel.state.value
        ThreadMenu.show(
            binding.overflow,
            state,
            ThreadMenu.Actions(
                theme = { openTheme() },
                mute = { viewModel.toggleMute() },
                details = { showDetails(viewModel.state.value) },
                refresh = { viewModel.refreshFromPhone() },
                pin = { viewModel.togglePin() },
                archive = { viewModel.archiveConversation() },
                trash = { confirmTrash(viewModel.state.value) },
                block = { report -> confirmBlock(viewModel.state.value, report) },
                settings = {
                    startActivity(Intent(requireContext(), SettingsActivity::class.java))
                },
            ),
        )
    }

    private fun openTheme() {
        val state = viewModel.state.value
        if (state.conversationId.isBlank()) return
        startActivity(Intent(requireContext(), ChatThemeActivity::class.java).apply {
            putExtra(ChatThemeActivity.EXTRA_ID, state.conversationId)
            putExtra(ChatThemeActivity.EXTRA_TITLE, state.title)
            putExtra(ChatThemeActivity.EXTRA_GROUP, state.isGroup)
        })
    }

    private fun showDetails(state: ThreadUiState) {
        val me = Participants.parse(state.participantsJson).firstOrNull { it.me }
        DetailsSheet.show(
            requireContext(),
            snapshot(state),
            viewModel.baseUrl.origin(),
            Participants.profileName(me),
        )
    }

    private fun confirmTrash(state: ThreadUiState) {
        val name = formatPhoneForDisplay(state.title.ifBlank { getString(R.string.thread_title) })
        val dialog = AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.trash_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.menu_trash) { _, _ -> viewModel.trashConversation() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFF8589.toInt())
        }
        dialog.show()
    }

    private val effectSeen = HashSet<String>()
    private var effectPrimedFor = ""

    /** Play a holiday/keyword effect once for a fresh message in the open thread. */
    private fun maybeScreenEffect(state: ThreadUiState) {
        if (state.conversationId.isBlank() || state.messages.isEmpty()) return
        if (effectPrimedFor != state.conversationId) {
            effectPrimedFor = state.conversationId
            effectSeen.clear()
            state.messages.forEach { effectSeen += it.messageId }
            return
        }
        val now = System.currentTimeMillis()
        var pick: ScreenEffects.Kind? = null
        for (m in state.messages) {
            if (!effectSeen.add(m.messageId)) continue
            if (m.timestampMs > 0L && now - m.timestampMs > 60_000L) continue
            ScreenEffects.detect(m.body)?.let { pick = it }
        }
        val kind = pick ?: return
        if (!appPrefs.holidayAnimations) return
        activity?.let { ScreenEffects.play(it, kind) }
    }

    /** "Clean links" in the composer's long-press / selection menu. */
    private fun bindCleanLinks() {
        val itemId = View.generateViewId()
        val cb = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                if (LinkUrls.firstHttpUrl(binding.composer.text?.toString().orEmpty()) != null) {
                    menu.add(android.view.Menu.NONE, itemId, 200, R.string.clean_links)
                }
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if (item.itemId != itemId) return false
                cleanComposerLinks()
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }
        binding.composer.customSelectionActionModeCallback = cb
        binding.composer.customInsertionActionModeCallback = cb
    }

    private fun cleanComposerLinks() {
        val edit = binding.composer
        val text = edit.text ?: return
        val res = LinkCleaner.cleanText(text.toString(), appPrefs.cleanLinksRule)
        if (res.count == 0) {
            toast(getString(R.string.clean_links_none))
            return
        }
        // Replace each changed URL in place so mention spans elsewhere survive.
        for ((from, to) in LinkCleaner.changes(text.toString(), appPrefs.cleanLinksRule).asReversed()) {
            text.replace(from.first, from.last + 1, to)
        }
        edit.setSelection(text.length)
        toast(getString(R.string.clean_links_done, res.count))
    }

    private fun confirmBlock(state: ThreadUiState, report: Boolean) {
        val name = formatPhoneForDisplay(state.title.ifBlank { getString(R.string.thread_title) })
        val dialog = AlertDialog.Builder(requireContext())
            .setMessage(getString(if (report) R.string.block_report_confirm else R.string.block_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (report) R.string.menu_block_report else R.string.menu_block) { _, _ ->
                viewModel.blockConversation(blocked = true, report = report)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFF8589.toInt())
        }
        dialog.show()
    }

    private fun closeThread() {
        setFragmentResult(RESULT_LIST, bundleOf())
        if (activity is ThreadActivity) {
            activity?.finish()
        } else {
            setFragmentResult(RESULT_CLOSE, bundleOf())
        }
    }

    // Google Messages-style "go to newest" FAB: visible whenever not at the bottom.
    private var jumpUnread = 0
    private var tailMessageId: String? = null

    private fun distanceFromBottom(): Int {
        val list = binding.messages
        return list.computeVerticalScrollRange() - list.computeVerticalScrollOffset() - list.computeVerticalScrollExtent()
    }

    private fun updateJumpButton() {
        val current = _binding ?: return
        val dist = distanceFromBottom()
        val near = (120f * resources.displayMetrics.density).toInt()
        if (dist < near) jumpUnread = 0
        val show = adapter.itemCount > 0 && dist >= near
        current.jumpNewest.isVisible = show
        current.jumpNewestBadge.isVisible = jumpUnread > 0
        current.jumpNewestBadge.text = if (jumpUnread > 99) "99+" else jumpUnread.toString()
    }

    private fun jumpToNewest() {
        val last = adapter.itemCount - 1
        if (last < 0) return
        val lm = binding.messages.layoutManager as? LinearLayoutManager
        val first = lm?.findLastVisibleItemPosition() ?: last
        if (last - first > 30) binding.messages.scrollToPosition(last - 8)
        binding.messages.post { _binding?.messages?.smoothScrollToPosition(last) }
        jumpUnread = 0
        updateJumpButton()
    }

    private fun noteIncoming(rows: List<com.ubermicrostudios.messagesenhanced.util.MessageRuns.Row>, atBottom: Boolean) {
        val prev = tailMessageId
        tailMessageId = rows.lastOrNull()?.message?.messageId
        if (atBottom || prev == null) { jumpUnread = 0; return }
        val idx = rows.indexOfLast { it.message.messageId == prev }
        if (idx < 0) return
        jumpUnread += rows.drop(idx + 1).count { !it.message.isFromMe && !it.message.status.startsWith("TOMBSTONE", ignoreCase = true) }
    }

    private fun updateListPadding() {
        val current = _binding ?: return
        current.jumpNewest.translationY = -current.composerDock.height.toFloat()
        val top = if (current.selectBar.isVisible) current.selectBar.height else current.header.height
        current.messages.setPadding(
            current.messages.paddingLeft,
            top,
            current.messages.paddingRight,
            current.composerDock.height,
        )
    }

    private fun renderSelection(state: ThreadUiState) {
        binding.header.isVisible = !state.selecting
        binding.selectBar.isVisible = state.selecting
        if (state.selecting) {
            binding.selectBar.setPadding(
                binding.header.paddingLeft,
                binding.header.paddingTop,
                binding.header.paddingRight,
                binding.header.paddingBottom,
            )
        }
        val count = state.selectedIds.size
        binding.selectCount.text = "$count selected"
        val enabled = count > 0
        for (button in listOf(binding.selectCopy, binding.selectForward, binding.selectDelete)) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.4f
        }
        adapter.setMarks(state.starredIds, state.selectedIds, state.selecting)
        syncBack()
        if (state.selecting) binding.selectBar.post { updateListPadding() } else updateListPadding()
    }

    private fun syncBack() {
        backCallback.isEnabled = viewModel.state.value.selecting || messageSheet?.isOpen() == true || plusOpen
    }

    private fun sheetHost() = object : MessageSheet.Host {
        override fun onReply(message: MessageDto) {
            viewModel.startReply(message)
        }

        override fun onForward(message: MessageDto) {
            openForward(listOf(message))
        }

        override fun onCopy(text: String) {
            copyText(text)
        }

        override fun onStar(message: MessageDto) {
            viewModel.toggleStar(message)
        }

        override fun onDelete(message: MessageDto) {
            confirmDelete(listOf(message))
        }

        override fun onSelect(message: MessageDto) {
            viewModel.startSelect(message)
        }

        override fun onInfo(message: MessageDto) {
            showMessageInfo(message)
        }

        override fun onDetails(message: MessageDto) {
            openMessageDetails(message)
        }

        override fun onReact(message: MessageDto, emoji: String) {
            EmojiCatalog.remember(emoji)
            viewModel.react(message, emoji)
            if (appPrefs.holidayAnimations) activity?.let { ScreenEffects.reaction(it, emoji) }
        }
    }

    private fun copySelection() {
        val state = viewModel.state.value
        val messages = MessageActions.selectedSorted(state.messages, state.selectedIds)
        copyText(MessageActions.selectionCopy(messages, state.title, state.isGroup))
        viewModel.stopSelect()
    }

    private fun forwardSelection() {
        val state = viewModel.state.value
        val messages = MessageActions.selectedSorted(state.messages, state.selectedIds)
        viewModel.stopSelect()
        openForward(messages)
    }

    private fun deleteSelection() {
        val state = viewModel.state.value
        val messages = MessageActions.deletable(MessageActions.selectedSorted(state.messages, state.selectedIds))
        if (messages.isNotEmpty()) confirmDelete(messages)
    }

    private fun openForward(messages: List<MessageDto>) {
        val count = messages.count { it.body.trim().isNotEmpty() || MessageActions.hasMedia(it) }
        if (count == 0) {
            Toast.makeText(requireContext(), "Nothing to forward", Toast.LENGTH_SHORT).show()
            return
        }
        val title = if (count > 1) "Forward $count messages to" else "Forward to"
        viewModel.forwardTargets { conversations ->
            if (_binding == null) return@forwardTargets
            ForwardPicker.show(requireContext(), title, conversations) { target ->
                viewModel.forward(messages, target)
            }
        }
    }

    private fun confirmDelete(messages: List<MessageDto>) {
        val prompt = MessageActions.deletePrompt(messages.size, viewModel.state.value.sourcePlatform)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(prompt.title)
            .setMessage(prompt.body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteMessages(messages) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFF8589.toInt())
        }
        dialog.show()
    }

    private fun openMessageDetails(message: MessageDto) {
        val state = viewModel.state.value
        val protocol = state.protocol.ifBlank { state.displayProtocol }
        val encrypted = Encryption.messageEncrypted(message, state.encryption) ||
            Encryption.protocolEncrypted(state.displayProtocol) ||
            Encryption.protocolEncrypted(state.protocol)
        MessageDetailsActivity.open(
            requireContext(),
            message,
            protocol = protocol,
            encrypted = encrypted,
            conversationPlatform = message.sourcePlatform.ifBlank { state.sourcePlatform },
        )
    }

    private fun showMessageInfo(message: MessageDto) {
        val state = viewModel.state.value
        val rows = MessageActions.infoRows(
            message = message,
            conversationName = state.title,
            sourcePlatform = state.sourcePlatform,
            metaProtocol = state.protocol,
            e2ee = state.encryption.e2ee,
            starred = message.messageId in state.starredIds,
            participants = Participants.parse(state.participantsJson),
            whenText = MessageActions.infoWhen(message.timestampMs),
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Info")
            .setMessage(MessageActions.infoText(rows))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyText(text: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun restoreMessageScroll(
        state: ThreadUiState,
        contentsChanged: Boolean = false,
        hasRows: Boolean = adapter.itemCount > 0,
    ) {
        if (_binding == null) return
        if (!messageScrollRestored) {
            val saved = viewModel.messageScroll()
            if (saved != null && hasRows) {
                binding.messages.layoutManager?.onRestoreInstanceState(saved)
                messageScrollRestored = true
                return
            }
            if (hasRows || state.ready) messageScrollRestored = true
            if (hasRows) binding.messages.scrollToPosition(adapter.itemCount - 1)
            return
        }
        val atBottom = stickToBottom || isNearBottom()
        val rows = adapter.currentList
        val mineTail = rows.lastOrNull()?.message?.isFromMe == true && rows.lastOrNull()?.message?.messageId != tailMessageId
        if (contentsChanged && hasRows) noteIncoming(rows, atBottom)
        if (contentsChanged && hasRows && (atBottom || mineTail)) {
            binding.messages.scrollToPosition(adapter.itemCount - 1)
            jumpUnread = 0
        }
        binding.messages.post { updateJumpButton() }
    }

    private fun headerTarget() = AvatarTarget(
        binding.headerAvatarBox,
        binding.headerAvatar,
        binding.headerAvatarPhoto,
        binding.headerAvatarDuoA,
        binding.headerAvatarDuoB,
    )

    private fun snapshot(state: ThreadUiState) = ConversationDto(
        conversationId = state.conversationId,
        name = state.title,
        unifiedName = state.title,
        isGroup = state.isGroup,
        participants = state.participantsJson,
        sourcePlatform = state.sourcePlatform,
        displayProtocol = state.displayProtocol.ifBlank { state.protocol },
        notificationMode = state.notificationMode,
        googlePinned = state.googlePinned,
        localPinnedAtMs = state.localPinnedAtMs,
    )

    private fun threadChrome(state: ThreadUiState): ThreadChrome {
        val display = when {
            Encryption.protocolEncrypted(state.displayProtocol) -> state.displayProtocol
            Encryption.protocolEncrypted(state.protocol) -> state.protocol
            else -> state.displayProtocol.ifBlank { state.protocol }
        }
        return ThreadChrome(state.colors, state.encryption, display)
    }

    private fun showWallpaper(url: String?) {
        if (url == wallpaperUrl) return
        wallpaperUrl = url
        val view = binding.wallpaper
        if (url.isNullOrBlank()) {
            view.dispose()
            view.setImageDrawable(null)
            view.isVisible = false
            binding.scrim.isVisible = false
            return
        }
        view.isVisible = true
        binding.scrim.isVisible = true
        val memoryKey = MemoryCache.Key(ImageCacheKeys.diskKey(url))
        val cached = view.context.imageLoader.memoryCache?.get(memoryKey)?.bitmap
        if (cached != null) {
            // Same frame as the chat switch: do not wait for Coil's coroutine.
            view.setImageBitmap(cached)
        } else {
            view.setImageDrawable(ColorDrawable(IMAGE_PLACEHOLDER_COLOR))
        }
        view.load(url) {
            cachedMedia(url)
            if (cached != null) placeholder(cached.toDrawable(view.resources))
        }
    }

    private fun setPlus(open: Boolean) {
        plusOpen = open
        if (_binding == null) return
        binding.plusSheet.isVisible = open
        binding.attach.setImageResource(if (open) R.drawable.ic_close else R.drawable.ic_add)
        binding.attach.contentDescription = getString(if (open) R.string.attach_close else R.string.attach_more)
        syncBack()
    }

    private var mentionPopup: android.widget.ListPopupWindow? = null

    private fun mentionNames(): List<String> {
        val st = viewModel.state.value
        if (!st.isGroup) return emptyList()
        return Participants.parse(st.participantsJson)
            .filter { !it.me && !it.hidden }
            .map { it.name.trim().ifBlank { it.firstName.trim() } }
            .filter { it.isNotBlank() && !it.all { c -> c.isDigit() || c in "+()- " } }
            .distinct()
    }

    private fun mentionPeople(): Map<String, com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto> =
        Participants.parse(viewModel.state.value.participantsJson)
            .filter { !it.me }
            .associateBy { it.name.trim().ifBlank { it.firstName.trim() } }

    private class MentionPickerAdapter(
        private val names: List<String>,
        private val people: Map<String, com.ubermicrostudios.messagesenhanced.data.model.ParticipantDto>,
        private val origin: String,
        private val density: Float,
    ) : android.widget.BaseAdapter() {
        override fun getCount() = names.size
        override fun getItem(position: Int) = names[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val ctx = parent.context
            val px = { dp: Int -> (dp * density).toInt() }
            val row = (convertView as? android.widget.LinearLayout) ?: android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(px(20), px(10), px(20), px(10))
                addView(android.widget.ImageView(ctx), android.widget.LinearLayout.LayoutParams(px(32), px(32)))
                addView(
                    android.widget.TextView(ctx).apply {
                        setTextColor(0xFFE3E3E3.toInt())
                        textSize = 16f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16) },
                )
            }
            val name = names[position]
            val photo = row.getChildAt(0) as android.widget.ImageView
            (row.getChildAt(1) as android.widget.TextView).text = "@$name"
            val p = people[name]
            val fallback = com.ubermicrostudios.messagesenhanced.util.InitialsBitmap.of(name, p?.id?.ifBlank { name } ?: name, px(32))
            photo.setImageBitmap(fallback)
            val url = p?.let {
                Participants.avatarUrl(
                    origin,
                    participantId = it.participantId.ifBlank { it.participantIdCamel }.ifBlank { it.id },
                    contactId = it.contactId.ifBlank { it.contactIdCamel },
                    phone = it.number.ifBlank { it.phone },
                )
            }
            if (url != null) {
                photo.load(url) {
                    circleAvatar(url)
                    placeholder(android.graphics.drawable.BitmapDrawable(ctx.resources, fallback))
                    error(android.graphics.drawable.BitmapDrawable(ctx.resources, fallback))
                }
            }
            return row
        }
    }

    /** Google-style @mentions: picker while typing "@", backspace trims last name then the mention. */
    private fun bindMentions() {
        val edit = binding.composer
        var pending: Pair<String, Int>? = null
        var applying = false
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                pending = if (!applying && s != null && count == 1 && after == 0) {
                    Mentions.backspace(s.toString(), start + 1)
                } else {
                    null
                }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) {
                val p = pending
                if (p != null && e != null) {
                    pending = null
                    applying = true
                    e.replace(0, e.length, p.first)
                    edit.setSelection(p.second.coerceIn(0, e.length))
                    applying = false
                }
                if (e != null) highlightMentions(e)
                if (!applying) updateMentionPicker()
            }
        })
    }

    /** Chip-style highlight for valid NBSP@NameNBSP mentions in the composer. */
    private fun highlightMentions(e: android.text.Editable) {
        e.getSpans(0, e.length, ComposerMentionSpan::class.java).forEach { e.removeSpan(it) }
        val ranges = Mentions.ranges(e.toString())
        if (ranges.isEmpty()) return
        val accent = requireContext().getColor(R.color.accent)
        val density = resources.displayMetrics.density
        for (r in ranges) {
            val end = (r.last + 1).coerceAtMost(e.length)
            if (r.first < 0 || r.first >= end) continue
            e.setSpan(ComposerMentionSpan(0x33808080, accent, density), r.first, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateMentionPicker() {
        val edit = binding.composer
        val text = edit.text?.toString().orEmpty()
        val q = Mentions.query(text, edit.selectionStart)
        val names = if (q == null) emptyList() else Mentions.matches(mentionNames(), q.second)
        if (names.isEmpty()) {
            mentionPopup?.dismiss()
            return
        }
        val popup = mentionPopup ?: android.widget.ListPopupWindow(requireContext()).also { mentionPopup = it }
        val density = resources.displayMetrics.density
        // Google Messages style: rounded dark card above the composer, photo + "@Full Name" rows.
        val anchor = binding.composerDock
        popup.anchorView = anchor
        popup.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1E1F22.toInt())
                cornerRadius = 24f * density
            },
        )
        popup.setAdapter(MentionPickerAdapter(names, mentionPeople(), viewModel.baseUrl.origin(), density))
        popup.horizontalOffset = (8 * density).toInt()
        popup.width = (anchor.width - (16 * density).toInt()).coerceAtLeast((240 * density).toInt())
        popup.isModal = false
        popup.setDropDownGravity(android.view.Gravity.START)
        popup.inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NEEDED
        popup.setOnItemClickListener { _, _, pos, _ ->
            val cur = edit.text?.toString().orEmpty()
            val res = Mentions.insert(cur, edit.selectionStart, names[pos])
            popup.dismiss()
            if (res != null) {
                edit.setText(res.first)
                edit.setSelection(res.second.coerceIn(0, res.first.length))
            }
        }
        if (!popup.isShowing) popup.show()
    }

    private fun bindComposerPaste() {
        ViewCompat.setOnReceiveContentListener(binding.composer, ComposerPaste.MIME_TYPES) { _, payload ->
            val split = payload.partition { item -> item.uri != null }
            val clip = split.first?.clip
            val uris = if (clip != null) {
                (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
            } else {
                emptyList()
            }
            if (uris.isNotEmpty()) importUris(uris)
            split.second
        }
    }

    private fun insertEmoji(emoji: String) {
        val text = binding.composer.text?.toString().orEmpty()
        val start = binding.composer.selectionStart.coerceAtLeast(0)
        val end = binding.composer.selectionEnd.coerceAtLeast(0)
        val next = ComposerPlan.insertEmoji(text, start, end, emoji)
        pendingCursor = next.cursor
        viewModel.onDraft(next.text)
    }

    private fun openGallery() {
        pickGallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
    }

    private fun openFiles() {
        pickFiles.launch(arrayOf("*/*"))
    }

    private fun openCamera() {
        val file = AttachmentImport.cameraFile(requireContext())
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.files", file)
        val probe = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (probe.resolveActivity(requireContext().packageManager) == null) {
            file.delete()
            toast(getString(R.string.camera_unavailable))
            return
        }
        cameraFile = file
        runCatching { takePicture.launch(uri) }.onFailure {
            cameraFile = null
            file.delete()
            toast(getString(R.string.camera_unavailable))
        }
    }

    /** Content from the Android share sheet / Direct Share: attach it and prefill; never sends. */
    private fun consumeShare(args: Bundle) {
        val text = args.getString(ARG_SHARE_TEXT).orEmpty()
        val files = args.getStringArrayList(ARG_SHARE_FILES).orEmpty().mapNotNull { decodeShareFile(it) }
        args.remove(ARG_SHARE_TEXT)
        args.remove(ARG_SHARE_FILES)
        // Shared text wins over an empty draft; otherwise it is appended to the saved draft.
        if (text.isNotBlank()) {
            val merged = com.ubermicrostudios.messagesenhanced.util.Drafts.mergeShared(viewModel.state.value.draft, text)
            pendingCursor = merged.length
            viewModel.onDraft(merged)
        }
        if (files.isNotEmpty()) viewModel.stagePending(files).forEach { toast(it) }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                uris.map { uri -> runCatching { AttachmentImport.copy(appContext, uri) }.getOrNull() }
            }
            if (_binding == null) {
                copied.forEach { file -> file?.let { File(it.path).delete() } }
                return@launch
            }
            val files = copied.filterNotNull()
            if (files.size < uris.size) toast(getString(R.string.could_not_attach))
            viewModel.stagePending(files).forEach { toast(it) }
        }
    }

    private fun renderPending(files: List<ComposerPlan.PendingFile>) {
        val key = files.joinToString("\n") { it.id }
        binding.attachTray.isVisible = files.isNotEmpty()
        if (key == pendingKey) return
        pendingKey = key
        binding.attachChips.removeAllViews()
        val inflater = LayoutInflater.from(binding.root.context)
        for (file in files) {
            val chip = ItemAttachChipBinding.inflate(inflater, binding.attachChips, true)
            val image = file.mime.startsWith("image/")
            chip.thumb.isVisible = image
            chip.name.isVisible = !image
            if (image) chip.thumb.load(File(file.path)) else chip.name.text = file.name
            chip.remove.setOnClickListener { viewModel.removePending(file.id) }
        }
    }

    private fun toast(text: String) {
        val host = context ?: return
        if (text.isBlank()) return
        Toast.makeText(host, text, Toast.LENGTH_SHORT).show()
    }

    private fun paintSend(enabled: Boolean) {
        val background = if (enabled) 0xFF2C4D6E.toInt() else 0xFF233A52.toInt()
        val foreground = if (enabled) 0xFFD3E3FF.toInt() else 0xFF7F93A8.toInt()
        binding.send.backgroundTintList = ColorStateList.valueOf(background)
        binding.send.imageTintList = ColorStateList.valueOf(foreground)
    }

    /** True while this thread is showing its own "can't reach the server" line. */
    fun isServerErrorVisible(): Boolean = _binding?.error?.isVisible == true

    private fun publishServerError(showing: Boolean) {
        if (serverErrorShown == showing) return
        serverErrorShown = showing
        // The list hides its health banner while this is up, so the two never stack.
        runCatching {
            setFragmentResult(RESULT_SERVER_ERROR, bundleOf(EXTRA_SERVER_ERROR to showing))
        }
    }

    /** Downloads an attachment into the shareable cache (capped size), or null. */
    private suspend fun attachmentFile(message: MessageDto): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(requireContext().applicationContext.cacheDir, "share/rich").apply { mkdirs() }
            val dest = File(dir, RichContent.fileName(message.messageId, message.mimeType, message.body))
            if (dest.isFile && dest.length() > 0) return@runCatching dest
            val req = okhttp3.Request.Builder().url(viewModel.baseUrl.mediaUrl(message.messageId)).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val part = File(dir, dest.name + ".part")
                var total = 0L
                r.body?.byteStream()?.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MessageActions.MAX_MEDIA_BYTES) error("too large")
                            out.write(buf, 0, n)
                        }
                    }
                } ?: return@runCatching null
                if (!part.renameTo(dest)) return@runCatching null
                dest
            }
        }.getOrNull()
    }

    private fun fetchRichText(message: MessageDto, cb: (String?) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val f = attachmentFile(message)
            val text = f?.takeIf { it.length() <= 512 * 1024 }?.let { withContext(Dispatchers.IO) { runCatching { it.readText() }.getOrNull() } }
            cb(text)
        }
    }

    private fun startSafe(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.rich_open_failed)) }
    }

    private fun openAttachment(message: MessageDto) {
        viewLifecycleOwner.lifecycleScope.launch {
            val f = attachmentFile(message) ?: return@launch toast(getString(R.string.rich_open_failed))
            val ctx = context ?: return@launch
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            val type = message.mimeType.ifBlank { "*/*" }.substringBefore(';')
            startSafe(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    /** Handles the rich-content actions locally; false lets the server handle it. */
    private fun richAction(message: MessageDto, action: String): Boolean {
        when {
            action.startsWith("copy:") -> {
                val cm = requireContext().getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("code", action.removePrefix("copy:")))
                toast(getString(R.string.rich_copied))
            }
            action.startsWith("open_url:") -> {
                val url = action.removePrefix("open_url:")
                if (url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("tel:", true)) {
                    startSafe(Intent(if (url.startsWith("tel:", true)) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            action.startsWith("reply_text:") -> {
                val t = action.removePrefix("reply_text:")
                binding.composer.setText(t)
                binding.composer.setSelection(t.length)
                binding.composer.requestFocus()
            }
            action == "open_attachment" -> openAttachment(message)
            action == "add_contact" -> fetchRichText(message) { text ->
                if (text.isNullOrBlank()) { openAttachment(message); return@fetchRichText }
                val c = RichContent.vcard(text)
                val i = Intent(android.provider.ContactsContract.Intents.Insert.ACTION).setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.NAME, c.name)
                c.phones.getOrNull(0)?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, it) }
                c.phones.getOrNull(1)?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
                c.emails.getOrNull(0)?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, it) }
                if (c.org.isNotBlank()) i.putExtra(android.provider.ContactsContract.Intents.Insert.COMPANY, c.org)
                startSafe(i)
            }
            action == "add_event" -> fetchRichText(message) { text ->
                val e = text?.let { RichContent.ics(it) } ?: run { openAttachment(message); return@fetchRichText }
                val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, e.title)
                    .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, e.location)
                    .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, e.description)
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
                e.startMs?.let { i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
                e.endMs?.let { i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, it) }
                startSafe(i)
            }
            action == "voice" -> toggleVoice(message)
            else -> return false
        }
        return true
    }

    private fun stopVoice() {
        voiceJob?.cancel(); voiceJob = null
        voicePlayer?.runCatching { release() }
        voicePlayer = null
    }

    private fun toggleVoice(message: MessageDto) {
        val p = voicePlayer
        if (p != null && adapter.voiceId == message.messageId) {
            if (p.isPlaying) p.pause() else p.start()
            adapter.setVoiceState(message.messageId, p.isPlaying, p.currentPosition.toFloat() / p.duration.coerceAtLeast(1), p.duration)
            return
        }
        stopVoice()
        adapter.setVoiceState(message.messageId, false, 0f, 0)
        viewLifecycleOwner.lifecycleScope.launch {
            val f = attachmentFile(message) ?: return@launch toast(getString(R.string.rich_open_failed))
            val mp = runCatching { android.media.MediaPlayer().apply { setDataSource(f.path); prepare() } }.getOrNull()
                ?: return@launch openAttachment(message)
            voicePlayer = mp
            mp.setOnCompletionListener {
                adapter.setVoiceState(message.messageId, false, 0f, it.duration)
                voiceJob?.cancel()
            }
            mp.start()
            voiceJob = viewLifecycleOwner.lifecycleScope.launch {
                while (true) {
                    val cur = voicePlayer ?: break
                    if (cur.isPlaying) adapter.setVoiceState(message.messageId, true, cur.currentPosition.toFloat() / cur.duration.coerceAtLeast(1), cur.duration)
                    kotlinx.coroutines.delay(250)
                }
            }
        }
    }

    override fun onDestroyView() {
        stopVoice()
        mentionPopup?.dismiss()
        mentionPopup = null
        if (serverErrorShown == true) {
            // Drop the line before notifying, so the list does not still see it as showing.
            _binding?.error?.isVisible = false
            publishServerError(false)
        }
        messagePress?.detach()
        messagePress = null
        messageSheet?.destroy()
        messageSheet = null
        gestures?.detach()
        gestures = null
        wallpaperUrl = null
        shownChrome = null
        messageSignature = ""
        shownReplyId = null
        stickToBottom = false
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_LIST = "thread_list_changed"
        const val RESULT_CLOSE = "thread_close"
        const val RESULT_SERVER_ERROR = "thread_server_error"
        const val EXTRA_SERVER_ERROR = "showing"

        fun args(conv: ConversationDto, jumpId: String? = null, jumpTs: Long = 0L) = bundleOf(
            ThreadViewModel.ARG_ID to conv.conversationId,
            ThreadViewModel.ARG_TITLE to Participants.displayName(conv),
            ThreadViewModel.ARG_GROUP to conv.isGroup,
            ThreadViewModel.ARG_PROTOCOL to conv.displayProtocol,
            ThreadViewModel.ARG_PARTICIPANTS to conv.participants,
            ThreadViewModel.ARG_SOURCE to conv.sourcePlatform,
            ThreadViewModel.ARG_GOOGLE_PINNED to conv.googlePinned,
            ThreadViewModel.ARG_LOCAL_PINNED to conv.localPinnedAtMs,
            ThreadViewModel.ARG_NOTIFICATION to conv.notificationMode,
            ThreadViewModel.ARG_JUMP_ID to jumpId.orEmpty(),
            ThreadViewModel.ARG_JUMP_TS to jumpTs,
        )

        fun newInstance(conv: ConversationDto, jumpId: String? = null, jumpTs: Long = 0L): ThreadFragment =
            ThreadFragment().apply { arguments = args(conv, jumpId, jumpTs) }

        fun writeIntent(intent: Intent, conv: ConversationDto, jumpId: String? = null, jumpTs: Long = 0L) {
            intent.putExtras(args(conv, jumpId, jumpTs))
        }

        const val ARG_SHARE_TEXT = "share_text"
        const val ARG_SHARE_FILES = "share_files"

        fun writeShare(intent: Intent, text: String, files: List<ComposerPlan.PendingFile>) {
            if (text.isNotBlank()) intent.putExtra(ARG_SHARE_TEXT, text)
            if (files.isNotEmpty()) {
                intent.putStringArrayListExtra(
                    ARG_SHARE_FILES,
                    ArrayList(files.map { listOf(it.id, it.path, it.name, it.mime, it.size.toString()).joinToString("\t") }),
                )
            }
        }

        internal fun decodeShareFile(raw: String): ComposerPlan.PendingFile? {
            val p = raw.split('\t')
            if (p.size != 5 || p[1].isBlank()) return null
            return ComposerPlan.PendingFile(id = p[0], path = p[1], name = p[2], mime = p[3], size = p[4].toLongOrNull() ?: 0L)
        }
    }
}
