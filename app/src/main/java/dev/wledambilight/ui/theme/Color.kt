package dev.wledambilight.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand: warm ambient light on a deep night background.
val Amber = Color(0xFFFFB547)
val AmberDeep = Color(0xFFE08A1E)
val Coral = Color(0xFFFF7A6B)
val Violet = Color(0xFF9B8CFF)
val Mint = Color(0xFF4FD8A4)
val Sky = Color(0xFF5CC8FF)
val Rose = Color(0xFFF472B6)

val NightBackground = Color(0xFF07090E)
val NightSurface = Color(0xFF10141C)
val NightSurfaceHigh = Color(0xFF171C27)
val NightSurfaceHighest = Color(0xFF202737)
val NightOutline = Color(0xFF2A3344)
val NightOutlineSoft = Color(0xFF1C2330)

val TextPrimary = Color(0xFFEEF2F8)
val TextSecondary = Color(0xFF9AA6B8)
val TextMuted = Color(0xFF6B778A)
val Danger = Color(0xFFFF6B6B)
val Warning = Color(0xFFFFC857)

/** Signature gradient used for the primary action and accents. */
val BrandGradient: Brush = Brush.linearGradient(listOf(Amber, Coral, Violet))
