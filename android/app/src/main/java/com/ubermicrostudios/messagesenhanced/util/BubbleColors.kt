package com.ubermicrostudios.messagesenhanced.util

/**
 * Thread bubble fills. With no palette, the bubbles match the thread screenshots:
 * a lighter translucent grey on the right (outgoing) and a darker one on the left.
 * A saved palette replaces those fills and stays slightly translucent over a wallpaper.
 */
object BubbleColors {
    private const val SCREEN_ME = 0xFF505760.toInt()
    private const val SCREEN_THEM = 0xFF2B2E34.toInt()

    fun fill(colors: ThemeColors, fromMe: Boolean): Int {
        val themed = colors.paletteId.isNotBlank() ||
            colors.me != ThemeColors.Default.me ||
            colors.them != ThemeColors.Default.them
        val solid = when {
            !themed && fromMe -> SCREEN_ME
            !themed -> SCREEN_THEM
            fromMe -> colors.me
            else -> colors.them
        }
        val alpha = if (fromMe) 0xE6 else 0xF0
        return (solid and 0x00FFFFFF) or (alpha shl 24)
    }
}
