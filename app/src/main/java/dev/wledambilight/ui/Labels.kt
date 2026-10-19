package dev.wledambilight.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.wledambilight.R
import dev.wledambilight.core.model.LedDirection
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.PerformanceProfile
import dev.wledambilight.core.model.SmoothingPreset
import dev.wledambilight.core.model.StartCorner
import dev.wledambilight.core.model.WledUdpMode
import dev.wledambilight.engine.EngineState

@Composable
fun smoothingLabel(preset: SmoothingPreset): String = stringResource(
    when (preset) {
        SmoothingPreset.OFF -> R.string.smoothing_off
        SmoothingPreset.FAST -> R.string.smoothing_fast
        SmoothingPreset.BALANCED -> R.string.smoothing_balanced
        SmoothingPreset.SMOOTH -> R.string.smoothing_smooth
        SmoothingPreset.CUSTOM -> R.string.smoothing_custom
    },
)

@Composable
fun performanceLabel(profile: PerformanceProfile): String = stringResource(
    when (profile) {
        PerformanceProfile.POWER_SAVER -> R.string.performance_power_saver
        PerformanceProfile.BALANCED -> R.string.performance_balanced
        PerformanceProfile.SMOOTH -> R.string.performance_smooth
        PerformanceProfile.LOW_LATENCY -> R.string.performance_low_latency
    },
)

@Composable
fun cornerLabel(corner: StartCorner): String = stringResource(
    when (corner) {
        StartCorner.TOP_LEFT -> R.string.calibration_corner_tl
        StartCorner.TOP_RIGHT -> R.string.calibration_corner_tr
        StartCorner.BOTTOM_RIGHT -> R.string.calibration_corner_br
        StartCorner.BOTTOM_LEFT -> R.string.calibration_corner_bl
    },
)

@Composable
fun directionLabel(direction: LedDirection): String = stringResource(
    when (direction) {
        LedDirection.CLOCKWISE -> R.string.calibration_cw
        LedDirection.COUNTER_CLOCKWISE -> R.string.calibration_ccw
    },
)

@Composable
fun protocolLabel(protocol: OutputProtocol): String = stringResource(
    when (protocol) {
        OutputProtocol.DDP -> R.string.protocol_ddp
        OutputProtocol.WLED_UDP -> R.string.protocol_wled_udp
    },
)

@Composable
fun wireModeLabel(mode: WledUdpMode): String = stringResource(
    when (mode) {
        WledUdpMode.WARLS -> R.string.mode_warls
        WledUdpMode.DRGB -> R.string.mode_drgb
        WledUdpMode.DNRGB -> R.string.mode_dnrgb
    },
)

fun EngineState.statusLabelRes(): Int = when (this) {
    EngineState.Idle -> R.string.status_idle
    EngineState.Starting -> R.string.status_starting
    is EngineState.Running -> R.string.status_running
    is EngineState.Failed -> R.string.status_failed
}
