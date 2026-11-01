package com.ubermicrostudios.messagesenhanced.ui.thread

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.data.cache.InboxCache
import com.ubermicrostudios.messagesenhanced.data.model.MessageDto
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.net.NetworkGate
import com.ubermicrostudios.messagesenhanced.data.net.SseClient
import com.ubermicrostudios.messagesenhanced.data.net.TypingTracker
import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import com.ubermicrostudios.messagesenhanced.data.repo.ThemeRepository
import com.ubermicrostudios.messagesenhanced.push.ConversationNotifications
import com.ubermicrostudios.messagesenhanced.data.model.GifPage
import com.ubermicrostudios.messagesenhanced.util.ComposerPlan
import com.ubermicrostudios.messagesenhanced.util.ConversationEncryption
import com.ubermicrostudios.messagesenhanced.util.FailureWindow
import com.ubermicrostudios.messagesenhanced.util.ResumeRetry
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import com.ubermicrostudios.messagesenhanced.util.NetErrors
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.util.MessageActions
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.ReplyTexts
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import com.ubermicrostudios.messagesenhanced.util.SwipeMath
import com.ubermicrostudios.messagesenhanced.util.ThemeColors
import com.ubermicrostudios.messagesenhanced.util.IdempotencyKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject

/** Composer quote, matching the web app's `state.replyTo`. */
data class ReplyTarget(
    val messageId: String,
    val author: String,
    val snippet: String,
)

data class ThreadUiState(
    val conversationId: String = "",
    val title: String = "",
    val isGroup: Boolean = false,
    val messages: List<MessageDto> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notificationMode: String = "all",
    val typingLabel: String = "",
    val encryption: ConversationEncryption = ConversationEncryption(),
    val protocol: String = "",
    val displayProtocol: String = "",
    val colors: ThemeColors = ThemeColors.Default,
    val backgroundUrl: String? = null,
    val participantsJson: String = "",
    val sourcePlatform: String = "",
    val googlePinned: Boolean = false,
    val localPinnedAtMs: Long = 0L,
    val reply: ReplyTarget? = null,
    val jumpMessageId: String? = null,
    val loadedOnce: Boolean = false,
    val starredIds: Set<String> = emptySet(),
    val selecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val pending: List<ComposerPlan.PendingFile> = emptyList(),
    /** False until the on-disk message cache has been checked, so an empty thread does not flash. */
    val ready: Boolean = false,
)

sealed interface ThreadEffect {
    data class Toast(val text: String) : ThreadEffect
    data object Close : ThreadEffect
    data object ListChanged : ThreadEffect
    /** The quoted original is now loaded: scroll to it and flash it. */
    data class ScrollToMessage(val id: String) : ThreadEffect
}

@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val messagesRepo: MessagesRepository,
    private val sse: SseClient,
    private val typing: TypingTracker,
    private val themes: ThemeRepository,
    val baseUrl: BaseUrlProvider,
    private val conversationNotifications: ConversationNotifications,
    private val inboxCache: InboxCache,
    private val prefs: AppPreferences,
    private val network: NetworkGate,
    private val savedStateHandle: SavedStateHandle,
    private val drafts: com.ubermicrostudios.messagesenhanced.data.prefs.DraftStore,
) : ViewModel() {

    private val initialId: String = savedStateHandle[ARG_ID] ?: ""
    private val initialTitle: String = savedStateHandle[ARG_TITLE] ?: ""
    private val initialGroup: Boolean = savedStateHandle[ARG_GROUP] ?: false
    private val initialProtocol: String = savedStateHandle[ARG_PROTOCOL] ?: ""
    private val jumpTs: Long = savedStateHandle[ARG_JUMP_TS] ?: 0L
    private var messageLimit = 80
    private var jumpWindowLoaded = savedStateHandle.get<String>(ARG_JUMP_ID).isNullOrBlank()

    private val openedMessages = if (initialId.isBlank()) emptyList() else inboxCache.peekMessages(initialId)

    private val _state = MutableStateFlow(
        ThreadUiState(
            conversationId = initialId,
            title = initialTitle,
            isGroup = initialGroup,
            displayProtocol = initialProtocol,
            messages = openedMessages.orEmpty(),
            participantsJson = savedStateHandle.get<String>(ARG_PARTICIPANTS).orEmpty(),
            sourcePlatform = savedStateHandle.get<String>(ARG_SOURCE).orEmpty(),
            googlePinned = savedStateHandle.get<Boolean>(ARG_GOOGLE_PINNED) ?: false,
            localPinnedAtMs = savedStateHandle.get<Long>(ARG_LOCAL_PINNED) ?: 0L,
            notificationMode = savedStateHandle.get<String>(ARG_NOTIFICATION).orEmpty().ifBlank { "all" },
            jumpMessageId = savedStateHandle.get<String>(ARG_JUMP_ID)?.takeIf { it.isNotBlank() },
            ready = openedMessages != null,
            draft = drafts.get(initialId),
        ),
    )
    val state: StateFlow<ThreadUiState> = _state

    private val _effects = MutableSharedFlow<ThreadEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<ThreadEffect> = _effects.asSharedFlow()

    private val failures = FailureWindow(gate = network)
    private var sseJob: Job? = null
    private var debounceJob: Job? = null
    private var pollJob: Job? = null
    private var followJob: Job? = null
    private var refreshGen = 0L
    private var typingJob: Job? = null
    private var serverMessagesFor: String? = null

    init {
        watchNetwork()
        sse.ensureStarted()
        viewModelScope.launch {
            themes.revision.collect {
                val id = _state.value.conversationId
                if (id.isBlank()) return@collect
                applyAppearance(id)
            }
        }
        if (initialId.isNotBlank()) bind(initialId, initialTitle, initialGroup, initialProtocol)
    }

    fun bind(
        conversationId: String,
        title: String,
        isGroup: Boolean,
        displayProtocol: String = "",
        participants: String = "",
        sourcePlatform: String = "",
        googlePinned: Boolean = false,
        localPinnedAtMs: Long = 0L,
        notificationMode: String = "",
    ) {
        val changed = _state.value.conversationId != conversationId
        if (changed) _state.value.pending.forEach { File(it.path).delete() }
        if (changed) serverMessagesFor = null
        if (conversationId.isNotBlank()) {
            conversationNotifications.dismiss(conversationId)
            inboxCache.touch(conversationId)
        }
        val cached = if (changed && conversationId.isNotBlank()) inboxCache.peekMessages(conversationId) else null
        _state.update {
            it.copy(
                conversationId = conversationId,
                title = title,
                isGroup = isGroup,
                messages = when {
                    changed && cached != null -> cached
                    changed -> emptyList()
                    else -> it.messages
                },
                ready = if (changed) cached != null else it.ready,
                draft = if (changed) drafts.get(conversationId) else it.draft,
                reply = if (changed) null else it.reply,
                error = null,
                typingLabel = if (changed) "" else it.typingLabel,
                encryption = if (changed) ConversationEncryption() else it.encryption,
                protocol = if (changed) "" else it.protocol,
                displayProtocol = displayProtocol.ifBlank { if (changed) "" else it.displayProtocol },
                // The first bind sees changed == false because the constructor already
                // stored the id. Snapshot fields live in that initial state, and a later
                // bind of the same id must not clobber a pin or mute the user just set.
                participantsJson = if (changed) participants else it.participantsJson,
                sourcePlatform = if (changed) sourcePlatform else it.sourcePlatform,
                googlePinned = if (changed) googlePinned else it.googlePinned,
                localPinnedAtMs = if (changed) localPinnedAtMs else it.localPinnedAtMs,
                notificationMode = if (changed) notificationMode.ifBlank { "all" } else it.notificationMode,
                jumpMessageId = if (changed) null else it.jumpMessageId,
                selecting = if (changed) false else it.selecting,
                selectedIds = if (changed) emptySet() else it.selectedIds,
                starredIds = if (changed) emptySet() else it.starredIds,
                pending = if (changed) emptyList() else it.pending,
            )
        }
        if (changed) {
            failures.success()
            messageLimit = 80
            jumpWindowLoaded = true
            lastReadMarkedTs = 0L
        }
        applyAppearance(conversationId)
        refresh(markRead = true)
        loadStars(conversationId)
        loadMeta(conversationId)
        watchTyping(conversationId, isGroup)
        startSse()
        viewModelScope.launch { runCatchingCancellable { themes.ensureLoaded() } }
    }

    fun onResume() {
        foreground = true
        sse.ensureStarted()
        failures.onForeground()
        clearNetworkError()
        val id = _state.value.conversationId
        if (id.isNotBlank()) {
            refresh(markRead = false)
            loadMeta(id)
            loadStars(id)
        }
    }

    fun saveMessageScroll(state: Parcelable?) {
        savedStateHandle[KEY_SCROLL] = state
    }

    fun messageScroll(): Parcelable? = savedStateHandle[KEY_SCROLL]

    fun onDraft(value: String) {
        _state.update { it.copy(draft = value) }
        drafts.put(_state.value.conversationId, value)
    }

    private var lastReadMarkedTs = 0L
    private var foreground = false

    fun onPause() {
        foreground = false
    }

    fun refresh(markRead: Boolean = false) {
        val id = _state.value.conversationId
        if (id.isBlank()) return
        val gen = ++refreshGen
        val cacheGen = inboxCache.generation()
        viewModelScope.launch {
            if (_state.value.conversationId == id && _state.value.messages.isEmpty() && serverMessagesFor != id) {
                val cached = runCatchingCancellable { inboxCache.readMessages(id) }.getOrDefault(emptyList())
                if (gen == refreshGen && _state.value.conversationId == id && _state.value.messages.isEmpty() &&
                    serverMessagesFor != id && cached.isNotEmpty()
                ) {
                    _state.update { it.copy(messages = cached, loading = false, ready = true) }
                }
            }
            if (gen != refreshGen || _state.value.conversationId != id) return@launch
            // Spinner only when this chat has nothing on screen and nothing cached.
            // Warm resume keeps the current rows; the network refresh stays silent.
            _state.update { it.copy(loading = it.messages.isEmpty(), ready = true) }
            if (_state.value.messages.isNotEmpty()) yield()
            if (gen != refreshGen || _state.value.conversationId != id) return@launch
            if (!jumpWindowLoaded && jumpTs > 0L && id == initialId) {
                messageLimit = runCatchingCancellable { messagesRepo.jumpWindow(id, jumpTs) }.getOrDefault(200)
                jumpWindowLoaded = true
            }
            if (gen != refreshGen || _state.value.conversationId != id) return@launch
            runCatchingCancellable { withResumeRetry { messagesRepo.messages(id, messageLimit) } }
                .onSuccess { list ->
                    failures.success()
                    // Mark read even when this response is stale (a poll/event refresh can
                    // overtake the open-time load); otherwise SMS threads stayed unread.
                    // Also re-mark whenever a newer incoming message shows up while open.
                    val newestIncoming = list.filter { !it.isFromMe }.maxOfOrNull { it.timestampMs } ?: 0L
                    if (_state.value.conversationId == id &&
                        (markRead || (foreground && newestIncoming > lastReadMarkedTs))
                    ) {
                        lastReadMarkedTs = maxOf(lastReadMarkedTs, newestIncoming)
                        launch {
                            messagesRepo.markRead(id)
                            conversationNotifications.dismiss(id)
                            effect(ThreadEffect.ListChanged)
                        }
                    }
                    // Drop stale responses: a slower older request must not overwrite a newer one.
                    if (gen != refreshGen || _state.value.conversationId != id) return@onSuccess
                    if (!inboxCache.isCurrent(cacheGen)) return@onSuccess
                    serverMessagesFor = id
                    inboxCache.saveMessages(id, list, cacheGen)
                    val pending = _state.value.messages.filter { local ->
                        local.messageId.startsWith("local-") && local.status != "OUTGOING_FAILED" &&
                            if (local.mediaId.isBlank()) {
                                list.none { m -> m.isFromMe && m.body == local.body }
                            } else {
                                list.none { m ->
                                    m.isFromMe && m.mediaId.isNotBlank() && m.body == local.body &&
                                        m.mimeType.substringBefore(';').equals(local.mimeType.substringBefore(';'), true) &&
                                        m.timestampMs >= local.timestampMs - 5_000
                                }
                            }
                    }
                    val next = list + pending
                    val kept = next.map { it.messageId }.toSet()
                    _state.value.messages.forEach { old ->
                        if (old.messageId.startsWith("local-") && old.messageId !in kept && old.localPreview.startsWith("/")) {
                            File(old.localPreview).delete()
                        }
                    }
                    _state.update {
                        it.copy(messages = next, loading = false, error = null, loadedOnce = true)
                    }
                }
                .onFailure { e ->
                    val banner = failures.failure(NetErrors.friendly(e))
                    _state.update { it.copy(loading = false, error = banner, loadedOnce = true, ready = true) }
                }
        }
    }

    /** Scroll target from a search hit. Cleared once the row is on screen, or missing. */
    fun consumeJump(found: Boolean) {
        if (_state.value.jumpMessageId == null) return
        _state.update { it.copy(jumpMessageId = null) }
        if (!found) effect(ThreadEffect.Toast("That message isn't loaded here."))
    }

    /** Quoted reply from a right-swipe. The strip shows the sender and the first 80 characters. */
    fun startReply(message: MessageDto) {
        if (!SwipeMath.canReply(message)) return
        val current = _state.value
        _state.update {
            it.copy(
                reply = ReplyTarget(
                    messageId = message.messageId,
                    author = ReplyTexts.author(message, current.title, current.isGroup),
                    snippet = ReplyTexts.barSnippet(ReplyTexts.snippetSource(message)),
                ),
            )
        }
    }

    fun clearReply() {
        if (_state.value.reply == null) return
        _state.update { it.copy(reply = null) }
    }

    fun react(message: MessageDto, emoji: String) {
        if (emoji.isBlank()) return
        val conversationId = message.conversationId.ifBlank { _state.value.conversationId }
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.react(conversationId, message.messageId, emoji) }
                .onSuccess {
                    if (_state.value.conversationId == conversationId) refresh(markRead = false)
                }
                .onFailure { error ->
                    val text = error.message.orEmpty()
                    if (!text.contains("login required", ignoreCase = true)) {
                        effect(ThreadEffect.Toast("Couldn't react: $text"))
                    }
                }
        }
    }

    fun toggleStar(message: MessageDto) {
        val conversationId = message.conversationId.ifBlank { _state.value.conversationId }
        val on = message.messageId !in _state.value.starredIds
        _state.update { it.copy(starredIds = MessageActions.applyStar(it.starredIds, message.messageId, on)) }
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.setStar(message.messageId, conversationId, on) }
                .onSuccess {
                    effect(ThreadEffect.Toast(if (on) "Starred (on this server)" else "Unstarred"))
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(starredIds = MessageActions.applyStar(it.starredIds, message.messageId, !on))
                    }
                    effect(ThreadEffect.Toast("Couldn't star: ${error.message}"))
                }
        }
    }

    fun startSelect(message: MessageDto) {
        _state.update { it.copy(selecting = true, selectedIds = setOf(message.messageId)) }
    }

    fun stopSelect() {
        if (!_state.value.selecting && _state.value.selectedIds.isEmpty()) return
        _state.update { it.copy(selecting = false, selectedIds = emptySet()) }
    }

    fun toggleSelected(messageId: String) {
        if (!_state.value.selecting) return
        _state.update { state ->
            val next = if (messageId in state.selectedIds) state.selectedIds - messageId else state.selectedIds + messageId
            state.copy(selectedIds = next)
        }
    }

    fun deleteMessages(messages: List<MessageDto>) {
        val list = MessageActions.deletable(messages)
        if (list.isEmpty()) return
        viewModelScope.launch {
            var scope: String? = null
            runCatchingCancellable {
                for (message in list) {
                    scope = messagesRepo.deleteMessage(message.messageId).scope
                    _state.update { state ->
                        state.copy(
                            messages = state.messages.filter { it.messageId != message.messageId },
                            selectedIds = state.selectedIds - message.messageId,
                        )
                    }
                }
            }.onSuccess {
                stopSelect()
                effect(ThreadEffect.Toast(MessageActions.deleteToast(scope, list.size)))
                refresh(markRead = false)
                effect(ThreadEffect.ListChanged)
            }.onFailure { error ->
                val text = error.message.orEmpty()
                if (!text.contains("login required", ignoreCase = true)) {
                    effect(ThreadEffect.Toast("Not deleted: $text"))
                }
                refresh(markRead = false)
            }
        }
    }

    fun forwardTargets(done: (List<ConversationDto>) -> Unit) {
        viewModelScope.launch {
            done(runCatchingCancellable { messagesRepo.conversations() }.getOrDefault(emptyList()))
        }
    }

    /** Sends each forwardable message, oldest first, through the normal send path. */
    fun forward(messages: List<MessageDto>, target: ConversationDto) {
        val pieces = MessageActions.forwardPlan(messages)
        val name = Participants.convName(target)
        if (pieces.isEmpty()) {
            effect(ThreadEffect.Toast("Nothing to forward"))
            return
        }
        viewModelScope.launch {
            var sent = 0
            runCatchingCancellable {
                for (piece in pieces) {
                    when (piece) {
                        is MessageActions.ForwardPiece.Text -> {
                            messagesRepo.send(target.conversationId, piece.body)
                            sent++
                        }
                        is MessageActions.ForwardPiece.Media -> {
                            val file = messagesRepo.downloadMedia(piece.messageId)
                            messagesRepo.sendMedia(
                                conversationId = target.conversationId,
                                bytes = file.bytes,
                                filename = piece.filename,
                                mime = file.mime.ifBlank { piece.mimeType },
                            )
                            sent++
                            val caption = piece.caption
                            if (!caption.isNullOrBlank()) messagesRepo.send(target.conversationId, caption)
                        }
                    }
                }
            }.onSuccess {
                effect(ThreadEffect.Toast(if (sent > 0) "Forwarded to $name" else "Nothing forwarded"))
            }.onFailure { error ->
                effect(ThreadEffect.Toast("Forward failed: ${error.message ?: "request failed"}"))
            }
            if (_state.value.conversationId == target.conversationId) refresh(markRead = false)
            effect(ThreadEffect.ListChanged)
        }
    }

    private fun loadStars(id: String) {
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.stars(id) }
                .onSuccess { response ->
                    if (_state.value.conversationId != id) return@onSuccess
                    _state.update { it.copy(starredIds = response.stars.keys) }
                }
        }
    }

    /** Copies picked files into the tray. Returns the web toast strings for files that did not fit. */
    fun stagePending(incoming: List<ComposerPlan.PendingFile>): List<String> {
        val outcome = ComposerPlan.addFiles(_state.value.pending, incoming)
        val keep = outcome.files.map { it.id }.toSet()
        incoming.filter { it.id !in keep }.forEach { File(it.path).delete() }
        _state.update { it.copy(pending = outcome.files) }
        return outcome.notices
    }

    fun removePending(id: String) {
        val file = _state.value.pending.find { it.id == id } ?: return
        File(file.path).delete()
        _state.update { it.copy(pending = it.pending.filter { item -> item.id != id }) }
    }

    suspend fun searchGifs(query: String, page: Int): GifPage =
        messagesRepo.gifs(query.trim(), page, ComposerPlan.GIF_PAGE_SIZE)

    fun send() {
        val current = _state.value
        if (current.sending || current.conversationId.isBlank()) return
        val replyId = current.reply?.messageId
        val plan = ComposerPlan.sendPlan(current.sourcePlatform, current.draft, replyId, current.pending)
        if (plan.media.isEmpty() && plan.text == null) return
        val files = current.pending
        val conv = current.conversationId
        if (plan.clearDraft) drafts.clear(conv)
        val now = System.currentTimeMillis()
        val optimistic = ArrayList<MessageDto>()
        plan.media.forEach { action ->
            val file = files[action.index]
            optimistic.add(
                localOutgoing(
                    id = "local-${file.id}",
                    conversationId = conv,
                    body = action.caption,
                    replyToId = action.replyToId,
                    mime = file.mime,
                    preview = file.path,
                    at = now + optimistic.size,
                ),
            )
        }
        val textKey = if (plan.text != null) IdempotencyKeys.newKey() else ""
        plan.text?.let { text ->
            optimistic.add(
                localOutgoing(
                    id = "local-$textKey",
                    conversationId = conv,
                    body = text.text,
                    replyToId = text.replyToId,
                    at = now + optimistic.size,
                ),
            )
        }
        _state.update {
            it.copy(
                draft = if (plan.clearDraft) "" else it.draft,
                reply = if (plan.clearReply) null else it.reply,
                pending = emptyList(),
                sending = true,
                messages = it.messages + optimistic,
                error = null,
            )
        }
        viewModelScope.launch {
            var failed: Throwable? = null
            for (action in plan.media) {
                val file = files[action.index]
                val bubble = "local-${file.id}"
                val result = runCatchingCancellable {
                    val bytes = withContext(Dispatchers.IO) { File(file.path).readBytes() }
                    messagesRepo.sendMedia(
                        conversationId = conv,
                        bytes = bytes,
                        filename = file.name.ifBlank { "attachment" },
                        mime = file.mime.ifBlank { "application/octet-stream" },
                        caption = action.caption,
                        replyToId = action.replyToId,
                    )
                }
                if (result.isFailure) {
                    failed = result.exceptionOrNull()
                    break
                }
                dropLocal(bubble)
                File(file.path).delete()
            }
            if (failed == null && plan.text != null) {
                val text = plan.text
                val result = runCatchingCancellable {
                    messagesRepo.send(conv, text.text, replyToId = text.replyToId.takeIf { it.isNotBlank() }, idempotencyKey = textKey)
                }
                if (result.isSuccess) dropLocal("local-$textKey") else failed = result.exceptionOrNull()
            }
            _state.update { it.copy(sending = false) }
            if (failed == null) {
                refresh()
                followUpSend()
            } else {
                val message = failed.message.orEmpty()
                if (!message.contains("login required", ignoreCase = true)) {
                    effect(ThreadEffect.Toast("Not sent: ${message.ifBlank { "request failed" }}"))
                }
                _state.update { state ->
                    state.copy(
                        messages = state.messages.map { messageRow ->
                            if (messageRow.messageId.startsWith("local-") && messageRow.status == "OUTGOING_SENDING") {
                                messageRow.copy(status = "OUTGOING_FAILED")
                            } else {
                                messageRow
                            }
                        },
                    )
                }
            }
        }
    }

    fun sendGif(gif: ComposerPlan.GifRef, previewAbsolute: String) {
        val current = _state.value
        if (current.sending || current.conversationId.isBlank() || gif.url.isBlank()) return
        if (current.pending.isNotEmpty()) {
            effect(ThreadEffect.Toast(ComposerPlan.GIF_PENDING_BLOCK))
            return
        }
        val plan = ComposerPlan.gifPlan(current.sourcePlatform, current.draft, current.reply?.messageId)
        val savedDraft = current.draft
        val savedReply = current.reply
        val conv = current.conversationId
        val now = System.currentTimeMillis()
        val gifId = "local-gif-$now"
        val textKey = if (plan.followUpText.isNotEmpty()) IdempotencyKeys.newKey() else ""
        val bubbles = ArrayList<MessageDto>()
        bubbles.add(
            localOutgoing(
                id = gifId,
                conversationId = conv,
                body = plan.caption,
                replyToId = plan.gifReply,
                mime = gif.mimeType.ifBlank { "image/gif" },
                preview = previewAbsolute,
                at = now,
            ),
        )
        if (plan.followUpText.isNotEmpty()) {
            bubbles.add(
                localOutgoing(
                    id = "local-$textKey",
                    conversationId = conv,
                    body = plan.followUpText,
                    replyToId = plan.followUpReply,
                    at = now + 1,
                ),
            )
        }
        _state.update {
            it.copy(
                draft = "",
                reply = null,
                sending = true,
                messages = it.messages + bubbles,
                error = null,
            )
        }
        viewModelScope.launch {
            val gifResult = runCatchingCancellable {
                messagesRepo.sendGif(conv, gif.url, caption = plan.caption, replyToId = plan.gifReply)
            }
            if (gifResult.isFailure) {
                val ids = bubbles.map { it.messageId }.toSet()
                _state.update { state ->
                    state.copy(
                        sending = false,
                        draft = state.draft.ifEmpty { savedDraft },
                        reply = state.reply ?: savedReply,
                        messages = state.messages.filter { it.messageId !in ids },
                    )
                }
                val message = gifResult.exceptionOrNull()?.message.orEmpty()
                if (!message.contains("login required", ignoreCase = true)) {
                    effect(ThreadEffect.Toast(message.ifBlank { ComposerPlan.GIF_FAILED }))
                }
                return@launch
            }
            dropLocal(gifId)
            if (plan.followUpText.isNotEmpty()) {
                val textResult = runCatchingCancellable {
                    messagesRepo.send(
                        conv,
                        plan.followUpText,
                        replyToId = plan.followUpReply.takeIf { it.isNotBlank() },
                        idempotencyKey = textKey,
                    )
                }
                if (textResult.isSuccess) {
                    dropLocal("local-$textKey")
                } else {
                    markLocalFailed("local-$textKey")
                    val message = textResult.exceptionOrNull()?.message.orEmpty()
                    if (!message.contains("login required", ignoreCase = true)) {
                        effect(ThreadEffect.Toast("Not sent: ${message.ifBlank { "request failed" }}"))
                    }
                }
            }
            _state.update { it.copy(sending = false) }
            refresh()
            followUpSend()
        }
    }

    private fun localOutgoing(
        id: String,
        conversationId: String,
        body: String,
        replyToId: String,
        mime: String = "",
        preview: String = "",
        at: Long,
    ) = MessageDto(
        messageId = id,
        conversationId = conversationId,
        senderName = "You",
        body = body,
        timestampMs = at,
        status = "OUTGOING_SENDING",
        isFromMe = true,
        mediaId = if (mime.isNotBlank() || preview.isNotBlank()) "local" else "",
        mimeType = mime,
        replyToId = replyToId,
        localPreview = preview,
    )

    private fun dropLocal(id: String) {
        val row = _state.value.messages.firstOrNull { it.messageId == id }
        if (row != null && row.localPreview.startsWith("/")) File(row.localPreview).delete()
        _state.update { it.copy(messages = it.messages.filter { message -> message.messageId != id }) }
    }

    private fun markLocalFailed(id: String) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.messageId == id) message.copy(status = "OUTGOING_FAILED") else message
                },
            )
        }
    }

    fun setNotificationMode(mode: String) {
        val id = _state.value.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.setNotificationMode(id, mode) }
                .onSuccess { conv ->
                    _state.update { it.copy(notificationMode = conv.notificationMode.ifBlank { mode }) }
                }
                .onFailure { e ->
                    val banner = failures.failure(NetErrors.friendly(e))
                    _state.update { it.copy(error = banner) }
                }
        }
    }

    private fun loadMeta(id: String) {
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.conversationMeta(id) }
                .onSuccess { meta ->
                    if (_state.value.conversationId != id) return@onSuccess
                    _state.update {
                        it.copy(
                            protocol = meta.protocol,
                            notificationMode = when {
                                meta.muted -> "muted"
                                it.notificationMode == "muted" -> "all"
                                else -> it.notificationMode
                            },
                            encryption = ConversationEncryption(
                                e2ee = meta.e2ee,
                                e2eeSource = meta.e2eeSource,
                                e2eeIds = meta.e2eeIds.toSet(),
                                loadedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
        }
    }

    fun toggleMute() {
        val current = _state.value
        val id = current.conversationId
        if (id.isBlank()) return
        val muted = current.notificationMode != "muted"
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.setMuted(id, muted) }
                .onSuccess {
                    if (_state.value.conversationId != id) return@onSuccess
                    _state.update { it.copy(notificationMode = if (muted) "muted" else "all") }
                    effect(ThreadEffect.ListChanged)
                }
                .onFailure { effect(ThreadEffect.Toast(NetErrors.friendly(it))) }
        }
    }

    fun togglePin() {
        val current = _state.value
        val id = current.conversationId
        if (id.isBlank() || current.googlePinned) return
        val pinned = current.localPinnedAtMs <= 0L
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.pin(id, pinned) }
                .onSuccess { response ->
                    if (_state.value.conversationId != id) return@onSuccess
                    _state.update {
                        it.copy(
                            googlePinned = response.googlePinned,
                            localPinnedAtMs = response.localPinnedAtMs,
                        )
                    }
                    effect(ThreadEffect.ListChanged)
                }
                .onFailure { effect(ThreadEffect.Toast(NetErrors.friendly(it))) }
        }
    }

    fun archiveConversation() {
        val id = _state.value.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.archive(id) }
                .onSuccess {
                    prefs.clearLastSelectedConversationId(id)
                    effect(ThreadEffect.ListChanged)
                    effect(ThreadEffect.Close)
                }
                .onFailure { effect(ThreadEffect.Toast(NetErrors.friendly(it))) }
        }
    }

    fun blockConversation(blocked: Boolean, report: Boolean) {
        val id = _state.value.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.block(id, blocked, report) }
                .onSuccess {
                    effect(ThreadEffect.ListChanged)
                    if (blocked) {
                        prefs.clearLastSelectedConversationId(id)
                        effect(ThreadEffect.Close)
                    }
                }
                .onFailure { effect(ThreadEffect.Toast(NetErrors.friendly(it))) }
        }
    }

    /** resend, download (MMS) or fullsize; the phone pushes the updated message back. */
    fun messageAction(messageId: String, action: String, quiet: Boolean = false) {
        if (messageId.isBlank() || messageId.startsWith("local-")) return
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.messageAction(messageId, action) }
                .onFailure { if (!quiet) effect(ThreadEffect.Toast(NetErrors.friendly(it))) }
        }
    }

    fun trashConversation() {
        val id = _state.value.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.trash(id) }
                .onSuccess {
                    prefs.clearLastSelectedConversationId(id)
                    effect(ThreadEffect.ListChanged)
                    effect(ThreadEffect.Close)
                }
                .onFailure { effect(ThreadEffect.Toast(NetErrors.friendly(it))) }
        }
    }

    /** Asks the phone to re-read this conversation, then reloads the open thread. */
    fun refreshFromPhone() {
        val id = _state.value.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messagesRepo.refreshConversation(id) }
                .onSuccess { message ->
                    effect(ThreadEffect.Toast(message))
                    refresh(markRead = false)
                }
                .onFailure { effect(ThreadEffect.Toast(NetErrors.friendly(it))) }
        }
    }

    private var findingOriginal: String? = null
    private val FIND_ORIGINAL_CAP = 2_000

    /**
     * Pages older history (bigger windows, capped) until the quoted message [messageId]
     * is loaded, then asks the screen to scroll to it. Toasts when it can't be found.
     */
    fun findOriginal(messageId: String) {
        val id = _state.value.conversationId
        if (id.isBlank() || messageId.isBlank() || findingOriginal == messageId) return
        findingOriginal = messageId
        viewModelScope.launch {
            try {
                var limit = maxOf(messageLimit, 80)
                while (limit < FIND_ORIGINAL_CAP) {
                    limit = minOf(limit * 2, FIND_ORIGINAL_CAP)
                    val list = runCatchingCancellable { messagesRepo.messages(id, limit) }.getOrNull() ?: break
                    if (_state.value.conversationId != id) return@launch
                    if (list.any { it.messageId == messageId }) {
                        messageLimit = limit
                        refreshGen++
                        val locals = _state.value.messages.filter { it.messageId.startsWith("local-") }
                        _state.update { it.copy(messages = list + locals, loadedOnce = true) }
                        effect(ThreadEffect.ScrollToMessage(messageId))
                        return@launch
                    }
                    if (list.size < limit) break // whole history loaded
                }
                effect(ThreadEffect.Toast("Message not available"))
            } finally {
                findingOriginal = null
            }
        }
    }

    private fun effect(event: ThreadEffect) {
        _effects.tryEmit(event)
    }

    private fun applyAppearance(id: String) {
        val look = themes.appearance(id)
        _state.update { it.copy(colors = look.colors, backgroundUrl = look.backgroundUrl) }
    }

    private fun watchTyping(id: String, isGroup: Boolean) {
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            typing.names.collect { map ->
                if (_state.value.conversationId != id) return@collect
                val label = TypingTracker.labelFor(map[id].orEmpty().map { formatPhoneForDisplay(it) }, isGroup)
                _state.update { it.copy(typingLabel = label) }
            }
        }
    }

    private fun watchNetwork() {
        viewModelScope.launch {
            network.foreground.collect { fg ->
                if (fg) {
                    failures.onForeground()
                    clearNetworkError()
                } else {
                    failures.onBackground()
                    clearNetworkError()
                }
            }
        }
    }

    private fun clearNetworkError() {
        _state.update { state ->
            if (NetErrors.isNetworkMessage(state.error)) state.copy(error = null) else state
        }
    }

    private suspend fun <T> withResumeRetry(block: suspend () -> T): T =
        ResumeRetry.run(network, { failures.inQuiet() }, block = block)

    /** Debounced refresh, like the web app's scheduleRefresh (150 ms). */
    private fun scheduleRefresh() {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch { delay(150); refresh() }
    }

    /** Web app: after a send, keep checking until it leaves "Sending". */
    private fun followUpSend() {
        followJob?.cancel()
        followJob = viewModelScope.launch {
            for (wait in longArrayOf(1_500, 3_000, 5_000, 8_000, 12_000)) {
                delay(wait)
                val sending = _state.value.messages.any {
                    it.isFromMe && (it.messageId.startsWith("local-") || it.status.contains("SENDING", true) ||
                        it.status.contains("YET_TO_SEND", true))
                }
                if (!sending) break
                refresh()
            }
        }
    }

    /** Web app's pollIfStreamDead: refresh the open thread every 4 s while the stream isn't live. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(4_000)
                if (!sse.isLive()) { sse.ensureStarted(); refresh() }
            }
        }
    }

    private fun startSse() {
        startPolling()
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            sse.events.collect { event ->
                val id = _state.value.conversationId
                when (event.type) {
                    "messages", "reconnected" -> {
                        if (event.conversationId.isBlank() || event.conversationId == id || event.type == "reconnected") {
                            scheduleRefresh()
                        }
                    }
                    "conversations" -> scheduleRefresh()
                }
            }
        }
    }

    override fun onCleared() {
        _state.value.pending.forEach { File(it.path).delete() }
        _state.value.messages.forEach { row ->
            if (row.localPreview.startsWith("/")) File(row.localPreview).delete()
        }
        super.onCleared()
    }

    companion object {
        const val ARG_ID = "conversation_id"
        const val ARG_TITLE = "conversation_title"
        const val ARG_GROUP = "conversation_group"
        const val ARG_PROTOCOL = "conversation_protocol"
        const val ARG_PARTICIPANTS = "conversation_participants"
        const val ARG_SOURCE = "conversation_source"
        const val ARG_GOOGLE_PINNED = "conversation_google_pinned"
        const val ARG_LOCAL_PINNED = "conversation_local_pinned"
        const val ARG_NOTIFICATION = "conversation_notification"
        const val ARG_JUMP_ID = "jump_message_id"
        const val ARG_JUMP_TS = "jump_message_ts"
        private const val KEY_SCROLL = "message_list_scroll"
    }
}
