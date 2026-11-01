package com.ubermicrostudios.messagesenhanced.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.data.model.GoogleCookiesRequest
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.PairingResponse
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.util.GoogleCookies
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import javax.inject.Inject

enum class GoogleReconnectPhase {
    WEB,
    SENDING,
    PAIRING,
    ERROR,
    SUCCESS,
}

data class GoogleReconnectUiState(
    val phase: GoogleReconnectPhase = GoogleReconnectPhase.WEB,
    val progress: String = "",
    val emoji: String = "",
    val error: String = "",
    val continueEnabled: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class GoogleReconnectViewModel @Inject constructor(
    private val api: MessagesApi,
) : ViewModel() {

    private val _state = MutableStateFlow(GoogleReconnectUiState())
    val state: StateFlow<GoogleReconnectUiState> = _state

    private var pollJob: Job? = null
    private var inFlight = false

    fun notifySignedIn() {
        if (_state.value.phase != GoogleReconnectPhase.WEB) return
        _state.update { it.copy(continueEnabled = true) }
    }

    /**
     * Filter, POST, and drop [cookies] in this call. Do not log the map.
     * Missing required names stay on the WebView with a prompt to finish signing in.
     */
    fun submit(cookies: Map<String, String>) {
        if (inFlight) return
        if (_state.value.phase != GoogleReconnectPhase.WEB) return
        inFlight = true
        viewModelScope.launch {
            val body = GoogleCookies.filterAllowed(cookies)
            if (!GoogleCookies.hasRequired(body)) {
                inFlight = false
                _state.update {
                    it.copy(error = "Finish signing in to Google, then tap Continue.")
                }
                return@launch
            }
            _state.update {
                it.copy(
                    phase = GoogleReconnectPhase.SENDING,
                    progress = "Sending to your server…",
                    error = "",
                    continueEnabled = false,
                    emoji = "",
                    done = false,
                )
            }
            val result = runCatchingCancellable {
                api.submitGoogleCookies(GoogleCookiesRequest(body))
            }
            result
                .onSuccess { response ->
                    applyPairing(response)
                    if (_state.value.phase != GoogleReconnectPhase.SUCCESS &&
                        _state.value.phase != GoogleReconnectPhase.ERROR
                    ) {
                        startPoll()
                    }
                }
                .onFailure { error ->
                    inFlight = false
                    _state.update {
                        it.copy(
                            phase = GoogleReconnectPhase.ERROR,
                            error = serverErrorMessage(error),
                            progress = "",
                        )
                    }
                }
        }
    }

    fun retry() {
        pollJob?.cancel()
        pollJob = null
        inFlight = false
        _state.value = GoogleReconnectUiState()
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + POLL_MAX_MS
            while (isActive) {
                val phase = _state.value.phase
                if (phase == GoogleReconnectPhase.SUCCESS ||
                    phase == GoogleReconnectPhase.ERROR ||
                    phase == GoogleReconnectPhase.WEB
                ) {
                    return@launch
                }
                if (System.currentTimeMillis() >= deadline) {
                    _state.update {
                        it.copy(
                            phase = GoogleReconnectPhase.ERROR,
                            error = "Pairing timed out. Try again.",
                            progress = "",
                        )
                    }
                    return@launch
                }
                delay(POLL_MS)
                val pairing = runCatchingCancellable { api.appPairing() }.getOrNull() ?: continue
                applyPairing(pairing)
            }
        }
    }

    private fun applyPairing(response: PairingResponse) {
        val google = response.google
        if (google?.connected == true && !google.needsPairing && !google.needsRepair) {
            markSuccess()
            return
        }
        val pairing = response.pairing
        when (pairing?.state) {
            "starting" -> _state.update {
                it.copy(
                    phase = GoogleReconnectPhase.PAIRING,
                    progress = "Starting pairing…",
                    emoji = "",
                    error = "",
                )
            }
            "emoji" -> _state.update {
                it.copy(
                    phase = GoogleReconnectPhase.PAIRING,
                    progress = pairing.message.ifBlank { "Tap this on your phone" },
                    emoji = pairing.emoji,
                    error = "",
                )
            }
            "success" -> markSuccess()
            "error" -> {
                pollJob?.cancel()
                _state.update {
                    it.copy(
                        phase = GoogleReconnectPhase.ERROR,
                        error = pairing.message.ifBlank { "Pairing failed." },
                        progress = "",
                    )
                }
            }
            else -> {
                if (_state.value.phase == GoogleReconnectPhase.SENDING) {
                    _state.update {
                        it.copy(
                            phase = GoogleReconnectPhase.PAIRING,
                            progress = "Starting pairing…",
                            emoji = "",
                            error = "",
                        )
                    }
                }
            }
        }
    }

    private fun markSuccess() {
        pollJob?.cancel()
        _state.update {
            it.copy(
                phase = GoogleReconnectPhase.SUCCESS,
                progress = "Paired. Connecting…",
                error = "",
                done = true,
            )
        }
    }

    private fun serverErrorMessage(error: Throwable): String {
        if (error is HttpException) {
            val raw = runCatchingCancellable {
                error.response()?.errorBody()?.string().orEmpty()
            }.getOrNull().orEmpty()
            val parsed = parseServerError(raw)
            if (!parsed.isNullOrBlank()) return parsed
        }
        return error.message?.ifBlank { null } ?: "Request failed."
    }

    companion object {
        const val POLL_MS = 1_500L
        const val POLL_MAX_MS = 5 * 60 * 1_000L

        fun parseServerError(raw: String): String? {
            if (raw.isBlank()) return null
            val element = runCatching { JsonConfig.json.parseToJsonElement(raw) }.getOrNull() ?: return null
            val obj = element as? JsonObject ?: return null
            when (val errorEl = obj["error"]) {
                is JsonPrimitive -> errorEl.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                is JsonObject -> errorEl["message"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                else -> {}
            }
            return obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }
}
