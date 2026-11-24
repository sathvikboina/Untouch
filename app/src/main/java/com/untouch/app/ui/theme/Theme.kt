package com.untouch.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PureBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF121212)
val SurfaceElevated = Color(0xFF1E1E1E)
val BorderGrey = Color(0xFF2C2C2C)
val TextPrimary = Color(0xFFF5F5F5)
val TextSecondary = Color(0xFF9E9E9E)
val AccentWhite = Color(0xFFFFFFFF)
val StatusGreen = Color(0xFF4CAF50)
val StatusRed = Color(0xFFE53935)
val StatusAmber = Color(0xFFFFB300)

private val UntouchDarkScheme = darkColorScheme(
    primary = AccentWhite,
    onPrimary = PureBlack,
    background = PureBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    error = StatusRed,
    onError = AccentWhite,
    outline = BorderGrey
)

@Composable
fun UntouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UntouchDarkScheme,
        content = content
    )
}
