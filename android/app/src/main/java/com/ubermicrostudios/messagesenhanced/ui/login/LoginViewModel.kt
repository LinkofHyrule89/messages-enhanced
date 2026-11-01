package com.ubermicrostudios.messagesenhanced.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.data.repo.AuthRepository
import com.ubermicrostudios.messagesenhanced.data.repo.LoginResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val secret: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(serverUrl = auth.serverUrl()),
    )
    val state: StateFlow<LoginUiState> = _state

    fun onServerUrl(value: String) {
        _state.update { it.copy(serverUrl = value, error = null) }
    }

    fun onSecret(value: String) {
        _state.update { it.copy(secret = value, error = null) }
    }

    fun submit() {
        val current = _state.value
        if (current.busy) return
        if (current.serverUrl.isBlank()) {
            _state.update { it.copy(error = "Enter the server URL.") }
            return
        }
        if (current.secret.isBlank()) {
            _state.update { it.copy(error = "Enter the server secret.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            auth.saveServerUrl(current.serverUrl)
            when (val result = auth.login(current.secret)) {
                LoginResult.Success -> _state.update { it.copy(busy = false, loggedIn = true) }
                LoginResult.WrongSecret -> _state.update { it.copy(busy = false, error = "Wrong secret.") }
                LoginResult.RateLimited -> _state.update {
                    it.copy(busy = false, error = "Too many attempts. Wait a minute and try again.")
                }
                is LoginResult.Failed -> _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }
}
