package com.ubermicrostudios.messagesenhanced.util

/**
 * Shared `tm.settings` fields stored by `GET`/`PUT /api/app/settings`.
 * `theme` is a palette id (`""` is the default look from the web app's CSS variables).
 * `appearance` is `dark` — the only appearance the web app ships.
 * [LEGACY_CONVERSATION] was an older native-only stand-in for the app-wide theme.
 */
object AppSettingsSync {
    const val THEME = "theme"
    const val APPEARANCE = "appearance"
    const val DARK = "dark"
    const val LEGACY_CONVERSATION = "native-app"

    data class Plan(
        val theme: String,
        val appearance: String,
        /** Keys to write. Empty means the server object already wins and no PUT is needed. */
        val upload: Map<String, String>,
        val deleteLegacy: Boolean,
    )

    fun plan(
        serverHasTheme: Boolean,
        serverTheme: String,
        serverHasAppearance: Boolean,
        serverAppearance: String,
        legacyPresent: Boolean,
        legacyPalette: String,
    ): Plan {
        if (!serverHasTheme && !serverHasAppearance && legacyPresent && legacyPalette.isNotBlank()) {
            return Plan(
                theme = legacyPalette,
                appearance = DARK,
                upload = mapOf(THEME to legacyPalette, APPEARANCE to DARK),
                deleteLegacy = true,
            )
        }
        if (serverHasTheme || serverHasAppearance) {
            return Plan(
                theme = if (serverHasTheme) serverTheme else "",
                appearance = if (serverHasAppearance && serverAppearance.isNotBlank()) serverAppearance else DARK,
                upload = emptyMap(),
                deleteLegacy = legacyPresent,
            )
        }
        return Plan(
            theme = "",
            appearance = DARK,
            upload = emptyMap(),
            deleteLegacy = legacyPresent,
        )
    }
}
