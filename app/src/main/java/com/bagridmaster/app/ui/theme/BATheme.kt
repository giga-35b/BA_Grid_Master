package com.bagridmaster.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9FF4E5),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    background = Color(0xFFF7FAFA),
    surface = Color(0xFFF7FAFA),
    surfaceVariant = Color(0xFFDCE5E2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D8C9),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9FF4E5),
    secondary = Color(0xFFB1CCC5),
    background = Color(0xFF101414),
    surface = Color(0xFF101414),
    surfaceVariant = Color(0xFF3F4946),
)

@Composable
fun BAGridMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

