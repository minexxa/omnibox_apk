package com.omnibox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF90A4AE),
    onPrimary = Color(0xFF102027),
    primaryContainer = Color(0xFF37474F),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFF1890FF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E7EA),
    surface = Color(0xFF181C20),
    onSurface = Color(0xFFE3E7EA),
    surfaceVariant = Color(0xFF262A2E),
    onSurfaceVariant = Color(0xFFC1C9CE),
    error = Color(0xFFCF6679),
    outline = Color(0xFF8B9198),
)

@Composable
fun OmniBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}

object AppColors {
    val Accent = Color(0xFF1890FF)
    val FocusFill = Color(0x4D333333)
    val FocusBorder = Color(0xFFFFFFFF)
    val CardBg = Color(0xFF1C2024)
    val Gold = Color(0xFFFFC107)
}
