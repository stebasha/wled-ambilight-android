package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.LedDirection
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.ScreenSide
import dev.wledambilight.core.model.StartCorner
import kotlin.math.roundToInt

/**
 * One LED zone on the screen perimeter.
 *
 * [startFraction] and [endFraction] are positions along the side in **geometric clockwise** order:
 * top left-to-right, right top-to-bottom, bottom right-to-left, left bottom-to-top.
 */
data class LedZone(
    val index: Int,
    val side: ScreenSide,
    val startFraction: Float,
    val endFraction: Float,
)

/**
 * Maps a physical LED index to a screen perimeter segment. This is the single source of truth used
 * both by the image analyser and by the calibration preview, so preview and real output cannot drift.
 */
object LedLayoutMapper {

    private val clockwiseSides = listOf(ScreenSide.TOP, ScreenSide.RIGHT, ScreenSide.BOTTOM, ScreenSide.LEFT)
    private val counterClockwiseSides = listOf(ScreenSide.TOP, ScreenSide.LEFT, ScreenSide.BOTTOM, ScreenSide.RIGHT)

    fun zones(layout: LedLayout): List<LedZone> {
        if (!layout.isValid()) return emptyList()
        val order = if (layout.direction == LedDirection.CLOCKWISE) clockwiseSides else counterClockwiseSides
        val startSide = startSide(layout.startCorner, layout.direction)
        val startIndex = order.indexOf(startSide)
        if (startIndex < 0) return emptyList()
        val traversal = order.drop(startIndex) + order.take(startIndex)

        var nextIndex = 0
        val result = ArrayList<LedZone>(layout.total)
        for (side in traversal) {
            val count = layout.count(side)
            if (count <= 0) continue
            val segments = ArrayList<Pair<Float, Float>>(count)
            for (i in 0 until count) {
                val a = i.toFloat() / count
                val b = (i + 1).toFloat() / count
                // For a counter-clockwise strip the traversal runs against the geometric direction.
                val geometric = if (layout.direction == LedDirection.CLOCKWISE) {
                    a to b
                } else {
                    (1f - b) to (1f - a)
                }
                segments += geometric
            }
            if (layout.reverse(side)) segments.reverse()
            for ((from, to) in segments) {
                result += LedZone(
                    index = nextIndex++,
                    side = side,
                    startFraction = from,
                    endFraction = to,
                )
            }
        }
        return result
    }

    fun startSide(corner: StartCorner, direction: LedDirection): ScreenSide = when (direction) {
        LedDirection.CLOCKWISE -> when (corner) {
            StartCorner.TOP_LEFT -> ScreenSide.TOP
            StartCorner.TOP_RIGHT -> ScreenSide.RIGHT
            StartCorner.BOTTOM_RIGHT -> ScreenSide.BOTTOM
            StartCorner.BOTTOM_LEFT -> ScreenSide.LEFT
        }
        LedDirection.COUNTER_CLOCKWISE -> when (corner) {
            StartCorner.TOP_LEFT -> ScreenSide.LEFT
            StartCorner.TOP_RIGHT -> ScreenSide.TOP
            StartCorner.BOTTOM_RIGHT -> ScreenSide.RIGHT
            StartCorner.BOTTOM_LEFT -> ScreenSide.BOTTOM
        }
    }
}

/**
 * Computes a capture/analysis resolution that follows the display aspect ratio.
 *
 * [AnalysisSizing.forDisplay] caps the **long side** so that both a 16:9 TV and a 20:9 phone use a
 * comparable pixel budget (a phone in portrait gets a narrow but tall buffer instead of a huge one).
 */
data class AnalysisSize(val width: Int, val height: Int)

object AnalysisSizing {
    fun forDisplay(displayWidth: Int, displayHeight: Int, targetLongSide: Int): AnalysisSize {
        val w = displayWidth.coerceAtLeast(1)
        val h = displayHeight.coerceAtLeast(1)
        val target = targetLongSide.coerceIn(64, 720)
        val longest = maxOf(w, h)
        val scale = target.toFloat() / longest
        val width = (w * scale).roundToInt().coerceIn(64, 960)
        val height = (h * scale).roundToInt().coerceIn(64, 960)
        return AnalysisSize(width, height)
    }
}

/**
 * Rate limiter for frame processing. Uses a monotonic nanosecond clock and tolerates timer jitter
 * by accepting a frame slightly earlier than the exact interval.
 */
class FrameCadenceLimiter(
    targetFps: Int,
    private val clock: () -> Long = { System.nanoTime() },
) {
    private val intervalNanos: Long = 1_000_000_000L / targetFps.coerceIn(1, 120)
    private var lastAcceptedNanos: Long = Long.MIN_VALUE

    var dropped: Long = 0L
        private set

    fun shouldAccept(nowNanos: Long = clock()): Boolean {
        if (lastAcceptedNanos == Long.MIN_VALUE) {
            lastAcceptedNanos = nowNanos
            return true
        }
        val elapsed = nowNanos - lastAcceptedNanos
        return if (elapsed >= intervalNanos - intervalNanos / 10) {
            lastAcceptedNanos = nowNanos
            true
        } else {
            dropped++
            false
        }
    }
}
