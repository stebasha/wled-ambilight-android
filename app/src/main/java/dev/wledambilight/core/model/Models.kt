package dev.wledambilight.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Transport used to stream colours to the controller. */
@Serializable
enum class OutputProtocol(val defaultPort: Int) {
    /** Distributed Display Protocol, WLED's recommended realtime protocol. UDP 4048. */
    DDP(4048),

    /** WLED UDP realtime protocols (WARLS / DRGB / DNRGB) on WLED's UDP port (21324 by default). */
    WLED_UDP(21324),
}

/** Packet flavour for [OutputProtocol.WLED_UDP]. See docs/PROTOCOLS.md. */
@Serializable
enum class WledUdpMode {
    WARLS,
    DRGB,
    DNRGB,
}

@Serializable
enum class StartCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
}

@Serializable
enum class LedDirection {
    CLOCKWISE,
    COUNTER_CLOCKWISE,
}

@Serializable
enum class ScreenSide {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
}

/** What kind of light a saved device is. Older saved devices have no field and decode as WLED. */
@Serializable
enum class DeviceKind {
    /** WLED controller: HTTP JSON API + DDP / WLED UDP realtime streaming. */
    WLED,

    /** Govee lamp or strip with "LAN Control" enabled; documented Govee LAN API, UDP 4003. */
    GOVEE,

    /** Home Assistant server; zones address its `light.*` entities through the REST API. */
    HOME_ASSISTANT,
}

/** What the outputs show while the captured picture is black (DRM, a black scene, a paused app). */
@Serializable
enum class BlackFrameBehavior {
    /** Follow the picture: LEDs go dark. */
    OFF,

    /** Keep the last colours seen before the picture went black. */
    KEEP_LAST,

    /** Show [AmbilightSettings.fallbackColor] on every output. */
    FALLBACK_COLOR,
}

@Serializable
data class WledDevice(
    val id: String = UUID.randomUUID().toString(),
    val kind: DeviceKind = DeviceKind.WLED,
    val name: String = "",
    val host: String = "",
    /** Govee model (SKU) reported by LAN discovery, e.g. H6199. Informational only. */
    val model: String? = null,
    /** Home Assistant only: use https:// (e.g. a TLS proxy). WLED always uses http. */
    val useHttps: Boolean = false,
    val httpPort: Int = 80,
    val protocol: OutputProtocol = OutputProtocol.DDP,
    val outputPort: Int = OutputProtocol.DDP.defaultPort,
    val wledUdpMode: WledUdpMode = WledUdpMode.DNRGB,
    val ledCount: Int? = null,
    val firmwareVersion: String? = null,
    val lastSeenEpochMillis: Long? = null,
    val realtimeActive: Boolean? = null,
    val httpReachable: Boolean? = null,
) {
    val displayName: String
        get() = name.ifBlank { host }

    val isWled: Boolean get() = kind == DeviceKind.WLED

    fun normalized(): WledDevice = copy(
        name = name.trim().take(64),
        host = host.trim(),
        model = model?.trim()?.take(32)?.ifBlank { null },
        httpPort = httpPort.coerceIn(1, 65535),
        outputPort = if (kind == DeviceKind.GOVEE) GOVEE_COMMAND_PORT else outputPort.coerceIn(1, 65535),
        useHttps = kind == DeviceKind.HOME_ASSISTANT && useHttps,
        ledCount = ledCount?.coerceIn(1, MAX_LEDS),
    )

    companion object {
        const val MAX_LEDS = 1500
        const val GOVEE_COMMAND_PORT = 4003
        const val HOME_ASSISTANT_PORT = 8123
    }
}

/** How an extra light zone takes its colours from the screen. */
@Serializable
enum class ZoneMapping {
    /** A strip that runs along one screen edge (desk strip -> bottom, side towers -> left/right). */
    EDGE,

    /** A strip around a frame with its own per-side LED counts (second TV, arch, monitor). */
    PERIMETER,

    /** Every LED (or a single-colour lamp) shows the average of one screen region. */
    SOLID,
}

/** Screen area averaged by [ZoneMapping.SOLID] zones. */
@Serializable
enum class ScreenRegion {
    FULL,
    LEFT_HALF,
    RIGHT_HALF,
    TOP_HALF,
    BOTTOM_HALF,
    LEFT_EDGE,
    RIGHT_EDGE,
    TOP_EDGE,
    BOTTOM_EDGE,
    CENTER,
}

/**
 * An additional output synchronised with the main screen strip: another WLED controller, another
 * LED range of the same controller (e.g. LEDs 76..135 under the desk) or a Govee lamp.
 *
 * All zones are computed from the same captured frame and sent in the same pipeline tick, so they
 * stay in sync with the main strip.
 */
@Serializable
data class LightZone(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val deviceId: String = "",
    val mapping: ZoneMapping = ZoneMapping.EDGE,
    /** First LED of this zone on the controller strip (WLED only). */
    val ledOffset: Int = 0,
    /** LEDs in the zone for [ZoneMapping.EDGE] and [ZoneMapping.SOLID]. Ignored for Govee. */
    val ledCount: Int = 30,
    val edge: ScreenSide = ScreenSide.BOTTOM,
    /** Edge strips run left->right (top/bottom) and bottom->top (left/right); this flips that. */
    val reverse: Boolean = false,
    val region: ScreenRegion = ScreenRegion.FULL,
    val layout: LedLayout = LedLayout(),
    /** Per-zone brightness in percent, applied on top of the global Ambilight brightness. */
    val brightness: Int = 100,
    /** Home Assistant only: the `light.*` entity driven by this zone. */
    val entityId: String? = null,
    /**
     * Govee edge zones only: segments streamed through the unofficial razer mode, spread along the
     * edge. 0 keeps the documented single-colour LAN API.
     */
    val goveeSegments: Int = 0,
    /** Spread the segments over the whole lamp, which tolerates a count that differs from the lamp's. */
    val goveeStretch: Boolean = false,
) {
    /** Number of LEDs this zone writes on a WLED controller. */
    fun ledsFor(kind: DeviceKind): Int = when {
        kind != DeviceKind.WLED -> 1
        mapping == ZoneMapping.PERIMETER -> layout.total
        else -> ledCount
    }

    fun sanitized(): LightZone = copy(
        name = name.trim().take(48),
        ledOffset = ledOffset.coerceIn(0, WledDevice.MAX_LEDS - 1),
        ledCount = ledCount.coerceIn(1, LedLayout.MAX_SIDE),
        brightness = brightness.coerceIn(0, 100),
        entityId = entityId?.trim()?.take(255)?.ifBlank { null },
        goveeSegments = if (goveeSegments <= 0) 0 else goveeSegments.coerceIn(MIN_GOVEE_SEGMENTS, MAX_GOVEE_SEGMENTS),
    )

    companion object {
        const val MAX_ZONES = 12
        const val MIN_GOVEE_SEGMENTS = 2
        const val MAX_GOVEE_SEGMENTS = 100
        const val DEFAULT_GOVEE_SEGMENTS = 10

        /** Segment counts reported by the Govee Platform API for known models. */
        private val GOVEE_MODEL_SEGMENTS = mapOf("H60B0" to 8)

        fun defaultGoveeSegments(model: String?): Int =
            GOVEE_MODEL_SEGMENTS[model?.uppercase()] ?: DEFAULT_GOVEE_SEGMENTS

        /** Checked on a real H60B0: it shows colour frames with stretch off and ignores them with it on. */
        private val GOVEE_NO_STRETCH = setOf("H60B0")

        fun supportsGoveeStretch(model: String?): Boolean = model?.uppercase() !in GOVEE_NO_STRETCH
    }
}

/**
 * Physical LED arrangement around the screen.
 *
 * Indices are assigned along the strip: index 0 is the first LED after [startCorner] in [direction].
 * Each side is traversed in the strip direction; [reverseX] flips the order of the LEDs of that side
 * without moving them to another side (useful for non-standard wiring).
 */
@Serializable
data class LedLayout(
    val topCount: Int = 24,
    val rightCount: Int = 14,
    val bottomCount: Int = 24,
    val leftCount: Int = 14,
    val startCorner: StartCorner = StartCorner.TOP_LEFT,
    val direction: LedDirection = LedDirection.CLOCKWISE,
    val reverseTop: Boolean = false,
    val reverseRight: Boolean = false,
    val reverseBottom: Boolean = false,
    val reverseLeft: Boolean = false,
) {
    val total: Int get() = topCount + rightCount + bottomCount + leftCount

    fun count(side: ScreenSide): Int = when (side) {
        ScreenSide.TOP -> topCount
        ScreenSide.RIGHT -> rightCount
        ScreenSide.BOTTOM -> bottomCount
        ScreenSide.LEFT -> leftCount
    }

    fun reverse(side: ScreenSide): Boolean = when (side) {
        ScreenSide.TOP -> reverseTop
        ScreenSide.RIGHT -> reverseRight
        ScreenSide.BOTTOM -> reverseBottom
        ScreenSide.LEFT -> reverseLeft
    }

    fun issues(): List<LayoutIssue> = buildList {
        val sides = listOf(topCount, rightCount, bottomCount, leftCount)
        if (sides.any { it < 0 }) add(LayoutIssue.NEGATIVE_COUNT)
        if (sides.any { it > MAX_SIDE }) add(LayoutIssue.SIDE_TOO_LARGE)
        if (sides.all { it == 0 }) add(LayoutIssue.EMPTY)
        if (total > MAX_TOTAL) add(LayoutIssue.TOTAL_TOO_LARGE)
    }

    fun isValid(): Boolean = issues().isEmpty()

    companion object {
        const val MAX_SIDE = 400
        const val MAX_TOTAL = 1500
    }
}

enum class LayoutIssue {
    NEGATIVE_COUNT,
    EMPTY,
    SIDE_TOO_LARGE,
    TOTAL_TOO_LARGE,
}

@Serializable
enum class SmoothingPreset {
    OFF,
    FAST,
    BALANCED,
    SMOOTH,
    CUSTOM,
}

@Serializable
data class SmoothingSettings(
    val preset: SmoothingPreset = SmoothingPreset.BALANCED,
    /** Used only by [SmoothingPreset.CUSTOM]: EMA weight, 0.05..0.9. */
    val amount: Float = 0.35f,
    /** Ignore changes smaller than this (0..0.2 of full channel range). */
    val threshold: Float = 0.012f,
    /** Maximum change per frame (0.02..1). */
    val maxStep: Float = 0.25f,
)

@Serializable
data class ColorBalance(
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f,
)

@Serializable
enum class PerformanceProfile {
    POWER_SAVER,
    BALANCED,
    SMOOTH,
    LOW_LATENCY,
}

/**
 * Long side of the capture/analysis buffer. The short side follows the screen aspect, so on a
 * 16:9 TV the choices are 160×90, 240×135, 320×180 and 480×270.
 */
@Serializable
enum class CaptureResolution(val longSide: Int) {
    /** 160 px for strips up to ~150 LEDs, 320 px above that; never above the performance profile. */
    AUTO(0),
    R160(160),
    R240(240),
    R320(320),
    R480(480),
}

@Serializable
enum class AppTheme {
    DARK,
    BLACK,
}

@Serializable
data class AmbilightSettings(
    val targetFps: Int = 30,
    /** Long-side pixel budget for the analysis buffer; the other side follows the display aspect. */
    val analysisWidth: Int = 320,
    /** Output brightness multiplier in percent, 0..100. This is independent of the WLED brightness. */
    val brightness: Int = 100,
    val saturation: Float = 1.0f,
    /** Display gamma; 2.2 is neutral, lower values brighten, higher values darken. */
    val gamma: Float = 2.2f,
    val smoothing: SmoothingSettings = SmoothingSettings(),
    /** Fraction of the screen dimension sampled from the edge, 0.02..0.35. */
    val edgeThickness: Float = 0.10f,
    /** Offset of the sampling band from the physical edge, 0..0.15 of the dimension. */
    val edgeInset: Float = 0.0f,
    /** Extra weight for pixels closest to the edge, 0..1. */
    val edgeWeighting: Float = 0.5f,
    val minBrightness: Int = 0,
    val blackBarCompensation: Boolean = true,
    /** Zones darker than this (percent luma, 0..25) are treated as black / switched off. */
    val blackThreshold: Int = 2,
    val blackFrameBehavior: BlackFrameBehavior = BlackFrameBehavior.OFF,
    /** ARGB colour used by [BlackFrameBehavior.FALLBACK_COLOR]. */
    val fallbackColor: Int = DEFAULT_FALLBACK_COLOR,
    val colorBalance: ColorBalance = ColorBalance(),
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
    /** An explicit choice here overrides the performance profile's resolution cap. */
    val captureResolution: CaptureResolution = CaptureResolution.AUTO,
    val keepScreenOn: Boolean = false,
    val theme: AppTheme = AppTheme.DARK,
) {
    fun sanitized(): AmbilightSettings = copy(
        targetFps = targetFps.coerceIn(5, 60),
        analysisWidth = analysisWidth.coerceIn(160, 720),
        brightness = brightness.coerceIn(0, 100),
        saturation = saturation.coerceIn(0f, 2f),
        gamma = gamma.coerceIn(1.4f, 3.0f),
        smoothing = smoothing.copy(
            amount = smoothing.amount.coerceIn(0.05f, 0.9f),
            threshold = smoothing.threshold.coerceIn(0f, 0.2f),
            maxStep = smoothing.maxStep.coerceIn(0.02f, 1f),
        ),
        edgeThickness = edgeThickness.coerceIn(0.02f, 0.35f),
        edgeInset = edgeInset.coerceIn(0f, 0.15f),
        edgeWeighting = edgeWeighting.coerceIn(0f, 1f),
        minBrightness = minBrightness.coerceIn(0, 80),
        blackThreshold = blackThreshold.coerceIn(0, 25),
        fallbackColor = fallbackColor or (0xFF shl 24),
        colorBalance = ColorBalance(
            red = colorBalance.red.coerceIn(0.5f, 1.5f),
            green = colorBalance.green.coerceIn(0.5f, 1.5f),
            blue = colorBalance.blue.coerceIn(0.5f, 1.5f),
        ),
    )

    /** Frames per second actually used after applying the performance profile. */
    val effectiveFps: Int
        get() = minOf(targetFps, performanceProfile.maxFps)

    companion object {
        /** Warm, dim amber: pleasant as a "TV is on" glow when the picture is protected. */
        const val DEFAULT_FALLBACK_COLOR: Int = 0xFF5A3410.toInt()
        const val AUTO_SMALL_SETUP_LEDS = 150
    }

    /**
     * Long side of the capture buffer for a session driving [totalLeds] LEDs. A fixed choice is
     * used as is; AUTO picks 160 px for small setups (each LED still gets several pixels) and
     * 320 px for long strips, within the performance profile.
     */
    fun analysisLongSide(totalLeds: Int): Int = when (captureResolution) {
        CaptureResolution.AUTO -> minOf(effectiveAnalysisWidth, if (totalLeds in 1..AUTO_SMALL_SETUP_LEDS) 160 else 320)
        else -> captureResolution.longSide
    }

    /** Analysis width actually used after applying the performance profile. */
    val effectiveAnalysisWidth: Int
        get() = minOf(analysisWidth, performanceProfile.maxAnalysisWidth)
}

val PerformanceProfile.maxFps: Int
    get() = when (this) {
        PerformanceProfile.POWER_SAVER -> 15
        PerformanceProfile.BALANCED -> 30
        PerformanceProfile.SMOOTH -> 45
        PerformanceProfile.LOW_LATENCY -> 60
    }

val PerformanceProfile.maxAnalysisWidth: Int
    get() = when (this) {
        PerformanceProfile.POWER_SAVER -> 240
        PerformanceProfile.BALANCED -> 320
        PerformanceProfile.SMOOTH -> 400
        PerformanceProfile.LOW_LATENCY -> 480
    }

/** Immutable view of a locally saved setup. Persisted as JSON in DataStore. */
@Serializable
data class AmbilightConfig(
    val settings: AmbilightSettings = AmbilightSettings(),
    val layout: LedLayout = LedLayout(),
    /** When false only [zones] are driven; the selected controller's screen strip is skipped. */
    val mainStripEnabled: Boolean = true,
    val zones: List<LightZone> = emptyList(),
)
