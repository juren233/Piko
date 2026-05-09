package com.piko.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val IOS_SYSTEM_BLUE_LIGHT = Color(0xFF007AFF)
internal val IOS_SYSTEM_BLUE_DARK = Color(0xFF0A84FF)
internal val IOS_SECONDARY_SYSTEM_BACKGROUND_LIGHT = Color(0xFFF2F2F7)
internal val IOS_SECONDARY_SYSTEM_BACKGROUND_DARK = Color(0xFF1C1C1E)
internal val IOS_SYSTEM_BACKGROUND_LIGHT = Color.White
internal val IOS_SYSTEM_BACKGROUND_DARK = Color.Black

internal object PikoColors {
    @Composable
    fun background(isDarkTheme: Boolean = isSystemInDarkTheme()): Color =
        if (isDarkTheme) IOS_SYSTEM_BACKGROUND_DARK else IOS_SYSTEM_BACKGROUND_LIGHT

    @Composable
    fun surfaceVariant(isDarkTheme: Boolean = isSystemInDarkTheme()): Color =
        if (isDarkTheme) IOS_SECONDARY_SYSTEM_BACKGROUND_DARK else IOS_SECONDARY_SYSTEM_BACKGROUND_LIGHT

    @Composable
    fun accent(isDarkTheme: Boolean = isSystemInDarkTheme()): Color =
        if (isDarkTheme) IOS_SYSTEM_BLUE_DARK else IOS_SYSTEM_BLUE_LIGHT
}

@Composable
internal fun PikoTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = pikoColorScheme(isDarkTheme),
        typography = MaterialTheme.typography,
        content = content,
    )
}

private fun pikoColorScheme(isDarkTheme: Boolean): ColorScheme {
    return if (isDarkTheme) {
        darkColorScheme(
            primary = IOS_SYSTEM_BLUE_DARK,
            onPrimary = Color.White,
            primaryContainer = IOS_SYSTEM_BLUE_DARK.copy(alpha = 0.18f),
            onPrimaryContainer = IOS_SYSTEM_BLUE_DARK,
            secondary = IOS_SYSTEM_BLUE_DARK,
            onSecondary = Color.White,
            background = IOS_SYSTEM_BACKGROUND_DARK,
            onBackground = Color.White,
            surface = IOS_SYSTEM_BACKGROUND_DARK,
            onSurface = Color.White,
            surfaceVariant = IOS_SECONDARY_SYSTEM_BACKGROUND_DARK,
            onSurfaceVariant = Color(0xFFEBEBF5).copy(alpha = 0.6f),
            outline = Color(0xFF545458),
            outlineVariant = Color(0xFF38383A),
            error = Color(0xFFFF453A),
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = IOS_SYSTEM_BLUE_LIGHT,
            onPrimary = Color.White,
            primaryContainer = IOS_SYSTEM_BLUE_LIGHT.copy(alpha = 0.14f),
            onPrimaryContainer = IOS_SYSTEM_BLUE_LIGHT,
            secondary = IOS_SYSTEM_BLUE_LIGHT,
            onSecondary = Color.White,
            background = IOS_SYSTEM_BACKGROUND_LIGHT,
            onBackground = Color.Black,
            surface = IOS_SYSTEM_BACKGROUND_LIGHT,
            onSurface = Color.Black,
            surfaceVariant = IOS_SECONDARY_SYSTEM_BACKGROUND_LIGHT,
            onSurfaceVariant = Color(0xFF3C3C43).copy(alpha = 0.6f),
            outline = Color(0xFFC6C6C8),
            outlineVariant = Color(0xFFE5E5EA),
            error = Color(0xFFFF3B30),
            onError = Color.White,
        )
    }
}
