package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.SmoothingPreset
import dev.wledambilight.core.model.SmoothingSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/** Concrete filter parameters; every preset has measurably different behaviour. */
data class SmoothingParams(
    val alpha: Float,
    val threshold: Float,
    val maxStep: Float,
) {
    companion object {
        fun of(settings: SmoothingSettings): SmoothingParams = when (settings.preset) {
            SmoothingPreset.OFF -> SmoothingParams(alpha = 1f, threshold = 0f, maxStep = 1f)
            SmoothingPreset.FAST -> SmoothingParams(alpha = 0.65f, threshold = 0.006f, maxStep = 0.5f)
            SmoothingPreset.BALANCED -> SmoothingParams(alpha = 0.35f, threshold = 0.012f, maxStep = 0.25f)
            SmoothingPreset.SMOOTH -> SmoothingParams(alpha = 0.15f, threshold = 0.02f, maxStep = 0.12f)
            SmoothingPreset.CUSTOM -> SmoothingParams(
                alpha = settings.amount.coerceIn(0.05f, 0.9f),
                threshold = settings.threshold.coerceIn(0f, 0.2f),
                maxStep = settings.maxStep.coerceIn(0.02f, 1f),
            )
        }
    }
}

/**
 * Stateful per-LED EMA with a dead band and a per-frame slew limit. This avoids flicker on small
 * changes and abrupt jumps on cuts without introducing a long trail.
 */
class ColorSmoother {

    private var previous = FloatArray(0)

    fun reset() {
        previous = FloatArray(0)
    }

    fun smooth(colors: IntArray, params: SmoothingParams): IntArray {
        val needed = colors.size * 3
        if (previous.size != needed) {
            // First frame (or LED count changed): start exactly at the target to avoid a fade-in flash.
            previous = FloatArray(needed) { i ->
                val argb = colors[i / 3]
                when (i % 3) {
                    0 -> ColorMath.red(argb) / 255f
                    1 -> ColorMath.green(argb) / 255f
                    else -> ColorMath.blue(argb) / 255f
                }
            }
            return colors.copyOf()
        }

        val out = IntArray(colors.size)
        for (i in colors.indices) {
            val argb = colors[i]
            val targetR = ColorMath.red(argb) / 255f
            val targetG = ColorMath.green(argb) / 255f
            val targetB = ColorMath.blue(argb) / 255f
            val r = step(i * 3, targetR, params)
            val g = step(i * 3 + 1, targetG, params)
            val b = step(i * 3 + 2, targetB, params)
            out[i] = ColorMath.toArgb(r, g, b)
        }
        return out
    }

    private fun step(index: Int, target: Float, params: SmoothingParams): Float {
        val current = previous[index]
        val delta = target - current
        val next = if (abs(delta) <= params.threshold) {
            current
        } else {
            val step = (delta * params.alpha).coerceIn(-params.maxStep, params.maxStep)
            val candidate = current + step
            when {
                delta > 0f && candidate >= target -> target
                delta < 0f && candidate <= target -> target
                else -> candidate
            }
        }
        previous[index] = next
        return next
    }

    /** Exposed for tests: current smoothed state as 8-bit channels. */
    fun snapshotArgb(): IntArray {
        val count = previous.size / 3
        return IntArray(count) { i ->
            ColorMath.toArgb(previous[i * 3], previous[i * 3 + 1], previous[i * 3 + 2])
        }
    }

    fun snapshot(): FloatArray = previous.copyOf()

    internal fun quantize(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
