package net.ft8vc.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Ft8Green,
    secondary = Ft8Blue,
    tertiary = Ft8Lime,
    error = Ft8Red,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = BackgroundDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    onSurfaceVariant = OnSurfaceMutedDark,
)

private val LightColors = lightColorScheme(
    primary = Ft8Green,
    secondary = Ft8Blue,
    tertiary = Ft8Lime,
    error = Ft8Red,
)

@Composable
fun Ft8vcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The operating UI is dark-first (waterfall readability). Light scheme is a
    // courtesy fallback for non-operating screens.
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Ft8vcTypography,
        content = content,
    )
}
