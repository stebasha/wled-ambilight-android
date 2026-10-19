package dev.wledambilight.ui.screens

import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.SmoothingPreset
import dev.wledambilight.engine.EngineState
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import dev.wledambilight.ui.components.LedPerimeterPreview
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.SliderRow
import dev.wledambilight.ui.components.StatTile
import dev.wledambilight.ui.components.StatusDot
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.hintRes
import dev.wledambilight.ui.smoothingLabel
import dev.wledambilight.ui.statusLabelRes
import dev.wledambilight.ui.titleRes
import dev.wledambilight.ui.theme.Danger
import dev.wledambilight.ui.theme.Mint
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.theme.Warning
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onOpenDevices: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val device by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val busyDeviceId by viewModel.busyDeviceId.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val projectionManager = remember(context) {
        context.getSystemService(MediaProjectionManager::class.java)
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.startSession(result.resultCode, data)
        } else {
            viewModel.reportProjectionDenied()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    fun requestSession() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    val running = state as? EngineState.Running
    val failed = state as? EngineState.Failed
    val runningNow = running != null
    val starting = state is EngineState.Starting
    val keepScreenOn = config.settings.keepScreenOn && runningNow
    val activity = context as? Activity
    DisposableEffect(keepScreenOn, activity) {
        if (keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (keepScreenOn) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val startFocus = remember { FocusRequester() }
    val stopFocus = remember { FocusRequester() }
    LaunchedEffect(runningNow) {
        runCatching {
            if (runningNow) stopFocus.requestFocus() else startFocus.requestFocus()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                StatusDot(
                    label = stringResource(state.statusLabelRes()),
                    color = when (state) {
                        EngineState.Idle -> TextSecondary
                        EngineState.Starting -> Warning
                        is EngineState.Running -> Mint
                        is EngineState.Failed -> Danger
                    },
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.home_session)) {
                if (running != null) {
                    Text(
                        text = stringResource(R.string.notification_running, running.deviceName, running.protocolLabel),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_start_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Button(
                        onClick = { viewModel.stopSession() },
                        modifier = Modifier
                            .focusRequester(stopFocus)
                            .tvFocusRing(),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Text(
                            text = stringResource(R.string.action_stop),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_start_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    if (device == null) {
                        Text(
                            text = stringResource(R.string.home_no_device),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Warning,
                        )
                    }
                    Button(
                        onClick = { requestSession() },
                        enabled = device != null && !starting,
                        modifier = Modifier
                            .focusRequester(startFocus)
                            .tvFocusRing(),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Text(
                            text = stringResource(
                                if (starting) R.string.status_starting else R.string.action_start,
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        if (failed != null) {
            item {
                SectionCard(title = stringResource(R.string.home_error_title)) {
                    Text(
                        text = stringResource(failed.code.titleRes()),
                        style = MaterialTheme.typography.titleMedium,
                        color = Danger,
                    )
                    failed.code.hintRes()?.let { hint ->
                        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                    failed.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                    TextButton(onClick = onOpenDiagnostics) {
                        Text(stringResource(R.string.home_open_diagnostics))
                    }
                }
            }
        }

        if (stats.blackFrameSeconds >= 2f) {
            item {
                SectionCard(title = stringResource(R.string.home_black_frame_title)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = Warning)
                        Text(
                            text = stringResource(R.string.home_black_frame_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.home_live_preview)) {
                LedPerimeterPreview(layout = config.layout, colors = preview)
                if (preview.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_preview_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.diagnostics_device)) {
                if (device == null) {
                    Text(
                        text = stringResource(R.string.home_no_device),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Button(onClick = onOpenDevices, modifier = Modifier.tvFocusRing()) {
                        Text(stringResource(R.string.home_open_devices))
                    }
                } else {
                    val selected = device!!
                    val unknown = stringResource(R.string.label_unknown)
                    val firmwareLabel = stringResource(R.string.devices_firmware)
                    val ledsLabel = stringResource(R.string.devices_leds)
                    Text(selected.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${selected.host}:${selected.httpPort} · ${viewModel.selectedProtocolLabel(selected)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Text(
                        text = "$firmwareLabel: ${selected.firmwareVersion ?: unknown}   " +
                            "$ledsLabel: ${selected.ledCount ?: unknown}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { viewModel.probeDevice(selected) },
                            enabled = busyDeviceId != selected.id,
                            modifier = Modifier.tvFocusRing(),
                        ) {
                            Text(
                                stringResource(
                                    if (busyDeviceId == selected.id) R.string.devices_probe_running else R.string.action_test,
                                ),
                            )
                        }
                        TextButton(onClick = onOpenDevices) {
                            Text(stringResource(R.string.home_open_devices))
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.home_stats)) {
                if (runningNow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = stringResource(R.string.diagnostics_fps),
                            value = stats.fps.roundToInt().toString(),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = stringResource(R.string.diagnostics_analysis_time),
                            value = "${stats.analysisMs.roundToInt()} ms",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            label = stringResource(R.string.diagnostics_packets),
                            value = stats.packetsPerSecond.roundToInt().toString(),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = stringResource(R.string.diagnostics_resolution),
                            value = "${stats.analysisWidth}×${stats.analysisHeight}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.diagnostics_not_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        item {
            val settings = config.settings
            SectionCard(title = stringResource(R.string.home_quick_settings)) {
                BrightnessQuickControl(
                    settings = settings,
                    onCommit = { value -> viewModel.updateSettings { it.copy(brightness = value) } },
                )
                Text(
                    text = stringResource(R.string.color_smoothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                ChoiceChips(
                    options = SmoothingPreset.entries,
                    selected = settings.smoothing.preset,
                    label = { smoothingLabel(it) },
                    onSelected = { preset ->
                        viewModel.updateSettings {
                            it.copy(smoothing = it.smoothing.copy(preset = preset))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BrightnessQuickControl(settings: AmbilightSettings, onCommit: (Int) -> Unit) {
    var value by remember(settings.brightness) { mutableFloatStateOf(settings.brightness.toFloat()) }
    SliderRow(
        label = stringResource(R.string.color_brightness),
        valueText = stringResource(R.string.color_brightness_value, value.roundToInt()),
        value = value,
        range = 0f..100f,
        steps = 99,
        onValueChange = { value = it },
        onValueChangeFinished = { onCommit(value.roundToInt()) },
    )
}
