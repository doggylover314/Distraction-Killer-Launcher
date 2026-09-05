package com.focushome.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Two flat, low-contrast palettes and nothing else. No dynamic colour, which
 * keeps the home screen looking the same whatever wallpaper is behind it.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C6FF),
    onPrimary = Color(0xFF16264F),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E2E6),
    onSurfaceVariant = Color(0xFFA9A8AD),
    outline = Color(0xFF3A3B3F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5AA9),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1B1F),
    onSurfaceVariant = Color(0xFF5B5D62),
    outline = Color(0xFFDDDDE2),
)

@Composable
fun FocusHomeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
