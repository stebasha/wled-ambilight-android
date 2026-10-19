package dev.wledambilight.core.data

import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.DiagnosticEvent
import dev.wledambilight.core.model.DiagnosticSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded in-memory journal. It intentionally never stores screen content, tokens or Wi-Fi
 * credentials; [exportText] is safe to share.
 */
class DiagnosticsRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEvents: Int = 200,
) {
    private val ids = AtomicLong(0)
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun report(
        code: DiagnosticCode,
        detail: String? = null,
        severity: DiagnosticSeverity = code.defaultSeverity,
    ): DiagnosticEvent {
        val event = DiagnosticEvent(
            id = ids.incrementAndGet(),
            timestampMillis = clock(),
            code = code,
            severity = severity,
            detail = detail?.take(300),
        )
        _events.update { current ->
            val next = current + event
            if (next.size > maxEvents) next.takeLast(maxEvents) else next
        }
        return event
    }

    fun clear() {
        _events.value = emptyList()
    }

    fun exportText(appVersion: String, deviceSummary: String): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val header = buildString {
            appendLine("WLED Ambilight diagnostics")
            appendLine("App version: $appVersion")
            appendLine("Device: $deviceSummary")
            appendLine("Events: ${events.value.size}")
            appendLine("No screen content, tokens or Wi-Fi credentials are included.")
            appendLine()
        }
        val body = events.value.joinToString("\n") { event ->
            val time = formatter.format(Instant.ofEpochMilli(event.timestampMillis))
            "$time  ${event.severity}  ${event.code}" + (event.detail?.let { "  ($it)" } ?: "")
        }
        return header + body
    }
}
