package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ChatThemeDto

/**
 * Chat theme panel rules, matching the web app's openChatTheme / renderChatTheme.
 * The screen keeps a draft until Apply. Category back restores the draft from
 * before that category was opened; Next keeps the pick and returns to the main page.
 */
object ThemeScreen {
    const val TITLE = "Chat theme"
    const val CATEGORY_HINT = "Tap a photo to preview it"
    const val DEVICE_LINE = "Shows on every device you use Messages on"
    const val PREVIEW_THEM = "Want to grab dinner after work?"
    const val PREVIEW_ME = "Yes, that sounds great!"
    const val PREVIEW_TIME = "4:35 PM"
    const val PREVIEW_HINT = "Message"
    const val PREVIEW_SENDER = "Alex"
    const val APPLY = "Apply"
    const val NEXT = "Next"
    const val SAVING = "Saving\u2026"
    const val SAVED = "Chat theme saved"
    const val WALLPAPER_LOADING = "Loading wallpapers\u2026"
    const val PHOTO_TOO_LARGE = "That photo is too large (15 MB max)"
    const val PHOTO_TYPE = "Use a JPEG, PNG, GIF or WebP photo"
    const val PHOTO_UNREADABLE = "Couldn't read that photo"
    const val MAX_UPLOAD_BYTES = 15 * 1024 * 1024

    /**
     * SweepGradient's 0 is at 3 o'clock. Rotating 180° puts 0 at 9 o'clock, which is
     * where CSS `conic-gradient(from -90deg, …)` starts. The top half is [light],
     * the bottom-right quadrant is [them], and the bottom-left quadrant is [me].
     */
    const val SWATCH_ROTATION_DEGREES = 180f

    private val HEX = Regex("#[0-9a-fA-F]{6}")
    private val PHOTO_MIME = Regex("^image/(jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE)

    data class ThemeFields(
        val palette: String = "",
        val color: String = "",
        val wallpaper: String = "",
        val custom: String = "",
        val pendingPath: String = "",
        val pendingMime: String = "",
        val pendingName: String = "",
    )

    sealed class ApplyPlan {
        data class Upload(val palette: String, val color: String) : ApplyPlan()
        data class Put(
            val palette: String,
            val color: String,
            val wallpaper: String,
            val custom: Boolean,
        ) : ApplyPlan()
        data object Delete : ApplyPlan()
    }

    data class SwatchSegment(val role: String, val start: Float, val end: Float)

    fun fromDto(theme: ChatThemeDto?): ThemeFields {
        if (theme == null) return ThemeFields()
        return ThemeFields(
            palette = theme.palette,
            color = theme.color,
            wallpaper = theme.wallpaper,
            custom = theme.custom,
        )
    }

    fun toDto(fields: ThemeFields): ChatThemeDto = ChatThemeDto(
        palette = fields.palette,
        color = fields.color,
        wallpaper = fields.wallpaper,
        custom = fields.custom,
    )

    /** `[palette, palette ? "" : color, wallpaper, custom, pending].join("|")`. */
    fun key(fields: ThemeFields): String {
        val color = if (fields.palette.isNotEmpty()) "" else fields.color
        return listOf(fields.palette, color, fields.wallpaper, fields.custom, fields.pendingPath)
            .joinToString("|")
    }

    fun title(inCategory: Boolean, categoryName: String): String =
        if (inCategory) categoryName else TITLE

    fun subtitle(inCategory: Boolean, contactName: String): String {
        if (inCategory) return CATEGORY_HINT
        val name = contactName.trim()
        return if (name.isEmpty()) DEVICE_LINE else "$name \u00B7 $DEVICE_LINE"
    }

    fun previewSender(isGroup: Boolean): String = if (isGroup) PREVIEW_SENDER else ""

    fun applyLabel(inCategory: Boolean, busy: Boolean): String = when {
        inCategory -> NEXT
        busy -> SAVING
        else -> APPLY
    }

    fun applyEnabled(
        inCategory: Boolean,
        busy: Boolean,
        draft: ThemeFields,
        saved: ThemeFields,
        catBefore: ThemeFields,
    ): Boolean {
        if (busy) return false
        return if (inCategory) key(draft) != key(catBefore) else key(draft) != key(saved)
    }

    fun resetEnabled(busy: Boolean, draft: ThemeFields): Boolean =
        !busy && key(draft) != key(ThemeFields())

    fun selectDefault(fields: ThemeFields): ThemeFields = fields.copy(palette = "", color = "")

    fun selectPalette(fields: ThemeFields, id: String): ThemeFields =
        fields.copy(palette = id, color = "")

    fun selectWallpaper(fields: ThemeFields, id: String): ThemeFields = fields.copy(
        wallpaper = id,
        custom = "",
        pendingPath = "",
        pendingMime = "",
        pendingName = "",
    )

    fun stagePhoto(fields: ThemeFields, path: String, mime: String, name: String): ThemeFields =
        fields.copy(
            pendingPath = path,
            pendingMime = mime,
            pendingName = name,
            wallpaper = "",
            custom = "",
        )

    fun removePhoto(fields: ThemeFields): ThemeFields = fields.copy(
        pendingPath = "",
        pendingMime = "",
        pendingName = "",
        custom = "",
        wallpaper = "",
    )

    fun resetDraft(): ThemeFields = ThemeFields()

    /** Next keeps [draft]. Back from a category restores [catBefore]. */
    fun finishCategory(draft: ThemeFields, catBefore: ThemeFields, keepPick: Boolean): ThemeFields =
        if (keepPick) draft else catBefore

    fun removeVisible(fields: ThemeFields): Boolean =
        fields.custom.isNotBlank() || fields.pendingPath.isNotBlank() || fields.wallpaper.isNotBlank()

    fun removeLabel(fields: ThemeFields): String =
        if (fields.custom.isNotBlank() || fields.pendingPath.isNotBlank()) "Remove photo" else "Remove wallpaper"

    fun defaultSwatchSelected(fields: ThemeFields): Boolean =
        fields.palette.isEmpty() && !validHex(fields.color)

    fun paletteSwatchSelected(fields: ThemeFields, id: String): Boolean = fields.palette == id

    /** Shown only for a saved custom hex that is not a named palette. Tapping it does nothing. */
    fun customSwatchHex(fields: ThemeFields): String? =
        if (validHex(fields.color) && fields.palette.isEmpty()) fields.color else null

    fun defaultSwatchLabel(): String = "Default colors"

    fun paletteSwatchLabel(name: String): String = "$name colors"

    fun customSwatchLabel(): String = "Current custom color"

    fun swatchSegments(): List<SwatchSegment> = listOf(
        SwatchSegment("light", 0f, 0.5f),
        SwatchSegment("them", 0.5f, 0.75f),
        SwatchSegment("me", 0.75f, 1f),
    )

    /** Palette surface, or null so the options sheet keeps the normal panel color. */
    fun panelSurface(fields: ThemeFields): Int? = ThemePalettes.byId(fields.palette)?.surface

    fun validHex(value: String): Boolean = HEX.matches(value)

    fun photoError(sizeBytes: Long, mime: String): String? {
        if (sizeBytes > MAX_UPLOAD_BYTES) return PHOTO_TOO_LARGE
        val type = mime.substringBefore(';').trim()
        if (type.isNotEmpty() && !PHOTO_MIME.matches(type)) return PHOTO_TYPE
        return null
    }

    fun uploadName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = base.filter { it.code in 32..126 && it != '"' }
        return clean.ifBlank { "photo.jpg" }
    }

    fun applyPlan(fields: ThemeFields): ApplyPlan {
        if (fields.pendingPath.isNotBlank()) return ApplyPlan.Upload(fields.palette, fields.color)
        val custom = fields.custom.isNotBlank()
        if (fields.palette.isBlank() && fields.color.isBlank() && fields.wallpaper.isBlank() && !custom) {
            return ApplyPlan.Delete
        }
        return ApplyPlan.Put(fields.palette, fields.color, fields.wallpaper, custom)
    }

    fun categorySelected(itemIds: List<String>, wallpaperId: String): Boolean =
        wallpaperId.isNotBlank() && itemIds.any { it == wallpaperId }

    fun credit(title: String, author: String, license: String, inThisCategory: Boolean): String {
        if (!inThisCategory) return ""
        return "Photo: $title \u00B7 $author \u00B7 $license"
    }

    fun photoContentDescription(title: String, index: Int, count: Int): String =
        "$title ($index of $count)"

    fun wallpaperError(message: String): String {
        val detail = message.ifBlank { "request failed" }
        return "Couldn't load wallpapers: $detail"
    }

    /** Null when the session is gone, so the screen stays quiet while login opens. */
    fun saveToast(message: String): String? =
        if (message == "login required") null else "Couldn't save the theme: $message"

    fun columnCount(widthPx: Int, minTilePx: Int): Int {
        if (widthPx <= 0 || minTilePx <= 0) return 3
        return (widthPx / minTilePx).coerceIn(3, 6)
    }
}
