package dev.wledambilight.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wledambilight.core.imaging.LedLayoutMapper
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.ScreenSide
import dev.wledambilight.ui.theme.TextSecondary
import kotlin.math.min

/** Visible focus ring for D-pad navigation; touch users simply never see it. */
@Composable
fun Modifier.tvFocusRing(shape: RoundedCornerShape = RoundedCornerShape(14.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun StatusDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(5.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun NumberStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - 1).coerceIn(range.first, range.last)) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(50)),
                enabled = value > range.first,
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "−")
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            IconButton(
                onClick = { onChange((value + 1).coerceIn(range.first, range.last)) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(50)),
                enabled = value < range.last,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "+")
            }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                label = { Text(label(option), fontSize = 14.sp) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(12.dp)),
            )
        }
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
    val outline = MaterialTheme.colorScheme.outline
    val background = MaterialTheme.colorScheme.surfaceVariant
    val markerColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    ) {
        val inset = 12.dp.toPx()
        val rect = Rect(inset, inset, size.width - inset, size.height - inset)
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        val corner = CornerRadius(14.dp.toPx())
        drawRoundRect(color = background, topLeft = rect.topLeft, size = rect.size, cornerRadius = corner)
        drawRoundRect(
            color = outline,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )

        val stroke = (min(rect.width, rect.height) * 0.045f).coerceIn(3.dp.toPx(), 14.dp.toPx())
        zones.forEach { zone ->
            val color = if (zone.index in colors.indices) Color(colors[zone.index]) else Color(0xFF2C3A4D)
            val center = (zone.startFraction + zone.endFraction) / 2f
            val half = (zone.endFraction - zone.startFraction) * 0.42f
            val from = pointOnSide(zone.side, center - half, rect, stroke)
            val to = pointOnSide(zone.side, center + half, rect, stroke)
            drawLine(color, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
        }

        if (showMarker && zones.isNotEmpty()) {
            val first = zones.first()
            val center = (first.startFraction + first.endFraction) / 2f
            val marker = pointOnSide(first.side, center, rect, stroke)
            drawCircle(color = Color.Black, radius = stroke * 0.9f, center = marker)
            drawCircle(color = markerColor, radius = stroke * 0.65f, center = marker)
            drawCircle(color = Color.White, radius = stroke * 0.28f, center = marker)
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
