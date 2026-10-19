package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.SmoothingPreset
import dev.wledambilight.core.model.SmoothingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothingTest {

    private val red = 0xFFFF0000.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `off applies the target immediately`() {
        val smoother = ColorSmoother()
        val params = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.OFF))
        assertEquals(black, smoother.smooth(intArrayOf(black), params)[0])
        assertEquals(red, smoother.smooth(intArrayOf(red), params)[0])
        assertEquals(black, smoother.smooth(intArrayOf(black), params)[0])
    }

    @Test
    fun `balanced moves by the per frame limit`() {
        val smoother = ColorSmoother()
        val params = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.BALANCED))
        smoother.smooth(intArrayOf(black), params)
        val next = smoother.smooth(intArrayOf(red), params)[0]
        val redChannel = (next shr 16) and 0xFF
        assertEquals(64, redChannel)
    }

    @Test
    fun `smooth takes more frames than fast`() {
        val fast = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.FAST))
        val smooth = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.SMOOTH))
        val fastFrames = framesToReach(230, fast)
        val smoothFrames = framesToReach(230, smooth)
        assertTrue("fast=$fastFrames smooth=$smoothFrames", smoothFrames > fastFrames)
        assertTrue(smoothFrames < 60)
    }

    @Test
    fun `presets have visibly different parameters`() {
        val off = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.OFF))
        val fast = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.FAST))
        val balanced = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.BALANCED))
        val smooth = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.SMOOTH))
        assertTrue(off.alpha > fast.alpha)
        assertTrue(fast.alpha > balanced.alpha)
        assertTrue(balanced.alpha > smooth.alpha)
        assertTrue(off.threshold < smooth.threshold)
    }

    @Test
    fun `dead band ignores tiny changes`() {
        val smoother = ColorSmoother()
        val params = SmoothingParams(alpha = 1f, threshold = 0.1f, maxStep = 1f)
        smoother.smooth(intArrayOf(black), params)
        val tiny = 0xFF0D0D0D.toInt() // 5% grey
        assertEquals(black, smoother.smooth(intArrayOf(tiny), params)[0])
        val large = 0xFF808080.toInt()
        assertEquals(large, smoother.smooth(intArrayOf(large), params)[0])
    }

    @Test
    fun `first frame starts at the target without a fade`() {
        val smoother = ColorSmoother()
        val params = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.SMOOTH))
        val input = intArrayOf(red, 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val output = smoother.smooth(input, params)
        assertEquals(input.toList(), output.toList())
    }

    @Test
    fun `changing led count resets the filter`() {
        val smoother = ColorSmoother()
        val params = SmoothingParams.of(SmoothingSettings(preset = SmoothingPreset.BALANCED))
        smoother.smooth(intArrayOf(red, red, red), params)
        val resized = smoother.smooth(intArrayOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt()), params)
        assertEquals(0xFF0000FF.toInt(), resized[0])
        assertEquals(0xFF00FF00.toInt(), resized[1])
    }

    private fun framesToReach(channel: Int, params: SmoothingParams): Int {
        val smoother = ColorSmoother()
        smoother.smooth(intArrayOf(black), params)
        repeat(120) { frame ->
            val value = smoother.smooth(intArrayOf(0xFFFFFFFF.toInt()), params)[0]
            if (((value shr 16) and 0xFF) >= channel) return frame + 1
        }
        return Int.MAX_VALUE
    }
}
