package dev.wledambilight.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.DeviceKind
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.LightZone
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.ScreenSide
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.ZoneMapping
import dev.wledambilight.engine.OutputPlanner
import dev.wledambilight.engine.PlanProblemKind
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import dev.wledambilight.ui.components.ColorStrip
import dev.wledambilight.ui.components.EmptyState
import dev.wledambilight.ui.components.NumberStepper
import dev.wledambilight.ui.components.Pill
import dev.wledambilight.ui.components.ScreenHeader
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.SliderRow
import dev.wledambilight.ui.components.SwitchRow
import dev.wledambilight.ui.components.ZoneSourceMap
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.mappingLabel
import dev.wledambilight.ui.regionLabel
import dev.wledambilight.ui.sideLabel
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.theme.Warning
import kotlin.math.roundToInt

@Composable
fun ZonesScreen(viewModel: AppViewModel, onOpenDevices: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val selected by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val zonePreview by viewModel.zonePreview.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<LightZone?>(null) }
    var deleteTarget by remember { mutableStateOf<LightZone?>(null) }

    // Same planner as the engine: problems shown here are exactly what a session would skip.
    val problems = remember(config, devices, selected) {
        OutputPlanner.plan(config, selected, devices).problems.associateBy { it.key }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.zones_title),
                subtitle = stringResource(R.string.zones_subtitle),
            ) {
                if (devices.isNotEmpty()) {
                    Button(
                        onClick = { editing = viewModel.newZone(devices.firstOrNull { it.isWled } ?: devices.first()) },
                        modifier = Modifier.tvFocusRing(),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.zones_add), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.zones_main),
                icon = Icons.Rounded.Tv,
                subtitle = selected?.let {
                    stringResource(R.string.zones_main_desc, it.displayName, (config.layout.total - 1).coerceAtLeast(0))
                } ?: stringResource(R.string.zones_main_none),
                trailing = {
                    Switch(
                        checked = config.mainStripEnabled,
                        onCheckedChange = { viewModel.setMainStripEnabled(it) },
                        modifier = Modifier.tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(50)),
                    )
                },
            ) {
                Text(
                    stringResource(R.string.zones_sync_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        if (devices.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Dashboard,
                    title = stringResource(R.string.zones_empty_title),
                    body = stringResource(R.string.zones_no_devices),
                ) {
                    Button(onClick = onOpenDevices, modifier = Modifier.tvFocusRing()) {
                        Text(stringResource(R.string.home_open_devices))
                    }
                }
            }
        } else if (config.zones.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Dashboard,
                    title = stringResource(R.string.zones_empty_title),
                    body = stringResource(R.string.zones_empty_body),
                )
            }
        }

        itemsIndexed(config.zones, key = { _, zone -> zone.id }) { index, zone ->
            ZoneCard(
                zone = zone,
                index = index,
                device = devices.firstOrNull { it.id == zone.deviceId },
                problem = problems[zone.id]?.kind,
                colors = zonePreview[zone.id],
                onToggle = { viewModel.setZoneEnabled(zone.id, it) },
                onTest = { viewModel.testZone(zone) },
                onEdit = { editing = zone },
                onDelete = { deleteTarget = zone },
            )
        }
    }

    editing?.let { zone ->
        ZoneDialog(
            initial = zone,
            isNew = config.zones.none { it.id == zone.id },
            index = config.zones.size,
            devices = devices,
            config = config,
            selected = selected,
            nextFree = { device -> viewModel.nextFreeOffset(device, excludingZoneId = zone.id) },
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveZone(it)
                editing = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.zone_delete_title)) },
            text = { Text(stringResource(R.string.zone_delete_body, target.name.ifBlank { "—" })) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteZone(target.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ZoneCard(
    zone: LightZone,
    index: Int,
    device: WledDevice?,
    problem: PlanProblemKind?,
    colors: IntArray?,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isGovee = device?.kind == DeviceKind.GOVEE
    SectionCard(
        title = zone.name.ifBlank { stringResource(R.string.zone_default_name, index + 1) },
        subtitle = device?.displayName ?: stringResource(R.string.zone_problem_device),
        icon = if (isGovee) Icons.Rounded.Lightbulb else Icons.Rounded.LinearScale,
        trailing = {
            Switch(
                checked = zone.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(50)),
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ZoneSourceMap(
                edge = if (zone.mapping == ZoneMapping.EDGE) zone.edge else null,
                region = when (zone.mapping) {
                    ZoneMapping.SOLID -> zone.region
                    ZoneMapping.PERIMETER -> ScreenRegion.FULL
                    ZoneMapping.EDGE -> null
                },
                reverse = zone.reverse,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(mappingLabel(zone.mapping))
                    if (zone.mapping == ZoneMapping.EDGE) Pill(sideLabel(zone.edge), MaterialTheme.colorScheme.tertiary)
                    if (zone.mapping == ZoneMapping.SOLID) Pill(regionLabel(zone.region), MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    if (isGovee) {
                        stringResource(R.string.zone_lamp)
                    } else {
                        val count = zone.ledsFor(DeviceKind.WLED)
                        stringResource(R.string.zone_range, zone.ledOffset, zone.ledOffset + count - 1)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
        if (colors != null) ColorStrip(colors)
        if (problem != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(problemText(problem)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onTest, enabled = device != null, modifier = Modifier.tvFocusRing()) {
                Text(stringResource(R.string.action_test))
            }
            TextButton(onClick = onEdit, modifier = Modifier.tvFocusRing()) { Text(stringResource(R.string.action_edit)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete, modifier = Modifier.tvFocusRing()) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun problemText(kind: PlanProblemKind): Int = when (kind) {
    PlanProblemKind.ZONE_OVERLAP -> R.string.zone_problem_overlap
    PlanProblemKind.ZONE_DEVICE_MISSING -> R.string.zone_problem_device
    else -> R.string.zone_problem_protocol
}

@Composable
private fun ZoneDialog(
    initial: LightZone,
    isNew: Boolean,
    index: Int,
    devices: List<WledDevice>,
    config: AmbilightConfig,
    selected: WledDevice?,
    nextFree: (WledDevice) -> Int,
    onDismiss: () -> Unit,
    onSave: (LightZone) -> Unit,
) {
    var zone by remember { mutableStateOf(initial) }
    val device = devices.firstOrNull { it.id == zone.deviceId }
    val isGovee = device?.kind == DeviceKind.GOVEE
    val defaultName = stringResource(R.string.zone_default_name, index + 1)

    // Validate the draft exactly as the engine would.
    val draftProblem = remember(zone, config, devices, selected) {
        val others = config.zones.filterNot { it.id == zone.id }
        OutputPlanner.plan(config.copy(zones = others + zone.copy(enabled = true)), selected, devices)
            .problems.firstOrNull { it.key == zone.id }?.kind
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.zone_dialog_add else R.string.zone_dialog_edit)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = zone.name,
                    onValueChange = { zone = zone.copy(name = it.take(48)) },
                    label = { Text(stringResource(R.string.zone_name)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Label(R.string.zone_device)
                ChoiceChips(
                    options = devices,
                    selected = device ?: devices.first(),
                    label = { it.displayName },
                    onSelected = { next ->
                        zone = zone.copy(
                            deviceId = next.id,
                            mapping = if (next.kind == DeviceKind.GOVEE && zone.mapping == ZoneMapping.PERIMETER) {
                                ZoneMapping.SOLID
                            } else {
                                zone.mapping
                            },
                            ledOffset = if (next.kind == DeviceKind.GOVEE) 0 else nextFree(next),
                        )
                    },
                )

                Label(R.string.zone_mapping)
                ChoiceChips(
                    options = if (isGovee) listOf(ZoneMapping.SOLID, ZoneMapping.EDGE) else ZoneMapping.entries,
                    selected = zone.mapping,
                    label = { mappingLabel(it) },
                    onSelected = { zone = zone.copy(mapping = it) },
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZoneSourceMap(
                        edge = if (zone.mapping == ZoneMapping.EDGE) zone.edge else null,
                        region = when (zone.mapping) {
                            ZoneMapping.SOLID -> zone.region
                            ZoneMapping.PERIMETER -> ScreenRegion.FULL
                            ZoneMapping.EDGE -> null
                        },
                        reverse = zone.reverse,
                    )
                    if (isGovee) {
                        Text(
                            stringResource(R.string.zone_govee_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                when (zone.mapping) {
                    ZoneMapping.EDGE -> {
                        Label(R.string.zone_edge)
                        ChoiceChips(
                            options = listOf(ScreenSide.BOTTOM, ScreenSide.LEFT, ScreenSide.RIGHT, ScreenSide.TOP),
                            selected = zone.edge,
                            label = { sideLabel(it) },
                            onSelected = { zone = zone.copy(edge = it) },
                        )
                        if (!isGovee) {
                            SwitchRow(
                                label = stringResource(R.string.zone_reverse),
                                description = stringResource(R.string.zone_reverse_desc),
                                checked = zone.reverse,
                                onCheckedChange = { zone = zone.copy(reverse = it) },
                            )
                        }
                    }
                    ZoneMapping.SOLID -> {
                        Label(R.string.zone_region)
                        ChoiceChips(
                            options = ScreenRegion.entries,
                            selected = zone.region,
                            label = { regionLabel(it) },
                            onSelected = { zone = zone.copy(region = it) },
                        )
                    }
                    ZoneMapping.PERIMETER -> {
                        Label(R.string.zone_perimeter_counts)
                        PerimeterCounts(zone.layout) { zone = zone.copy(layout = it) }
                    }
                }

                if (!isGovee) {
                    if (zone.mapping != ZoneMapping.PERIMETER) {
                        NumberStepper(
                            label = stringResource(R.string.zone_count),
                            value = zone.ledCount,
                            range = 1..LedLayout.MAX_SIDE,
                            bigStep = 10,
                            onChange = { zone = zone.copy(ledCount = it) },
                        )
                    }
                    NumberStepper(
                        label = stringResource(R.string.zone_offset),
                        description = stringResource(R.string.zone_offset_desc),
                        value = zone.ledOffset,
                        range = 0 until WledDevice.MAX_LEDS,
                        bigStep = 10,
                        onChange = { zone = zone.copy(ledOffset = it) },
                    )
                    if (device != null) {
                        val free = nextFree(device)
                        if (free != zone.ledOffset) {
                            TextButton(
                                onClick = { zone = zone.copy(ledOffset = free) },
                                modifier = Modifier.tvFocusRing(),
                            ) { Text(stringResource(R.string.zone_offset_next, free)) }
                        }
                    }
                }

                SliderRow(
                    label = stringResource(R.string.zone_brightness),
                    valueText = "${zone.brightness}%",
                    value = zone.brightness.toFloat(),
                    range = 0f..100f,
                    onValueChange = { zone = zone.copy(brightness = it.roundToInt()) },
                )

                if (draftProblem != null) {
                    Surface(color = Warning.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                        Row(
                            Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                            Text(stringResource(problemText(draftProblem)), style = MaterialTheme.typography.bodySmall, color = Warning)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(zone.copy(name = zone.name.trim()).sanitized()) },
                enabled = device != null,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Label(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
}

@Composable
private fun PerimeterCounts(layout: LedLayout, onChange: (LedLayout) -> Unit) {
    NumberStepper(stringResource(R.string.calibration_top), layout.topCount, 0..LedLayout.MAX_SIDE, { onChange(layout.copy(topCount = it)) }, bigStep = 10)
    NumberStepper(stringResource(R.string.calibration_right), layout.rightCount, 0..LedLayout.MAX_SIDE, { onChange(layout.copy(rightCount = it)) }, bigStep = 10)
    NumberStepper(stringResource(R.string.calibration_bottom), layout.bottomCount, 0..LedLayout.MAX_SIDE, { onChange(layout.copy(bottomCount = it)) }, bigStep = 10)
    NumberStepper(stringResource(R.string.calibration_left), layout.leftCount, 0..LedLayout.MAX_SIDE, { onChange(layout.copy(leftCount = it)) }, bigStep = 10)
    Text(stringResource(R.string.calibration_total, layout.total), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
}
