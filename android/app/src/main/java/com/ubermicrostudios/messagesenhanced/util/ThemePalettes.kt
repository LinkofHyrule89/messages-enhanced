package com.ubermicrostudios.messagesenhanced.util

import com.ubermicrostudios.messagesenhanced.data.model.ChatThemeDto

/**
 * Colors live on the client, matching the web app's palette table and the default
 * `:root` variables in app.css. The server stores palette ids only. The app-wide
 * choice is `theme` on `GET`/`PUT /api/app/settings`, not a conversation theme.
 */
object ThemePalettes {
    /** Older builds stored the app-wide palette on this fake conversation id. */
    const val APP_CONVERSATION_ID = AppSettingsSync.LEGACY_CONVERSATION

    val all: List<Palette> = listOf(
        Palette("blue", "Blue", 0xFFA8C7FA.toInt(), 0xFF1F5FBF.toInt(), 0xFF26313F.toInt(), 0xFF2F6FE4.toInt(), 0xFF0E2A3E.toInt()),
        Palette("periwinkle", "Periwinkle", 0xFFBAC3FF.toInt(), 0xFF4454B8.toInt(), 0xFF2D2F45.toInt(), 0xFF4F5FCF.toInt(), 0xFF1B1F3D.toInt()),
        Palette("sky", "Sky", 0xFF86D1EC.toInt(), 0xFF006689.toInt(), 0xFF1E3640.toInt(), 0xFF00769E.toInt(), 0xFF0B2B35.toInt()),
        Palette("lilac", "Lilac", 0xFFE3B8FF.toInt(), 0xFF7A3FAE.toInt(), 0xFF382B47.toInt(), 0xFF8A4CC0.toInt(), 0xFF2A1D38.toInt()),
        Palette("sage", "Sage", 0xFFC3CFB2.toInt(), 0xFF48633E.toInt(), 0xFF2C3628.toInt(), 0xFF4F6E44.toInt(), 0xFF1D2A1D.toInt()),
        Palette("coral", "Peach", 0xFFFFB690.toInt(), 0xFFA1461C.toInt(), 0xFF46302A.toInt(), 0xFFB04E20.toInt(), 0xFF36221A.toInt()),
        Palette("rose", "Pink", 0xFFFFB1CF.toInt(), 0xFFA6356B.toInt(), 0xFF462A37.toInt(), 0xFFB53B76.toInt(), 0xFF361C29.toInt()),
        Palette("gold", "Gold", 0xFFEFCF72.toInt(), 0xFF7A5600.toInt(), 0xFF3B3322.toInt(), 0xFF8A6200.toInt(), 0xFF2E2612.toInt()),
        Palette("slate", "Slate", 0xFFC4C7CC.toInt(), 0xFF505760.toInt(), 0xFF2B2E34.toInt(), 0xFF5A626C.toInt(), 0xFF1E2227.toInt()),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Palette? = byId[id.trim().lowercase()]

    fun offered(serverIds: List<String>): List<Palette> {
        if (serverIds.isEmpty()) return all
        val known = serverIds.mapNotNull { byId(it) }
        return known.ifEmpty { all }
    }

    fun colors(theme: ChatThemeDto?): ThemeColors {
        if (theme == null) return ThemeColors.Default
        byId(theme.palette)?.let { return it.toColors() }
        val hex = theme.color.trim()
        if (hex.matches(Regex("#[0-9a-fA-F]{6}"))) {
            val color = hex.removePrefix("#").toInt(16) or 0xFF000000.toInt()
            return ThemeColors(
                paletteId = "",
                me = color,
                them = ThemeColors.Default.them,
                accent = color,
                light = color,
                surface = ThemeColors.Default.surface,
            )
        }
        return ThemeColors.Default
    }

    fun hasOwnColors(theme: ChatThemeDto?): Boolean {
        if (theme == null) return false
        if (byId(theme.palette) != null) return true
        return theme.color.trim().matches(Regex("#[0-9a-fA-F]{6}"))
    }
}

data class Palette(
    val id: String,
    val name: String,
    val light: Int,
    val me: Int,
    val them: Int,
    val accent: Int,
    val surface: Int,
) {
    fun toColors() = ThemeColors(id, me, them, accent, light, surface)
}

data class ThemeColors(
    val paletteId: String = "",
    val me: Int = 0xFF2F6FE4.toInt(),
    val them: Int = 0xFF262B33.toInt(),
    val accent: Int = 0xFF3E8BFF.toInt(),
    val light: Int = 0xFF9CC3FF.toInt(),
    val surface: Int = 0xFF0D0F12.toInt(),
) {
    companion object {
        val Default = ThemeColors()
    }
}
