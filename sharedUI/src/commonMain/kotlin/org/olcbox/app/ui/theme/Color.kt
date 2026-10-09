package org.olcbox.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand colours taken from the Murka VPN logo: neon cyan "M" and shield, violet rim.
internal val MurkaCyan = Color(0xFF2EA8FF)
internal val MurkaBlue = Color(0xFF3D7BFF)
internal val MurkaViolet = Color(0xFF7B5CFF)

/** Cyan → violet sweep of the logo rim; closes on cyan so a full circle has no seam. */
internal val MurkaRimColors = listOf(MurkaCyan, MurkaBlue, MurkaViolet, MurkaBlue, MurkaCyan)

internal val OlcboxDarkColorScheme = darkColorScheme(
    primary = MurkaCyan,
    onPrimary = Color(0xFF00213A),
    primaryContainer = Color(0xFF0D3A66),
    onPrimaryContainer = Color(0xFFCDE6FF),
    inversePrimary = Color(0xFF0064A8),
    secondary = Color(0xFF9B87FF),
    onSecondary = Color(0xFF1E0F5C),
    secondaryContainer = Color(0xFF31237A),
    onSecondaryContainer = Color(0xFFE4DDFF),
    tertiary = Color(0xFF5EE6FF),
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF004E5B),
    onTertiaryContainer = Color(0xFFB3F1FF),
    background = Color(0xFF060913),
    onBackground = Color(0xFFE3ECFF),
    surface = Color(0xFF060913),
    onSurface = Color(0xFFE3ECFF),
    surfaceVariant = Color(0xFF1C2742),
    onSurfaceVariant = Color(0xFF9FB0D0),
    surfaceTint = MurkaCyan,
    surfaceContainerLowest = Color(0xFF03050C),
    surfaceContainerLow = Color(0xFF0B1120),
    surfaceContainer = Color(0xFF0E1527),
    surfaceContainerHigh = Color(0xFF141D35),
    surfaceContainerHighest = Color(0xFF1B2643),
    inverseSurface = Color(0xFFE3ECFF),
    inverseOnSurface = Color(0xFF1A2236),
    outline = Color(0xFF3A4D78),
    outlineVariant = Color(0xFF1F2B48),
    error = Color(0xFFFF6B81),
    onError = Color(0xFF4A0010),
    errorContainer = Color(0xFF6E1024),
    onErrorContainer = Color(0xFFFFD9DE)
)

internal val OlcboxLightColorScheme = lightColorScheme(
    primary = Color(0xFF0067B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E6FF),
    onPrimaryContainer = Color(0xFF001C38),
    inversePrimary = MurkaCyan,
    secondary = Color(0xFF5B47D6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5DEFF),
    onSecondaryContainer = Color(0xFF1A0B66),
    tertiary = Color(0xFF00687A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFAEEDFF),
    onTertiaryContainer = Color(0xFF001F26),
    background = Color(0xFFF5F8FF),
    onBackground = Color(0xFF0B1324),
    surface = Color(0xFFF5F8FF),
    onSurface = Color(0xFF0B1324),
    surfaceVariant = Color(0xFFDCE4F5),
    onSurfaceVariant = Color(0xFF43506B),
    surfaceTint = Color(0xFF0067B8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF3FD),
    surfaceContainer = Color(0xFFE7EDFA),
    surfaceContainerHigh = Color(0xFFE0E8F7),
    surfaceContainerHighest = Color(0xFFD8E1F3),
    inverseSurface = Color(0xFF1A2236),
    inverseOnSurface = Color(0xFFEEF3FD),
    outline = Color(0xFF73809D),
    outlineVariant = Color(0xFFC3CCE0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
