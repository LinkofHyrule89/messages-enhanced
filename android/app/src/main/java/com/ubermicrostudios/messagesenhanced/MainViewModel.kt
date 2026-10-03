package com.ubermicrostudios.messagesenhanced

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.net.CookieLogic
import com.ubermicrostudios.messagesenhanced.net.ServerAddress
import com.ubermicrostudios.messagesenhanced.net.ServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, WEB }
enum class StatusKind { NONE, INFO, OK, ERROR }

/**
 * UI state. Holds cookie NAMES only; cookie values and the server password are
 * never put in UI state.
 */
data class UiState(
    val screen: Screen = Screen.HOME,
    val address: String = ServerAddress.DEFAULT,
    val passwordSaved: Boolean = false,
    val busy: Boolean = false,
    val found: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val extras: List<String> = emptyList(),
    val checked: Boolean = false,
    val status: String = "",
    val statusKind: StatusKind = StatusKind.NONE,
    val googleBlocked: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = SecurePrefs(app)
    private val client = ServerClient()

    private val _state = MutableStateFlow(
        UiState(address = prefs.serverAddress, passwordSaved = prefs.hasPassword)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setAddress(v: String) = _state.update { it.copy(address = v) }

    /** Validate + persist the address and (if typed) a new password. */
    fun saveSettings(newPassword: String): Boolean {
        val norm = ServerAddress.normalize(_state.value.address)
        if (!norm.ok) {
            setStatus(norm.error ?: "Invalid address.", StatusKind.ERROR)
            return false
        }
        val keep = keepAddress(_state.value.address, norm.origin)
        prefs.serverAddress = keep
        if (newPassword.isNotEmpty()) prefs.savePassword(newPassword)
        _state.update {
            it.copy(address = keep, passwordSaved = prefs.hasPassword)
        }
        setStatus("Settings saved.", StatusKind.OK)
        return true
    }

    fun forgetPassword() {
        prefs.clearPassword()
        _state.update { it.copy(passwordSaved = false) }
        setStatus("Server password removed from this phone.", StatusKind.INFO)
    }

    fun openWeb() = _state.update { it.copy(screen = Screen.WEB, googleBlocked = false) }
    fun closeWeb() = _state.update { it.copy(screen = Screen.HOME) }
    fun setGoogleBlocked(blocked: Boolean) = _state.update { it.copy(googleBlocked = blocked) }

    /** Signs this app's WebView out of Google (clears only this app's cookies). */
    fun clearGoogleSession() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { cm.flush() }
        android.webkit.WebStorage.getInstance().deleteAllData()
        _state.update { it.copy(found = emptyList(), missing = emptyList(), extras = emptyList(), checked = false) }
        setStatus("Signed out of Google in this app.", StatusKind.INFO)
    }

    /** Only shows which cookie names are present; sends nothing. */
    fun checkCookies() {
        val map = WebViewCookies.collect()
        showNames(map)
        val st = CookieLogic.requiredStatus(map)
        if (st.missing.isEmpty()) setStatus("All required cookies found.", StatusKind.OK)
        else setStatus(missingHint(st.missing), StatusKind.ERROR)
    }

    fun send() {
        if (_state.value.busy) return
        val norm = ServerAddress.normalize(_state.value.address)
        if (!norm.ok) {
            setStatus(norm.error ?: "Invalid address.", StatusKind.ERROR); return
        }
        val secret = prefs.readPassword()
        if (secret.isNullOrEmpty()) {
            setStatus("Enter and save the server password first.", StatusKind.ERROR); return
        }
        prefs.serverAddress = keepAddress(_state.value.address, norm.origin)

        // Cookie values stay in this local only; the UI sees names.
        val map = WebViewCookies.collect()
        showNames(map)
        val st = CookieLogic.requiredStatus(map)
        if (st.missing.isNotEmpty()) {
            setStatus(missingHint(st.missing), StatusKind.ERROR); return
        }

        _state.update { it.copy(busy = true, address = norm.origin) }
        setStatus("Sending to ${norm.origin}…", StatusKind.INFO)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { client.send(norm.origin, secret, map) }
            when (outcome) {
                is ServerClient.Outcome.Saved -> setStatus(outcome.message, StatusKind.OK)
                is ServerClient.Outcome.WrongPassword ->
                    setStatus("Wrong server password. Enter it again and tap Save.", StatusKind.ERROR)
                is ServerClient.Outcome.NeedLogin ->
                    setStatus("The server did not accept the login session. Check the password and try again.", StatusKind.ERROR)
                is ServerClient.Outcome.Unreachable -> setStatus(outcome.message, StatusKind.ERROR)
                is ServerClient.Outcome.Error -> setStatus(outcome.message, StatusKind.ERROR)
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun showNames(map: Map<String, String>) {
        val st = CookieLogic.requiredStatus(map)
        val extras = CookieLogic.presentNames(map).filter { it !in CookieLogic.REQUIRED }
        _state.update { it.copy(found = st.found, missing = st.missing, extras = extras, checked = true) }
    }

    private fun missingHint(missing: List<String>): String {
        val hint = if ("OSID" in missing && missing.size == 1)
            "OSID appears after Google Messages for web finishes loading. Open it, wait for the page, then try again."
        else
            "Tap \"Sign in to Google Messages\" and sign in to your Google account first."
        return "Missing: ${missing.joinToString(", ")}. $hint"
    }

    private fun setStatus(msg: String, kind: StatusKind) =
        _state.update { it.copy(status = msg, statusKind = kind) }

    /**
     * The address to store: the web app's start URL, not just the origin, so
     * this screen never strips the path the setup screen found. Same server
     * as saved: keep the saved start URL. Otherwise: what was typed, with the
     * app's start path added.
     */
    private fun keepAddress(typed: String, origin: String): String {
        val saved = prefs.serverAddress
        if (saved.isNotBlank() && ServerAddress.normalize(saved).origin == origin) return saved
        return ServerAddress.startUrl(origin, ServerAddress.basePath(typed))
    }
}
