package ru.pauza.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PauseGreen = Color(0xFF1E4B3A)
val PauseGreenSoft = Color(0xFFEAF0E7)
val PauseBackground = Color(0xFFF7F8F4)
val PauseSurface = Color(0xFFFFFFFF)
val PauseText = Color(0xFF1E2420)
val PauseMuted = Color(0xFF6D746F)
val PauseLine = Color(0xFFE1E5DF)
val PauseWarning = Color(0xFFF3EFE4)

private val PauseColors = lightColorScheme(
    primary = PauseGreen,
    onPrimary = Color.White,
    primaryContainer = PauseGreenSoft,
    onPrimaryContainer = PauseGreen,
    background = PauseBackground,
    onBackground = PauseText,
    surface = PauseSurface,
    onSurface = PauseText,
    outline = PauseLine,
)

@Composable
fun PauseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PauseColors,
        typography = Typography(),
        content = content,
    )
}
