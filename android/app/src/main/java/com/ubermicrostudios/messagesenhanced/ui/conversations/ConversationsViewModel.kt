package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.data.cache.InboxCache
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.data.net.NetworkGate
import com.ubermicrostudios.messagesenhanced.data.net.SseClient
import com.ubermicrostudios.messagesenhanced.data.net.TypingTracker
import com.ubermicrostudios.messagesenhanced.data.repo.AuthRepository
import com.ubermicrostudios.messagesenhanced.data.repo.HealthRepository
import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import com.ubermicrostudios.messagesenhanced.data.repo.RemoteSearch
import com.ubermicrostudios.messagesenhanced.data.repo.ThemeRepository
import com.ubermicrostudios.messagesenhanced.util.FailureWindow
import com.ubermicrostudios.messagesenhanced.util.ResumeRetry
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import com.ubermicrostudios.messagesenhanced.util.HealthBanner
import com.ubermicrostudios.messagesenhanced.util.InboxSearch
import com.ubermicrostudios.messagesenhanced.util.NetErrors
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.ThemeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchPhase { IDLE, SHORT, LOADING, RESULTS, EMPTY }

data class SearchUi(
    val open: Boolean = false,
    val query: String = "",
    val phase: SearchPhase = SearchPhase.IDLE,
    val conversations: List<ConversationDto> = emptyList(),
    val messages: List<InboxSearch.MessageHit> = emptyList(),
    val messageError: String? = null,
)

/** Header profile: account photo when the server has one, otherwise your contact photo. */
data class ProfileUi(
    val photoUrl: String? = null,
    val fallbackUrl: String? = null,
    val initial: String = "?",
    val colorSeed: String = "Me",
    val name: String = "Me",
)

data class ConversationsUiState(
    val conversations: List<ConversationDto> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val selectedId: String? = null,
    val typingNames: Map<String, List<String>> = emptyMap(),
    val colors: ThemeColors = ThemeColors.Default,
    val notice: String? = null,
    /** Trash succeeded for this id. The list activity closes the tablet pane when it is open. */
    val closeThreadId: String? = null,
    val search: SearchUi = SearchUi(),
    val profile: ProfileUi = ProfileUi(),
    /** Google / VPN connection. Null when healthy. Hidden in the UI while [error] is showing. */
    val healthBanner: HealthBanner.Banner? = null,
    /** False until the on-disk conversation cache has been checked. */
    val ready: Boolean = false,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val messages: MessagesRepository,
    private val auth: AuthRepository,
    private val sse: SseClient,
    private val typing: TypingTracker,
    private val themes: ThemeRepository,
    private val health: HealthRepository,
    private val inboxCache: InboxCache,
    private val prefs: AppPreferences,
    private val network: NetworkGate,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val cachedConversations = inboxCache.peekConversations()

    private val _state = MutableStateFlow(
        ConversationsUiState(
            conversations = cachedConversations.orEmpty(),
            ready = cachedConversations != null,
        ),
    )
    val state: StateFlow<ConversationsUiState> = _state
    private val failures = FailureWindow(gate = network)
    private var sseJob: Job? = null
    private var debounceJob: Job? = null
    private var pollJob: Job? = null
    private var refreshGen = 0L
    private var searchJob: Job? = null
    private var searchSeq = 0
    private var lastRemote = RemoteSearch()
    private var profileHash: String = ""
    private var profileCheckedAt: Long = 0L
    private var profileBusy = false
    private var profileRetriedEmpty = false
    private var serverListArrived = false

    init {
        watchNetwork()
        if (auth.hasSession()) {
            sse.ensureStarted()
            health.start()
            viewModelScope.launch {
                health.banner.collect { banner ->
                    _state.update { it.copy(healthBanner = banner) }
                }
            }
        }
        publishProfile()
        refreshProfile(force = false)
        viewModelScope.launch {
            val cached = runCatchingCancellable { inboxCache.readConversations() }.getOrDefault(emptyList())
            if (!serverListArrived) {
                _state.update { state ->
                    val conversations = if (state.conversations.isEmpty()) cached else state.conversations
                    state.copy(conversations = conversations, ready = true, loading = false)
                }
            } else {
                _state.update { it.copy(ready = true) }
            }
            refresh(initial = true)
        }
        viewModelScope.launch { runCatchingCancellable { themes.refresh() } }
        viewModelScope.launch {
            typing.names.collect { names -> _state.update { it.copy(typingNames = names) } }
        }
        // App-wide palettes stay on the server; this client only paints per-chat themes.
        startSse()
    }

    fun hasSession(): Boolean = auth.hasSession()

    fun origin(): String = auth.serverUrl().trim().trimEnd('/')

    fun select(id: String?) {
        _state.update { it.copy(selectedId = id) }
    }

    fun onResume() {
        if (auth.hasSession()) {
            sse.ensureStarted()
            health.refresh()
        }
        failures.onForeground()
        clearNetworkError()
        refreshProfile(force = false)
        refresh()
    }

    fun saveListScroll(state: Parcelable?) {
        savedStateHandle[KEY_LIST_SCROLL] = state
    }

    fun listScroll(): Parcelable? = savedStateHandle[KEY_LIST_SCROLL]

    /** Magnifier: open the field, or close it when it is already empty. */
    fun onSearchButton() {
        val search = _state.value.search
        if (search.open && search.query.isBlank()) closeSearch()
        else _state.update { it.copy(search = search.copy(open = true)) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchSeq++
        lastRemote = RemoteSearch()
        _state.update { it.copy(search = SearchUi()) }
    }

    fun onSearchInput(value: String) {
        _state.update { it.copy(search = it.search.copy(open = true, query = value)) }
        scheduleSearch(value)
    }

    fun submitSearch() {
        searchJob?.cancel()
        runSearch(_state.value.search.query)
    }

    /** The account photo 404s: drop the hash and fall back to the contact photo. */
    fun onProfilePhotoError() {
        if (profileHash.isBlank()) return
        profileHash = ""
        publishProfile()
    }

    private fun scheduleSearch(value: String) {
        searchJob?.cancel()
        val q = value.trim()
        if (q.length < InboxSearch.MIN_QUERY) {
            searchSeq++
            val phase = if (q.isEmpty()) SearchPhase.IDLE else SearchPhase.SHORT
            _state.update {
                it.copy(
                    search = it.search.copy(
                        phase = phase,
                        conversations = emptyList(),
                        messages = emptyList(),
                        messageError = null,
                    ),
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(q)
        }
    }

    private fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.length < InboxSearch.MIN_QUERY) return
        val seq = ++searchSeq
        _state.update { it.copy(search = it.search.copy(phase = SearchPhase.LOADING, messageError = null)) }
        viewModelScope.launch {
            val remote = runCatchingCancellable { messages.search(q) }.getOrElse { error ->
                RemoteSearch(messageError = NetErrors.friendly(error))
            }
            if (seq != searchSeq || _state.value.search.query.trim() != q) return@launch
            lastRemote = remote
            val merged = InboxSearch.merge(q, _state.value.conversations, remote.conversations, remote.messages)
            val empty = merged.conversations.isEmpty() && merged.messages.isEmpty()
            _state.update {
                it.copy(
                    search = it.search.copy(
                        phase = if (empty) SearchPhase.EMPTY else SearchPhase.RESULTS,
                        conversations = merged.conversations,
                        messages = merged.messages,
                        messageError = remote.messageError,
                    ),
                )
            }
        }
    }

    /**
     * Account photo, re-checked every 30 minutes and when the screen resumes.
     * An empty hash is tried once more after 15 seconds: the server fetches it
     * in the background on a cold start.
     */
    fun refreshProfile(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && profileCheckedAt != 0L && now - profileCheckedAt < PROFILE_TTL_MS) {
            publishProfile()
            return
        }
        if (profileBusy) return
        profileBusy = true
        viewModelScope.launch {
            try {
                val profile = runCatchingCancellable { messages.profile() }.getOrNull()
                if (profile != null) {
                    profileCheckedAt = System.currentTimeMillis()
                    if (profile.photoHash != profileHash) profileHash = profile.photoHash
                    if (profile.photoHash.isBlank() && !profileRetriedEmpty) {
                        profileRetriedEmpty = true
                        viewModelScope.launch {
                            delay(PROFILE_RETRY_MS)
                            refreshProfile(force = true)
                        }
                    }
                }
            } finally {
                profileBusy = false
                publishProfile()
            }
        }
    }

    private fun publishProfile() {
        val self = Participants.self(_state.value.conversations)
        val name = Participants.profileName(self)
        val origin = origin()
        val photo = Participants.profilePhotoUrl(origin, profileHash)
        publishedProfilePhotoUrl = photo
        _state.update {
            it.copy(
                profile = ProfileUi(
                    photoUrl = photo,
                    fallbackUrl = if (photo == null && self != null) Participants.selfAvatarUrl(origin, self) else null,
                    initial = Participants.profileInitial(name),
                    colorSeed = Participants.profileSeed(self, name),
                    name = name,
                ),
            )
        }
    }

    fun pin(conversation: ConversationDto, pinned: Boolean) {
        viewModelScope.launch {
            runCatchingCancellable { messages.pin(conversation.conversationId, pinned) }
                .onSuccess { response ->
                    _state.update { state ->
                        state.copy(
                            conversations = state.conversations.map { item ->
                                if (item.conversationId == conversation.conversationId) {
                                    item.copy(
                                        googlePinned = response.googlePinned,
                                        localPinnedAtMs = response.localPinnedAtMs,
                                    )
                                } else {
                                    item
                                }
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = NetErrors.friendly(error)) }
                }
        }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun consumeCloseThread() {
        _state.update { it.copy(closeThreadId = null) }
    }

    /** Re-read one conversation from the phone, then reload the list. */
    fun refreshConversation(conversation: ConversationDto) {
        val id = conversation.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messages.refreshConversation(id) }
                .onSuccess { message ->
                    _state.update { it.copy(notice = message) }
                    refresh()
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = NetErrors.friendly(error)) }
                }
        }
    }

    fun archive(conversation: ConversationDto, successNotice: String) {
        val id = conversation.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messages.archive(id) }
                .onSuccess {
                    prefs.clearLastSelectedConversationId(id)
                    _state.update { state ->
                        state.copy(
                            conversations = state.conversations.filter { it.conversationId != id },
                            selectedId = if (state.selectedId == id) null else state.selectedId,
                            closeThreadId = id,
                            notice = successNotice,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = NetErrors.friendly(error)) }
                }
        }
    }

    fun trash(conversation: ConversationDto) {
        val id = conversation.conversationId
        if (id.isBlank()) return
        viewModelScope.launch {
            runCatchingCancellable { messages.trash(id) }
                .onSuccess {
                    prefs.clearLastSelectedConversationId(id)
                    _state.update { state ->
                        state.copy(
                            conversations = state.conversations.filter { it.conversationId != id },
                            selectedId = if (state.selectedId == id) null else state.selectedId,
                            closeThreadId = id,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = NetErrors.friendly(error)) }
                }
        }
    }

    /**
     * Background refreshes (polling, events, list-changed) never show a spinner;
     * only first load ([initial]) and a user pull ([userInitiated]) do.
     */
    fun refresh(initial: Boolean = false, userInitiated: Boolean = false) {
        val gen = ++refreshGen
        val cacheGen = inboxCache.generation()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = initial && it.conversations.isEmpty(),
                    refreshing = userInitiated,
                )
            }
            runCatchingCancellable { withResumeRetry { messages.conversations() } }
                .onSuccess { list ->
                    failures.success()
                    if (gen != refreshGen) {
                        _state.update { it.copy(refreshing = false) }
                        return@onSuccess
                    }
                    if (!inboxCache.isCurrent(cacheGen)) {
                        _state.update { it.copy(refreshing = false) }
                        return@onSuccess
                    }
                    serverListArrived = true
                    inboxCache.saveConversations(list, cacheGen)
                    _state.update {
                        it.copy(conversations = list, loading = false, refreshing = false, error = null, ready = true)
                    }
                    publishProfile()
                    val q = _state.value.search.query.trim()
                    if (q.length >= InboxSearch.MIN_QUERY && _state.value.search.phase != SearchPhase.LOADING) {
                        val merged = InboxSearch.merge(q, list, lastRemote.conversations, lastRemote.messages)
                        val empty = merged.conversations.isEmpty() && merged.messages.isEmpty()
                        _state.update { state ->
                            state.copy(
                                search = state.search.copy(
                                    phase = if (empty) SearchPhase.EMPTY else SearchPhase.RESULTS,
                                    conversations = merged.conversations,
                                    messages = merged.messages,
                                ),
                            )
                        }
                    }
                }
                .onFailure { e ->
                    val banner = failures.failure(NetErrors.friendly(e))
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = banner, ready = true)
                    }
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

    private fun scheduleRefresh() {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch { delay(150); refresh() }
    }

    /** Web app polls the list every ~8-12 s while the stream isn't live. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                if (!sse.isLive()) { sse.ensureStarted(); refresh() }
            }
        }
    }

    private fun startSse() {
        startPolling()
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            sse.events.collect { event ->
                when (event.type) {
                    // "status" and "reconnected" also refresh the health banner (HealthRepository).
                    "conversations", "messages", "reconnected" -> scheduleRefresh()
                }
            }
        }
    }

    override fun onCleared() {
        health.stop()
        super.onCleared()
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val PROFILE_TTL_MS = 30L * 60L * 1000L
        private const val PROFILE_RETRY_MS = 15_000L
        private const val KEY_LIST_SCROLL = "conversation_list_scroll"

        /**
         * Account photo URL last written by [publishProfile].
         * The group-details self row uses this, the same URL as the app bar avatar.
         */
        @Volatile
        var publishedProfilePhotoUrl: String? = null
            private set
    }
}
