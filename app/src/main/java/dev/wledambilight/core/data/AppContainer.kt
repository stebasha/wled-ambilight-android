package dev.wledambilight.core.data

import android.content.Context
import dev.wledambilight.core.discovery.GoveeDiscovery
import dev.wledambilight.core.discovery.WledDiscovery
import dev.wledambilight.core.network.SubnetScanner
import dev.wledambilight.core.network.WledHttpClient
import dev.wledambilight.engine.AmbilightEngine

/**
 * Application-scoped dependency container. Constructor injection keeps the graph explicit and
 * testable without an annotation processor; see IMPLEMENTATION_PLAN.md for the rationale.
 */
class AppContainer(context: Context) {
    val appContext = context.applicationContext
    private val json = WledHttpClient.defaultJson

    val diagnostics = DiagnosticsRepository()
    val codec = SettingsCodec(json)
    val settingsRepository = SettingsRepository(appContext, codec, diagnostics)
    val httpClient = WledHttpClient()
    val deviceRepository = DeviceRepository(settingsRepository, httpClient, diagnostics)
    val discovery = WledDiscovery(appContext, diagnostics)
    val goveeDiscovery = GoveeDiscovery(appContext, diagnostics)
    val scanner = SubnetScanner()
    val haClient = dev.wledambilight.core.network.HomeAssistantClient()
    val engine = AmbilightEngine(appContext, diagnostics, httpClient, haClient)

    /** Tokens of the Home Assistant servers that zones use; read just before a session starts. */
    suspend fun haTokens(devices: List<dev.wledambilight.core.model.WledDevice>): Map<String, String> =
        devices.filter { it.kind == dev.wledambilight.core.model.DeviceKind.HOME_ASSISTANT }
            .mapNotNull { device -> settingsRepository.haToken(device.id)?.let { device.id to it } }
            .toMap()
}
