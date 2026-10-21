package dev.wledambilight.core.data

import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.network.WledHttpClient
import dev.wledambilight.core.network.WledHttpError
import dev.wledambilight.core.network.WledProbe
import dev.wledambilight.core.network.isRealtimeActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

sealed interface ProbeOutcome {
    data class Success(val device: WledDevice, val probe: WledProbe) : ProbeOutcome
    data class Failure(val code: DiagnosticCode, val detail: String) : ProbeOutcome
}

/**
 * Owns the saved controllers and all HTTP interactions with them. Discovery is a separate concern;
 * this repository only persists what the user (or discovery) supplies.
 */
class DeviceRepository(
    private val settings: SettingsRepository,
    private val http: WledHttpClient,
    private val diagnostics: DiagnosticsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val devices: Flow<List<WledDevice>> = settings.devices

    /** The WLED controller that drives the main screen strip. Govee lamps can only be zones. */
    val selectedDevice: Flow<WledDevice?> = combine(settings.devices, settings.selectedDeviceId) { list, id ->
        list.firstOrNull { it.id == id && it.isWled } ?: list.firstOrNull { it.isWled }
    }

    suspend fun save(device: WledDevice): WledDevice {
        val existed = settings.devices.first().any { it.id == device.id }
        val normalized = device.normalized()
        settings.saveDevice(normalized)
        diagnostics.report(if (existed) DiagnosticCode.DEVICE_UPDATED else DiagnosticCode.DEVICE_ADDED, normalized.host)
        return normalized
    }

    suspend fun delete(id: String) {
        settings.deleteDevice(id)
        diagnostics.report(DiagnosticCode.DEVICE_REMOVED, id.take(8))
    }

    suspend fun select(id: String) {
        settings.selectDevice(id)
        diagnostics.report(DiagnosticCode.DEVICE_SELECTED, id.take(8))
    }

    /** Calls `/json/info`, stores firmware/LED metadata and maps failures to typed diagnostics. */
    suspend fun probe(device: WledDevice): ProbeOutcome {
        return try {
            val probe = http.probe(device.host, device.httpPort)
            val updated = device.copy(
                name = device.name.ifBlank { probe.info.name?.trim()?.take(64) ?: device.host },
                firmwareVersion = probe.info.ver,
                ledCount = probe.info.leds?.count ?: device.ledCount,
                lastSeenEpochMillis = clock(),
                realtimeActive = probe.info.isRealtimeActive,
                httpReachable = true,
            ).normalized()
            settings.saveDevice(updated)
            diagnostics.report(
                DiagnosticCode.HTTP_PROBE_OK,
                "${updated.host}:${updated.httpPort}, WLED ${probe.info.ver ?: "?"}, " +
                    "${probe.info.leds?.count ?: "?"} LEDs, ${probe.latencyMs} ms",
            )
            ProbeOutcome.Success(updated, probe)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val code = error.toDiagnosticCode()
            diagnostics.report(code, "${device.host}:${device.httpPort} ${error.javaClass.simpleName}")
            ProbeOutcome.Failure(code, error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun sendHttpColor(device: WledDevice, argb: Int): Boolean {
        return try {
            http.sendSolidColor(device.host, device.httpPort, argb)
            diagnostics.report(DiagnosticCode.HTTP_COLOR_TEST_SENT, device.host)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostics.report(error.toDiagnosticCode(), device.host)
            false
        }
    }

    fun summary(device: WledDevice?): String = if (device == null) {
        "none"
    } else {
        "${device.displayName} ${device.host}:${device.httpPort} ${device.protocol} " +
            "out=${device.outputPort} leds=${device.ledCount ?: "?"} fw=${device.firmwareVersion ?: "?"}"
    }
}

fun Throwable.toDiagnosticCode(): DiagnosticCode = when (this) {
    is dev.wledambilight.core.network.HaUnauthorized -> DiagnosticCode.HA_UNAUTHORIZED
    is WledHttpError.Timeout -> DiagnosticCode.DEVICE_HTTP_TIMEOUT
    is WledHttpError.Unreachable -> DiagnosticCode.DEVICE_HTTP_UNREACHABLE
    is WledHttpError.BadStatus -> DiagnosticCode.DEVICE_HTTP_ERROR
    is WledHttpError.InvalidPayload -> DiagnosticCode.DEVICE_HTTP_BAD_JSON
    is WledHttpError.PayloadTooLarge -> DiagnosticCode.DEVICE_HTTP_TOO_LARGE
    else -> DiagnosticCode.DEVICE_HTTP_ERROR
}
