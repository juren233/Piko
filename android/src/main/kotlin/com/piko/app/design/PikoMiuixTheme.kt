package com.piko.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal object PikoSpacing {
    val screenHorizontal = 20.dp
    val screenTop = 14.dp
    val section = 16.dp
    val item = 10.dp
}

internal object PikoStatusTone {
    val direct = Color(0xFF1F7A4D)
    val waiting = Color(0xFF936000)
    val offline = Color(0xFFA22A2A)
}

@Composable
internal fun PikoMiuixTheme(
    content: @Composable () -> Unit,
) {
    val controller = remember {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.Light,
            lightColors = lightColorScheme(
                primary = Color(0xFF3F7DF6),
                onPrimary = Color.White,
                background = Color(0xFFF7F8FB),
                surface = Color(0xFFFFFFFF),
            ),
            darkColors = darkColorScheme(
                primary = Color(0xFF8DB3FF),
                onPrimary = Color(0xFF08285F),
                background = Color(0xFF101114),
                surface = Color(0xFF1B1C20),
            ),
            keyColor = Color(0xFF3F7DF6),
            paletteStyle = ThemePaletteStyle.TonalSpot,
        )
    }

    MiuixTheme(
        controller = controller,
        content = content,
    )
}
