package dev.wledambilight.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.core.model.LedDirection
import dev.wledambilight.core.model.StartCorner
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import dev.wledambilight.ui.components.LedPerimeterPreview
import dev.wledambilight.ui.components.NumberStepper
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.SwitchRow
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.cornerLabel
import dev.wledambilight.ui.directionLabel
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.theme.Warning
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun CalibrationScreen(viewModel: AppViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val device by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val layout = config.layout
    val issues = layout.issues()

    var chaseRunning by remember { mutableStateOf(false) }
    var chaseIndex by remember { mutableIntStateOf(0) }
    var flashFirst by remember { mutableStateOf(false) }

    val baseColor = 0xFF16212E.toInt()
    val amber = 0xFFFFB020.toInt()
    val previewColors = remember(layout, chaseRunning, chaseIndex, flashFirst) {
        val colors = IntArray(layout.total) { baseColor }
        if (colors.isNotEmpty()) {
            if (chaseRunning) {
                colors[chaseIndex.coerceIn(colors.indices)] = amber
            } else if (flashFirst) {
                colors[0] = amber
            }
        }
        colors
    }

    LaunchedEffect(chaseRunning, layout, device?.id) {
        if (!chaseRunning || device == null || !layout.isValid()) return@LaunchedEffect
        var index = 0
        while (isActive) {
            val colors = IntArray(layout.total) { baseColor }
            colors[index] = amber
            chaseIndex = index
            viewModel.sendTestPattern(colors)
            delay(160)
            index = (index + 1) % layout.total
        }
    }

    LaunchedEffect(flashFirst) {
        if (flashFirst) {
            delay(1200)
            flashFirst = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.calibration_counts)) {
            NumberStepper(
                label = stringResource(R.string.calibration_top),
                value = layout.topCount,
                range = 0..400,
                onChange = { value -> viewModel.updateLayout { it.copy(topCount = value) } },
            )
            NumberStepper(
                label = stringResource(R.string.calibration_right),
                value = layout.rightCount,
                range = 0..400,
                onChange = { value -> viewModel.updateLayout { it.copy(rightCount = value) } },
            )
            NumberStepper(
                label = stringResource(R.string.calibration_bottom),
                value = layout.bottomCount,
                range = 0..400,
                onChange = { value -> viewModel.updateLayout { it.copy(bottomCount = value) } },
            )
            NumberStepper(
                label = stringResource(R.string.calibration_left),
                value = layout.leftCount,
                range = 0..400,
                onChange = { value -> viewModel.updateLayout { it.copy(leftCount = value) } },
            )
            Text(stringResource(R.string.calibration_total, layout.total), color = TextSecondary)
            if (issues.isNotEmpty()) {
                Text(
                    stringResource(R.string.calibration_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionCard(title = stringResource(R.string.calibration_start_corner)) {
            ChoiceChips(
                options = StartCorner.entries,
                selected = layout.startCorner,
                label = { cornerLabel(it) },
                onSelected = { corner -> viewModel.updateLayout { it.copy(startCorner = corner) } },
            )
        }

        SectionCard(title = stringResource(R.string.calibration_direction)) {
            ChoiceChips(
                options = LedDirection.entries,
                selected = layout.direction,
                label = { directionLabel(it) },
                onSelected = { direction -> viewModel.updateLayout { it.copy(direction = direction) } },
            )
        }

        SectionCard(title = stringResource(R.string.calibration_reverse)) {
            SwitchRow(
                label = stringResource(R.string.calibration_reverse_top),
                checked = layout.reverseTop,
                onCheckedChange = { value -> viewModel.updateLayout { it.copy(reverseTop = value) } },
            )
            SwitchRow(
                label = stringResource(R.string.calibration_reverse_right),
                checked = layout.reverseRight,
                onCheckedChange = { value -> viewModel.updateLayout { it.copy(reverseRight = value) } },
            )
            SwitchRow(
                label = stringResource(R.string.calibration_reverse_bottom),
                checked = layout.reverseBottom,
                onCheckedChange = { value -> viewModel.updateLayout { it.copy(reverseBottom = value) } },
            )
            SwitchRow(
                label = stringResource(R.string.calibration_reverse_left),
                checked = layout.reverseLeft,
                onCheckedChange = { value -> viewModel.updateLayout { it.copy(reverseLeft = value) } },
            )
        }

        SectionCard(title = stringResource(R.string.calibration_preview)) {
            LedPerimeterPreview(layout = layout, colors = previewColors, showMarker = true)
            Text(
                stringResource(R.string.calibration_preview_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            if (device?.ledCount != null && device?.ledCount != layout.total) {
                Text(
                    text = stringResource(R.string.calibration_mismatch, layout.total, device?.ledCount ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warning,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (device != null && layout.total > 0) {
                            val colors = IntArray(layout.total) { baseColor }
                            colors[0] = amber
                            flashFirst = true
                            viewModel.sendTestPatternOnce(colors)
                        }
                    },
                    enabled = device != null && layout.total > 0 && layout.isValid(),
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(stringResource(R.string.calibration_first_led))
                }
                Button(
                    onClick = { chaseRunning = !chaseRunning },
                    enabled = device != null && layout.total > 0 && layout.isValid(),
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(
                        stringResource(
                            if (chaseRunning) R.string.action_stop_test else R.string.calibration_chase,
                        ),
                    )
                }
            }
            if (device == null) {
                Text(
                    stringResource(R.string.calibration_no_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warning,
                )
            }
        }

        Text(
            stringResource(R.string.calibration_chase_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
