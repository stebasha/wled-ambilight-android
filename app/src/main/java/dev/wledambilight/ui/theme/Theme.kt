package dev.wledambilight.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.wledambilight.core.model.AppTheme

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun WledAmbilightTheme(
    theme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit,
) {
    val black = theme == AppTheme.BLACK
    val background = if (black) Color.Black else NightBackground
    val surface = if (black) Color(0xFF07080A) else NightSurface
    val surfaceHigh = if (black) Color(0xFF0E1014) else NightSurfaceHigh
    val surfaceHighest = if (black) Color(0xFF15181E) else NightSurfaceHighest
    val scheme = darkColorScheme(
        primary = Amber,
        onPrimary = Color(0xFF241500),
        primaryContainer = Color(0xFF3A2708),
        onPrimaryContainer = Amber,
        secondary = Mint,
        onSecondary = Color(0xFF00210F),
        secondaryContainer = Color(0xFF0E3326),
        onSecondaryContainer = Mint,
        tertiary = Violet,
        onTertiary = Color(0xFF140D3A),
        tertiaryContainer = Color(0xFF24204A),
        onTertiaryContainer = Violet,
        background = background,
        onBackground = TextPrimary,
        surface = surface,
        onSurface = TextPrimary,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = TextSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHighest,
        outline = NightOutline,
        outlineVariant = NightOutlineSoft,
        error = Danger,
        onError = Color(0xFF2A0000),
        errorContainer = Color(0xFF3B1214),
        onErrorContainer = Danger,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
