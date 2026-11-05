package com.oguricap.efb.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF0B3D6B)
private val Steel = Color(0xFF4A6C8C)
private val Sky = Color(0xFF7FB2E5)

private val LightColors = lightColorScheme(
    primary = Navy,
    secondary = Steel,
    tertiary = Sky,
)

private val DarkColors = darkColorScheme(
    primary = Sky,
    secondary = Steel,
    tertiary = Navy,
)

@Composable
fun EfbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
