package com.ubermicrostudios.messagesenhanced.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.BuildConfig
import com.ubermicrostudios.messagesenhanced.data.model.GrokStatus
import com.ubermicrostudios.messagesenhanced.data.repo.AuthRepository
import com.ubermicrostudios.messagesenhanced.data.repo.MessagesRepository
import com.ubermicrostudios.messagesenhanced.data.repo.PushRepository
import com.ubermicrostudios.messagesenhanced.data.repo.ThemeRepository
import com.ubermicrostudios.messagesenhanced.util.AppSettingsSync
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import com.ubermicrostudios.messagesenhanced.util.NetErrors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val notifications: Boolean = false,
    val hideText: Boolean = false,
    val version: String = BuildConfig.VERSION_NAME,
    val busy: Boolean = false,
    val message: String? = null,
    val loggedOut: Boolean = false,
    val appearance: String = AppSettingsSync.DARK,
    val autoGoogleRepair: Boolean = true,
    val sessionSaved: Boolean = false,
    val grokVisible: Boolean = false,
    val grokUnavailable: Boolean = false,
    val grokEnabled: Boolean = false,
    val groqEnabled: Boolean = false,
    val grokTrigger: String = "me",
    val grokKey: Boolean = false,
    val groqKey: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val push: PushRepository,
    private val themes: ThemeRepository,
    private val messages: MessagesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            serverUrl = auth.serverUrl(),
            notifications = push.notificationsEnabled(),
            hideText = push.hideMessageText(),
        ),
    )
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            themes.appearanceMode.collect { mode ->
                _state.update { it.copy(appearance = mode.ifBlank { AppSettingsSync.DARK }) }
            }
        }
        viewModelScope.launch { runCatchingCancellable { themes.ensureLoaded() } }
        loadGrok()
        loadAutoRepair()
    }

    fun onResume() {
        viewModelScope.launch { runCatchingCancellable { themes.refresh() } }
        loadGrok()
        loadAutoRepair()
    }

    fun refreshEverything() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runCatchingCancellable { messages.refreshEverything() }
                .onSuccess { text -> _state.update { it.copy(busy = false, message = text) } }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, message = NetErrors.friendly(error)) }
                }
        }
    }

    fun setGrokEnabled(enabled: Boolean) {
        val current = _state.value
        if (enabled == current.grokEnabled) return
        saveGrok(enabled, current.grokTrigger, current.groqEnabled)
    }

    fun setGroqEnabled(enabled: Boolean) {
        val current = _state.value
        if (enabled == current.groqEnabled) return
        saveGrok(current.grokEnabled, current.grokTrigger, enabled)
    }

    fun setGrokTrigger(trigger: String) {
        val current = _state.value
        if (trigger == current.grokTrigger) return
        if (trigger != "me" && trigger != "everyone") return
        saveGrok(current.grokEnabled, trigger, current.groqEnabled)
    }

    fun onServerUrl(value: String) {
        _state.update { it.copy(serverUrl = value) }
    }

    fun saveServerUrl() {
        val url = _state.value.serverUrl.trim()
        if (url.isBlank()) {
            _state.update { it.copy(message = "Enter the server URL.") }
            return
        }
        auth.saveServerUrl(url)
        _state.update {
            it.copy(
                message = "Server URL saved.",
                loggedOut = !auth.hasSession(),
            )
        }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = if (enabled) push.subscribe(_state.value.hideText) else push.unsubscribe()
            result
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            notifications = enabled,
                            message = if (enabled) "Notifications on." else "Notifications off.",
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(busy = false, notifications = push.notificationsEnabled(), message = e.message)
                    }
                }
        }
    }

    fun setHideText(hide: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            push.setHideText(hide)
                .onSuccess {
                    _state.update { it.copy(busy = false, hideText = hide, message = "Hide-text updated.") }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, hideText = push.hideMessageText(), message = e.message) }
                }
        }
    }

    fun sendTest() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            push.sendTest()
                .onSuccess { _state.update { it.copy(busy = false, message = "Test notification sent.") } }
                .onFailure { e -> _state.update { it.copy(busy = false, message = e.message) } }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            auth.logout()
            runCatchingCancellable { push.unsubscribe() }
            _state.update { it.copy(busy = false, loggedOut = true) }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun setAutoGoogleRepair(enabled: Boolean) {
        if (enabled == _state.value.autoGoogleRepair) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, autoGoogleRepair = enabled) }
            runCatchingCancellable { messages.setAutoGoogleRepair(enabled) }
                .onSuccess { on ->
                    _state.update {
                        it.copy(
                            busy = false,
                            autoGoogleRepair = on,
                            message = if (on) "Auto re-pair on." else "Auto re-pair off.",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            autoGoogleRepair = !enabled,
                            message = NetErrors.friendly(error),
                        )
                    }
                }
        }
    }

    fun forgetGoogleSession() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runCatchingCancellable { messages.forgetGoogleSession() }
                .onSuccess { pairing ->
                    _state.update {
                        it.copy(
                            busy = false,
                            sessionSaved = pairing.sessionSaved,
                            autoGoogleRepair = pairing.autoRepairEnabled,
                            message = "Saved Google session forgotten.",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, message = NetErrors.friendly(error)) }
                }
        }
    }

    private fun loadAutoRepair() {
        viewModelScope.launch {
            runCatchingCancellable { messages.autoGoogleRepairEnabled() }
                .onSuccess { on -> _state.update { it.copy(autoGoogleRepair = on) } }
        }
    }


    private fun loadGrok() {
        viewModelScope.launch {
            runCatchingCancellable { messages.grokStatus() }
                .onSuccess { status -> _state.update { it.withGrok(status).copy(busy = it.busy) } }
                .onFailure {
                    _state.update { it.copy(grokVisible = true, grokUnavailable = true) }
                }
        }
    }

    private fun saveGrok(enabled: Boolean, trigger: String, groqEnabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runCatchingCancellable { messages.saveGrok(enabled, trigger, groqEnabled) }
                .onSuccess { status ->
                    _state.update { it.withGrok(status).copy(busy = false, message = "Saved.") }
                }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, message = NetErrors.friendly(error)) }
                    loadGrok()
                }
        }
    }

    private fun SettingsUiState.withGrok(status: GrokStatus) = copy(
        grokVisible = true,
        grokUnavailable = false,
        grokEnabled = status.enabled,
        groqEnabled = status.groqEnabled,
        grokTrigger = if (status.trigger == "everyone") "everyone" else "me",
        grokKey = status.keyConfigured,
        groqKey = status.groqKeyConfigured,
    )
}
