package com.ubermicrostudios.messagesenhanced.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.data.model.ContactsResponse
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.repo.AuthRepository
import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import com.ubermicrostudios.messagesenhanced.util.NetErrors
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import com.ubermicrostudios.messagesenhanced.util.NewChat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewChatUiState(
    val query: String = "",
    val group: Boolean = false,
    val chips: List<ContactDto> = emptyList(),
    val data: ContactsResponse? = null,
    val loading: Boolean = true,
    val error: String = "",
    val busy: Boolean = false,
    val rows: List<NewChat.Row> = emptyList(),
) {
    val title: String get() = if (group) "New group conversation" else "New conversation"
    val groupLabel: String get() = if (group) "Cancel group" else "Start group conversation"
    val nextVisible: Boolean get() = group
    val nextEnabled: Boolean get() = !busy && chips.size >= 2
    val nextLabel: String get() = when {
        busy -> "Opening…"
        chips.size >= 2 -> "Next (${chips.size})"
        else -> "Next"
    }
}

sealed interface NewChatEffect {
    data class Open(val conversation: ConversationDto) : NewChatEffect
    data class Toast(val text: String) : NewChatEffect
}

@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val messages: MessagesRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewChatUiState())
    val state: StateFlow<NewChatUiState> = _state
    private val _effects = MutableSharedFlow<NewChatEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<NewChatEffect> = _effects.asSharedFlow()

    init {
        load()
    }

    fun origin(): String = auth.serverUrl().trim().trimEnd('/')

    fun onQuery(value: String) {
        _state.update { it.copy(query = value).withRows() }
    }

    fun toggleGroup() {
        _state.update {
            val group = !it.group
            it.copy(group = group, chips = if (group) it.chips else emptyList()).withRows()
        }
    }

    fun pick(contact: ContactDto) {
        val current = _state.value
        if (current.busy) return
        if (current.group) {
            toggleChip(contact)
            return
        }
        if (contact.conversationId.isNotBlank()) {
            finish(contact.conversationId, contact.displayName)
            return
        }
        start(listOf(contact.phone), contact.displayName)
    }

    fun pickTyped(query: String) {
        val current = _state.value
        if (current.busy) return
        if (current.group) {
            if (!NewChat.isChip(ContactDto(number = query), current.chips)) {
                _state.update { it.copy(chips = it.chips + ContactDto(name = query, number = query), query = "").withRows() }
            } else {
                _state.update { it.copy(query = "").withRows() }
            }
            return
        }
        val key = NewChat.contactKey(query)
        val known = current.data?.all?.firstOrNull { NewChat.contactKey(it.phone) == key }
        if (known != null) {
            pick(known)
            return
        }
        start(listOf(query), query)
    }

    fun next() {
        val chips = _state.value.chips
        if (chips.size < 2 || _state.value.busy) return
        start(chips.map { it.phone }, chips.joinToString(", ") { it.displayName })
    }

    fun removeChip(contact: ContactDto) {
        toggleChip(contact)
    }

    private fun toggleChip(contact: ContactDto) {
        _state.update { state ->
            val key = NewChat.contactKey(contact.phone)
            val chips = if (state.chips.any { NewChat.contactKey(it.phone) == key }) {
                state.chips.filter { NewChat.contactKey(it.phone) != key }
            } else {
                state.chips + contact
            }
            state.copy(chips = chips, query = "").withRows()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "").withRows() }
            runCatchingCancellable { messages.contacts() }
                .onSuccess { data -> _state.update { it.copy(data = data, loading = false, error = "").withRows() } }
                .onFailure { error ->
                    _state.update {
                        it.copy(loading = false, error = if (it.data == null) NetErrors.friendly(error) else "").withRows()
                    }
                }
        }
    }

    private fun start(numbers: List<String>, name: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true).withRows() }
        viewModelScope.launch {
            _effects.emit(NewChatEffect.Toast("Opening conversation…"))
            runCatchingCancellable { messages.startConversation(numbers) }
                .onSuccess { result ->
                    _state.update { it.copy(busy = false).withRows() }
                    finish(result.conversationId, result.name.ifBlank { name }, result.isGroup)
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false).withRows() }
                    val text = NetErrors.friendly(error)
                    if (text != "login required") {
                        _effects.emit(NewChatEffect.Toast("Couldn't open the conversation: $text"))
                    }
                }
        }
    }

    private fun finish(id: String, name: String, isGroup: Boolean = false) {
        if (id.isBlank()) return
        viewModelScope.launch {
            _effects.emit(
                NewChatEffect.Open(
                    ConversationDto(conversationId = id, name = name, isGroup = isGroup),
                ),
            )
        }
    }

    private fun NewChatUiState.withRows(): NewChatUiState = copy(
        rows = NewChat.rows(query, data, group, chips, loading, error),
    )
}
