package dev.wledambilight.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wledambilight.BuildConfig
import dev.wledambilight.core.data.AppContainer
import dev.wledambilight.core.data.toDiagnosticCode
import dev.wledambilight.core.discovery.DiscoveredDevice
import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.DeviceKind
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.LightZone
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.ZoneMapping
import dev.wledambilight.core.network.NetworkUtils
import dev.wledambilight.core.network.ScanHit
import dev.wledambilight.core.network.SubnetPlanner
import dev.wledambilight.core.network.WledPreset
import dev.wledambilight.core.protocol.GoveeScanResult
import dev.wledambilight.core.protocol.TestPatternSender
import dev.wledambilight.engine.EngineState
import dev.wledambilight.engine.OutputPlanner
import dev.wledambilight.engine.SessionSource
import dev.wledambilight.engine.StopReason
import dev.wledambilight.service.AmbilightService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Live state of the WLED controller screen (power, brightness, presets, effects). */
data class ControllerUiState(
    val deviceId: String? = null,
    val loading: Boolean = false,
    val on: Boolean? = null,
    val brightness: Int? = null,
    val live: Boolean? = null,
    val presetId: Int? = null,
    val effectId: Int? = null,
    val presets: List<WledPreset> = emptyList(),
    val effects: List<String> = emptyList(),
    val errorCode: DiagnosticCode? = null,
)

/**
 * Single presentation facade for the Compose UI. The app is a single-activity Compose app; one view
 * model keeps configuration, device list, discovery and the live session consistent across screens.
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settingsRepository
    private val deviceRepository = container.deviceRepository
    private val engine = container.engine
    private val diagnostics = container.diagnostics
    private val http = container.httpClient
    private val testSender = TestPatternSender(diagnostics)

    val config: StateFlow<AmbilightConfig> = settings.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, AmbilightConfig())

    val devices: StateFlow<List<WledDevice>> = deviceRepository.devices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedDevice: StateFlow<WledDevice?> = deviceRepository.selectedDevice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val onboardingComplete: StateFlow<Boolean?> = settings.onboardingComplete
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val engineState = engine.state
    val stats = engine.stats
    val preview = engine.preview
    val zonePreview = engine.zonePreview
    val events = diagnostics.events

    private val _discovered = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<DiscoveredDevice>> = _discovered.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _scanHits = MutableStateFlow<List<ScanHit>>(emptyList())
    val scanHits: StateFlow<List<ScanHit>> = _scanHits.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanSubnet = MutableStateFlow<String?>(null)
    val scanSubnet: StateFlow<String?> = _scanSubnet.asStateFlow()

    private val _goveeHits = MutableStateFlow<List<GoveeScanResult>>(emptyList())
    val goveeHits: StateFlow<List<GoveeScanResult>> = _goveeHits.asStateFlow()

    private val _goveeScanning = MutableStateFlow(false)
    val goveeScanning: StateFlow<Boolean> = _goveeScanning.asStateFlow()

    private val _busyDeviceId = MutableStateFlow<String?>(null)
    val busyDeviceId: StateFlow<String?> = _busyDeviceId.asStateFlow()

    private val _controller = MutableStateFlow(ControllerUiState())
    val controller: StateFlow<ControllerUiState> = _controller.asStateFlow()

    private var discoveryJob: Job? = null
    private var scanJob: Job? = null
    private var goveeJob: Job? = null
    private var lastScanReport = 0L

    fun updateSettings(transform: (AmbilightSettings) -> AmbilightSettings) {
        viewModelScope.launch { settings.updateSettings(transform) }
    }

    fun updateLayout(transform: (LedLayout) -> LedLayout) {
        viewModelScope.launch { settings.updateLayout(transform) }
    }

    fun selectDevice(id: String) {
        viewModelScope.launch { deviceRepository.select(id) }
    }

    fun deleteDevice(id: String) {
        viewModelScope.launch { deviceRepository.delete(id) }
    }

    /** Saves a manually entered or discovered device and immediately probes it. */
    fun saveDevice(device: WledDevice, probe: Boolean = true) {
        viewModelScope.launch {
            _busyDeviceId.value = device.id
            try {
                val saved = deviceRepository.save(device)
                if (probe) probeInternal(saved)
            } finally {
                _busyDeviceId.value = null
            }
        }
    }

    fun addDiscovered(discoveredDevice: DiscoveredDevice) {
        val existing = devices.value.firstOrNull { it.host == discoveredDevice.host }
        if (existing != null) {
            selectDevice(existing.id)
            return
        }
        saveDevice(
            WledDevice(
                name = discoveredDevice.name,
                host = discoveredDevice.host,
                protocol = OutputProtocol.DDP,
                outputPort = OutputProtocol.DDP.defaultPort,
            ),
        )
    }

    fun addScanHit(hit: ScanHit) {
        val existing = devices.value.firstOrNull { it.host == hit.host }
        if (existing != null) {
            selectDevice(existing.id)
            return
        }
        saveDevice(
            WledDevice(
                name = hit.name ?: hit.host,
                host = hit.host,
                firmwareVersion = hit.version,
                protocol = OutputProtocol.DDP,
                outputPort = OutputProtocol.DDP.defaultPort,
            ),
        )
    }

    fun addGovee(hit: GoveeScanResult) {
        if (devices.value.any { it.host == hit.ip }) return
        saveDevice(
            WledDevice(
                kind = DeviceKind.GOVEE,
                name = "Govee ${hit.sku ?: ""}".trim(),
                host = hit.ip,
                model = hit.sku,
                outputPort = WledDevice.GOVEE_COMMAND_PORT,
            ),
        )
    }

    fun probeDevice(device: WledDevice) {
        viewModelScope.launch {
            _busyDeviceId.value = device.id
            try {
                probeInternal(device)
            } finally {
                _busyDeviceId.value = null
            }
        }
    }

    /** WLED: `/json/info`. Govee has no request/response API without listening, so it gets a test colour. */
    private suspend fun probeInternal(device: WledDevice) {
        if (device.kind == DeviceKind.GOVEE) {
            testSender.send(device, intArrayOf(TEST_COLOR), report = true)
        } else {
            deviceRepository.probe(device)
        }
    }

    fun sendHttpColorTest(device: WledDevice) {
        viewModelScope.launch {
            if (device.kind == DeviceKind.GOVEE) {
                testSender.send(device, intArrayOf(TEST_COLOR), report = true)
            } else {
                deviceRepository.sendHttpColor(device, TEST_COLOR)
            }
        }
    }

    fun sendUdpColorTest() {
        val device = selectedDevice.value ?: return
        val colors = IntArray(config.value.layout.total) { TEST_COLOR }
        viewModelScope.launch {
            testSender.send(device, colors, report = true)
        }
    }

    /** Used by calibration; intentionally does not report every chase frame. */
    suspend fun sendTestPattern(colors: IntArray, report: Boolean = false) {
        val device = selectedDevice.value ?: return
        testSender.send(device, colors, report)
    }

    fun sendTestPatternOnce(colors: IntArray, report: Boolean = false) {
        viewModelScope.launch { sendTestPattern(colors, report) }
    }

    // ---------------------------------------------------------------------------------------------
    // Zones

    fun saveZone(zone: LightZone) {
        viewModelScope.launch {
            settings.updateConfig { current ->
                val index = current.zones.indexOfFirst { it.id == zone.id }
                val zones = if (index >= 0) {
                    current.zones.toMutableList().also { it[index] = zone }
                } else {
                    current.zones + zone
                }
                current.copy(zones = zones)
            }
        }
    }

    fun deleteZone(id: String) {
        viewModelScope.launch {
            settings.updateConfig { current -> current.copy(zones = current.zones.filterNot { it.id == id }) }
        }
    }

    fun setZoneEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settings.updateConfig { current ->
                current.copy(zones = current.zones.map { if (it.id == id) it.copy(enabled = enabled) else it })
            }
        }
    }

    fun setMainStripEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.updateConfig { it.copy(mainStripEnabled = enabled) } }
    }

    /** Lights only the zone's LED range (or the lamp) in amber so the user can identify it. */
    fun testZone(zone: LightZone) {
        val device = devices.value.firstOrNull { it.id == zone.deviceId } ?: return
        val count = zone.ledsFor(device.kind).coerceAtLeast(1)
        viewModelScope.launch {
            testSender.send(device, IntArray(count) { TEST_COLOR }, report = true, ledOffset = zone.ledOffset)
        }
    }

    /** Suggests the first free LED after everything already used on [device]. */
    fun nextFreeOffset(device: WledDevice, excludingZoneId: String? = null): Int {
        val current = config.value
        var end = 0
        if (current.mainStripEnabled && selectedDevice.value?.id == device.id) end = current.layout.total
        current.zones
            .filter { it.deviceId == device.id && it.id != excludingZoneId }
            .forEach { end = maxOf(end, it.ledOffset + it.ledsFor(device.kind)) }
        return end
    }

    fun newZone(device: WledDevice): LightZone = LightZone(
        name = "",
        deviceId = device.id,
        mapping = if (device.kind == DeviceKind.GOVEE) ZoneMapping.SOLID else ZoneMapping.EDGE,
        ledOffset = if (device.kind == DeviceKind.GOVEE) 0 else nextFreeOffset(device),
    )

    // ---------------------------------------------------------------------------------------------
    // Session

    fun startSession(resultCode: Int, data: Intent) {
        AmbilightService.start(container.appContext, resultCode, data)
    }

    /** Runs the pipeline on a synthetic picture: no screen capture and no consent dialog needed. */
    fun startDemo() {
        if (engineState.value is EngineState.Running || engineState.value is EngineState.Starting) return
        viewModelScope.launch {
            val current = config.value
            val plan = OutputPlanner.plan(current, selectedDevice.value, devices.value)
            engine.start(SessionSource.Demo, plan, current.layout, current.settings)
        }
    }

    fun reportProjectionDenied() {
        diagnostics.report(DiagnosticCode.PROJECTION_DENIED)
    }

    fun stopSession() {
        engine.stop(StopReason.USER)
    }

    fun finishOnboarding() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }

    // ---------------------------------------------------------------------------------------------
    // Discovery

    fun startDiscovery() {
        if (_discovering.value) return
        discoveryJob?.cancel()
        _discovered.value = emptyList()
        _discovering.value = true
        discoveryJob = viewModelScope.launch {
            try {
                container.discovery.discover().collect { found ->
                    _discovered.update { current ->
                        if (current.any { it.host == found.host }) current else current + found
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.report(DiagnosticCode.DISCOVERY_UNAVAILABLE, error.javaClass.simpleName)
            } finally {
                _discovering.value = false
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _discovering.value = false
    }

    fun scanNetwork() {
        if (_scanning.value) return
        val local = NetworkUtils.localIpv4()
        if (local == null) {
            diagnostics.report(DiagnosticCode.DISCOVERY_UNAVAILABLE, "no local IPv4 address")
            return
        }
        val subnet = SubnetPlanner.subnetLabel(local)
        _scanSubnet.value = subnet
        _scanHits.value = emptyList()
        _scanning.value = true
        diagnostics.report(DiagnosticCode.SCAN_STARTED, subnet)
        scanJob = viewModelScope.launch {
            try {
                val hits = container.scanner.scan(SubnetPlanner.hostsAround(local))
                _scanHits.value = hits
                val now = System.currentTimeMillis()
                if (now - lastScanReport > 1_000) {
                    lastScanReport = now
                    diagnostics.report(DiagnosticCode.SCAN_FINISHED, "${hits.size} devices on $subnet")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.report(DiagnosticCode.DISCOVERY_UNAVAILABLE, error.javaClass.simpleName)
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun scanGovee() {
        if (_goveeScanning.value) return
        _goveeScanning.value = true
        _goveeHits.value = emptyList()
        goveeJob = viewModelScope.launch {
            try {
                _goveeHits.value = container.goveeDiscovery.scan()
            } finally {
                _goveeScanning.value = false
            }
        }
    }

    fun stopGoveeScan() {
        goveeJob?.cancel()
        goveeJob = null
        _goveeScanning.value = false
    }

    // ---------------------------------------------------------------------------------------------
    // WLED controller (presets / effects / power)

    fun loadController(device: WledDevice?) {
        if (device == null || !device.isWled) {
            _controller.value = ControllerUiState()
            return
        }
        _controller.value = ControllerUiState(deviceId = device.id, loading = true)
        viewModelScope.launch {
            try {
                val state = http.state(device.host, device.httpPort)
                val presets = runCatching { http.presets(device.host, device.httpPort) }.getOrDefault(emptyList())
                val effects = runCatching { http.effects(device.host, device.httpPort) }.getOrDefault(emptyList())
                _controller.value = ControllerUiState(
                    deviceId = device.id,
                    on = state.on,
                    brightness = state.bri,
                    live = state.live,
                    presetId = state.ps?.takeIf { it > 0 },
                    effectId = state.seg?.firstOrNull()?.fx,
                    presets = presets,
                    effects = effects,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = error.toDiagnosticCode()
                diagnostics.report(code, device.host)
                _controller.value = ControllerUiState(deviceId = device.id, errorCode = code)
            }
        }
    }

    fun setControllerPower(device: WledDevice, on: Boolean) = controllerCall(device, { it.copy(on = on) }) {
        http.setPower(device.host, device.httpPort, on)
    }

    fun setControllerBrightness(device: WledDevice, value: Int) =
        controllerCall(device, { it.copy(brightness = value) }) {
            http.setBrightness(device.host, device.httpPort, value)
        }

    fun applyPreset(device: WledDevice, presetId: Int) = controllerCall(device, { it.copy(presetId = presetId) }) {
        http.applyPreset(device.host, device.httpPort, presetId)
    }

    fun applyEffect(device: WledDevice, effectId: Int) =
        controllerCall(device, { it.copy(effectId = effectId, on = true) }) {
            http.applyEffect(device.host, device.httpPort, effectId)
        }

    fun releaseRealtime(device: WledDevice) = controllerCall(device, { it.copy(live = false) }) {
        http.exitRealtime(device.host, device.httpPort)
    }

    private fun controllerCall(
        device: WledDevice,
        optimistic: (ControllerUiState) -> ControllerUiState,
        call: suspend () -> Unit,
    ) {
        _controller.update(optimistic)
        viewModelScope.launch {
            try {
                call()
                diagnostics.report(DiagnosticCode.WLED_CONTROL_SENT, device.host)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = error.toDiagnosticCode()
                diagnostics.report(code, device.host)
                _controller.update { it.copy(errorCode = code) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Misc

    fun clearLog() {
        diagnostics.clear()
    }

    fun resetAppData() {
        viewModelScope.launch {
            engine.stop(StopReason.USER)
            settings.resetAll()
        }
    }

    fun exportReport(): String {
        val device = selectedDevice.value
        val current = config.value
        val zoneSummary = "zones=${current.zones.size} (enabled ${current.zones.count { it.enabled }}), " +
            "devices=${devices.value.size} (Govee ${devices.value.count { it.kind == DeviceKind.GOVEE }})"
        return diagnostics.exportText(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            deviceSummary = deviceRepository.summary(device) + "; " + zoneSummary,
        )
    }

    /** Layout, colour settings and zones as JSON. Devices (addresses) are not part of a profile. */
    fun exportProfile(): String = container.codec.encodeConfig(config.value)

    /** Returns false when [raw] is not a valid profile; nothing is changed in that case. */
    fun importProfile(raw: String): Boolean {
        val decoded = container.codec.decodeConfig(raw)
        if (decoded.corrupted || raw.isBlank()) return false
        viewModelScope.launch { settings.updateConfig { decoded.value } }
        diagnostics.report(DiagnosticCode.CONFIG_SAVED, "profile imported")
        return true
    }

    fun selectedProtocolLabel(device: WledDevice?): String = when {
        device?.kind == DeviceKind.GOVEE -> "Govee LAN"
        device?.protocol == OutputProtocol.WLED_UDP -> "WLED UDP ${device.wledUdpMode.name}"
        else -> "DDP"
    }

    override fun onCleared() {
        // A demo has no foreground service; it must not outlive the UI.
        if ((engineState.value as? EngineState.Running)?.demo == true) engine.stop(StopReason.USER)
        testSender.close()
        super.onCleared()
    }

    companion object {
        const val TEST_COLOR = 0xFFFFB020.toInt()

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(container) }
        }
    }
}
