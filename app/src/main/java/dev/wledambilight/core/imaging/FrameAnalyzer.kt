package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.LedLayout
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
 */
class FrameAnalyzer(private val maxSamplesPerZone: Int = 64) {

    fun analyze(frame: VideoFrame, layout: LedLayout, settings: AmbilightSettings): AnalysisResult {
        val zones = LedLayoutMapper.zones(layout)
        if (zones.isEmpty() || frame.width <= 0 || frame.height <= 0 ||
            frame.argb.size < frame.width * frame.height
        ) {
            return AnalysisResult(IntArray(zones.size.coerceAtLeast(0)), 0, true)
        }

        val raw = Array(zones.size) { FloatArray(3) }
        var sampled = 0
        for ((i, zone) in zones.withIndex()) {
            val rect = zoneRect(frame, zone, settings.edgeThickness, settings.edgeInset)
            val result = sampleZone(frame, rect, settings.edgeWeighting)
            raw[i] = result.color
            sampled += result.samples
        }

        if (settings.blackBarCompensation) compensateBlackBars(raw)

        var black = true
        for (color in raw) {
            if (ColorMath.lumaOf(color) >= BLACK_LEVEL) {
                black = false
                break
            }
        }

        val out = IntArray(zones.size) { i ->
            val color = raw[i].copyOf()
            ColorMath.adjust(color, settings)
            ColorMath.toArgb(color[0], color[1], color[2])
        }
        return AnalysisResult(out, sampled, black)
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
        val xStart = (zone.startFraction * w).roundToInt().coerceIn(0, w - 1)
        val xEnd = (zone.endFraction * w).roundToInt().coerceIn(xStart + 1, w)
        val yStart = (zone.startFraction * h).roundToInt().coerceIn(0, h - 1)
        val yEnd = (zone.endFraction * h).roundToInt().coerceIn(yStart + 1, h)

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

    private fun sampleZone(frame: VideoFrame, rect: ZoneRect, edgeWeighting: Float): SampleResult {
        val rectWidth = (rect.right - rect.left).coerceAtLeast(1)
        val rectHeight = (rect.bottom - rect.top).coerceAtLeast(1)
        val area = rectWidth * rectHeight
        val step = ceil(sqrt(area.toFloat() / maxSamplesPerZone.coerceAtLeast(1))).toInt().coerceAtLeast(1)

        var r = 0f
        var g = 0f
        var b = 0f
        var totalWeight = 0f
        var samples = 0

        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val argb = frame.argb[y * frame.width + x]
                val depth = when (rect.side) {
                    ScreenSide.TOP -> (y - rect.top).toFloat() / rectHeight
                    ScreenSide.BOTTOM -> (rect.bottom - 1 - y).toFloat() / rectHeight
                    ScreenSide.LEFT -> (x - rect.left).toFloat() / rectWidth
                    ScreenSide.RIGHT -> (rect.right - 1 - x).toFloat() / rectWidth
                }
                val weight = 1f + edgeWeighting * (1f - depth.coerceIn(0f, 1f)) * 2f
                r += ColorMath.red(argb) / 255f * weight
                g += ColorMath.green(argb) / 255f * weight
                b += ColorMath.blue(argb) / 255f * weight
                totalWeight += weight
                samples++
                x += step
            }
            y += step
        }

        if (samples == 0 || totalWeight <= 0f) {
            val centerX = ((rect.left + rect.right) / 2).coerceIn(0, frame.width - 1)
            val centerY = ((rect.top + rect.bottom) / 2).coerceIn(0, frame.height - 1)
            val argb = frame.argb[centerY * frame.width + centerX]
            return SampleResult(
                floatArrayOf(
                    ColorMath.red(argb) / 255f,
                    ColorMath.green(argb) / 255f,
                    ColorMath.blue(argb) / 255f,
                ),
                1,
            )
        }
        return SampleResult(floatArrayOf(r / totalWeight, g / totalWeight, b / totalWeight), samples)
    }

    /**
     * Suppresses dark letterbox/pillarbox bars: a dark zone between two clearly lit neighbours is
     * replaced with their average. A genuinely black scene has no lit neighbours and stays black.
     */
    private fun compensateBlackBars(zones: Array<FloatArray>) {
        if (zones.size < 3) return
        val snapshot = Array(zones.size) { zones[it].copyOf() }
        for (i in zones.indices) {
            if (ColorMath.lumaOf(zones[i]) >= BLACK_LEVEL) continue
            val previous = snapshot[(i - 1 + zones.size) % zones.size]
            val next = snapshot[(i + 1) % zones.size]
            if (ColorMath.lumaOf(previous) > NEIGHBOUR_LEVEL && ColorMath.lumaOf(next) > NEIGHBOUR_LEVEL) {
                zones[i][0] = (previous[0] + next[0]) * 0.5f
                zones[i][1] = (previous[1] + next[1]) * 0.5f
                zones[i][2] = (previous[2] + next[2]) * 0.5f
            }
        }
    }

    private data class ZoneRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val side: ScreenSide,
    ) {
        fun inset(): ZoneRect {
            if (right - left <= 3 || bottom - top <= 3) return this
            return ZoneRect(left + 1, top + 1, right - 1, bottom - 1, side)
        }
    }

    private data class SampleResult(val color: FloatArray, val samples: Int)

    companion object {
        const val BLACK_LEVEL = 0.02f
        const val NEIGHBOUR_LEVEL = 0.06f
    }
}
