package com.example.hibi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Teal80,
    secondary = TealGrey80,
    tertiary = Coral80,
)

private val LightColors = lightColorScheme(
    primary = Teal40,
    secondary = TealGrey40,
    tertiary = Coral40,
)

@Composable
fun HibiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Fixed brand palette (no dynamic color) so the app looks the same everywhere.
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
