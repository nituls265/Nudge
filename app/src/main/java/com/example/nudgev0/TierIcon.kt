package com.example.nudgev0

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Maps each WellnessTier to the closest Material outlined icon.
// Icons are stroked/outlined so they read as line glyphs — matching the design
// system's TierIcon.jsx which uses single-weight SVG paths with no fill.
// Tint is passed in so the icon always matches its tier color (unlike emoji
// which have fixed colors and ignore the surrounding color scheme).

fun tierImageVector(tier: WellnessTier): ImageVector = when (tier) {
    WellnessTier.MINDFUL    -> Icons.Outlined.Eco          // leaf / sprout — calm growth
    WellnessTier.BALANCED   -> Icons.Outlined.AutoAwesome  // sparkle — in equilibrium
    WellnessTier.DRIFTING   -> Icons.Outlined.Waves        // wave — drifting
    WellnessTier.HEAVY_USE  -> Icons.Outlined.Bolt         // lightning — heavy use
    WellnessTier.OVERLOADED -> Icons.Outlined.Error        // alert circle — overloaded
}

@Composable
fun TierIcon(
    tier: WellnessTier,
    tint: Color,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector        = tierImageVector(tier),
        contentDescription = tier.label,
        tint               = tint,
        modifier           = modifier
    )
}
