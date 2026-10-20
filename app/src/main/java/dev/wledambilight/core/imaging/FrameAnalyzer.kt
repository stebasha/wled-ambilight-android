package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.ScreenSide
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One captured frame in ARGB_8888, already downscaled by the capture layer. */
class VideoFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val timestampNanos: Long,
)

data class AnalysisResult(
    val colors: IntArray,
    val sampledPixels: Int,
    val blackFrame: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnalysisResult) return false
        return sampledPixels == other.sampledPixels &&
            blackFrame == other.blackFrame &&
            colors.contentEquals(other.colors)
    }

    override fun hashCode(): Int = 31 * colors.contentHashCode() + sampledPixels + (if (blackFrame) 1 else 0)
}

/**
 * Pure image analyser: samples a band along each screen edge, averages the pixels of every LED zone
 * and applies the colour pipeline. No Android dependencies, fully unit-testable.
 *
 * Zone fractions follow the geometric clockwise convention of [LedLayoutMapper]: top runs
 * left->right, right top->bottom, bottom right->left and left bottom->top.
 *
 * Not thread-safe: one instance belongs to one pipeline.
 */
class FrameAnalyzer(private val maxSamplesPerZone: Int = 64) {

    private var cachedLayout: LedLayout? = null
    private var cachedZones: List<LedZone> = emptyList()
    private var scratch = FloatArray(0)

    fun analyze(frame: VideoFrame, layout: LedLayout, settings: AmbilightSettings): AnalysisResult {
        if (layout != cachedLayout) {
            cachedLayout = layout
            cachedZones = LedLayoutMapper.zones(layout)
        }
        return analyzeZones(frame, cachedZones, settings)
    }

    /**
     * Analyses an explicit zone list. [brightnessScale] (0..1) is applied on top of the settings
     * brightness, which lets extra zones be dimmer than the main strip without a second pipeline.
     */
    fun analyzeZones(
        frame: VideoFrame,
        zones: List<LedZone>,
        settings: AmbilightSettings,
        brightnessScale: Float = 1f,
    ): AnalysisResult {
        if (zones.isEmpty() || !frame.isUsable()) {
            return AnalysisResult(IntArray(zones.size), 0, true)
        }

        val raw = scratchFor(zones.size)
        val sample = FloatArray(3)
        var sampled = 0
        for ((i, zone) in zones.withIndex()) {
            sampled += sampleZone(frame, zoneRect(frame, zone, settings.edgeThickness, settings.edgeInset), settings.edgeWeighting, sample)
            raw[i * 3] = sample[0]
            raw[i * 3 + 1] = sample[1]
            raw[i * 3 + 2] = sample[2]
        }

        if (settings.blackBarCompensation) compensateBlackBars(raw, zones.size)

        var black = true
        for (i in zones.indices) {
            if (ColorMath.luma(raw[i * 3], raw[i * 3 + 1], raw[i * 3 + 2]) >= BLACK_LEVEL) {
                black = false
                break
            }
        }

        val adjusted = settings.withScaledBrightness(brightnessScale)
        val out = IntArray(zones.size) { i ->
            sample[0] = raw[i * 3]
            sample[1] = raw[i * 3 + 1]
            sample[2] = raw[i * 3 + 2]
            ColorMath.adjust(sample, adjusted)
            ColorMath.toArgb(sample[0], sample[1], sample[2])
        }
        return AnalysisResult(out, sampled, black)
    }

    /** Average colour of a whole screen region, with the colour pipeline applied. */
    fun regionColor(
        frame: VideoFrame,
        region: ScreenRegion,
        settings: AmbilightSettings,
        brightnessScale: Float = 1f,
    ): Int {
        if (!frame.isUsable()) return ColorMath.toArgb(0f, 0f, 0f)
        val (l, t, r, b) = regionFractions(region, settings.edgeThickness)
        val w = frame.width
        val h = frame.height
        val left = (l * w).roundToInt().coerceIn(0, w - 1)
        val top = (t * h).roundToInt().coerceIn(0, h - 1)
        val rect = ZoneRect(
            left = left,
            top = top,
            right = (r * w).roundToInt().coerceIn(left + 1, w),
            bottom = (b * h).roundToInt().coerceIn(top + 1, h),
            side = null,
        )
        val sample = FloatArray(3)
        sampleZone(frame, rect, 0f, sample, samplesBudget = REGION_SAMPLES)
        ColorMath.adjust(sample, settings.withScaledBrightness(brightnessScale))
        return ColorMath.toArgb(sample[0], sample[1], sample[2])
    }

    private fun VideoFrame.isUsable(): Boolean =
        width > 0 && height > 0 && argb.size >= width * height

    private fun scratchFor(zoneCount: Int): FloatArray {
        val needed = zoneCount * 3
        if (scratch.size < needed) scratch = FloatArray(needed)
        return scratch
    }

    private fun zoneRect(
        frame: VideoFrame,
        zone: LedZone,
        edgeThickness: Float,
        edgeInset: Float,
    ): ZoneRect {
        val w = frame.width
        val h = frame.height
        val bandX = (w * edgeThickness).roundToInt().coerceIn(2, maxOf(2, w / 2))
        val bandY = (h * edgeThickness).roundToInt().coerceIn(2, maxOf(2, h / 2))
        val insetX = (w * edgeInset).roundToInt().coerceIn(0, maxOf(0, w / 3))
        val insetY = (h * edgeInset).roundToInt().coerceIn(0, maxOf(0, h / 3))

        // Convert the geometric clockwise fraction to a left->right / top->bottom screen fraction.
        val flipped = zone.side == ScreenSide.BOTTOM || zone.side == ScreenSide.LEFT
        val from = if (flipped) 1f - zone.endFraction else zone.startFraction
        val to = if (flipped) 1f - zone.startFraction else zone.endFraction
        val xStart = (from * w).roundToInt().coerceIn(0, w - 1)
        val xEnd = (to * w).roundToInt().coerceIn(xStart + 1, w)
        val yStart = (from * h).roundToInt().coerceIn(0, h - 1)
        val yEnd = (to * h).roundToInt().coerceIn(yStart + 1, h)

        return when (zone.side) {
            ScreenSide.TOP -> ZoneRect(
                left = xStart,
                top = insetY,
                right = xEnd,
                bottom = (insetY + bandY).coerceAtMost(h),
                side = ScreenSide.TOP,
            )
            ScreenSide.BOTTOM -> {
                val bottom = h - insetY
                ZoneRect(
                    left = xStart,
                    top = (bottom - bandY).coerceAtLeast(0),
                    right = xEnd,
                    bottom = bottom,
                    side = ScreenSide.BOTTOM,
                )
            }
            ScreenSide.LEFT -> ZoneRect(
                left = insetX,
                top = yStart,
                right = (insetX + bandX).coerceAtMost(w),
                bottom = yEnd,
                side = ScreenSide.LEFT,
            )
            ScreenSide.RIGHT -> {
                val right = w - insetX
                ZoneRect(
                    left = (right - bandX).coerceAtLeast(0),
                    top = yStart,
                    right = right,
                    bottom = yEnd,
                    side = ScreenSide.RIGHT,
                )
            }
        }.inset()
    }

    /** Writes the weighted average (0..1 sRGB) into [out] and returns the number of samples. */
    private fun sampleZone(
        frame: VideoFrame,
        rect: ZoneRect,
        edgeWeighting: Float,
        out: FloatArray,
        samplesBudget: Int = maxSamplesPerZone,
    ): Int {
        val rectWidth = (rect.right - rect.left).coerceAtLeast(1)
        val rectHeight = (rect.bottom - rect.top).coerceAtLeast(1)
        val area = rectWidth * rectHeight
        val step = ceil(sqrt(area.toFloat() / samplesBudget.coerceAtLeast(1))).toInt().coerceAtLeast(1)
        val pixels = frame.argb
        val stride = frame.width

        var r = 0f
        var g = 0f
        var b = 0f
        var totalWeight = 0f
        var samples = 0

        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            val row = y * stride
            while (x < rect.right) {
                val argb = pixels[row + x]
                val weight = if (edgeWeighting <= 0f || rect.side == null) {
                    1f
                } else {
                    val depth = when (rect.side) {
                        ScreenSide.TOP -> (y - rect.top).toFloat() / rectHeight
                        ScreenSide.BOTTOM -> (rect.bottom - 1 - y).toFloat() / rectHeight
                        ScreenSide.LEFT -> (x - rect.left).toFloat() / rectWidth
                        ScreenSide.RIGHT -> (rect.right - 1 - x).toFloat() / rectWidth
                    }
                    1f + edgeWeighting * (1f - depth.coerceIn(0f, 1f)) * 2f
                }
                r += ((argb shr 16) and 0xFF) * weight
                g += ((argb shr 8) and 0xFF) * weight
                b += (argb and 0xFF) * weight
                totalWeight += weight
                samples++
                x += step
            }
            y += step
        }

        if (samples == 0 || totalWeight <= 0f) {
            val centerX = ((rect.left + rect.right) / 2).coerceIn(0, frame.width - 1)
            val centerY = ((rect.top + rect.bottom) / 2).coerceIn(0, frame.height - 1)
            val argb = pixels[centerY * stride + centerX]
            out[0] = ColorMath.red(argb) / 255f
            out[1] = ColorMath.green(argb) / 255f
            out[2] = ColorMath.blue(argb) / 255f
            return 1
        }
        val norm = 1f / (totalWeight * 255f)
        out[0] = r * norm
        out[1] = g * norm
        out[2] = b * norm
        return samples
    }

    /**
     * Suppresses dark letterbox/pillarbox bars: a dark zone between two clearly lit neighbours is
     * replaced with their average. A genuinely black scene has no lit neighbours and stays black.
     */
    private fun compensateBlackBars(zones: FloatArray, count: Int) {
        if (count < 3) return
        val snapshot = zones.copyOf(count * 3)
        fun luma(i: Int) = ColorMath.luma(snapshot[i * 3], snapshot[i * 3 + 1], snapshot[i * 3 + 2])
        for (i in 0 until count) {
            if (luma(i) >= BLACK_LEVEL) continue
            val previous = (i - 1 + count) % count
            val next = (i + 1) % count
            if (luma(previous) > NEIGHBOUR_LEVEL && luma(next) > NEIGHBOUR_LEVEL) {
                for (c in 0..2) {
                    zones[i * 3 + c] = (snapshot[previous * 3 + c] + snapshot[next * 3 + c]) * 0.5f
                }
            }
        }
    }

    private data class ZoneRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val side: ScreenSide?,
    ) {
        fun inset(): ZoneRect {
            if (right - left <= 3 || bottom - top <= 3) return this
            return ZoneRect(left + 1, top + 1, right - 1, bottom - 1, side)
        }
    }

    private data class Fractions(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun regionFractions(region: ScreenRegion, edge: Float): Fractions {
        val e = edge.coerceIn(0.02f, 0.5f)
        return when (region) {
            ScreenRegion.FULL -> Fractions(0f, 0f, 1f, 1f)
            ScreenRegion.LEFT_HALF -> Fractions(0f, 0f, 0.5f, 1f)
            ScreenRegion.RIGHT_HALF -> Fractions(0.5f, 0f, 1f, 1f)
            ScreenRegion.TOP_HALF -> Fractions(0f, 0f, 1f, 0.5f)
            ScreenRegion.BOTTOM_HALF -> Fractions(0f, 0.5f, 1f, 1f)
            ScreenRegion.LEFT_EDGE -> Fractions(0f, 0f, e, 1f)
            ScreenRegion.RIGHT_EDGE -> Fractions(1f - e, 0f, 1f, 1f)
            ScreenRegion.TOP_EDGE -> Fractions(0f, 0f, 1f, e)
            ScreenRegion.BOTTOM_EDGE -> Fractions(0f, 1f - e, 1f, 1f)
            ScreenRegion.CENTER -> Fractions(0.25f, 0.25f, 0.75f, 0.75f)
        }
    }

    private fun AmbilightSettings.withScaledBrightness(scale: Float): AmbilightSettings =
        if (scale >= 0.999f) this else copy(brightness = (brightness * scale.coerceIn(0f, 1f)).roundToInt())

    companion object {
        const val BLACK_LEVEL = 0.02f
        const val NEIGHBOUR_LEVEL = 0.06f
        private const val REGION_SAMPLES = 1024
    }
}
