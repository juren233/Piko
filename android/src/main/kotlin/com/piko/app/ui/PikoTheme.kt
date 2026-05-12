package com.piko.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit

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

internal object PikoTypography {
    @Composable
    fun current(base: Typography = MaterialTheme.typography): Typography {
        val widthDp = LocalConfiguration.current.screenWidthDp
        val textScale = when {
            widthDp <= 375 -> PikoScreenTextScale.Compact
            widthDp >= 430 -> PikoScreenTextScale.Expanded
            else -> PikoScreenTextScale.Regular
        }
        return base.scaled(textScale.factor)
    }
}

private enum class PikoScreenTextScale(val factor: Float) {
    Compact(0.92f),
    Regular(1f),
    Expanded(1.06f),
}

@Composable
internal fun PikoTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = pikoColorScheme(isDarkTheme),
        typography = PikoTypography.current(),
        content = content,
    )
}

private fun Typography.scaled(factor: Float): Typography {
    if (factor == 1f) {
        return this
    }
    return copy(
        displayLarge = displayLarge.scaled(factor),
        displayMedium = displayMedium.scaled(factor),
        displaySmall = displaySmall.scaled(factor),
        headlineLarge = headlineLarge.scaled(factor),
        headlineMedium = headlineMedium.scaled(factor),
        headlineSmall = headlineSmall.scaled(factor),
        titleLarge = titleLarge.scaled(factor),
        titleMedium = titleMedium.scaled(factor),
        titleSmall = titleSmall.scaled(factor),
        bodyLarge = bodyLarge.scaled(factor),
        bodyMedium = bodyMedium.scaled(factor),
        bodySmall = bodySmall.scaled(factor),
        labelLarge = labelLarge.scaled(factor),
        labelMedium = labelMedium.scaled(factor),
        labelSmall = labelSmall.scaled(factor),
    )
}

private fun TextStyle.scaled(factor: Float): TextStyle {
    return copy(
        fontSize = fontSize.scaled(factor),
        lineHeight = lineHeight.scaled(factor),
    )
}

private fun TextUnit.scaled(factor: Float): TextUnit {
    return if (this == TextUnit.Unspecified) {
        this
    } else {
        this * factor
    }
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
