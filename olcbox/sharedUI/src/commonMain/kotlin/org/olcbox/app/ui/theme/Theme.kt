package org.olcbox.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

internal val LocalThemeIsDark = compositionLocalOf { mutableStateOf(true) }

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    // Brand colours everywhere; no Android wallpaper colours.
    AppTheme(useDynamicColor = false, content = content)
}

@Composable
expect fun AppTheme(
    useDynamicColor: Boolean,
    content: @Composable () -> Unit
)
