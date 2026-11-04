package com.ubermicrostudios.messagesenhanced.ui.thread

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import com.ubermicrostudios.messagesenhanced.databinding.ActivityImageViewerBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.util.ConversationEncryption
import com.ubermicrostudios.messagesenhanced.util.EmojiCatalog
import com.ubermicrostudios.messagesenhanced.util.Encryption
import com.ubermicrostudios.messagesenhanced.util.Formatters
import com.ubermicrostudios.messagesenhanced.util.ImageStore
import com.ubermicrostudios.messagesenhanced.util.SafeFiles
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import com.ubermicrostudios.messagesenhanced.util.MessageActions
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.ReplyTexts
import com.ubermicrostudios.messagesenhanced.util.ShareIndex
import com.ubermicrostudios.messagesenhanced.util.ViewerBundle
import com.ubermicrostudios.messagesenhanced.util.stableCacheKey
import com.ubermicrostudios.messagesenhanced.util.ViewerGallery
import com.ubermicrostudios.messagesenhanced.util.ViewerReplies
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import com.ubermicrostudios.messagesenhanced.util.toMessage
import com.ubermicrostudios.messagesenhanced.util.toViewerSnap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Full-screen photos for one conversation. Swipe across every image, starting at the one that was tapped.
 * Pinch, pan, and double-tap zoom. A zoomed photo pans instead of changing pages. Swipe down or back closes.
 */
@AndroidEntryPoint
class ImageViewerActivity : SessionActivity() {

    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var http: OkHttpClient
    @Inject lateinit var messagesRepo: MessagesRepository
    @Inject lateinit var baseUrl: BaseUrlProvider

    private val model: ImageViewerState by viewModels()
    private lateinit var binding: ActivityImageViewerBinding
    private lateinit var pageAdapter: ImagePageAdapter
    private val replyAdapter = ViewerReplyAdapter()

    private var messages: List<MessageDto> = emptyList()
    private var launchPath: String? = null
    private var shownId: String? = null
    private var sendingComment = false

    private var url = ""
    private var filePath = ""
    private var mime = ""
    private var messageId = ""
    private var closing = false
    private var transferring = false
    private var transfer: Job? = null

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else toast(R.string.image_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enable(this)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        observeAuthLoss()

        launchPath = intent.getStringExtra(EXTRA_PATH)
        if (model.bundle == null) {
            val loaded = launchPath?.let { readBundle(cacheDir, it) }
            if (loaded == null) {
                finish()
                return
            }
            model.bundle = loaded
            model.messages = loaded.messages.map { it.toMessage() }
            if (savedInstanceState != null) {
                model.commentsOpen = savedInstanceState.getBoolean(STATE_COMMENTS)
                model.pageId = savedInstanceState.getString(STATE_PAGE)
            }
        }
        val meta = model.bundle ?: run { finish(); return }
        messages = model.messages.orEmpty()
        val images = gallery()
        if (images.isEmpty()) {
            finish()
            return
        }

        applyInsets()
        applyPeek()
        pageAdapter = ImagePageAdapter(
            imageLoader = imageLoader,
            mediaUrl = { baseUrl.mediaUrl(it.messageId) },
            onZoomed = { position, zoomed ->
                if (position == binding.pager.currentItem) binding.pager.isUserInputEnabled = !zoomed
            },
            onDrag = { dy ->
                val span = binding.pager.height
                val fraction = if (span <= 0) 0f else (dy / span).coerceIn(0f, 1f)
                val alpha = 1f - fraction
                binding.topBar.alpha = alpha
                binding.bottomStack.alpha = alpha
            },
            onDismiss = { close() },
        )
        binding.pager.adapter = pageAdapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                showPage(position, clearDraft = true)
            }
        })
        pageAdapter.submit(images)
        val start = model.pageId ?: meta.startId
        val index = ViewerGallery.startIndex(images, start)
        binding.pager.setCurrentItem(index, false)
        showPage(index, clearDraft = false)

        binding.close.setOnClickListener { finish() }
        binding.download.setOnClickListener { save() }
        binding.delete.setOnClickListener { confirmDelete() }
        binding.overflow.setOnClickListener { showMenu() }
        binding.reply.setOnClickListener { toggleReplies() }
        binding.commentSend.setOnClickListener { sendComment() }
        binding.commentInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComment()
                true
            } else {
                false
            }
        }
        binding.replies.layoutManager = LinearLayoutManager(this)
        binding.replies.adapter = replyAdapter
        buildReactions()
        renderBottom(scrollEnd = false)
        refreshFromServer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, model.pageId)
        outState.putBoolean(STATE_COMMENTS, model.commentsOpen)
    }

    private fun applyInsets() {
        val bar = binding.topBar
        val baseStart = bar.paddingStart
        val baseTop = bar.paddingTop
        val baseEnd = bar.paddingEnd
        val baseBottom = bar.paddingBottom
        val stack = binding.bottomStack
        val stackStart = stack.paddingStart
        val stackTop = stack.paddingTop
        val stackEnd = stack.paddingEnd
        val stackBottom = stack.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewerRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            bar.setPadding(baseStart + bars.left, baseTop + bars.top, baseEnd + bars.right, baseBottom)
            stack.setPadding(stackStart + bars.left, stackTop, stackEnd + bars.right, stackBottom + max(bars.bottom, ime))
            insets
        }
        ViewCompat.requestApplyInsets(binding.viewerRoot)
    }

    /**
     * Side padding lets the next photo peek. ViewPager2 1.1 lays its list inside the pager's own
     * padding, which only shrinks the page. The inset goes on the inner list, with clipping off,
     * and a page margin opens a gap between cards.
     */
    private fun applyPeek() {
        val density = resources.displayMetrics.density
        val peek = (28 * density).toInt()
        val margin = (12 * density).toInt()
        val pager = binding.pager
        pager.setPadding(0, 0, 0, 0)
        pager.clipToPadding = false
        pager.clipChildren = false
        pager.offscreenPageLimit = 1
        pager.setPageTransformer(MarginPageTransformer(margin))
        val recycler = pager.getChildAt(0) as RecyclerView
        recycler.clipToPadding = false
        recycler.clipChildren = false
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        recycler.setPadding(peek, 0, peek, 0)
    }

    private fun gallery(): List<MessageDto> =
        ViewerGallery.images(messages) { baseUrl.mediaUrl(it.messageId) }

    private fun showPage(position: Int, clearDraft: Boolean) {
        val message = gallery().getOrNull(position) ?: return
        if (clearDraft && shownId != null && shownId != message.messageId) {
            binding.commentInput.setText("")
            hideKeyboard()
            binding.pager.isUserInputEnabled = true
        }
        shownId = message.messageId
        model.pageId = message.messageId
        arm(message)
        binding.sender.text = ViewerReplies.senderLabel(message, model.bundle?.title.orEmpty(), model.bundle?.isGroup == true)
        val whenText = Formatters.relativeTime(message.timestampMs)
        binding.sentWhen.text = whenText
        binding.sentWhen.isVisible = whenText.isNotBlank()
        // MessagesApi.deleteMessage is the delete call. Hide it only for rows that call cannot accept.
        binding.delete.isVisible = MessageActions.deletable(listOf(message)).isNotEmpty()
        renderBottom(scrollEnd = false)
    }

    private fun arm(message: MessageDto) {
        val source = ImageStore.openSource(message, baseUrl.mediaUrl(message.messageId))
        url = ""
        filePath = ""
        when (source) {
            is ImageStore.Source.Remote -> url = source.url
            is ImageStore.Source.Local -> filePath = source.path
            null -> Unit
        }
        mime = message.mimeType.substringBefore(';').trim()
        messageId = message.messageId.ifBlank { "photo" }
    }

    private fun currentMessage(): MessageDto? = gallery().getOrNull(binding.pager.currentItem)

    private fun buildReactions() {
        val row = binding.reactions
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (40 * density).toInt()
        for (emoji in MessageActions.QUICK_REACTIONS) {
            row.addView(TextView(this).apply {
                text = emoji
                textSize = 22f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(size, size)
                contentDescription = "React $emoji"
                setOnClickListener { react(emoji) }
            })
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_add_reaction)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE3E3E3.toInt())
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = getString(R.string.more_reactions)
            setOnClickListener {
                EmojiPicker.show(this@ImageViewerActivity) { emoji ->
                    EmojiCatalog.remember(emoji)
                    react(emoji)
                }
            }
        })
    }

    private fun react(emoji: String) {
        val message = currentMessage() ?: return
        if (emoji.isBlank()) return
        val conversation = message.conversationId.ifBlank { model.bundle?.conversationId.orEmpty() }
        lifecycleScope.launch {
            runCatchingCancellable { messagesRepo.react(conversation, message.messageId, emoji) }
                .onFailure { error ->
                    val text = error.message.orEmpty()
                    if (!text.contains("login required", ignoreCase = true)) {
                        toast("Couldn't react: $text")
                    }
                }
        }
    }

    private fun toggleReplies() {
        model.commentsOpen = !model.commentsOpen
        renderBottom(scrollEnd = model.commentsOpen)
        if (!model.commentsOpen) hideKeyboard()
    }

    private fun renderBottom(scrollEnd: Boolean) {
        val meta = model.bundle
        val id = currentMessage()?.messageId ?: model.pageId.orEmpty()
        val replies = ViewerReplies.directReplies(messages, id)
        val badge = ViewerReplies.badgeLabel(replies.size)
        binding.replyBadge.isVisible = badge != null
        binding.replyBadge.text = badge.orEmpty()
        val open = model.commentsOpen
        binding.repliesPanel.isVisible = open
        binding.reactionsScroll.isVisible = !open
        binding.commentBar.isVisible = open
        binding.replies.isVisible = open && replies.isNotEmpty()
        binding.repliesEmpty.isVisible = open && replies.isEmpty()
        replyAdapter.title = meta?.title.orEmpty()
        replyAdapter.isGroup = meta?.isGroup == true
        replyAdapter.origin = meta?.origin.orEmpty()
        replyAdapter.participantsJson = meta?.participantsJson.orEmpty()
        replyAdapter.submit(replies)
        if (scrollEnd && replies.isNotEmpty()) binding.replies.scrollToPosition(replies.lastIndex)
    }

    private fun sendComment() {
        val message = currentMessage() ?: return
        val text = binding.commentInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || sendingComment) return
        sendingComment = true
        val now = System.currentTimeMillis()
        val conversation = message.conversationId.ifBlank { model.bundle?.conversationId.orEmpty() }
        val optimistic = MessageDto(
            messageId = "local-comment-$now",
            conversationId = conversation,
            senderName = ReplyTexts.YOU,
            body = text,
            timestampMs = now,
            isFromMe = true,
            replyToId = message.messageId,
            status = "OUTGOING_COMPLETE",
        )
        messages = ViewerReplies.withReply(messages, optimistic)
        model.messages = messages
        binding.commentInput.setText("")
        renderBottom(scrollEnd = true)
        persist()
        val imageId = message.messageId
        lifecycleScope.launch {
            val result = runCatchingCancellable {
                messagesRepo.send(conversation, text, replyToId = message.messageId)
            }
            sendingComment = false
            if (result.isFailure) {
                messages = messages.filter { it.messageId != optimistic.messageId }
                model.messages = messages
                renderBottom(false)
                persist()
                val err = result.exceptionOrNull()?.message.orEmpty()
                if (!err.contains("login required", ignoreCase = true)) {
                    toast(err.ifBlank { getString(R.string.comment_failed) })
                }
                return@launch
            }
            val remote = runCatchingCancellable { messagesRepo.messages(conversation, 1000) }.getOrNull()
            if (remote != null) {
                messages = ViewerReplies.mergeRemote(messages, remote, optimistic)
                model.messages = messages
                refreshGallery(imageId)
                persist()
            }
            renderBottom(true)
        }
    }

    private fun refreshFromServer() {
        val conversation = model.bundle?.conversationId.orEmpty()
        if (conversation.isBlank()) return
        lifecycleScope.launch {
            val remote = runCatchingCancellable { messagesRepo.messages(conversation, 1000) }.getOrNull() ?: return@launch
            val keep = model.pageId ?: return@launch
            messages = ViewerReplies.mergeRemote(messages, remote)
            model.messages = messages
            refreshGallery(keep)
            persist()
        }
    }

    private fun refreshGallery(keepId: String) {
        val images = gallery()
        if (images.isEmpty()) {
            finish()
            return
        }
        val index = ViewerGallery.startIndex(images, keepId)
        if (!pageAdapter.sameIds(images)) pageAdapter.submit(images)
        binding.pager.setCurrentItem(index, false)
        showPage(index, clearDraft = false)
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.overflow)
        popup.menuInflater.inflate(R.menu.menu_image_viewer, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.viewer_forward -> {
                    forward()
                    true
                }
                R.id.viewer_share -> {
                    share()
                    true
                }
                R.id.viewer_details -> {
                    openDetails()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openDetails() {
        val message = currentMessage() ?: return
        val meta = model.bundle ?: return
        val protocol = meta.protocol
        val encrypted = Encryption.messageEncrypted(message, meta.encryption()) ||
            Encryption.protocolEncrypted(protocol)
        MessageDetailsActivity.open(
            this,
            message,
            protocol = protocol,
            encrypted = encrypted,
            conversationPlatform = message.sourcePlatform.ifBlank { meta.sourcePlatform },
        )
    }

    private fun forward() {
        val message = currentMessage() ?: return
        val pieces = MessageActions.forwardPlan(listOf(message))
        if (pieces.isEmpty()) {
            toast("Nothing to forward")
            return
        }
        val caption = pieces.filterIsInstance<MessageActions.ForwardPiece.Media>()
            .firstNotNullOfOrNull { it.caption }
            .orEmpty()
        val thumb = url.ifBlank { filePath }
        lifecycleScope.launch {
            val conversations = runCatchingCancellable { messagesRepo.conversations() }.getOrDefault(emptyList())
            val contacts = runCatchingCancellable { messagesRepo.contacts().all }.getOrDefault(emptyList())
            if (isFinishing) return@launch
            ForwardPicker.share(
                context = this@ImageViewerActivity,
                origin = baseUrl.origin(),
                conversations = conversations,
                contacts = contacts,
                thumbnail = thumb,
                caption = caption,
                onSend = { selection -> lifecycleScope.launch { sendShare(pieces, selection, asGroup = false) } },
                onSendGroup = { selection -> lifecycleScope.launch { sendShare(pieces, selection, asGroup = true) } },
            )
        }
    }

    private sealed interface Prepared {
        data class Text(val body: String) : Prepared
        data class Media(val bytes: ByteArray, val filename: String, val mime: String) : Prepared
    }

    private suspend fun prepare(pieces: List<MessageActions.ForwardPiece>): List<Prepared> {
        val out = ArrayList<Prepared>()
        for (piece in pieces) {
            when (piece) {
                is MessageActions.ForwardPiece.Text -> out.add(Prepared.Text(piece.body))
                is MessageActions.ForwardPiece.Media -> {
                    val file = messagesRepo.downloadMedia(piece.messageId)
                    out.add(
                        Prepared.Media(
                            bytes = file.bytes,
                            filename = piece.filename,
                            mime = file.mime.ifBlank { piece.mimeType },
                        ),
                    )
                }
            }
        }
        return out
    }

    private suspend fun deliver(prepared: List<Prepared>, conversationId: String, caption: String) {
        var captionLeft = caption.trim()
        for (piece in prepared) {
            when (piece) {
                is Prepared.Text -> messagesRepo.send(conversationId, piece.body)
                is Prepared.Media -> {
                    messagesRepo.sendMedia(
                        conversationId = conversationId,
                        bytes = piece.bytes,
                        filename = piece.filename,
                        mime = piece.mime,
                    )
                    if (captionLeft.isNotBlank()) {
                        messagesRepo.send(conversationId, captionLeft)
                        captionLeft = ""
                    }
                }
            }
        }
        if (captionLeft.isNotBlank()) messagesRepo.send(conversationId, captionLeft)
    }

    private suspend fun sendShare(
        pieces: List<MessageActions.ForwardPiece>,
        selection: ForwardPicker.Selection,
        asGroup: Boolean,
    ) {
        if (selection.conversations.isEmpty() && selection.contacts.isEmpty()) {
            toast("Nothing forwarded")
            return
        }
        val caption = selection.caption.trim()
        val prepared = runCatchingCancellable { prepare(pieces) }.getOrElse { error ->
            toast("Forward failed: ${error.message ?: "request failed"}")
            return
        }
        var separate = false
        if (asGroup) {
            val numbers = ShareIndex.groupNumbers(selection.contacts, selection.conversations)
            val started = if (numbers.size >= 2) {
                runCatchingCancellable { messagesRepo.startConversation(numbers) }.getOrNull()
            } else {
                null
            }
            val groupId = started?.conversationId.orEmpty()
            if (groupId.isNotBlank() && runCatchingCancellable { deliver(prepared, groupId, caption) }.isSuccess) {
                toast("Forwarded")
                return
            }
            separate = true
        }
        val targets = LinkedHashMap<String, String>()
        for (conversation in selection.conversations) {
            if (conversation.conversationId.isNotBlank()) {
                targets.putIfAbsent(conversation.conversationId, Participants.convName(conversation))
            }
        }
        for (contact in selection.contacts) {
            val id = resolveContact(contact)
            if (id.isNotBlank()) targets.putIfAbsent(id, contact.displayName)
        }
        if (targets.isEmpty()) {
            toast(if (separate) getString(R.string.share_sent_separately) else "Nothing forwarded")
            return
        }
        var sent = 0
        var error: String? = null
        for (id in targets.keys) {
            val result = runCatchingCancellable { deliver(prepared, id, caption) }
            if (result.isSuccess) sent++ else error = result.exceptionOrNull()?.message
        }
        when {
            sent == 0 -> toast("Forward failed: ${error ?: "request failed"}")
            separate -> toast(getString(R.string.share_sent_separately))
            else -> toast(if (sent == 1) "Forwarded" else "Forwarded to $sent")
        }
    }

    private suspend fun resolveContact(contact: ContactDto): String {
        if (contact.conversationId.isNotBlank()) return contact.conversationId
        if (contact.phone.isBlank()) return ""
        return runCatchingCancellable { messagesRepo.startConversation(listOf(contact.phone)) }.getOrNull()?.conversationId.orEmpty()
    }

    /** Confirms, then calls [MessagesRepository.deleteMessage], which posts to [com.ubermicrostudios.messagesenhanced.data.net.MessagesApi.deleteMessage]. */
    private fun confirmDelete() {
        val message = currentMessage() ?: return
        if (MessageActions.deletable(listOf(message)).isEmpty()) return
        val prompt = MessageActions.deletePrompt(1, message.sourcePlatform.ifBlank { model.bundle?.sourcePlatform.orEmpty() })
        val dialog = AlertDialog.Builder(this)
            .setTitle(prompt.title)
            .setMessage(prompt.body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_message) { _, _ -> delete(message) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFFFF8589.toInt())
        }
        dialog.show()
    }

    private fun delete(message: MessageDto) {
        val images = gallery()
        val index = images.indexOfFirst { it.messageId == message.messageId }
        val neighbor = when {
            index + 1 < images.size -> images[index + 1].messageId
            index > 0 -> images[index - 1].messageId
            else -> null
        }
        lifecycleScope.launch {
            runCatchingCancellable { messagesRepo.deleteMessage(message.messageId) }
                .onSuccess { response ->
                    messages = messages.filter { it.messageId != message.messageId }
                    model.messages = messages
                    persist()
                    toast(MessageActions.deleteToast(response.scope, 1))
                    if (neighbor == null || gallery().isEmpty()) finish() else refreshGallery(neighbor)
                }
                .onFailure { error ->
                    val text = error.message.orEmpty()
                    if (!text.contains("login required", ignoreCase = true)) {
                        toast(text.ifBlank { getString(R.string.image_delete_failed) })
                    }
                }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.commentInput.windowToken, 0)
    }

    private fun persist() {
        val path = launchPath ?: return
        val meta = model.bundle ?: return
        val bundle = meta.copy(
            messages = messages.map { it.toViewerSnap() },
            startId = model.pageId ?: meta.startId,
        )
        model.bundle = bundle
        val root = cacheDir
        lifecycleScope.launch(Dispatchers.IO) {
            val file = SafeFiles.fileWithin(root, path) ?: return@launch
            synchronized(writeLock) {
                runCatchingCancellable { file.writeText(JsonConfig.json.encodeToString(bundle)) }
            }
        }
    }

    private fun share() = transfer(R.string.image_share_failed) { cached -> send(cached) }

    private fun save() {
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        transfer(R.string.image_save_failed) { cached ->
            val ok = withContext(Dispatchers.IO) { writeGallery(cached) }
            if (!isFinishing) toast(if (ok) R.string.image_saved else R.string.image_save_failed)
        }
    }

    private fun transfer(failure: Int, block: suspend (Cached) -> Unit) {
        if (transfer?.isActive == true || closing) return
        transfer = lifecycleScope.launch {
            transferring = true
            syncProgress()
            val cached = try {
                materialize()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            transferring = false
            syncProgress()
            if (cached == null) {
                if (!isFinishing) toast(failure)
                return@launch
            }
            if (!isFinishing) block(cached)
        }
    }

    private fun send(cached: Cached) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", cached.file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = cached.mime.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(getString(R.string.photo), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(share, getString(R.string.share))
        runCatchingCancellable { startActivity(chooser) }.onFailure { toast(R.string.image_share_failed) }
    }

    private suspend fun materialize(): Cached = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, ImageStore.SHARE_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 3_600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val hinted = mime.ifBlank { "image/jpeg" }
        if (filePath.isNotBlank()) {
            val from = SafeFiles.fileWithin(cacheDir, filePath) ?: error("missing")
            if (!from.isFile) error("missing")
            if (from.length() > MessageActions.MAX_MEDIA_BYTES) error("too large")
            val dest = File(dir, ImageStore.fileName(messageId, hinted, System.currentTimeMillis()))
            from.copyTo(dest, overwrite = true)
            return@withContext Cached(dest, hinted)
        }
        try {
            copyCoil(dir, hinted)?.let { return@withContext it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (_: Exception) {
            // Disk cache miss. The OkHttp client below is the one Coil uses.
        }
        download(dir, hinted)
    }

    /** Original bytes Coil stored for this URL, when the disk cache still has them. */
    @OptIn(ExperimentalCoilApi::class)
    private suspend fun copyCoil(dir: File, hinted: String): Cached? {
        val result = imageLoader.execute(
            ImageRequest.Builder(this)
                .data(url)
                .size(Size.ORIGINAL)
                .diskCachePolicy(CachePolicy.ENABLED)
                .stableCacheKey(url)
                .build(),
        )
        val key = (result as? SuccessResult)?.diskCacheKey ?: return null
        val snapshot = imageLoader.diskCache?.openSnapshot(key) ?: return null
        return snapshot.use { snap ->
            val source = snap.data.toFile()
            if (!source.isFile || source.length() == 0L) return@use null
            if (source.length() > MessageActions.MAX_MEDIA_BYTES) error("too large")
            val dest = File(dir, ImageStore.fileName(messageId, hinted, System.currentTimeMillis()))
            source.copyTo(dest, overwrite = true)
            if (dest.isFile && dest.length() > 0L) Cached(dest, hinted) else null
        }
    }

    private suspend fun download(dir: File, hinted: String): Cached {
        val dest = File(dir, "dl-${System.nanoTime()}.part")
        var named: File? = null
        var type = hinted
        var ok = false
        val call = http.newCall(Request.Builder().url(url).build())
        try {
            call.execute().use { response ->
                coroutineContext.ensureActive()
                if (!response.isSuccessful) error("status ${response.code}")
                val header = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                if (header.startsWith("image/")) type = header
                val body = response.body ?: error("empty")
                dest.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MessageActions.MAX_MEDIA_BYTES) error("too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            coroutineContext.ensureActive()
            if (dest.length() == 0L) error("empty")
            val file = File(dir, ImageStore.fileName(messageId, type, System.currentTimeMillis()))
            named = file
            if (!dest.renameTo(file)) dest.copyTo(file, overwrite = true)
            ok = file.isFile && file.length() > 0L
            if (!ok) error("empty")
            return Cached(file, type)
        } finally {
            if (dest.exists()) dest.delete()
            if (!ok) named?.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeGallery(cached: Cached): Boolean {
        val type = cached.mime.ifBlank { "image/jpeg" }
        val name = cached.file.name
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, type)
                put(MediaStore.Images.Media.RELATIVE_PATH, ImageStore.RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (_: Exception) {
                null
            } ?: return false
            return try {
                val output = resolver.openOutputStream(uri)
                if (output == null) {
                    resolver.delete(uri, null, null)
                    return false
                }
                output.use { stream ->
                    cached.file.inputStream().use { input -> input.copyTo(stream) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (_: Exception) {
                runCatchingCancellable { resolver.delete(uri, null, null) }
                false
            }
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ImageStore.ALBUM,
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        val dest = File(dir, name)
        return try {
            cached.file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(type), null)
            true
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    private fun syncProgress() {
        binding.progress.isVisible = transferring
    }

    private fun close() {
        if (closing) return
        closing = true
        finish()
    }

    private fun toast(message: Int) = toast(getString(message))

    private fun toast(message: String) {
        if (message.isBlank() || isFinishing) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class Cached(val file: File, val mime: String)

    companion object {
        private const val EXTRA_PATH = "viewer_path"
        private const val STATE_PAGE = "page"
        private const val STATE_COMMENTS = "comments"
        private val writeLock = Any()
        private val sessionWriter = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "viewer-session").apply { isDaemon = true }
        }

        fun open(
            context: Context,
            messages: List<MessageDto>,
            tapped: MessageDto,
            conversationId: String,
            title: String,
            isGroup: Boolean,
            sourcePlatform: String,
            protocol: String,
            encryption: ConversationEncryption,
            origin: String,
            participantsJson: String = "",
        ) {
            val root = origin.trim().trimEnd('/')
            val media = { message: MessageDto -> "$root/api/media/${message.messageId}" }
            if (ViewerGallery.images(messages, media).none { it.messageId == tapped.messageId }) return
            val bundle = ViewerBundle(
                conversationId = conversationId.ifBlank { tapped.conversationId },
                title = title,
                isGroup = isGroup,
                sourcePlatform = sourcePlatform,
                protocol = protocol,
                e2ee = encryption.e2ee,
                e2eeSource = encryption.e2eeSource,
                e2eeIds = encryption.e2eeIds.toList(),
                e2eeLoadedAt = encryption.loadedAt,
                startId = tapped.messageId,
                messages = messages.map { it.toViewerSnap() },
                participantsJson = participantsJson,
                origin = root,
            )
            val cacheRoot = context.cacheDir
            sessionWriter.execute {
                val path = synchronized(writeLock) {
                    val dir = File(cacheRoot, "viewer").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, "session.json")
                    val wrote = runCatchingCancellable { file.writeText(JsonConfig.json.encodeToString(bundle)) }.isSuccess
                    if (!wrote || !file.isFile) null else file.absolutePath
                }
                if (path != null) {
                    Handler(Looper.getMainLooper()).post {
                        if (context is Activity && (context.isFinishing || context.isDestroyed)) return@post
                        runCatchingCancellable {
                            context.startActivity(
                                Intent(context, ImageViewerActivity::class.java).putExtra(EXTRA_PATH, path),
                            )
                        }
                    }
                }
            }
        }

        private fun readBundle(cacheDir: File, path: String): ViewerBundle? {
            val file = SafeFiles.fileWithin(cacheDir, path) ?: return null
            if (!file.isFile) return null
            return runCatchingCancellable {
                JsonConfig.json.decodeFromString(ViewerBundle.serializer(), file.readText())
            }.getOrNull()
        }
    }
}

class ImageViewerState : ViewModel() {
    var bundle: ViewerBundle? = null
    var messages: List<MessageDto>? = null
    var commentsOpen: Boolean = false
    var pageId: String? = null
}
