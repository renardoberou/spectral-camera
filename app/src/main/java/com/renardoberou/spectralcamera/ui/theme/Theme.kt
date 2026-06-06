package com.renardoberou.spectralcamera.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = spectralPrimary,
    onPrimary = Color.Black,
    secondary = spectralSecondary,
    onSecondary = Color.Black,
    tertiary = spectralTertiary,
    onTertiary = Color.Black,
    background = spectralBlack,
    onBackground = Color(0xFFE9E4F0),
    surface = spectralSurface,
    onSurface = Color(0xFFE9E4F0),
    surfaceVariant = spectralSurfaceVariant,
    onSurfaceVariant = Color(0xFFD7D1E3),
    error = spectralError,
    onError = Color.Black,
)

@Composable
fun SpectralCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
