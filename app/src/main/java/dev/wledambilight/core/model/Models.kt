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

enum class ScreenSide {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
}

@Serializable
data class WledDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
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

    fun normalized(): WledDevice = copy(
        name = name.trim().take(64),
        host = host.trim(),
        httpPort = httpPort.coerceIn(1, 65535),
        outputPort = outputPort.coerceIn(1, 65535),
        ledCount = ledCount?.coerceIn(1, MAX_LEDS),
    )

    companion object {
        const val MAX_LEDS = 1500
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
    val colorBalance: ColorBalance = ColorBalance(),
    val performanceProfile: PerformanceProfile = PerformanceProfile.BALANCED,
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
        colorBalance = ColorBalance(
            red = colorBalance.red.coerceIn(0.5f, 1.5f),
            green = colorBalance.green.coerceIn(0.5f, 1.5f),
            blue = colorBalance.blue.coerceIn(0.5f, 1.5f),
        ),
    )

    /** Frames per second actually used after applying the performance profile. */
    val effectiveFps: Int
        get() = minOf(targetFps, performanceProfile.maxFps)

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
)
