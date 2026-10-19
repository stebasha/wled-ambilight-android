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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.core.model.HostValidator
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.WledUdpMode
import dev.wledambilight.core.network.ScanHit
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.ChoiceChips
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.protocolLabel
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.wireModeLabel
import java.util.UUID

@Composable
fun DevicesScreen(viewModel: AppViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val selected by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val discovering by viewModel.discovering.collectAsStateWithLifecycle()
    val scanHits by viewModel.scanHits.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val scanSubnet by viewModel.scanSubnet.collectAsStateWithLifecycle()
    val busyId by viewModel.busyDeviceId.collectAsStateWithLifecycle()

    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WledDevice?>(null) }
    var deleteTarget by remember { mutableStateOf<WledDevice?>(null) }
    var scanConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.displaySmall)
                Button(onClick = { addOpen = true }, modifier = Modifier.tvFocusRing()) {
                    Text(stringResource(R.string.devices_add_manual))
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (discovering) viewModel.stopDiscovery() else viewModel.startDiscovery() },
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(
                        stringResource(
                            if (discovering) R.string.action_stop else R.string.devices_discover,
                        ),
                    )
                }
                OutlinedButton(
                    onClick = { if (scanning) viewModel.stopScan() else scanConfirm = true },
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(stringResource(if (scanning) R.string.action_stop else R.string.devices_scan))
                }
            }
        }

        if (discovering) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text(stringResource(R.string.devices_discovering), color = TextSecondary)
                }
            }
        }

        if (discovered.isNotEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.devices_discovered)) {
                    discovered.forEach { found ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(found.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${found.host}:${found.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.addDiscovered(found) },
                                modifier = Modifier.tvFocusRing(),
                            ) {
                                Text(stringResource(R.string.action_add))
                            }
                        }
                    }
                }
            }
        }

        if (scanning) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.devices_scanning, scanSubnet ?: "…"),
                        color = TextSecondary,
                    )
                }
            }
        }

        if (scanHits.isNotEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.devices_scan_results)) {
                    scanHits.forEach { hit -> ScanHitRow(hit) { viewModel.addScanHit(hit) } }
                }
            }
        }

        item {
            Text(stringResource(R.string.devices_saved), style = MaterialTheme.typography.titleLarge)
        }

        if (devices.isEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.devices_empty)) {
                    Text(
                        stringResource(R.string.devices_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        items(devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,
                selected = selected?.id == device.id,
                busy = busyId == device.id,
                onSelect = { viewModel.selectDevice(device.id) },
                onTest = { viewModel.probeDevice(device) },
                onEdit = { editing = device },
                onDelete = { deleteTarget = device },
            )
        }
    }

    if (addOpen || editing != null) {
        DeviceDialog(
            initial = editing,
            onDismiss = { addOpen = false; editing = null },
            onSave = { device ->
                viewModel.saveDevice(device)
                addOpen = false
                editing = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.devices_delete_title)) },
            text = { Text(stringResource(R.string.devices_delete_body, target.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDevice(target.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (scanConfirm) {
        AlertDialog(
            onDismissRequest = { scanConfirm = false },
            title = { Text(stringResource(R.string.devices_scan_warning_title)) },
            text = { Text(stringResource(R.string.devices_scan_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scanConfirm = false
                    viewModel.scanNetwork()
                }) { Text(stringResource(R.string.devices_scan)) }
            },
            dismissButton = {
                TextButton(onClick = { scanConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ScanHitRow(hit: ScanHit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hit.name ?: hit.host, style = MaterialTheme.typography.titleMedium)
            Text(
                "${hit.host} · ${hit.version ?: stringResource(R.string.label_unknown)} · ${hit.latencyMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        TextButton(onClick = onAdd, modifier = Modifier.tvFocusRing()) {
            Text(stringResource(R.string.action_add))
        }
    }
}

@Composable
private fun DeviceCard(
    device: WledDevice,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val protocolText = protocolLabel(device.protocol)
        val unknown = stringResource(R.string.label_unknown)
        val notTested = stringResource(R.string.devices_not_tested)
        val firmwareLabel = stringResource(R.string.devices_firmware)
        val ledsLabel = stringResource(R.string.devices_leds)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${device.host}:${device.httpPort} · $protocolText → ${device.outputPort}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                if (selected) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            stringResource(R.string.devices_selected),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Text(
                text = "$firmwareLabel: ${device.firmwareVersion ?: notTested}   ·   " +
                    "$ledsLabel: ${device.ledCount ?: unknown}" +
                    if (device.protocol == OutputProtocol.WLED_UDP) "   ·   ${device.wledUdpMode.name}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                } else {
                    OutlinedButton(onClick = onTest, modifier = Modifier.tvFocusRing()) {
                        Text(stringResource(R.string.action_test))
                    }
                }
                TextButton(onClick = onEdit, modifier = Modifier.tvFocusRing()) {
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(onClick = onDelete, modifier = Modifier.tvFocusRing()) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                if (!selected) {
                    Button(onClick = onSelect, modifier = Modifier.tvFocusRing()) {
                        Text(stringResource(R.string.action_select))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceDialog(
    initial: WledDevice?,
    onDismiss: () -> Unit,
    onSave: (WledDevice) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var httpPort by remember { mutableStateOf((initial?.httpPort ?: 80).toString()) }
    var protocol by remember { mutableStateOf(initial?.protocol ?: OutputProtocol.DDP) }
    var outputPort by remember {
        mutableStateOf((initial?.outputPort ?: OutputProtocol.DDP.defaultPort).toString())
    }
    var mode by remember { mutableStateOf(initial?.wledUdpMode ?: WledUdpMode.DNRGB) }
    var ledCount by remember { mutableStateOf(initial?.ledCount?.toString() ?: "") }
    var showErrors by remember { mutableStateOf(false) }

    val hostError = HostValidator.error(host)
    val httpPortValue = httpPort.toIntOrNull()
    val httpPortError = if (httpPortValue == null) true else HostValidator.portError(httpPortValue) != null
    val outputPortValue = outputPort.toIntOrNull()
    val outputPortError = if (outputPortValue == null) true else HostValidator.portError(outputPortValue) != null
    val ledValue = ledCount.trim().ifBlank { null }?.toIntOrNull()
    val ledError = ledCount.isNotBlank() && (ledValue == null || HostValidator.ledCountError(ledValue))
    val valid = hostError == null && !httpPortError && !outputPortError && !ledError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.devices_dialog_add else R.string.devices_dialog_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.field_host)) },
                    singleLine = true,
                    isError = showErrors && hostError != null,
                    supportingText = if (showErrors && hostError != null) {
                        { Text(stringResource(R.string.validation_host)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = httpPort,
                        onValueChange = { httpPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.field_http_port)) },
                        singleLine = true,
                        isError = showErrors && httpPortError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = outputPort,
                        onValueChange = { outputPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.field_output_port)) },
                        singleLine = true,
                        isError = showErrors && outputPortError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showErrors && (httpPortError || outputPortError)) {
                    Text(
                        stringResource(R.string.validation_port),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.field_protocol), style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    options = OutputProtocol.entries,
                    selected = protocol,
                    label = { protocolLabel(it) },
                    onSelected = { next ->
                        if (next != protocol) {
                            val previousDefault = protocol.defaultPort.toString()
                            protocol = next
                            if (outputPort == previousDefault || outputPort.isBlank()) {
                                outputPort = next.defaultPort.toString()
                            }
                        }
                    },
                )
                if (protocol == OutputProtocol.WLED_UDP) {
                    Text(stringResource(R.string.field_mode), style = MaterialTheme.typography.bodyMedium)
                    ChoiceChips(
                        options = WledUdpMode.entries,
                        selected = mode,
                        label = { wireModeLabel(it) },
                        onSelected = { mode = it },
                    )
                }
                OutlinedTextField(
                    value = ledCount,
                    onValueChange = { ledCount = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.field_leds)) },
                    singleLine = true,
                    isError = showErrors && ledError,
                    supportingText = if (showErrors && ledError) {
                        { Text(stringResource(R.string.validation_leds)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (protocol == OutputProtocol.WLED_UDP) {
                    Text(
                        stringResource(R.string.settings_protocols_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showErrors = true
                if (valid) {
                    val device = WledDevice(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        host = host.trim(),
                        httpPort = httpPortValue ?: 80,
                        protocol = protocol,
                        outputPort = outputPortValue ?: protocol.defaultPort,
                        wledUdpMode = mode,
                        ledCount = ledValue,
                        firmwareVersion = initial?.firmwareVersion,
                        lastSeenEpochMillis = initial?.lastSeenEpochMillis,
                        realtimeActive = initial?.realtimeActive,
                        httpReachable = initial?.httpReachable,
                    )
                    onSave(device)
                }
            }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
