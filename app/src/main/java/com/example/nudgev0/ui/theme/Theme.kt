package com.example.nudgev0.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 1. Map your Custom Colors to the Material Design System
private val NudgeColorScheme = darkColorScheme(
    primary = Emerald400,          // Success/Action color
    onPrimary = Slate900,          // Text on primary buttons
    background = Slate900,         // Main app background
    onBackground = Slate50,        // Main text color
    surface = Slate900,            // Base surface color
    surfaceVariant = Color(0xFF1E293B), // Slightly lighter card color (Slate 800)
    onSurface = Slate50,
    onSurfaceVariant = Slate400,   // Subtitles and secondary text
    error = Red500,
    onError = Slate50
)

@Composable
fun Nudgev0Theme(
    // We force the dark Nudge aesthetic regardless of system settings for a consistent brand look
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = NudgeColorScheme
    val view = LocalView.current

    // 2. Color the System Status Bar to match the background
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // Make sure this matches your Type.kt
        content = content
    )
}