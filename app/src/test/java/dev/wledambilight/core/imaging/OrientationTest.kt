package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.ScreenSide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for strip orientation: a clockwise strip must see the bottom edge right->left
 * and the left edge bottom->top, exactly as the calibration preview draws it.
 */
class OrientationTest {

    private val analyzer = FrameAnalyzer()
    private val neutral = AmbilightSettings(
        edgeThickness = 0.3f,
        edgeWeighting = 0f,
        blackBarCompensation = false,
    )
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun frame(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) =
        VideoFrame(width, height, IntArray(width * height) { pixel(it % width, it / width) }, 0L)

    private fun redOf(color: Int) = (color shr 16) and 0xFF
    private fun blueOf(color: Int) = color and 0xFF

    @Test
    fun `clockwise bottom runs from right to left`() {
        val layout = LedLayout(topCount = 0, rightCount = 0, bottomCount = 2, leftCount = 0)
        val image = frame(40, 20) { x, _ -> if (x < 20) red else blue }
        val colors = analyzer.analyze(image, layout, neutral).colors
        // LED 0 is the first after the bottom-right corner -> blue (right half).
        assertEquals(255, blueOf(colors[0]))
        assertEquals(255, redOf(colors[1]))
    }

    @Test
    fun `clockwise left runs from bottom to top`() {
        val layout = LedLayout(topCount = 0, rightCount = 0, bottomCount = 0, leftCount = 2)
        val image = frame(20, 40) { _, y -> if (y < 20) red else blue }
        val colors = analyzer.analyze(image, layout, neutral).colors
        assertEquals(255, blueOf(colors[0])) // bottom first
        assertEquals(255, redOf(colors[1]))
    }

    @Test
    fun `clockwise right runs from top to bottom`() {
        val layout = LedLayout(topCount = 0, rightCount = 2, bottomCount = 0, leftCount = 0)
        val image = frame(20, 40) { _, y -> if (y < 20) red else blue }
        val colors = analyzer.analyze(image, layout, neutral).colors
        assertEquals(255, redOf(colors[0]))
        assertEquals(255, blueOf(colors[1]))
    }

    @Test
    fun `edge strips run left to right and bottom to top`() {
        val horizontal = frame(40, 20) { x, _ -> if (x < 20) red else blue }
        val bottom = analyzer.analyzeZones(horizontal, LedLayoutMapper.edgeZones(ScreenSide.BOTTOM, 2, false), neutral).colors
        assertEquals(255, redOf(bottom[0]))
        assertEquals(255, blueOf(bottom[1]))
        val top = analyzer.analyzeZones(horizontal, LedLayoutMapper.edgeZones(ScreenSide.TOP, 2, false), neutral).colors
        assertEquals(255, redOf(top[0]))

        val vertical = frame(20, 40) { _, y -> if (y < 20) red else blue }
        for (side in listOf(ScreenSide.LEFT, ScreenSide.RIGHT)) {
            val colors = analyzer.analyzeZones(vertical, LedLayoutMapper.edgeZones(side, 2, false), neutral).colors
            assertEquals("$side starts at the bottom", 255, blueOf(colors[0]))
            assertEquals(255, redOf(colors[1]))
            val reversed = analyzer.analyzeZones(vertical, LedLayoutMapper.edgeZones(side, 2, true), neutral).colors
            assertEquals("$side reversed starts at the top", 255, redOf(reversed[0]))
        }
    }

    @Test
    fun `edge zones are contiguous and indexed from zero`() {
        val zones = LedLayoutMapper.edgeZones(ScreenSide.BOTTOM, 5, reverse = false)
        assertEquals((0 until 5).toList(), zones.map { it.index })
        assertEquals(5, zones.size)
        assertEquals(emptyList<LedZone>(), LedLayoutMapper.edgeZones(ScreenSide.TOP, 0, false))
    }

    @Test
    fun `region colour averages the requested area`() {
        val image = frame(40, 20) { x, _ -> if (x < 20) red else blue }
        assertEquals(255, redOf(analyzer.regionColor(image, ScreenRegion.LEFT_HALF, neutral)))
        assertEquals(255, blueOf(analyzer.regionColor(image, ScreenRegion.RIGHT_EDGE, neutral)))
        val full = analyzer.regionColor(image, ScreenRegion.FULL, neutral)
        // Half red, half blue (averaged in sRGB like every zone): both channels around mid level.
        assert(redOf(full) in 100..160 && blueOf(full) in 100..160) { "got ${Integer.toHexString(full)}" }
    }

    @Test
    fun `zone brightness scale dims only that zone`() {
        val image = frame(20, 20) { _, _ -> 0xFFFFFFFF.toInt() }
        val zones = LedLayoutMapper.edgeZones(ScreenSide.TOP, 1, false)
        val full = analyzer.analyzeZones(image, zones, neutral).colors[0]
        val half = analyzer.analyzeZones(image, zones, neutral, brightnessScale = 0.5f).colors[0]
        assert(redOf(half) < redOf(full))
    }
}
