package dev.wledambilight.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import dev.wledambilight.ui.components.EmptyState
import dev.wledambilight.ui.components.ScreenHeader
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.SliderRow
import dev.wledambilight.ui.components.SwitchRow
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.hintRes
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.theme.Warning
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControllerScreen(viewModel: AppViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val selected by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val state by viewModel.controller.collectAsStateWithLifecycle()
    val wled = devices.filter { it.isWled }
    var deviceId by remember { mutableStateOf(selected?.id) }
    val device = wled.firstOrNull { it.id == deviceId } ?: wled.firstOrNull()
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(device?.id) { viewModel.loadController(device) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.ctrl_title),
                subtitle = stringResource(R.string.ctrl_subtitle),
            ) {
                if (device != null) {
                    OutlinedButton(onClick = { viewModel.loadController(device) }, modifier = Modifier.tvFocusRing()) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.ctrl_reload))
                    }
                }
            }
        }

        if (device == null) {
            item {
                EmptyState(Icons.Rounded.SettingsRemote, stringResource(R.string.ctrl_title), stringResource(R.string.ctrl_no_wled))
            }
            return@LazyColumn
        }

        if (wled.size > 1) {
            item {
                ChoiceChips(options = wled, selected = device, label = { it.displayName }, onSelected = { deviceId = it.id })
            }
        }

        if (state.loading) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(stringResource(R.string.ctrl_loading), color = TextSecondary)
                }
            }
        }

        state.errorCode?.let { code ->
            item {
                SectionCard(title = stringResource(R.string.ctrl_error)) {
                    code.hintRes()?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }
        }

        if (!state.loading && state.errorCode == null && state.deviceId == device.id) {
            item {
                SectionCard(title = device.displayName, subtitle = device.host, icon = Icons.Rounded.PowerSettingsNew) {
                    SwitchRow(
                        label = stringResource(R.string.ctrl_power),
                        checked = state.on == true,
                        onCheckedChange = { viewModel.setControllerPower(device, it) },
                    )
                    var bri by remember(state.brightness) { mutableFloatStateOf((state.brightness ?: 128).toFloat()) }
                    SliderRow(
                        label = stringResource(R.string.ctrl_brightness),
                        valueText = "${(bri / 2.55f).roundToInt()}%",
                        value = bri,
                        range = 1f..255f,
                        onValueChange = { bri = it },
                        onValueChangeFinished = { viewModel.setControllerBrightness(device, bri.roundToInt()) },
                    )
                    if (state.live == true) {
                        Surface(color = Warning.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium) {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.ctrl_live),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Warning,
                                    modifier = Modifier.padding12(),
                                )
                                Text(
                                    stringResource(R.string.ctrl_live_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding12(),
                                )
                                OutlinedButton(
                                    onClick = { viewModel.releaseRealtime(device) },
                                    modifier = Modifier.padding12().tvFocusRing(),
                                ) { Text(stringResource(R.string.ctrl_release)) }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.ctrl_presets), icon = Icons.Rounded.Bookmarks) {
                    if (state.presets.isEmpty()) {
                        Text(stringResource(R.string.ctrl_presets_empty), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.presets.forEach { preset ->
                                FilterChip(
                                    selected = state.presetId == preset.id,
                                    onClick = { viewModel.applyPreset(device, preset.id) },
                                    label = { Text(preset.name) },
                                    leadingIcon = if (state.presetId == preset.id) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else {
                                        null
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.tvFocusRing(RoundedCornerShape(12.dp)),
                                )
                            }
                        }
                    }
                }
            }

            if (state.effects.isNotEmpty()) {
                item {
                    SectionCard(title = stringResource(R.string.ctrl_effects), icon = Icons.Rounded.AutoAwesome) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it.take(32) },
                            label = { Text(stringResource(R.string.ctrl_filter)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val shown = state.effects.withIndex()
                    .filter { it.value != "RSVD" && it.value != "-" && it.value.contains(filter.trim(), ignoreCase = true) }
                    .toList()
                items(shown, key = { it.index }) { (id, name) ->
                    val active = state.effectId == id
                    Surface(
                        onClick = { viewModel.applyEffect(device, id) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(18.dp)),
                    ) {
                        Row(
                            Modifier.padding12(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("#$id", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Text(name.substringBefore('@'), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (active) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.padding12(): Modifier = this.padding(12.dp)
