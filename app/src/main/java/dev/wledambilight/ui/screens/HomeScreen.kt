package dev.wledambilight.ui.screens

import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.SmoothingPreset
import dev.wledambilight.engine.EngineState
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import dev.wledambilight.ui.components.ColorStrip
import dev.wledambilight.ui.components.GlowBackdrop
import dev.wledambilight.ui.components.LedPerimeterPreview
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.SliderRow
import dev.wledambilight.ui.components.StatTile
import dev.wledambilight.ui.components.StatusDot
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.hintRes
import dev.wledambilight.ui.smoothingLabel
import dev.wledambilight.ui.statusLabelRes
import dev.wledambilight.ui.theme.BrandGradient
import dev.wledambilight.ui.theme.Danger
import dev.wledambilight.ui.theme.Mint
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.theme.Violet
import dev.wledambilight.ui.theme.Warning
import dev.wledambilight.ui.titleRes
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onOpenDevices: () -> Unit,
    onOpenZones: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val device by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val zonePreview by viewModel.zonePreview.collectAsStateWithLifecycle()

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
    val enabledZones = config.zones.filter { it.enabled }
    val hasOutputs = (config.mainStripEnabled && device != null) || enabledZones.isNotEmpty()

    val keepScreenOn = config.settings.keepScreenOn && runningNow
    val activity = context as? Activity
    DisposableEffect(keepScreenOn, activity) {
        if (keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (keepScreenOn) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val powerFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { powerFocus.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            GlowBackdrop(colors = preview, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
                            Text(
                                stringResource(R.string.app_tagline),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                            )
                        }
                        StatusDot(
                            label = stringResource(state.statusLabelRes()),
                            color = when (state) {
                                EngineState.Idle -> TextSecondary
                                EngineState.Starting -> Warning
                                is EngineState.Running -> if (running?.demo == true) Violet else Mint
                                is EngineState.Failed -> Danger
                            },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        PowerButton(
                            running = runningNow,
                            busy = starting,
                            enabled = runningNow || (!starting && hasOutputs),
                            modifier = Modifier.focusRequester(powerFocus),
                            onClick = { if (runningNow) viewModel.stopSession() else requestSession() },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val title = when {
                                running != null && running.demo -> stringResource(R.string.home_demo_running)
                                running != null -> "${running.deviceName} · ${running.protocolLabel}"
                                device != null -> device!!.displayName
                                else -> stringResource(R.string.home_no_device)
                            }
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                running != null -> stringResource(R.string.home_outputs, running.targets)
                                !hasOutputs -> stringResource(R.string.home_no_outputs)
                                else -> stringResource(R.string.home_start_hint)
                            }
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!runningNow && !starting) {
                                    OutlinedButton(
                                        onClick = { viewModel.startDemo() },
                                        modifier = Modifier.tvFocusRing(),
                                    ) {
                                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text(stringResource(R.string.home_demo), modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                                if (device == null && !runningNow) {
                                    TextButton(onClick = onOpenDevices, modifier = Modifier.tvFocusRing()) {
                                        Text(stringResource(R.string.home_open_devices))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (failed != null) {
            item {
                SectionCard(
                    title = stringResource(failed.code.titleRes()),
                    icon = Icons.Rounded.ErrorOutline,
                ) {
                    failed.code.hintRes()?.let { hint ->
                        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                    failed.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    TextButton(onClick = onOpenDiagnostics, modifier = Modifier.tvFocusRing()) {
                        Text(stringResource(R.string.home_open_diagnostics))
                    }
                }
            }
        }

        if (stats.blackFrameSeconds >= 2f) {
            item {
                SectionCard(title = stringResource(R.string.home_black_frame_title), icon = Icons.Rounded.Warning) {
                    Text(
                        text = stringResource(R.string.home_black_frame_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.home_live_preview),
                subtitle = if (preview.isEmpty()) stringResource(R.string.home_preview_placeholder) else null,
                icon = Icons.Rounded.Insights,
            ) {
                LedPerimeterPreview(layout = config.layout, colors = preview)
            }
        }

        if (config.zones.isNotEmpty()) {
            item {
                SectionCard(
                    title = stringResource(R.string.home_zones_title),
                    icon = Icons.Rounded.Dashboard,
                    trailing = {
                        TextButton(onClick = onOpenZones, modifier = Modifier.tvFocusRing()) {
                            Text(stringResource(R.string.action_edit))
                        }
                    },
                ) {
                    config.zones.forEachIndexed { index, zone ->
                        val zoneDevice = devices.firstOrNull { it.id == zone.deviceId }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    zone.name.ifBlank { stringResource(R.string.zone_default_name, index + 1) },
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                    color = if (zone.enabled) MaterialTheme.colorScheme.onSurface else TextSecondary,
                                )
                                Text(
                                    zoneDevice?.displayName ?: "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                )
                            }
                            ColorStrip(colors = zonePreview[zone.id] ?: IntArray(0))
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.home_stats), icon = Icons.Rounded.Speed) {
                if (runningNow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            label = stringResource(R.string.diagnostics_packets),
                            value = stats.packetsPerSecond.roundToInt().toString(),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = stringResource(R.string.home_outputs_label),
                            value = stringResource(
                                R.string.home_outputs_value,
                                (stats.outputs - stats.outputsDown).coerceAtLeast(0),
                                stats.outputs,
                            ),
                            accent = if (stats.outputsDown > 0) Warning else null,
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
            SectionCard(title = stringResource(R.string.home_quick_settings), icon = Icons.Rounded.PowerSettingsNew) {
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
                        viewModel.updateSettings { it.copy(smoothing = it.smoothing.copy(preset = preset)) }
                    },
                )
            }
        }
    }
}

/** Large round start/stop control; easy to hit with a thumb and obvious with a remote. */
@Composable
private fun PowerButton(
    running: Boolean,
    busy: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "glow",
    )
    val label = stringResource(if (running) R.string.action_stop else R.string.action_start)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        if (running) {
            Box(
                Modifier
                    .size(108.dp)
                    .scale(glow)
                    .background(
                        Brush.radialGradient(listOf(Mint.copy(alpha = 0.45f), Color.Transparent)),
                        CircleShape,
                    ),
            )
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = modifier
                .size(if (focused) 98.dp else 92.dp)
                .semantics { contentDescription = label }
                .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (!enabled) {
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            )
                        } else if (running) {
                            Brush.linearGradient(listOf(Mint, Color(0xFF2AA67A)))
                        } else {
                            BrandGradient
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (enabled) Color(0xFF14100A) else TextSecondary,
                        modifier = Modifier.size(44.dp),
                    )
                }
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
