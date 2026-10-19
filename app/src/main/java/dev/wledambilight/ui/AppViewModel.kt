package dev.wledambilight.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wledambilight.BuildConfig
import dev.wledambilight.core.data.AppContainer
import dev.wledambilight.core.discovery.DiscoveredDevice
import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.WledUdpMode
import dev.wledambilight.core.network.NetworkUtils
import dev.wledambilight.core.network.ScanHit
import dev.wledambilight.core.network.SubnetPlanner
import dev.wledambilight.core.protocol.TestPatternSender
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

/**
 * Single presentation facade for the Compose UI. The app is a single-activity Compose app; one view
 * model keeps configuration, device list, discovery and the live session consistent across screens.
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settingsRepository
    private val deviceRepository = container.deviceRepository
    private val engine = container.engine
    private val diagnostics = container.diagnostics
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

    private val _busyDeviceId = MutableStateFlow<String?>(null)
    val busyDeviceId: StateFlow<String?> = _busyDeviceId.asStateFlow()

    private var discoveryJob: Job? = null
    private var scanJob: Job? = null
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

    /** Saves a manually entered or discovered device and immediately probes its HTTP API. */
    fun saveDevice(device: WledDevice, probe: Boolean = true) {
        viewModelScope.launch {
            _busyDeviceId.value = device.id
            try {
                val saved = deviceRepository.save(device)
                if (probe) deviceRepository.probe(saved)
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

    fun probeDevice(device: WledDevice) {
        viewModelScope.launch {
            _busyDeviceId.value = device.id
            try {
                deviceRepository.probe(device)
            } finally {
                _busyDeviceId.value = null
            }
        }
    }

    fun sendHttpColorTest(device: WledDevice) {
        viewModelScope.launch { deviceRepository.sendHttpColor(device, 0xFFFFB020.toInt()) }
    }

    fun sendUdpColorTest() {
        val device = selectedDevice.value ?: return
        val colors = IntArray(config.value.layout.total) { 0xFFFFB020.toInt() }
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

    fun startSession(resultCode: Int, data: Intent) {
        AmbilightService.start(container.appContext, resultCode, data)
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
        return diagnostics.exportText(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            deviceSummary = deviceRepository.summary(device),
        )
    }

    fun discoveredAsDevice(device: DiscoveredDevice): WledDevice = WledDevice(
        name = device.name,
        host = device.host,
        protocol = OutputProtocol.DDP,
        outputPort = OutputProtocol.DDP.defaultPort,
    )

    fun selectedProtocolLabel(device: WledDevice?): String = when (device?.protocol) {
        OutputProtocol.DDP, null -> "DDP"
        OutputProtocol.WLED_UDP -> "WLED UDP ${device.wledUdpMode.name}"
    }

    fun defaultOutputPort(protocol: OutputProtocol): Int = protocol.defaultPort

    fun defaultMode(mode: WledUdpMode): WledUdpMode = mode

    override fun onCleared() {
        testSender.close()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(container) }
        }
    }
}
