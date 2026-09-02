package com.tomppi.enderslicer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * EnderSlicerCura brand palette ("engineering cockpit", see
 * docs/ux-redesign/DESIGN_PROPOSAL.md). Pinned colors instead of wallpaper
 * dynamic color so the app keeps its identity on every device.
 *
 * Dark is the primary design; light keeps the same hue structure.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB454),
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Color(0xFF4A2F14),
    onPrimaryContainer = Color(0xFFFFD28A),
    inversePrimary = Color(0xFF7A4A12),
    secondary = Color(0xFF99A6B3),
    onSecondary = Color(0xFF0E1116),
    secondaryContainer = Color(0xFF232C38),
    onSecondaryContainer = Color(0xFFD6DEE6),
    tertiary = Color(0xFF6FB8FF),
    onTertiary = Color(0xFF08131F),
    tertiaryContainer = Color(0xFF16344F),
    onTertiaryContainer = Color(0xFFBDDDFF),
    background = Color(0xFF0B0E13),
    onBackground = Color(0xFFE8EEF4),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE8EEF4),
    surfaceVariant = Color(0xFF1E2733),
    onSurfaceVariant = Color(0xFF99A6B3),
    surfaceTint = Color(0xFFFFB454),
    inverseSurface = Color(0xFFE8EEF4),
    inverseOnSurface = Color(0xFF1E2733),
    outline = Color(0xFF2E3947),
    outlineVariant = Color(0xFF232C38),
    error = Color(0xFFF0655D),
    onError = Color(0xFF2A0705),
    errorContainer = Color(0xFF4A1412),
    onErrorContainer = Color(0xFFFFB4AC),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFA05A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDFB0),
    onPrimaryContainer = Color(0xFF4A2C00),
    inversePrimary = Color(0xFFFFB454),
    secondary = Color(0xFF4D5A66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E5EF),
    onSecondaryContainer = Color(0xFF131A20),
    tertiary = Color(0xFF1C5B93),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDE6FF),
    onTertiaryContainer = Color(0xFF04192E),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A212A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A212A),
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF4D5A66),
    surfaceTint = Color(0xFFA05A00),
    inverseSurface = Color(0xFF2E3947),
    inverseOnSurface = Color(0xFFF7F9FC),
    outline = Color(0xFF77828D),
    outlineVariant = Color(0xFFD9E0E7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

@Composable
fun EnderSlicerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Shared layout tokens for the app UI (4 px grid). New screens should use
 * these instead of ad-hoc dp values; see docs/ui-style-guide.md.
 */
object EnderSlicerDimens {
    val Space2 = 2.dp
    val Space4 = 4.dp
    val Space6 = 6.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val TouchTarget = 48.dp
    val CardPadding = 12.dp
    val SheetPadding = 12.dp
}
