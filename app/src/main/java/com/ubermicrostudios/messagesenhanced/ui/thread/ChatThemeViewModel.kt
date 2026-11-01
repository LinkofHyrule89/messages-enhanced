package com.ubermicrostudios.messagesenhanced.ui.thread

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ubermicrostudios.messagesenhanced.data.model.ChatThemeDto
import com.ubermicrostudios.messagesenhanced.data.model.WallpaperCategoryDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.repo.ThemeRepository
import com.ubermicrostudios.messagesenhanced.util.ThemeScreen
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ChatThemeViewModel @Inject constructor(
    private val themes: ThemeRepository,
    private val baseUrl: BaseUrlProvider,
) : ViewModel() {
    data class Ui(
        val conversationId: String = "",
        val contactName: String = "",
        val isGroup: Boolean = false,
        val saved: ThemeScreen.ThemeFields = ThemeScreen.ThemeFields(),
        val draft: ThemeScreen.ThemeFields = ThemeScreen.ThemeFields(),
        val inCategory: Boolean = false,
        val categoryId: String = "",
        val categoryName: String = "",
        val catBefore: ThemeScreen.ThemeFields = ThemeScreen.ThemeFields(),
        val busy: Boolean = false,
        val done: Boolean = false,
        val wallpapers: List<WallpaperCategoryDto>? = null,
        val wallpaperError: String = "",
    )

    sealed class Effect {
        data class Toast(val message: String) : Effect()
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private var started = false
    private var importGen = 0
    private val stagedPaths = LinkedHashSet<String>()
    var saveAnnounced = false

    fun origin(): String = baseUrl.origin()

    fun backgroundUrl(conversationId: String, theme: ChatThemeDto): String? =
        themes.backgroundUrl(conversationId, theme)

    fun start(conversationId: String, contactName: String, isGroup: Boolean) {
        if (started) return
        started = true
        val fields = ThemeScreen.fromDto(themes.conversationTheme(conversationId))
        _ui.value = Ui(
            conversationId = conversationId,
            contactName = contactName,
            isGroup = isGroup,
            saved = fields,
            draft = fields,
        )
        viewModelScope.launch {
            runCatchingCancellable { themes.ensureWallpapers() }
                .onSuccess { list -> _ui.update { it.copy(wallpapers = list, wallpaperError = "") } }
                .onFailure { error ->
                    _ui.update { it.copy(wallpaperError = ThemeScreen.wallpaperError(error.message.orEmpty())) }
                }
        }
        viewModelScope.launch {
            runCatchingCancellable { themes.fetchConversationTheme(conversationId) }
                .onSuccess { remote ->
                    _ui.update { state ->
                        if (ThemeScreen.key(state.draft) != ThemeScreen.key(state.saved)) state
                        else {
                            val fresh = ThemeScreen.fromDto(remote)
                            state.copy(saved = fresh, draft = fresh)
                        }
                    }
                }
        }
    }

    /** True when the activity should stay open (category back, or a save still running). */
    fun back(): Boolean {
        val state = _ui.value
        if (state.busy) return true
        if (!state.inCategory) return false
        _ui.value = state.copy(
            inCategory = false,
            draft = ThemeScreen.finishCategory(state.draft, state.catBefore, keepPick = false),
            categoryId = "",
            categoryName = "",
        )
        return true
    }

    fun openCategory(id: String, name: String) {
        val state = _ui.value
        if (state.busy || state.inCategory) return
        _ui.value = state.copy(inCategory = true, categoryId = id, categoryName = name, catBefore = state.draft)
    }

    fun selectDefault() = edit { ThemeScreen.selectDefault(it) }

    fun selectPalette(id: String) = edit { ThemeScreen.selectPalette(it, id) }

    fun selectWallpaper(id: String) = edit { ThemeScreen.selectWallpaper(it, id) }

    fun removePhoto() = edit { ThemeScreen.removePhoto(it) }

    fun reset() = edit { ThemeScreen.resetDraft() }

    fun importPhoto(context: Context, uri: Uri) {
        if (_ui.value.busy) return
        val gen = ++importGen
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { ThemePhotoImport.import(context, uri) }
            if (gen != importGen) {
                if (outcome is ThemePhotoImport.Result.Ready) runCatchingCancellable { File(outcome.path).delete() }
                return@launch
            }
            when (outcome) {
                is ThemePhotoImport.Result.Rejected -> _effects.tryEmit(Effect.Toast(outcome.message))
                is ThemePhotoImport.Result.Ready -> {
                    stagedPaths.add(outcome.path)
                    edit { ThemeScreen.stagePhoto(it, outcome.path, outcome.mime, outcome.name) }
                }
            }
        }
    }

    fun primary() {
        val state = _ui.value
        if (state.busy) return
        if (state.inCategory) {
            if (ThemeScreen.key(state.draft) == ThemeScreen.key(state.catBefore)) return
            _ui.value = state.copy(
                inCategory = false,
                draft = ThemeScreen.finishCategory(state.draft, state.catBefore, keepPick = true),
            )
            return
        }
        if (!ThemeScreen.applyEnabled(false, false, state.draft, state.saved, state.catBefore)) return
        val draft = state.draft
        _ui.value = state.copy(busy = true)
        viewModelScope.launch {
            runCatchingCancellable { themes.applyDraft(state.conversationId, draft) }
                .onSuccess { _ui.update { it.copy(busy = false, done = true) } }
                .onFailure { error ->
                    _ui.update { it.copy(busy = false) }
                    val toast = ThemeScreen.saveToast(error.message.orEmpty())
                    if (toast != null) _effects.tryEmit(Effect.Toast(toast))
                }
        }
    }

    private fun edit(block: (ThemeScreen.ThemeFields) -> ThemeScreen.ThemeFields) {
        val state = _ui.value
        if (state.busy) return
        _ui.value = state.copy(draft = block(state.draft))
    }

    override fun onCleared() {
        stagedPaths.forEach { path -> runCatchingCancellable { File(path).delete() } }
        stagedPaths.clear()
        super.onCleared()
    }
}
