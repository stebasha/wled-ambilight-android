package dev.wledambilight.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import dev.wledambilight.core.capture.FrameSourceEvent
import dev.wledambilight.core.capture.MediaProjectionFrameSource
import dev.wledambilight.core.data.DiagnosticsRepository
import dev.wledambilight.core.imaging.AnalysisSizing
import dev.wledambilight.core.imaging.ColorSmoother
import dev.wledambilight.core.imaging.FrameAnalyzer
import dev.wledambilight.core.imaging.FrameCadenceLimiter
import dev.wledambilight.core.imaging.SmoothingParams
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.SessionStats
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.WledUdpMode
import dev.wledambilight.core.network.WledHttpClient
import dev.wledambilight.core.protocol.DdpPacketBuilder
import dev.wledambilight.core.protocol.UdpLedOutput
import dev.wledambilight.core.protocol.WledUdp
import dev.wledambilight.core.protocol.WledUdpPacketBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetAddress

sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data class Running(
        val deviceName: String,
        val protocolLabel: String,
        val startedAtMillis: Long,
    ) : EngineState

    data class Failed(val code: DiagnosticCode, val detail: String?) : EngineState
}

enum class StopReason {
    USER,
    ERROR,
    PROJECTION_STOPPED,
    SERVICE_DESTROYED,
}

/**
 * Owns one ambilight session: capture -> analysis -> smoothing -> UDP output.
 *
 * State transitions are guarded by [startMutex]/[lock]; [stop] is safe from any thread and from
 * inside the session's own coroutines. Resources are always released in [cleanup].
 */
class AmbilightEngine(
    context: Context,
    private val diagnostics: DiagnosticsRepository,
    private val http: WledHttpClient = WledHttpClient(),
) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val analyzer = FrameAnalyzer()
    private val smoother = ColorSmoother()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val startMutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _preview = MutableStateFlow(IntArray(0))
    val preview: StateFlow<IntArray> = _preview.asStateFlow()

    private var generation = 0L
    private var session: Session? = null

    suspend fun start(
        projection: MediaProjection,
        device: WledDevice,
        layout: LedLayout,
        settings: AmbilightSettings,
    ): Boolean = startMutex.withLock {
        val myGeneration = synchronized(lock) { ++generation }
        closeSession(StopReason.USER)
        _state.value = EngineState.Starting

        if (!layout.isValid()) {
            return@withLock abortStart(projection, DiagnosticCode.UNSUPPORTED_PROTOCOL, "invalid LED layout")
        }
        if (device.protocol == OutputProtocol.WLED_UDP &&
            device.wledUdpMode == WledUdpMode.DRGB &&
            layout.total > WledUdp.MAX_LEDS_PER_PACKET
        ) {
            return@withLock abortStart(
                projection,
                DiagnosticCode.UNSUPPORTED_PROTOCOL,
                "DRGB supports at most ${WledUdp.MAX_LEDS_PER_PACKET} LEDs; use DNRGB",
            )
        }
        if (device.protocol == OutputProtocol.WLED_UDP &&
            device.wledUdpMode == WledUdpMode.WARLS &&
            layout.total > WledUdp.WARLS_MAX_LEDS
        ) {
            return@withLock abortStart(
                projection,
                DiagnosticCode.UNSUPPORTED_PROTOCOL,
                "WARLS supports at most ${WledUdp.WARLS_MAX_LEDS} LEDs; use DNRGB or DDP",
            )
        }

        val sanitized = settings.sanitized()
        val probe = try {
            withTimeout(2500) { http.probe(device.host, device.httpPort) }
        } catch (cancelled: CancellationException) {
            runCatching { projection.stop() }
            throw cancelled
        } catch (error: Exception) {
            diagnostics.report(
                DiagnosticCode.DEVICE_HTTP_UNREACHABLE,
                "${device.host}:${device.httpPort}; continuing with UDP only",
            )
            null
        }

        if (probe != null) {
            val reportedCount = probe.info.leds?.count
            if (reportedCount != null && reportedCount != layout.total) {
                diagnostics.report(
                    DiagnosticCode.LED_COUNT_MISMATCH,
                    "WLED reports $reportedCount LEDs, layout has ${layout.total}",
                )
            }
            if (probe.info.live == true) {
                diagnostics.report(DiagnosticCode.REALTIME_OCCUPIED, "WLED is already in realtime mode")
            }
        }

        if (!stillCurrent(myGeneration)) {
            runCatching { projection.stop() }
            return@withLock false
        }

        try {
            withTimeout(3500) {
                withContext(Dispatchers.IO) { InetAddress.getByName(device.host) }
            }
        } catch (cancelled: CancellationException) {
            runCatching { projection.stop() }
            throw cancelled
        } catch (error: Exception) {
            return@withLock abortStart(projection, DiagnosticCode.DEVICE_NOT_FOUND, device.host)
        }

        val ddpBuilder = DdpPacketBuilder()
        val wledBuilder = WledUdpPacketBuilder(device.wledUdpMode)
        var ddpSequence = 0
        val packetizer: (ByteArray) -> List<ByteArray> = when (device.protocol) {
            OutputProtocol.DDP -> { rgb ->
                val packets = ddpBuilder.build(rgb, ddpSequence)
                ddpSequence = (ddpSequence + packets.size) and 0xFF
                packets
            }
            OutputProtocol.WLED_UDP -> { rgb -> wledBuilder.build(rgb) }
        }
        val output = UdpLedOutput(device.host, device.outputPort, packetizer)

        val (displayWidth, displayHeight) = displaySize()
        val size = AnalysisSizing.forDisplay(displayWidth, displayHeight, sanitized.effectiveAnalysisWidth)
        val source = MediaProjectionFrameSource(appContext, projection, size.width, size.height)
        val startResult = source.start()
        if (startResult.isFailure) {
            output.close()
            runCatching { projection.stop() }
            return@withLock failed(
                DiagnosticCode.CAPTURE_FAILED,
                startResult.exceptionOrNull()?.message,
            )
        }

        val active = Session(
            projection = projection,
            source = source,
            output = output,
            wifiLock = acquireWifiLock(),
            cadence = FrameCadenceLimiter(sanitized.effectiveFps),
            startedAt = System.currentTimeMillis(),
            analysisWidth = size.width,
            analysisHeight = size.height,
            protocolLabel = protocolLabel(device),
            ledCount = layout.total,
        )

        val committed = synchronized(lock) {
            if (generation != myGeneration) false else {
                session = active
                true
            }
        }
        if (!committed) {
            cleanup(active)
            return@withLock false
        }

        smoother.reset()
        _preview.value = IntArray(layout.total)
        val smoothingParams = SmoothingParams.of(sanitized.smoothing)

        active.pipelineJob = scope.launch {
            source.frames.collectLatest { frame ->
                if (!active.cadence.shouldAccept(frame.timestampNanos)) return@collectLatest
                val startedAnalysis = SystemClock.elapsedRealtimeNanos()
                val analysis = analyzer.analyze(frame, layout, sanitized)
                val smoothed = smoother.smooth(analysis.colors, smoothingParams)
                val analysisNanos = SystemClock.elapsedRealtimeNanos() - startedAnalysis

                val startedSend = SystemClock.elapsedRealtimeNanos()
                try {
                    withTimeout(2000) { output.send(smoothed) }
                    active.sendFailures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    active.sendFailures++
                    if (active.sendFailures >= MAX_SEND_FAILURES) {
                        failSession(active, DiagnosticCode.NETWORK_LOST, error.message)
                    }
                    return@collectLatest
                }

                active.frames++
                active.analysisMs = analysisNanos / 1_000_000f
                active.sendMs = (SystemClock.elapsedRealtimeNanos() - startedSend) / 1_000_000f
                _preview.value = smoothed.copyOf()

                val now = SystemClock.elapsedRealtime()
                if (analysis.blackFrame) {
                    if (active.blackSince == 0L) active.blackSince = now
                    if (!active.blackReported && now - active.blackSince > BLACK_FRAME_WARNING_MS) {
                        active.blackReported = true
                        diagnostics.report(
                            DiagnosticCode.BLACK_FRAME_SUSPECTED,
                            "capture has been black for ${(now - active.blackSince) / 1000}s",
                        )
                    }
                } else {
                    active.blackSince = 0L
                    active.blackReported = false
                }
            }
        }

        active.eventJob = scope.launch {
            source.events.collect { event ->
                when (event) {
                    FrameSourceEvent.StoppedBySystem -> failSession(
                        active,
                        DiagnosticCode.PROJECTION_STOPPED,
                        "MediaProjection was stopped by the system or the user",
                    )
                    is FrameSourceEvent.Failed -> failSession(
                        active,
                        DiagnosticCode.CAPTURE_FAILED,
                        event.message,
                    )
                }
            }
        }

        active.statsJob = scope.launch {
            var lastFrames = 0L
            var lastPackets = 0L
            var lastTick = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val elapsedSeconds = ((now - lastTick).coerceAtLeast(1L)) / 1000f
                val packets = output.packetsSent
                val fps = (active.frames - lastFrames) / elapsedSeconds
                val packetsPerSecond = (packets - lastPackets) / elapsedSeconds
                lastFrames = active.frames
                lastPackets = packets
                lastTick = now
                val blackSeconds = if (active.blackSince > 0L) (now - active.blackSince) / 1000f else 0f
                _stats.value = SessionStats(
                    running = true,
                    fps = fps,
                    analysisMs = active.analysisMs,
                    sendMs = active.sendMs,
                    packetsPerSecond = packetsPerSecond,
                    droppedFrames = active.cadence.dropped,
                    blackFrameSeconds = blackSeconds,
                    analysisWidth = active.analysisWidth,
                    analysisHeight = active.analysisHeight,
                    ledCount = active.ledCount,
                    protocolLabel = active.protocolLabel,
                    startedEpochMillis = active.startedAt,
                )
            }
        }

        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val (width, height) = displaySize()
                val resized = AnalysisSizing.forDisplay(width, height, sanitized.effectiveAnalysisWidth)
                source.resize(resized.width, resized.height)
                active.analysisWidth = resized.width
                active.analysisHeight = resized.height
            }
        }
        active.displayListener = displayListener
        runCatching {
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }

        _state.value = EngineState.Running(
            deviceName = device.displayName,
            protocolLabel = active.protocolLabel,
            startedAtMillis = active.startedAt,
        )
        diagnostics.report(
            DiagnosticCode.SESSION_STARTED,
            "${device.displayName} ${active.protocolLabel} ${size.width}x${size.height}",
        )
        true
    }

    fun stop(reason: StopReason = StopReason.USER) {
        synchronized(lock) { generation++ }
        val active = synchronized(lock) {
            val current = session
            session = null
            current
        }
        if (active != null) {
            cleanup(active)
            diagnostics.report(DiagnosticCode.SESSION_STOPPED, reason.name)
        }
        _preview.value = IntArray(0)
        _stats.value = SessionStats()
        _state.value = EngineState.Idle
    }

    fun shutdown() {
        stop(StopReason.SERVICE_DESTROYED)
        scope.cancel()
    }

    private fun closeSession(reason: StopReason) {
        val active = synchronized(lock) {
            val current = session
            session = null
            current
        }
        if (active != null) {
            cleanup(active)
            diagnostics.report(DiagnosticCode.SESSION_STOPPED, reason.name)
        }
    }

    private fun cleanup(active: Session) {
        active.pipelineJob?.cancel()
        active.statsJob?.cancel()
        active.eventJob?.cancel()
        active.displayListener?.let { listener ->
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        active.wifiLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        runCatching { active.source.close() }
        runCatching { active.output.close() }
    }

    private fun failSession(active: Session, code: DiagnosticCode, detail: String?) {
        val current = synchronized(lock) {
            if (session !== active) false else {
                session = null
                true
            }
        }
        if (!current) return
        diagnostics.report(code, detail)
        cleanup(active)
        _preview.value = IntArray(0)
        _stats.value = SessionStats()
        diagnostics.report(DiagnosticCode.SESSION_STOPPED, StopReason.ERROR.name)
        _state.value = EngineState.Failed(code, detail)
    }

    private fun abortStart(projection: MediaProjection, code: DiagnosticCode, detail: String?): Boolean {
        runCatching { projection.stop() }
        return failed(code, detail)
    }

    private fun failed(code: DiagnosticCode, detail: String?): Boolean {
        diagnostics.report(code, detail)
        _state.value = EngineState.Failed(code, detail)
        return false
    }

    private fun stillCurrent(generationValue: Long): Boolean = synchronized(lock) {
        generation == generationValue
    }

    private fun protocolLabel(device: WledDevice): String = when (device.protocol) {
        OutputProtocol.DDP -> "DDP"
        OutputProtocol.WLED_UDP -> "WLED UDP ${device.wledUdpMode.name}"
    }

    private fun displaySize(): Pair<Int, Int> {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return 1920 to 1080
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun acquireWifiLock(): WifiManager.WifiLock? = try {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wled-ambilight")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (error: Exception) {
        Log.w(TAG, "Wi-Fi lock unavailable", error)
        null
    }

    private class Session(
        val projection: MediaProjection,
        val source: MediaProjectionFrameSource,
        val output: UdpLedOutput,
        val wifiLock: WifiManager.WifiLock?,
        val cadence: FrameCadenceLimiter,
        val startedAt: Long,
        @Volatile var analysisWidth: Int,
        @Volatile var analysisHeight: Int,
        val protocolLabel: String,
        val ledCount: Int,
    ) {
        var pipelineJob: Job? = null
        var statsJob: Job? = null
        var eventJob: Job? = null
        var displayListener: DisplayManager.DisplayListener? = null

        @Volatile var frames: Long = 0
        @Volatile var analysisMs: Float = 0f
        @Volatile var sendMs: Float = 0f
        @Volatile var blackSince: Long = 0
        @Volatile var blackReported: Boolean = false
        @Volatile var sendFailures: Int = 0
    }

    companion object {
        private const val TAG = "AmbilightEngine"
        private const val MAX_SEND_FAILURES = 5
        private const val BLACK_FRAME_WARNING_MS = 2000L
    }
}
