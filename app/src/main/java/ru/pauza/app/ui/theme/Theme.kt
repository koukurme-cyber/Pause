package ru.pauza.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PauseGreen = Color(0xFF184B34)
val PauseGreenSoft = Color(0xFFE7F2E3)
val PauseBackground = Color(0xFFF8F6EF)
val PauseSurface = Color(0xFFFFFEFA)
val PauseText = Color(0xFF171B18)
val PauseMuted = Color(0xFF6C736E)
val PauseLine = Color(0xFFE1E3DC)
val PauseWarning = Color(0xFFFFF3D7)

private val PauseColors = lightColorScheme(
    primary = PauseGreen,
    onPrimary = Color.White,
    primaryContainer = PauseGreenSoft,
    onPrimaryContainer = PauseGreen,
    background = PauseBackground,
    onBackground = PauseText,
    surface = PauseSurface,
    onSurface = PauseText,
    surfaceVariant = Color(0xFFF2F2ED),
    onSurfaceVariant = PauseMuted,
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
