package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val StudioColorScheme = darkColorScheme(
    primary = VocalCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF8CF4FF),
    secondary = VocalPurple,
    onSecondary = Color(0xFF2C1052),
    secondaryContainer = Color(0xFF452277),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = VocalPink,
    onTertiary = Color(0xFF5E002B),
    background = StudioDarkBg,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = StudioCardBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = Typography,
        content = content
    )
}

