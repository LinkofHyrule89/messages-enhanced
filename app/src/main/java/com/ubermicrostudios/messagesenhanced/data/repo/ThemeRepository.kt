package com.ubermicrostudios.messagesenhanced.data.repo

import com.ubermicrostudios.messagesenhanced.data.model.ChatThemeDto
import com.ubermicrostudios.messagesenhanced.data.model.JsonConfig
import com.ubermicrostudios.messagesenhanced.data.model.ThemePutBody
import com.ubermicrostudios.messagesenhanced.data.model.ThemePutRequest
import com.ubermicrostudios.messagesenhanced.data.model.WallpaperCategoryDto
import com.ubermicrostudios.messagesenhanced.data.net.BaseUrlProvider
import com.ubermicrostudios.messagesenhanced.data.net.MessagesApi
import com.ubermicrostudios.messagesenhanced.util.AppSettingsSync
import com.ubermicrostudios.messagesenhanced.util.runCatchingCancellable
import com.ubermicrostudios.messagesenhanced.util.ThemeColors
import com.ubermicrostudios.messagesenhanced.util.ThemePalettes
import com.ubermicrostudios.messagesenhanced.util.ThemeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ChatAppearance(
    val colors: ThemeColors = ThemeColors.Default,
    val backgroundUrl: String? = null,
)

/**
 * Per-conversation themes come from `GET /api/app/themes`. The app-wide palette
 * and appearance are `theme` and `appearance` on `GET`/`PUT /api/app/settings`,
 * the same fields the web app syncs. A conversation's own palette still wins
 * inside that thread. Older builds stored the app-wide palette as conversation
 * id `native-app`; the first successful settings read migrates that and deletes it.
 */
@Singleton
class ThemeRepository @Inject constructor(
    private val api: MessagesApi,
    private val baseUrl: BaseUrlProvider,
) {
    private val themes = HashMap<String, ChatThemeDto>()
    private val wallpaperUrls = HashMap<String, String>()
    private val wallpaperCategories = ArrayList<WallpaperCategoryDto>()

    private val _appColors = MutableStateFlow(ThemeColors.Default)
    val appColors: StateFlow<ThemeColors> = _appColors

    private val _paletteIds = MutableStateFlow<List<String>>(emptyList())
    val paletteIds: StateFlow<List<String>> = _paletteIds

    private val _selectedPalette = MutableStateFlow("")
    val selectedPalette: StateFlow<String> = _selectedPalette

    private val _appearance = MutableStateFlow(AppSettingsSync.DARK)
    val appearanceMode: StateFlow<String> = _appearance

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    @Volatile private var loaded = false

    /** Drops in-memory chat themes so a later account does not paint the previous one. */
    fun clear() {
        synchronized(this) {
            themes.clear()
            wallpaperUrls.clear()
            wallpaperCategories.clear()
            loaded = false
        }
        _appColors.value = ThemeColors.Default
        _paletteIds.value = emptyList()
        _selectedPalette.value = ""
        _appearance.value = AppSettingsSync.DARK
        _revision.value = _revision.value + 1
    }

    suspend fun refresh() {
        val response = api.themes()
        synchronized(this) {
            themes.clear()
            themes.putAll(response.themes)
            loaded = true
        }
        _paletteIds.value = response.palettes
        val settingsResult = runCatchingCancellable { api.appSettings() }
        if (settingsResult.isFailure) {
            publishLegacy()
        } else {
            applySettings(settingsResult.getOrThrow().settings)
        }
        _revision.value = _revision.value + 1
        val needsWallpaper = response.themes.values.any { it.wallpaper.isNotBlank() && it.custom.isBlank() }
        if (needsWallpaper && synchronized(this) { wallpaperUrls.isEmpty() }) {
            runCatchingCancellable { ensureWallpapers() }
        }
    }

    fun conversationTheme(conversationId: String): ChatThemeDto? =
        synchronized(this) { themes[conversationId]?.copy() }

    suspend fun ensureWallpapers(): List<WallpaperCategoryDto> {
        val cached = synchronized(this) { if (wallpaperCategories.isEmpty()) null else wallpaperCategories.toList() }
        if (cached != null) return cached
        val body = api.wallpapers()
        synchronized(this) {
            wallpaperCategories.clear()
            wallpaperCategories.addAll(body.categories)
            indexWallpapers(body.categories)
        }
        _revision.value = _revision.value + 1
        return synchronized(this) { wallpaperCategories.toList() }
    }

    /**
     * PUT /api/app/theme, or DELETE when palette, color, wallpaper, and custom are all empty.
     * [custom] keeps an already uploaded photo.
     */
    suspend fun saveConversationTheme(
        conversationId: String,
        palette: String,
        color: String,
        wallpaper: String,
        custom: Boolean,
    ) {
        val empty = palette.isBlank() && color.isBlank() && wallpaper.isBlank() && !custom
        if (empty) {
            val response = api.deleteTheme(conversationId)
            if (!response.isSuccessful && response.code() != 404) {
                val code = response.code()
                val raw = response.errorBody()?.string().orEmpty()
                response.body()?.close()
                if (code == 401) error("login required")
                val parsed = runCatchingCancellable {
                    JsonConfig.json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                error(parsed?.takeIf { it.isNotBlank() } ?: "Couldn't save the theme (HTTP $code).")
            }
            response.body()?.close()
            response.errorBody()?.close()
            synchronized(this) { themes.remove(conversationId) }
        } else {
            val saved = try {
                api.putTheme(
                    ThemePutRequest(
                        conversationId,
                        ThemePutBody(
                            palette = palette,
                            color = color,
                            wallpaper = wallpaper,
                            custom = custom,
                        ),
                    ),
                )
            } catch (error: Throwable) {
                parseThemeError(error)
            }
            synchronized(this) { themes[conversationId] = saved.theme }
            if (saved.theme.wallpaper.isNotBlank() && synchronized(this) { wallpaperUrls[saved.theme.wallpaper] } == null) {
                runCatchingCancellable { ensureWallpapers() }
            }
        }
        _revision.value = _revision.value + 1
    }

    /** GET /api/app/theme. Stores the server copy and returns null when the conversation has no theme. */
    suspend fun fetchConversationTheme(conversationId: String): ChatThemeDto? {
        val response = try {
            api.theme(conversationId)
        } catch (error: Throwable) {
            parseThemeError(error)
        }
        synchronized(this) {
            if (response.set) themes[conversationId] = response.theme else themes.remove(conversationId)
        }
        _revision.value = _revision.value + 1
        return if (response.set) synchronized(this) { themes[conversationId]?.copy() } else null
    }

    /**
     * Apply a theme-screen draft. A staged photo is POST /api/app/theme/background, then PUT
     * with custom true (the upload already stored the file). An empty draft DELETEs.
     * A failed PUT after a successful upload still keeps the uploaded theme, matching the web client.
     */
    suspend fun applyDraft(conversationId: String, draft: ThemeScreen.ThemeFields) {
        when (val plan = ThemeScreen.applyPlan(draft)) {
            is ThemeScreen.ApplyPlan.Upload -> {
                val bytes = withContext(Dispatchers.IO) {
                    val file = File(draft.pendingPath)
                    if (!file.isFile) error(ThemeScreen.PHOTO_UNREADABLE)
                    file.readBytes()
                }
                val mime = draft.pendingMime.ifBlank { "image/jpeg" }
                val uploaded = try {
                    api.uploadThemeBackground(
                        conversationId.toRequestBody("text/plain".toMediaType()),
                        MultipartBody.Part.createFormData(
                            "file",
                            ThemeScreen.uploadName(draft.pendingName),
                            bytes.toRequestBody(mime.toMediaType()),
                        ),
                    )
                } catch (error: Throwable) {
                    parseThemeError(error)
                }
                synchronized(this) { themes[conversationId] = uploaded.theme }
                _revision.value = _revision.value + 1
                val saved = try {
                    api.putTheme(
                        ThemePutRequest(
                            conversationId,
                            ThemePutBody(
                                palette = plan.palette,
                                color = plan.color,
                                wallpaper = "",
                                custom = true,
                            ),
                        ),
                    )
                } catch (error: Throwable) {
                    parseThemeError(error)
                }
                synchronized(this) { themes[conversationId] = saved.theme }
                _revision.value = _revision.value + 1
            }
            is ThemeScreen.ApplyPlan.Put ->
                saveConversationTheme(conversationId, plan.palette, plan.color, plan.wallpaper, plan.custom)
            ThemeScreen.ApplyPlan.Delete ->
                saveConversationTheme(conversationId, "", "", "", false)
        }
    }

    suspend fun ensureLoaded() {
        if (!loaded) runCatchingCancellable { refresh() }
    }

    fun appearance(conversationId: String): ChatAppearance {
        val own = synchronized(this) { themes[conversationId] }
        val colors = if (ThemePalettes.hasOwnColors(own)) ThemePalettes.colors(own) else ThemeColors.Default
        return ChatAppearance(
            colors = colors,
            backgroundUrl = own?.let { backgroundUrl(conversationId, it) },
        )
    }

    suspend fun setAppPalette(paletteId: String) {
        val current = api.appSettings().settings
        val appearance = current.optString(AppSettingsSync.APPEARANCE).ifBlank { AppSettingsSync.DARK }
        val merged = merge(
            current,
            mapOf(
                AppSettingsSync.THEME to paletteId,
                AppSettingsSync.APPEARANCE to appearance,
            ),
        )
        val saved = api.putSettings(merged)
        dropLegacy()
        publish(
            saved.settings.optString(AppSettingsSync.THEME),
            saved.settings.optString(AppSettingsSync.APPEARANCE).ifBlank { AppSettingsSync.DARK },
        )
        _revision.value = _revision.value + 1
    }

    private suspend fun applySettings(settings: JsonObject) {
        val legacy = synchronized(this) { themes[AppSettingsSync.LEGACY_CONVERSATION] }
        val plan = AppSettingsSync.plan(
            serverHasTheme = settings.containsKey(AppSettingsSync.THEME),
            serverTheme = settings.optString(AppSettingsSync.THEME),
            serverHasAppearance = settings.containsKey(AppSettingsSync.APPEARANCE),
            serverAppearance = settings.optString(AppSettingsSync.APPEARANCE),
            legacyPresent = legacy != null,
            legacyPalette = legacy?.palette.orEmpty(),
        )
        var committed = plan.upload.isEmpty()
        if (plan.upload.isNotEmpty()) {
            committed = runCatchingCancellable { api.putSettings(merge(settings, plan.upload)) }.isSuccess
        }
        if (committed && plan.deleteLegacy) dropLegacy()
        publish(plan.theme, plan.appearance)
    }

    private suspend fun dropLegacy() {
        val response = runCatchingCancellable { api.deleteTheme(AppSettingsSync.LEGACY_CONVERSATION) }.getOrNull() ?: return
        response.body()?.close()
        response.errorBody()?.close()
        if (response.isSuccessful || response.code() == 404) {
            synchronized(this) { themes.remove(AppSettingsSync.LEGACY_CONVERSATION) }
        }
    }

    /** Settings could not be read (older server). Show the legacy palette and leave it in place. */
    private fun publishLegacy() {
        val theme = synchronized(this) { themes[AppSettingsSync.LEGACY_CONVERSATION] }
        publish(theme?.palette.orEmpty(), AppSettingsSync.DARK)
    }

    private fun publish(theme: String, appearance: String) {
        _selectedPalette.value = theme
        _appearance.value = appearance.ifBlank { AppSettingsSync.DARK }
        _appColors.value = if (theme.isBlank()) {
            ThemeColors.Default
        } else {
            ThemePalettes.colors(ChatThemeDto(palette = theme))
        }
    }

    private fun merge(base: JsonObject, extra: Map<String, String>): JsonObject {
        val map = base.toMutableMap()
        extra.forEach { (key, value) -> map[key] = JsonPrimitive(value) }
        return JsonObject(map)
    }

    private fun JsonObject.optString(key: String): String = this[key].jsonString()

    private fun JsonElement?.jsonString(): String {
        val prim = this as? JsonPrimitive ?: return ""
        return prim.contentOrNull ?: ""
    }

    private fun parseThemeError(error: Throwable): Nothing {
        if (error is CancellationException) throw error
        if (error is HttpException) {
            if (error.code() == 401) kotlin.error("login required")
            val raw = runCatchingCancellable { error.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            val parsed = runCatchingCancellable {
                JsonConfig.json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!parsed.isNullOrBlank()) kotlin.error(parsed)
        }
        val message = error.message.orEmpty()
        kotlin.error(message.ifBlank { "request failed" })
    }

    private fun indexWallpapers(categories: List<WallpaperCategoryDto>) {
        for (category in categories) {
            for (item in category.items) {
                val path = item.fullUrl.ifBlank { if (item.file.isBlank()) "" else "/wallpapers/${item.file}" }
                if (item.id.isNotBlank() && path.isNotBlank()) wallpaperUrls[item.id] = path
            }
        }
    }

    fun backgroundUrl(conversationId: String, theme: ChatThemeDto): String? {
        val origin = baseUrl.origin()
        if (theme.custom.isNotBlank()) {
            val id = URLEncoder.encode(conversationId, Charsets.UTF_8.name())
            val version = URLEncoder.encode(theme.custom, Charsets.UTF_8.name())
            return "$origin/api/app/theme/background?conversation_id=$id&v=$version"
        }
        if (theme.wallpaper.isBlank()) return null
        val path = synchronized(this) { wallpaperUrls[theme.wallpaper] } ?: return null
        return if (path.startsWith("http")) path else origin + path
    }
}
