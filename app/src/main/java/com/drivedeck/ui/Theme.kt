package com.drivedeck.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object DeckColors {
    val Accent = Color(0xFF00E676)
    val AccentDim = Color(0xFF00B85C)
    val Bg = Color(0xFF0A0D12)
    val Surface = Color(0xFF121821)
    val SurfaceHigh = Color(0xFF1A2230)
    val Outline = Color(0xFF2A3340)
    val TextMuted = Color(0xFF8A96A8)
    val Warn = Color(0xFFFFB74D)
}

private val scheme = darkColorScheme(
    primary = DeckColors.Accent,
    onPrimary = Color(0xFF00210D),
    primaryContainer = Color(0xFF0E3B24),
    onPrimaryContainer = DeckColors.Accent,
    secondary = Color(0xFF9FB3C8),
    secondaryContainer = Color(0xFF1C2735),
    onSecondaryContainer = DeckColors.Accent,
    background = DeckColors.Bg,
    onBackground = Color(0xFFE8EDF3),
    surface = DeckColors.Bg,
    onSurface = Color(0xFFE8EDF3),
    surfaceVariant = DeckColors.SurfaceHigh,
    onSurfaceVariant = DeckColors.TextMuted,
    surfaceContainer = DeckColors.Surface,
    surfaceContainerHigh = DeckColors.SurfaceHigh,
    surfaceContainerHighest = DeckColors.SurfaceHigh,
    surfaceContainerLow = DeckColors.Surface,
    outline = DeckColors.Outline,
    outlineVariant = DeckColors.Outline,
    error = Color(0xFFFF6B6B),
)

private val type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
    )
}

@Composable
fun DriveDeckTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
