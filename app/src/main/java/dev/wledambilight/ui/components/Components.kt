package dev.wledambilight.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wledambilight.core.imaging.LedLayoutMapper
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.ScreenSide
import dev.wledambilight.ui.theme.TextMuted
import dev.wledambilight.ui.theme.TextSecondary
import kotlin.math.min

/** Visible focus ring and a gentle lift for D-pad navigation; touch users simply never see it. */
@Composable
fun Modifier.tvFocusRing(shape: RoundedCornerShape = RoundedCornerShape(14.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "focusScale")
    return this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) IconBadge(icon)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
                trailing?.invoke(this)
            }
            content()
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 36.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/** Status shown with a dot *and* a text label, so it never relies on colour alone. */
@Composable
fun StatusDot(label: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label; stateDescription = valueText },
        )
    }
}

/**
 * Number input that works with a remote: +/-1 buttons and, with [bigStep], +/-[bigStep] buttons
 * for long strips (LED offsets go up to 1500).
 */
@Composable
fun NumberStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    bigStep: Int? = null,
    description: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bigStep != null) {
                StepTextButton("−$bigStep", enabled = value > range.first) {
                    onChange((value - bigStep).coerceIn(range.first, range.last))
                }
            }
            IconButton(
                onClick = { onChange((value - 1).coerceIn(range.first, range.last)) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(50)),
                enabled = value > range.first,
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "$label −1")
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(52.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = { onChange((value + 1).coerceIn(range.first, range.last)) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(50)),
                enabled = value < range.last,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "$label +1")
            }
            if (bigStep != null) {
                StepTextButton("+$bigStep", enabled = value < range.last) {
                    onChange((value + bigStep).coerceIn(range.first, range.last))
                }
            }
        }
    }
}

@Composable
private fun StepTextButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusRing(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        // The row handles the click; the switch itself stays out of the focus order.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = { Text(label(option), style = MaterialTheme.typography.labelLarge) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.tvFocusRing(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** Navigation row for list-style menus ("More" screen). */
@Composable
fun ListRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        color = if (focused) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(icon, tint = tint, size = 42.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
        }
    }
}

/** Soft ambient glow built from the current LED colours; drawn behind the hero card. */
@Composable
fun GlowBackdrop(colors: IntArray, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val glow = remember(colors.size, colors.firstOrNull(), colors.getOrNull(colors.size / 4), colors.getOrNull(colors.size / 2), colors.getOrNull(3 * colors.size / 4)) {
        if (colors.isEmpty()) null else listOf(0, colors.size / 4, colors.size / 2, 3 * colors.size / 4).map { Color(colors[it]) }
    }
    val idle = listOf(
        MaterialTheme.colorScheme.primary,
        dev.wledambilight.ui.theme.Coral,
        MaterialTheme.colorScheme.tertiary,
        dev.wledambilight.ui.theme.Sky,
    )
    val palette = glow ?: idle
    val alpha = if (glow == null) 0.18f else 0.42f
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val anchors = listOf(
                Offset(size.width * 0.15f, 0f),
                Offset(size.width, size.height * 0.2f),
                Offset(size.width * 0.85f, size.height),
                Offset(0f, size.height * 0.8f),
            )
            val radius = maxOf(size.width, size.height) * 0.6f
            palette.forEachIndexed { index, color ->
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = anchors[index % anchors.size],
                        radius = radius,
                    ),
                    radius = radius,
                    center = anchors[index % anchors.size],
                )
            }
        }
        content()
    }
}

/**
 * Draws the physical strip around a stylised screen. Uses the exact same [LedLayoutMapper] as the
 * analyser, so the preview and the real output cannot disagree about LED order.
 */
@Composable
fun LedPerimeterPreview(
    layout: LedLayout,
    colors: IntArray,
    modifier: Modifier = Modifier,
    showMarker: Boolean = false,
) {
    val zones = remember(layout) { LedLayoutMapper.zones(layout) }
    val screen = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.primary
    val idleLed = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .semantics { contentDescription = "LED preview, ${zones.size} LEDs" },
    ) {
        val inset = 14.dp.toPx()
        val rect = Rect(inset, inset, size.width - inset, size.height - inset)
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        val stroke = (min(rect.width, rect.height) * 0.05f).coerceIn(3.dp.toPx(), 14.dp.toPx())

        // Halo: each LED casts its colour outward, like light on the wall.
        zones.forEach { zone ->
            if (zone.index !in colors.indices) return@forEach
            val center = (zone.startFraction + zone.endFraction) / 2f
            val point = pointOnSide(zone.side, center, rect, stroke)
            val color = Color(colors[zone.index])
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), point, stroke * 3.2f),
                radius = stroke * 3.2f,
                center = point,
            )
        }

        val screenInset = stroke * 1.6f
        val corner = CornerRadius(12.dp.toPx())
        drawRoundRect(
            color = screen,
            topLeft = Offset(rect.left + screenInset, rect.top + screenInset),
            size = Size(rect.width - screenInset * 2, rect.height - screenInset * 2),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = outline,
            topLeft = Offset(rect.left + screenInset, rect.top + screenInset),
            size = Size(rect.width - screenInset * 2, rect.height - screenInset * 2),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )

        zones.forEach { zone ->
            val color = if (zone.index in colors.indices) Color(colors[zone.index]) else idleLed
            val center = (zone.startFraction + zone.endFraction) / 2f
            val half = (zone.endFraction - zone.startFraction) * 0.40f
            val from = pointOnSide(zone.side, center - half, rect, stroke)
            val to = pointOnSide(zone.side, center + half, rect, stroke)
            drawLine(color, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
        }

        if (showMarker && zones.isNotEmpty()) {
            val first = zones.first()
            val center = (first.startFraction + first.endFraction) / 2f
            val marker = pointOnSide(first.side, center, rect, stroke)
            drawCircle(color = Color.Black, radius = stroke * 0.95f, center = marker)
            drawCircle(color = markerColor, radius = stroke * 0.7f, center = marker)
            drawCircle(color = Color.White, radius = stroke * 0.3f, center = marker)
        }
    }
}

private fun pointOnSide(side: ScreenSide, fraction: Float, rect: Rect, stroke: Float): Offset {
    val f = fraction.coerceIn(0f, 1f)
    val offset = stroke / 2f + stroke * 0.12f
    return when (side) {
        ScreenSide.TOP -> Offset(rect.left + rect.width * f, rect.top + offset)
        ScreenSide.RIGHT -> Offset(rect.right - offset, rect.top + rect.height * f)
        // Geometric clockwise convention (see LedLayoutMapper): bottom runs right -> left.
        ScreenSide.BOTTOM -> Offset(rect.right - rect.width * f, rect.bottom - offset)
        ScreenSide.LEFT -> Offset(rect.left + offset, rect.bottom - rect.height * f)
    }
}

/** A horizontal bar of LED colours: a compact live preview for extra zones. */
@Composable
fun ColorStrip(colors: IntArray, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2)),
    ) {
        if (colors.isEmpty()) {
            drawRect(idle)
            return@Canvas
        }
        val w = size.width / colors.size
        colors.forEachIndexed { i, argb -> drawRect(Color(argb), Offset(i * w, 0f), Size(w + 0.5f, size.height)) }
    }
}

/**
 * Miniature screen that highlights where a zone takes its colours from, so users can see
 * "desk strip = bottom edge" without reading.
 */
@Composable
fun ZoneSourceMap(
    edge: ScreenSide?,
    region: ScreenRegion?,
    modifier: Modifier = Modifier,
    reverse: Boolean = false,
) {
    val screen = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(112.dp).aspectRatio(16f / 9f)) {
        val corner = CornerRadius(6.dp.toPx())
        drawRoundRect(screen, cornerRadius = corner)
        drawRoundRect(outline, cornerRadius = corner, style = Stroke(1.dp.toPx()))
        val band = size.minDimension * 0.22f
        val highlight = accent.copy(alpha = 0.55f)
        if (edge != null) {
            val (topLeft, rectSize) = when (edge) {
                ScreenSide.TOP -> Offset.Zero to Size(size.width, band)
                ScreenSide.BOTTOM -> Offset(0f, size.height - band) to Size(size.width, band)
                ScreenSide.LEFT -> Offset.Zero to Size(band, size.height)
                ScreenSide.RIGHT -> Offset(size.width - band, 0f) to Size(band, size.height)
            }
            drawRoundRect(highlight, topLeft, rectSize, corner)
            // Arrow showing the physical LED order.
            val horizontal = edge == ScreenSide.TOP || edge == ScreenSide.BOTTOM
            val mid = Offset(topLeft.x + rectSize.width / 2, topLeft.y + rectSize.height / 2)
            val len = (if (horizontal) rectSize.width else rectSize.height) * 0.35f
            val forward = if (horizontal) Offset(len, 0f) else Offset(0f, -len)
            val dir = if (reverse) Offset(-forward.x, -forward.y) else forward
            val start = Offset(mid.x - dir.x, mid.y - dir.y)
            val end = Offset(mid.x + dir.x, mid.y + dir.y)
            drawLine(Color.White, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.White, 3.dp.toPx(), end)
        } else if (region != null) {
            val e = 0.22f
            val (l, t, r, b) = when (region) {
                ScreenRegion.FULL -> listOf(0f, 0f, 1f, 1f)
                ScreenRegion.LEFT_HALF -> listOf(0f, 0f, 0.5f, 1f)
                ScreenRegion.RIGHT_HALF -> listOf(0.5f, 0f, 1f, 1f)
                ScreenRegion.TOP_HALF -> listOf(0f, 0f, 1f, 0.5f)
                ScreenRegion.BOTTOM_HALF -> listOf(0f, 0.5f, 1f, 1f)
                ScreenRegion.LEFT_EDGE -> listOf(0f, 0f, e, 1f)
                ScreenRegion.RIGHT_EDGE -> listOf(1f - e, 0f, 1f, 1f)
                ScreenRegion.TOP_EDGE -> listOf(0f, 0f, 1f, e)
                ScreenRegion.BOTTOM_EDGE -> listOf(0f, 1f - e, 1f, 1f)
                ScreenRegion.CENTER -> listOf(0.25f, 0.25f, 0.75f, 0.75f)
            }
            drawRoundRect(
                highlight,
                Offset(size.width * l, size.height * t),
                Size(size.width * (r - l), size.height * (b - t)),
                corner,
            )
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, size = 56.dp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}
