package dev.wledambilight.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.BlackFrameBehavior
import dev.wledambilight.ui.blackBehaviorLabel
import androidx.compose.foundation.layout.size
import dev.wledambilight.core.model.PerformanceProfile
import dev.wledambilight.core.model.SmoothingPreset
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.SliderRow
import dev.wledambilight.ui.components.SwitchRow
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.performanceLabel
import dev.wledambilight.ui.smoothingLabel
import dev.wledambilight.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ColorScreen(viewModel: AppViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var draft by remember(config.settings) { mutableStateOf(config.settings) }

    fun commit(next: AmbilightSettings) {
        draft = next
        viewModel.updateSettings { next }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.color_title)) {
            SliderRow(
                label = stringResource(R.string.color_brightness),
                valueText = stringResource(R.string.color_brightness_value, draft.brightness),
                value = draft.brightness.toFloat(),
                range = 0f..100f,
                steps = 99,
                onValueChange = { draft = draft.copy(brightness = it.roundToInt()) },
                onValueChangeFinished = { commit(draft) },
            )
            Text(
                stringResource(R.string.color_brightness_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            SliderRow(
                label = stringResource(R.string.color_saturation),
                valueText = format(draft.saturation),
                value = draft.saturation,
                range = 0f..2f,
                onValueChange = { draft = draft.copy(saturation = it) },
                onValueChangeFinished = { commit(draft) },
            )
            SliderRow(
                label = stringResource(R.string.color_gamma),
                valueText = format(draft.gamma),
                value = draft.gamma,
                range = 1.4f..3.0f,
                onValueChange = { draft = draft.copy(gamma = it) },
                onValueChangeFinished = { commit(draft) },
            )
            Text(
                stringResource(R.string.color_gamma_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            SliderRow(
                label = stringResource(R.string.color_min_brightness),
                valueText = stringResource(R.string.color_brightness_value, draft.minBrightness),
                value = draft.minBrightness.toFloat(),
                range = 0f..80f,
                steps = 79,
                onValueChange = { draft = draft.copy(minBrightness = it.roundToInt()) },
                onValueChangeFinished = { commit(draft) },
            )
            SliderRow(
                label = stringResource(R.string.color_edge_thickness),
                valueText = stringResource(
                    R.string.color_edge_thickness_value,
                    (draft.edgeThickness * 100).roundToInt(),
                ),
                value = draft.edgeThickness,
                range = 0.02f..0.35f,
                onValueChange = { draft = draft.copy(edgeThickness = it) },
                onValueChangeFinished = { commit(draft) },
            )
            SliderRow(
                label = stringResource(R.string.color_edge_inset),
                valueText = stringResource(
                    R.string.color_edge_inset_value,
                    (draft.edgeInset * 100).roundToInt(),
                ),
                value = draft.edgeInset,
                range = 0f..0.15f,
                onValueChange = { draft = draft.copy(edgeInset = it) },
                onValueChangeFinished = { commit(draft) },
            )
            SliderRow(
                label = stringResource(R.string.color_edge_weighting),
                valueText = format(draft.edgeWeighting),
                value = draft.edgeWeighting,
                range = 0f..1f,
                onValueChange = { draft = draft.copy(edgeWeighting = it) },
                onValueChangeFinished = { commit(draft) },
            )
            Text(
                stringResource(R.string.color_edge_weighting_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            SwitchRow(
                label = stringResource(R.string.color_black_bars),
                description = stringResource(R.string.color_black_bars_desc),
                checked = draft.blackBarCompensation,
                onCheckedChange = { commit(draft.copy(blackBarCompensation = it)) },
            )
            SwitchRow(
                label = stringResource(R.string.color_keep_screen_on),
                description = stringResource(R.string.color_keep_screen_on_desc),
                checked = draft.keepScreenOn,
                onCheckedChange = { commit(draft.copy(keepScreenOn = it)) },
            )
        }

        SectionCard(title = stringResource(R.string.color_black_title)) {
            SliderRow(
                label = stringResource(R.string.color_black_threshold),
                valueText = stringResource(R.string.color_brightness_value, draft.blackThreshold),
                value = draft.blackThreshold.toFloat(),
                range = 0f..25f,
                steps = 24,
                onValueChange = { draft = draft.copy(blackThreshold = it.roundToInt()) },
                onValueChangeFinished = { commit(draft) },
            )
            Text(
                stringResource(R.string.color_black_threshold_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(stringResource(R.string.color_black_behavior), style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(
                options = BlackFrameBehavior.entries,
                selected = draft.blackFrameBehavior,
                label = { blackBehaviorLabel(it) },
                onSelected = { commit(draft.copy(blackFrameBehavior = it)) },
            )
            if (draft.blackFrameBehavior == BlackFrameBehavior.FALLBACK_COLOR) {
                Text(stringResource(R.string.color_fallback_color), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FALLBACK_SWATCHES.forEach { swatch ->
                        val selected = (draft.fallbackColor or (0xFF shl 24)) == swatch
                        androidx.compose.material3.Surface(
                            onClick = { commit(draft.copy(fallbackColor = swatch)) },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = androidx.compose.ui.graphics.Color(swatch),
                            border = androidx.compose.foundation.BorderStroke(
                                if (selected) 3.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            ),
                            modifier = Modifier
                                .size(40.dp)
                                .tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(50)),
                        ) {}
                    }
                }
            }
            Text(
                stringResource(R.string.color_black_behavior_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        SectionCard(title = stringResource(R.string.color_balance)) {
            SliderRow(
                label = stringResource(R.string.color_balance_red),
                valueText = format(draft.colorBalance.red),
                value = draft.colorBalance.red,
                range = 0.5f..1.5f,
                onValueChange = { draft = draft.copy(colorBalance = draft.colorBalance.copy(red = it)) },
                onValueChangeFinished = { commit(draft) },
            )
            SliderRow(
                label = stringResource(R.string.color_balance_green),
                valueText = format(draft.colorBalance.green),
                value = draft.colorBalance.green,
                range = 0.5f..1.5f,
                onValueChange = { draft = draft.copy(colorBalance = draft.colorBalance.copy(green = it)) },
                onValueChangeFinished = { commit(draft) },
            )
            SliderRow(
                label = stringResource(R.string.color_balance_blue),
                valueText = format(draft.colorBalance.blue),
                value = draft.colorBalance.blue,
                range = 0.5f..1.5f,
                onValueChange = { draft = draft.copy(colorBalance = draft.colorBalance.copy(blue = it)) },
                onValueChangeFinished = { commit(draft) },
            )
        }

        SectionCard(title = stringResource(R.string.color_smoothing)) {
            ChoiceChips(
                options = SmoothingPreset.entries,
                selected = draft.smoothing.preset,
                label = { smoothingLabel(it) },
                onSelected = { preset ->
                    commit(draft.copy(smoothing = draft.smoothing.copy(preset = preset)))
                },
            )
            if (draft.smoothing.preset == SmoothingPreset.CUSTOM) {
                SliderRow(
                    label = stringResource(R.string.color_smoothing_amount),
                    valueText = format(draft.smoothing.amount),
                    value = draft.smoothing.amount,
                    range = 0.05f..0.9f,
                    onValueChange = { draft = draft.copy(smoothing = draft.smoothing.copy(amount = it)) },
                    onValueChangeFinished = { commit(draft) },
                )
                SliderRow(
                    label = stringResource(R.string.color_smoothing_threshold),
                    valueText = format(draft.smoothing.threshold),
                    value = draft.smoothing.threshold,
                    range = 0f..0.2f,
                    onValueChange = { draft = draft.copy(smoothing = draft.smoothing.copy(threshold = it)) },
                    onValueChangeFinished = { commit(draft) },
                )
                SliderRow(
                    label = stringResource(R.string.color_smoothing_max_step),
                    valueText = format(draft.smoothing.maxStep),
                    value = draft.smoothing.maxStep,
                    range = 0.02f..1f,
                    onValueChange = { draft = draft.copy(smoothing = draft.smoothing.copy(maxStep = it)) },
                    onValueChangeFinished = { commit(draft) },
                )
            }
        }

        SectionCard(title = stringResource(R.string.color_performance)) {
            ChoiceChips(
                options = PerformanceProfile.entries,
                selected = draft.performanceProfile,
                label = { performanceLabel(it) },
                onSelected = { profile ->
                    commit(draft.copy(performanceProfile = profile))
                },
            )
            Text(
                stringResource(R.string.color_performance_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    commit(
                        AmbilightSettings(
                            theme = draft.theme,
                            keepScreenOn = draft.keepScreenOn,
                        ),
                    )
                },
                modifier = Modifier.tvFocusRing(),
            ) {
                Text(stringResource(R.string.color_reset))
            }
        }
    }
}

private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

/** Warm amber (default), soft white, blue, violet and red as quick fallback colours. */
private val FALLBACK_SWATCHES = listOf(
    AmbilightSettings.DEFAULT_FALLBACK_COLOR,
    0xFF8C7A60.toInt(),
    0xFF1E3A8A.toInt(),
    0xFF4C1D95.toInt(),
    0xFF7F1D1D.toInt(),
)
