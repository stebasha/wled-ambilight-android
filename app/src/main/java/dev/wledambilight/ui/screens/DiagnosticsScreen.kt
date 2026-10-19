package dev.wledambilight.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wledambilight.R
import dev.wledambilight.core.model.DiagnosticEvent
import dev.wledambilight.core.model.SessionStats
import dev.wledambilight.ui.AppViewModel
import dev.wledambilight.ui.components.SectionCard
import dev.wledambilight.ui.components.StatTile
import dev.wledambilight.ui.components.tvFocusRing
import dev.wledambilight.ui.hintRes
import dev.wledambilight.ui.color
import dev.wledambilight.ui.statusLabelRes
import dev.wledambilight.ui.theme.TextSecondary
import dev.wledambilight.ui.titleRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun DiagnosticsScreen(viewModel: AppViewModel) {
    val device by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val busyId by viewModel.busyDeviceId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.displaySmall)
        }

        item {
            SectionCard(title = stringResource(R.string.diagnostics_device)) {
                if (device == null) {
                    Text(
                        stringResource(R.string.diagnostics_no_device),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                } else {
                    val selected = device!!
                    Text(selected.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${selected.host}:${selected.httpPort} · ${viewModel.selectedProtocolLabel(selected)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.probeDevice(selected) },
                            enabled = busyId != selected.id,
                            modifier = Modifier.tvFocusRing(),
                        ) {
                            Text(stringResource(R.string.diagnostics_http_check))
                        }
                        OutlinedButton(
                            onClick = { viewModel.sendHttpColorTest(selected) },
                            enabled = busyId != selected.id,
                            modifier = Modifier.tvFocusRing(),
                        ) {
                            Text(stringResource(R.string.diagnostics_http_color))
                        }
                    }
                    Button(
                        onClick = { viewModel.sendUdpColorTest() },
                        modifier = Modifier.tvFocusRing(),
                    ) {
                        Text(stringResource(R.string.diagnostics_udp_color))
                    }
                    Text(
                        stringResource(R.string.diagnostics_http_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.diagnostics_session)) {
                Text(
                    text = stringResource(R.string.diagnostics_leds_configured) + ": " + config.layout.total,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Text(
                    stringResource(state.statusLabelRes()),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (stats.running) {
                    StatsGrid(stats, formatter)
                } else {
                    Text(
                        stringResource(R.string.diagnostics_not_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.diagnostics_black_frame_title)) {
                Text(
                    stringResource(R.string.diagnostics_black_frame_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                if (stats.blackFrameSeconds >= 1f) {
                    Text(
                        "Black frames: ${stats.blackFrameSeconds.roundToInt()} s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.diagnostics_log), style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton(onClick = { viewModel.clearLog() }) {
                        Text(stringResource(R.string.diagnostics_clear))
                    }
                    TextButton(onClick = {
                        val report = viewModel.exportReport()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_export_subject))
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                intent,
                                context.getString(R.string.diagnostics_export_chooser),
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.diagnostics_export))
                    }
                }
            }
        }

        if (events.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.diagnostics_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        } else {
            items(events.asReversed(), key = { it.id }) { event ->
                DiagnosticEventRow(event, formatter)
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: SessionStats, formatter: DateTimeFormatter) {
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
            label = stringResource(R.string.diagnostics_dropped),
            value = stats.droppedFrames.toString(),
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            label = stringResource(R.string.diagnostics_resolution),
            value = "${stats.analysisWidth}×${stats.analysisHeight}",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.diagnostics_send_time),
            value = "${stats.sendMs.roundToInt()} ms",
            modifier = Modifier.weight(1f),
        )
    }
    stats.startedEpochMillis?.let { started ->
        Text(
            stringResource(R.string.diagnostics_started) + ": " +
                formatter.format(Instant.ofEpochMilli(started)),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

@Composable
private fun DiagnosticEventRow(event: DiagnosticEvent, formatter: DateTimeFormatter) {
    val color = event.severity.color()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatter.format(Instant.ofEpochMilli(event.timestampMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Text(
                text = stringResource(event.code.titleRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
        event.detail?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        event.code.hintRes()?.let { hint ->
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
    }
}
