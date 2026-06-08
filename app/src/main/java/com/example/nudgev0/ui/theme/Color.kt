package com.example.nudgev0.ui.theme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// --- 1. The Raw Palette (The "Ingredients") ---
val Slate900 = Color(0xFF0F172A) // Deep Navy Background
val Slate50 = Color(0xFFF8FAFC)  // White Text
val Slate400 = Color(0xFF94A3B8) // Grey Secondary Text
val Emerald400 = Color(0xFF34D399) // Success Green
val Red500 = Color(0xFFEF4444)     // Error Red
val Slate950 = Color(0xFFFFFF) // Deep Navy Background

// --- 2. The Semantic Colors (The "Usage") ---
// This is how we map colors to their job.
// If you want to change the background later, you only change it HERE.

val AppBackground = Slate950
val PrimaryText = Slate50
val SecondaryText = Slate400
val AccentColor = Emerald400
val ErrorColor = Red500

// --- 3. The Frost Effects ---
// We can define complex Brushes and Alphas here too!

val FrostBorder = Color(0x4DFFFFFF) // 30% White

val FrostGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x4D2DD4BF), // Teal-400 (30%)
        Color(0x4D34D399)  // Emerald-400 (30%)
    )
)

val GlassButtonWhite = Color(0x33FFFFFF) // 20% White