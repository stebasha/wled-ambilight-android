package dev.wledambilight.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.wledambilight.core.model.AppTheme

@Composable
fun WledAmbilightTheme(
    theme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit,
) {
    val background = if (theme == AppTheme.BLACK) Color.Black else NightBackground
    val surface = if (theme == AppTheme.BLACK) Color(0xFF050505) else NightSurface
    val surfaceHigh = if (theme == AppTheme.BLACK) Color(0xFF0D0D0D) else NightSurfaceHigh
    val scheme = darkColorScheme(
        primary = Amber,
        onPrimary = Color(0xFF1A1200),
        primaryContainer = AmberDeep,
        onPrimaryContainer = Color(0xFF1A1200),
        secondary = Mint,
        onSecondary = Color(0xFF001A10),
        tertiary = Sky,
        onTertiary = Color(0xFF001A24),
        background = background,
        onBackground = TextPrimary,
        surface = surface,
        onSurface = TextPrimary,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = TextSecondary,
        outline = NightOutline,
        outlineVariant = NightOutline,
        error = Danger,
        onError = Color(0xFF2A0000),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content,
    )
}
