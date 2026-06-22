package com.example.nudgev0.ui.theme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// --- 1. The Raw Slate Ramp ---
val Slate950 = Color(0xFF0B1120) // Deepest inset / chart tracks
val Slate900 = Color(0xFF0F172A) // App background (Deep Navy)
val Slate800 = Color(0xFF1E293B) // Card / surface
val Slate700 = Color(0xFF334155) // Hairline dividers
val Slate500 = Color(0xFF64748B) // Tertiary text
val Slate400 = Color(0xFF94A3B8) // Secondary text / labels
val Slate50  = Color(0xFFF8FAFC) // Primary text (white)

// --- 2. Semantic Accents (one hue = one job) ---
val Emerald400 = Color(0xFF34D399) // Brand / primary / success
val Red500     = Color(0xFFEF4444) // Destructive / over-threshold
val Orange500  = Color(0xFFF97316) // Warning / flagged apps

// --- 3. Muted Metric Trio ---
// Low chroma (~oklch C=0.05), ~72% lightness — reads as calm "data",
// never competing with the punchy semantic or tier palette.
val MetricScrolls = Color(0xFF8DBFAA) // oklch(0.72 0.05 165) — muted sage green
val MetricUnlocks = Color(0xFF85A3C4) // oklch(0.72 0.05 250) — muted periwinkle
val MetricTime    = Color(0xFFA490BF) // oklch(0.72 0.05 305) — muted mauve

// --- 4. Semantic Aliases ---
val AppBackground = Slate900
val PrimaryText   = Slate50
val SecondaryText = Slate400
val AccentColor   = Emerald400
val ErrorColor    = Red500

// --- 5. Frost Effects ---
val FrostBorder = Color(0x4DFFFFFF) // border-white/30

val FrostGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x4D2DD4BF), // teal-400 / 30%
        Color(0x4D34D399)  // emerald-400 / 30%
    )
)

val GlassButtonWhite = Color(0x33FFFFFF) // 20% white glass