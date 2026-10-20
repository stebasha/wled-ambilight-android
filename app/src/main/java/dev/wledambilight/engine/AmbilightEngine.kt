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
import dev.wledambilight.core.capture.DemoFrameSource
import dev.wledambilight.core.capture.FrameSource
import dev.wledambilight.core.capture.FrameSourceEvent
import dev.wledambilight.core.capture.MediaProjectionFrameSource
import dev.wledambilight.core.data.DiagnosticsRepository
import dev.wledambilight.core.imaging.AnalysisSizing
import dev.wledambilight.core.imaging.ColorSmoother
import dev.wledambilight.core.imaging.FrameAnalyzer
import dev.wledambilight.core.imaging.FrameCadenceLimiter
import dev.wledambilight.core.imaging.LedLayoutMapper
import dev.wledambilight.core.imaging.SmoothingParams
import dev.wledambilight.core.imaging.VideoFrame
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.SessionStats
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.network.WledHttpClient
import dev.wledambilight.core.protocol.DdpPacketBuilder
import dev.wledambilight.core.protocol.GoveeOutput
import dev.wledambilight.core.protocol.RgbEncoder
import dev.wledambilight.core.protocol.UdpLedOutput
import dev.wledambilight.core.protocol.WledUdpPacketBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data class Running(
        val deviceName: String,
        val protocolLabel: String,
        val startedAtMillis: Long,
        val demo: Boolean = false,
        val targets: Int = 1,
    ) : EngineState

    data class Failed(val code: DiagnosticCode, val detail: String?) : EngineState
}

enum class StopReason {
    USER,
    ERROR,
    PROJECTION_STOPPED,
    SERVICE_DESTROYED,
}

/** What feeds the pipeline: the real screen (with user consent) or the synthetic demo. */
sealed interface SessionSource {
    class Projection(val projection: MediaProjection) : SessionSource
    data object Demo : SessionSource
}

/**
 * Owns one ambilight session: frames -> analysis -> smoothing -> every configured output.
 *
 * All outputs (main strip, extra zones on the same or other WLED controllers, Govee lamps) are
 * computed from the same frame in the same tick, which keeps them in sync. WLED targets get a
 * keep-alive resend while the picture is static, so the controller does not drop out of realtime
 * mode, and are released with `"live": false` when the session ends.
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Survives [shutdown] so the realtime release can still go out after the session scope dies. */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val startMutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    /** Colours of the main screen layout (also computed in demo mode without a controller). */
    private val _preview = MutableStateFlow(IntArray(0))
    val preview: StateFlow<IntArray> = _preview.asStateFlow()

    /** Latest colours of every extra zone, keyed by zone id. */
    private val _zonePreview = MutableStateFlow<Map<String, IntArray>>(emptyMap())
    val zonePreview: StateFlow<Map<String, IntArray>> = _zonePreview.asStateFlow()

    private var generation = 0L
    private var session: Session? = null

    suspend fun start(
        source: SessionSource,
        plan: SessionPlan,
        previewLayout: LedLayout,
        settings: AmbilightSettings,
    ): Boolean = startMutex.withLock {
        val myGeneration = synchronized(lock) { ++generation }
        closeSession(StopReason.USER)
        _state.value = EngineState.Starting
        val projection = (source as? SessionSource.Projection)?.projection
        val demo = source is SessionSource.Demo

        plan.problems.forEach { problem ->
            diagnostics.report(DiagnosticCode.ZONE_SKIPPED, "${problem.kind}: ${problem.detail}")
        }
        if (plan.isEmpty && !demo) {
            val code = if (plan.problems.isEmpty()) DiagnosticCode.DEVICE_NOT_FOUND else DiagnosticCode.UNSUPPORTED_PROTOCOL
            return@withLock abortStart(projection, code, "no usable outputs")
        }

        val sanitized = settings.sanitized()
        val wledTargets = try {
            prepareWledTargets(plan.wled)
        } catch (cancelled: CancellationException) {
            projection?.let { runCatching { it.stop() } }
            throw cancelled
        }
        val goveeTargets = plan.govee.map { GoveeTarget(it, GoveeOutput(it.device.host)) }
        if (wledTargets.isEmpty() && goveeTargets.isEmpty() && !demo) {
            return@withLock abortStart(projection, DiagnosticCode.DEVICE_NOT_FOUND, plan.wled.joinToString { it.device.host })
        }

        if (!stillCurrent(myGeneration)) {
            closeTargets(wledTargets, goveeTargets, release = false)
            projection?.let { runCatching { it.stop() } }
            return@withLock false
        }

        val (displayWidth, displayHeight) = displaySize()
        val size = AnalysisSizing.forDisplay(displayWidth, displayHeight, sanitized.effectiveAnalysisWidth)
        val frameSource: FrameSource = if (projection != null) {
            MediaProjectionFrameSource(appContext, projection, size.width, size.height)
        } else {
            DemoFrameSource(size.width.coerceAtMost(240), size.height.coerceAtMost(240), sanitized.effectiveFps)
        }
        val startResult = frameSource.start()
        if (startResult.isFailure) {
            closeTargets(wledTargets, goveeTargets, release = false)
            projection?.let { runCatching { it.stop() } }
            return@withLock failed(DiagnosticCode.CAPTURE_FAILED, startResult.exceptionOrNull()?.message)
        }

        val active = Session(
            source = frameSource,
            wled = wledTargets,
            govee = goveeTargets,
            wifiLock = if (wledTargets.isNotEmpty() || goveeTargets.isNotEmpty()) acquireWifiLock() else null,
            cadence = FrameCadenceLimiter(sanitized.effectiveFps),
            startedAt = System.currentTimeMillis(),
            analysisWidth = size.width,
            analysisHeight = size.height,
            protocolLabel = protocolLabel(wledTargets, goveeTargets, demo),
            ledCount = plan.totalLeds,
            previewZones = LedLayoutMapper.zones(previewLayout),
            demo = demo,
        )

        val committed = synchronized(lock) {
            if (generation != myGeneration) false else {
                session = active
                true
            }
        }
        if (!committed) {
            cleanup(active, release = false)
            return@withLock false
        }

        goveeTargets.forEach { target ->
            runCatching { target.output.prepare() }
                .onFailure { diagnostics.report(DiagnosticCode.UDP_SEND_FAILED, "govee ${target.plan.device.host}") }
        }

        _preview.value = IntArray(active.previewZones.size)
        _zonePreview.value = emptyMap()
        val smoothingParams = SmoothingParams.of(sanitized.smoothing)
        val analyzer = FrameAnalyzer()

        active.pipelineJob = scope.launch {
            frameSource.frames.collectLatest { frame ->
                if (!active.cadence.shouldAccept(frame.timestampNanos)) return@collectLatest
                processFrame(active, frame, analyzer, sanitized, smoothingParams)
            }
        }
        active.keepAliveJob = scope.launch { keepAliveLoop(active) }
        active.eventJob = scope.launch {
            frameSource.events.collect { event ->
                when (event) {
                    FrameSourceEvent.StoppedBySystem -> failSession(
                        active,
                        DiagnosticCode.PROJECTION_STOPPED,
                        "MediaProjection was stopped by the system or the user",
                    )
                    is FrameSourceEvent.Failed -> failSession(active, DiagnosticCode.CAPTURE_FAILED, event.message)
                }
            }
        }
        active.statsJob = scope.launch { statsLoop(active) }

        if (projection != null) {
            val displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    val (width, height) = displaySize()
                    val resized = AnalysisSizing.forDisplay(width, height, sanitized.effectiveAnalysisWidth)
                    frameSource.resize(resized.width, resized.height)
                    active.analysisWidth = resized.width
                    active.analysisHeight = resized.height
                }
            }
            active.displayListener = displayListener
            runCatching {
                displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            }
        }

        val mainName = wledTargets.firstOrNull()?.device?.displayName
            ?: goveeTargets.firstOrNull()?.plan?.device?.displayName
            ?: "Demo"
        _state.value = EngineState.Running(
            deviceName = mainName,
            protocolLabel = active.protocolLabel,
            startedAtMillis = active.startedAt,
            demo = demo,
            targets = wledTargets.size + goveeTargets.size,
        )
        diagnostics.report(
            DiagnosticCode.SESSION_STARTED,
            "${if (demo) "demo " else ""}$mainName ${active.protocolLabel} ${size.width}x${size.height}, " +
                "${wledTargets.size} WLED, ${goveeTargets.size} Govee",
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
            cleanup(active, release = true)
            diagnostics.report(DiagnosticCode.SESSION_STOPPED, reason.name)
        }
        resetLiveOutputs()
        _state.value = EngineState.Idle
    }

    fun shutdown() {
        stop(StopReason.SERVICE_DESTROYED)
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------------
    // Pipeline

    private suspend fun processFrame(
        active: Session,
        frame: VideoFrame,
        analyzer: FrameAnalyzer,
        settings: AmbilightSettings,
        smoothingParams: SmoothingParams,
    ) {
        val startedAnalysis = SystemClock.elapsedRealtimeNanos()
        val main = analyzer.analyzeZones(frame, active.previewZones, settings)
        val mainSmoothed = active.previewSmoother.smooth(main.colors, smoothingParams)

        for (target in active.wled) {
            for (span in target.spans) {
                val raw = when (val source = span.plan.source) {
                    is SpanSource.Zones -> if (span.plan.key == OutputPlanner.MAIN_KEY &&
                        span.plan.brightnessScale >= 0.999f && source.zones == active.previewZones
                    ) {
                        main.colors
                    } else {
                        analyzer.analyzeZones(frame, source.zones, settings, span.plan.brightnessScale).colors
                    }
                    is SpanSource.Solid -> {
                        val color = analyzer.regionColor(frame, source.region, settings, span.plan.brightnessScale)
                        IntArray(source.count) { color }
                    }
                }
                span.last = span.smoother.smooth(raw, smoothingParams)
            }
        }
        val goveeColors = active.govee.map { target ->
            val color = analyzer.regionColor(frame, target.plan.region, settings, target.plan.brightnessScale)
            target.smoother.smooth(intArrayOf(color), smoothingParams)[0].also { target.last = it }
        }
        val analysisNanos = SystemClock.elapsedRealtimeNanos() - startedAnalysis

        val startedSend = SystemClock.elapsedRealtimeNanos()
        val now = SystemClock.elapsedRealtime()
        for (target in active.wled) {
            if (!target.readyAt(now)) continue
            val packets = target.buildPackets()
            target.lastPackets = packets
            sendWled(active, target, packets, now)
        }
        active.govee.forEachIndexed { index, target ->
            if (!target.readyAt(now)) return@forEachIndexed
            try {
                target.output.offer(goveeColors[index])
                target.onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onTargetFailure(active, target, target.plan.device, error, now)
            }
        }

        active.frames++
        active.analysisMs = analysisNanos / 1_000_000f
        active.sendMs = (SystemClock.elapsedRealtimeNanos() - startedSend) / 1_000_000f
        publishPreview(active, mainSmoothed, now)
        trackBlackFrames(active, main.blackFrame, now)
        if (active.allTargetsDown()) {
            failSession(active, DiagnosticCode.NETWORK_LOST, "all outputs failed repeatedly")
        }
    }

    private suspend fun sendWled(active: Session, target: WledTarget, packets: List<ByteArray>, now: Long) {
        try {
            withTimeout(SEND_TIMEOUT_MS) { target.output.sendPackets(packets) }
            target.lastSendAt = now
            target.onSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onTargetFailure(active, target, target.device, error, now)
        }
    }

    private fun onTargetFailure(active: Session, target: TargetHealth, device: WledDevice, error: Exception, now: Long) {
        if (target.onFailure(now)) {
            diagnostics.report(
                DiagnosticCode.NETWORK_LOST,
                "${device.displayName} (${device.host}): ${error.javaClass.simpleName}; retry in ${target.backoffMs / 1000}s",
            )
            (target as? WledTarget)?.output?.invalidateAddress()
        }
        if (active.allTargetsDown()) {
            failSession(active, DiagnosticCode.NETWORK_LOST, error.message)
        }
    }

    /** WLED leaves realtime after its timeout (2.5 s by default) when packets stop on a still image. */
    private suspend fun keepAliveLoop(active: Session) {
        while (scope.isActive) {
            delay(KEEPALIVE_CHECK_MS)
            val now = SystemClock.elapsedRealtime()
            for (target in active.wled) {
                val packets = target.lastPackets ?: continue
                if (now - target.lastSendAt < KEEPALIVE_INTERVAL_MS || !target.readyAt(now)) continue
                sendWled(active, target, packets, now)
            }
        }
    }

    private suspend fun statsLoop(active: Session) {
        var lastFrames = 0L
        var lastPackets = 0L
        var lastTick = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val elapsedSeconds = ((now - lastTick).coerceAtLeast(1L)) / 1000f
            val packets = active.wled.sumOf { it.output.packetsSent } + active.govee.sumOf { it.output.packetsSent }
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
                outputs = active.wled.size + active.govee.size,
                outputsDown = active.wled.count { !it.readyAt(now) } + active.govee.count { !it.readyAt(now) },
                demo = active.demo,
            )
        }
    }

    private fun publishPreview(active: Session, main: IntArray, now: Long) {
        if (now - active.lastPreviewAt < PREVIEW_INTERVAL_MS) return
        active.lastPreviewAt = now
        _preview.value = main.copyOf()
        val zones = HashMap<String, IntArray>()
        for (target in active.wled) {
            for (span in target.spans) {
                if (span.plan.key != OutputPlanner.MAIN_KEY) span.last?.let { zones[span.plan.key] = it.copyOf() }
            }
        }
        for (target in active.govee) zones[target.plan.key] = intArrayOf(target.last)
        _zonePreview.value = zones
    }

    private fun trackBlackFrames(active: Session, black: Boolean, now: Long) {
        if (active.demo) return
        if (black) {
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

    // ---------------------------------------------------------------------------------------------
    // Targets

    private suspend fun prepareWledTargets(plans: List<WledPlan>): List<WledTarget> = withContext(Dispatchers.IO) {
        plans.map { plan ->
            async {
                val device = plan.device
                val resolved = withTimeoutOrNull(3_500) {
                    runCatching { InetAddress.getByName(device.host) }.getOrNull()
                }
                if (resolved == null) {
                    diagnostics.report(DiagnosticCode.DEVICE_NOT_FOUND, "${device.displayName} ${device.host}")
                    return@async null
                }
                val probe = try {
                    withTimeout(2_500) { http.probe(device.host, device.httpPort) }
                } catch (cancelled: CancellationException) {
                    if (cancelled is kotlinx.coroutines.TimeoutCancellationException) null else throw cancelled
                } catch (error: Exception) {
                    null
                }
                if (probe == null) {
                    diagnostics.report(
                        DiagnosticCode.DEVICE_HTTP_UNREACHABLE,
                        "${device.host}:${device.httpPort}; continuing with UDP only",
                    )
                } else {
                    val reported = probe.info.leds?.count
                    val needed = plan.spans.maxOf { it.end }
                    if (reported != null && reported != needed) {
                        diagnostics.report(
                            DiagnosticCode.LED_COUNT_MISMATCH,
                            "${device.displayName}: WLED reports $reported LEDs, zones use $needed",
                        )
                    }
                    if (probe.info.live == true) {
                        diagnostics.report(DiagnosticCode.REALTIME_OCCUPIED, "${device.displayName} is already in realtime mode")
                    }
                }
                WledTarget(device, plan.spans.map { SpanState(it) }, UdpLedOutput(device.host, device.outputPort), httpReachable = probe != null)
            }
        }.awaitAll().filterNotNull()
    }

    private fun closeTargets(wled: List<WledTarget>, govee: List<GoveeTarget>, release: Boolean) {
        wled.forEach { target ->
            runCatching { target.output.close() }
            if (release && target.httpReachable) {
                releaseScope.launch {
                    withTimeoutOrNull(RELEASE_TIMEOUT_MS) {
                        runCatching { http.exitRealtime(target.device.host, target.device.httpPort) }
                    }
                }
            }
        }
        govee.forEach { runCatching { it.output.close() } }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle helpers

    private fun closeSession(reason: StopReason) {
        val active = synchronized(lock) {
            val current = session
            session = null
            current
        }
        if (active != null) {
            cleanup(active, release = true)
            diagnostics.report(DiagnosticCode.SESSION_STOPPED, reason.name)
        }
    }

    private fun cleanup(active: Session, release: Boolean) {
        active.pipelineJob?.cancel()
        active.keepAliveJob?.cancel()
        active.statsJob?.cancel()
        active.eventJob?.cancel()
        active.displayListener?.let { listener ->
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        active.wifiLock?.let { wifiLock ->
            runCatching { if (wifiLock.isHeld) wifiLock.release() }
        }
        runCatching { active.source.close() }
        closeTargets(active.wled, active.govee, release)
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
        cleanup(active, release = true)
        resetLiveOutputs()
        diagnostics.report(DiagnosticCode.SESSION_STOPPED, StopReason.ERROR.name)
        _state.value = EngineState.Failed(code, detail)
    }

    private fun resetLiveOutputs() {
        _preview.value = IntArray(0)
        _zonePreview.value = emptyMap()
        _stats.value = SessionStats()
    }

    private fun abortStart(projection: MediaProjection?, code: DiagnosticCode, detail: String?): Boolean {
        projection?.let { runCatching { it.stop() } }
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

    private fun protocolLabel(wled: List<WledTarget>, govee: List<GoveeTarget>, demo: Boolean): String {
        val parts = buildList {
            wled.map { protocolLabel(it.device) }.distinct().forEach { add(it) }
            if (govee.isNotEmpty()) add("Govee LAN")
        }
        return when {
            parts.isEmpty() -> if (demo) "Demo" else "—"
            else -> parts.joinToString(" + ")
        }
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
        @Suppress("DEPRECATION")
        wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wled-ambilight")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (error: Exception) {
        Log.w(TAG, "Wi-Fi lock unavailable", error)
        null
    }

    /** Consecutive-failure tracking with exponential backoff, shared by WLED and Govee targets. */
    private abstract class TargetHealth {
        @Volatile var failures = 0
        @Volatile var retryAt = 0L
        @Volatile var backoffMs = 0L

        fun readyAt(now: Long): Boolean = now >= retryAt

        fun onSuccess() {
            failures = 0
            backoffMs = 0L
        }

        /** Returns true when the target has just been marked down. */
        fun onFailure(now: Long): Boolean {
            failures++
            if (failures < MAX_SEND_FAILURES) return false
            backoffMs = (if (backoffMs == 0L) 1_000L else backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            retryAt = now + backoffMs
            failures = 0
            return true
        }
    }

    private class SpanState(val plan: SpanPlan) {
        val smoother = ColorSmoother()
        @Volatile var last: IntArray? = null
    }

    private class WledTarget(
        val device: WledDevice,
        val spans: List<SpanState>,
        val output: UdpLedOutput,
        val httpReachable: Boolean,
    ) : TargetHealth() {
        private val ddp = DdpPacketBuilder()
        private val wledUdp = WledUdpPacketBuilder(device.wledUdpMode)
        private var sequence = 0

        @Volatile var lastPackets: List<ByteArray>? = null
        @Volatile var lastSendAt = 0L

        fun buildPackets(): List<ByteArray> {
            val packets = ArrayList<ByteArray>()
            spans.forEachIndexed { index, span ->
                val colors = span.last ?: return@forEachIndexed
                val rgb = RgbEncoder.toRgbBytes(colors)
                when (device.protocol) {
                    OutputProtocol.DDP -> {
                        val built = ddp.build(rgb, sequence, span.plan.ledOffset, push = index == spans.lastIndex)
                        sequence = (sequence + built.size) and 0xFF
                        packets += built
                    }
                    OutputProtocol.WLED_UDP -> packets += wledUdp.build(rgb, span.plan.ledOffset)
                }
            }
            return packets
        }
    }

    private class GoveeTarget(val plan: GoveePlan, val output: GoveeOutput) : TargetHealth() {
        val smoother = ColorSmoother()
        @Volatile var last: Int = 0xFF000000.toInt()
    }

    private class Session(
        val source: FrameSource,
        val wled: List<WledTarget>,
        val govee: List<GoveeTarget>,
        val wifiLock: WifiManager.WifiLock?,
        val cadence: FrameCadenceLimiter,
        val startedAt: Long,
        @Volatile var analysisWidth: Int,
        @Volatile var analysisHeight: Int,
        val protocolLabel: String,
        val ledCount: Int,
        val previewZones: List<dev.wledambilight.core.imaging.LedZone>,
        val demo: Boolean,
    ) {
        val previewSmoother = ColorSmoother()
        var pipelineJob: Job? = null
        var keepAliveJob: Job? = null
        var statsJob: Job? = null
        var eventJob: Job? = null
        var displayListener: DisplayManager.DisplayListener? = null

        @Volatile var frames: Long = 0
        @Volatile var analysisMs: Float = 0f
        @Volatile var sendMs: Float = 0f
        @Volatile var blackSince: Long = 0
        @Volatile var blackReported: Boolean = false
        @Volatile var lastPreviewAt: Long = 0

        /** The session only fails when every output is in backoff; one dead lamp never stops the rest. */
        fun allTargetsDown(): Boolean {
            val all: List<TargetHealth> = wled + govee
            if (all.isEmpty()) return false
            return all.all { it.retryAt > 0L && it.backoffMs >= ALL_DOWN_BACKOFF_MS }
        }
    }

    companion object {
        private const val TAG = "AmbilightEngine"
        private const val MAX_SEND_FAILURES = 5
        private const val MAX_BACKOFF_MS = 15_000L
        private const val ALL_DOWN_BACKOFF_MS = 4_000L
        private const val BLACK_FRAME_WARNING_MS = 2000L
        private const val SEND_TIMEOUT_MS = 2000L
        private const val KEEPALIVE_CHECK_MS = 250L
        private const val KEEPALIVE_INTERVAL_MS = 1000L
        private const val PREVIEW_INTERVAL_MS = 50L
        private const val RELEASE_TIMEOUT_MS = 1500L
    }
}
