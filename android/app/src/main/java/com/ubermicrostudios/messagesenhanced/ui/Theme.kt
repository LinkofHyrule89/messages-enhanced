package com.ubermicrostudios.messagesenhanced.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BrandBlue = Color(0xFF3E6AE1)
val OkGreen = Color(0xFF4CAF50)
val ErrRed = Color(0xFFFF6B6B)

private val Dark = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF9E9E9E),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2D),
    onSurfaceVariant = Color(0xFFBDBDBD),
    error = ErrRed,
)

@Composable
fun METheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}
