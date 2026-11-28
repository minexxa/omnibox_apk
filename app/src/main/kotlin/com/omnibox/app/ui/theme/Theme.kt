@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.omnibox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

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

/** Compose for TV 配色：与上面的 Material3 配色保持一致，供 tv-material 组件（Button/Tab/ListItem/Card…）使用。 */
@OptIn(ExperimentalTvMaterial3Api::class)
private val TvDark = tvDarkColorScheme(
    primary = Color(0xFF90A4AE),
    onPrimary = Color(0xFF102027),
    primaryContainer = Color(0xFF37474F),
    onPrimaryContainer = Color(0xFFE3E7EA),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF102027),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E7EA),
    surface = Color(0xFF181C20),
    onSurface = Color(0xFFE3E7EA),
    surfaceVariant = Color(0xFF262A2E),
    onSurfaceVariant = Color(0xFFC1C9CE),
    error = Color(0xFFCF6679),
    border = Color(0xFF8B9198),
)

/**
 * 同时提供 Material3 主题（进度条 / 输入框 / 对话框 / Snackbar 仍用 M3）
 * 和 Compose for TV 主题（Button / TabRow / ListItem / Card / Text 等 TV 组件）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OmniBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark) {
        TvMaterialTheme(colorScheme = TvDark, content = content)
    }
}

object AppColors {
    val Accent = Color(0xFF1890FF)
    val FocusFill = Color(0x4D333333)
    val FocusBorder = Color(0xFFFFFFFF)
    val CardBg = Color(0xFF1C2024)
    val Gold = Color(0xFFFFC107)
    val ButtonBg = Color(0x6C3D3D3D)
    val PanelBg = Color(0xFF252B33)
}
