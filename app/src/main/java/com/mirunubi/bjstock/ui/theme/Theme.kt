package com.mirunubi.bjstock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PaperGreen = Color(0xFF1B5E20)
private val PaperGreenLight = Color(0xFFA5D6A7)

private val LightColors = lightColorScheme(
    primary = PaperGreen,
    secondary = PaperGreen,
    tertiary = PaperGreen,
)

private val DarkColors = darkColorScheme(
    primary = PaperGreenLight,
    secondary = PaperGreenLight,
    tertiary = PaperGreenLight,
)

@Composable
fun BJStockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
