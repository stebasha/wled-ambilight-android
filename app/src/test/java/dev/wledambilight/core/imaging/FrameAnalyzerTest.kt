package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.LedLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnalyzerTest {

    private val analyzer = FrameAnalyzer()
    private val neutral = AmbilightSettings(
        brightness = 100,
        saturation = 1f,
        gamma = 2.2f,
        edgeThickness = 0.5f,
        edgeWeighting = 0f,
        blackBarCompensation = false,
        minBrightness = 0,
    )

    private fun frame(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): VideoFrame {
        val pixels = IntArray(width * height) { index -> pixel(index % width, index / width) }
        return VideoFrame(width, height, pixels, timestampNanos = 0L)
    }

    private fun color(alpha: Int = 0xFF, r: Int, g: Int, b: Int): Int =
        (alpha shl 24) or (r shl 16) or (g shl 8) or b

    private fun argb(colors: IntArray, index: Int): Triple<Int, Int, Int> {
        val value = colors[index]
        return Triple((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
    }

    @Test
    fun `top zones split the screen horizontally`() {
        val layout = LedLayout(topCount = 2, rightCount = 0, bottomCount = 0, leftCount = 0)
        val image = frame(40, 20) { x, _ -> if (x < 20) color(r = 255, g = 0, b = 0) else color(r = 0, g = 0, b = 255) }
        val result = analyzer.analyze(image, layout, neutral)
        assertEquals(2, result.colors.size)
        assertEquals(Triple(255, 0, 0), argb(result.colors, 0))
        assertEquals(Triple(0, 0, 255), argb(result.colors, 1))
        assertFalse(result.blackFrame)
    }

    @Test
    fun `bottom zones sample the bottom band`() {
        val layout = LedLayout(topCount = 0, rightCount = 0, bottomCount = 1, leftCount = 0)
        val image = frame(20, 20) { _, y -> if (y >= 10) color(r = 0, g = 255, b = 0) else color(r = 255, g = 0, b = 0) }
        val result = analyzer.analyze(image, layout, neutral)
        assertEquals(Triple(0, 255, 0), argb(result.colors, 0))
    }

    @Test
    fun `left and right zones sample vertical bands`() {
        val left = LedLayout(topCount = 0, rightCount = 0, bottomCount = 0, leftCount = 1)
        val right = LedLayout(topCount = 0, rightCount = 1, bottomCount = 0, leftCount = 0)
        val image = frame(40, 20) { x, _ ->
            when {
                x < 10 -> color(r = 255, g = 255, b = 0)
                x >= 30 -> color(r = 0, g = 255, b = 255)
                else -> color(r = 0, g = 0, b = 0)
            }
        }
        assertEquals(
            Triple(255, 255, 0),
            argb(analyzer.analyze(image, left, neutral.copy(edgeThickness = 0.25f)).colors, 0),
        )
        assertEquals(
            Triple(0, 255, 255),
            argb(analyzer.analyze(image, right, neutral.copy(edgeThickness = 0.25f)).colors, 0),
        )
    }

    @Test
    fun `black bar compensation replaces a dark zone between lit neighbours`() {
        val layout = LedLayout(topCount = 3, rightCount = 0, bottomCount = 0, leftCount = 0)
        val image = frame(90, 30) { x, _ ->
            if (x < 30 || x >= 60) color(r = 255, g = 0, b = 0) else color(r = 0, g = 0, b = 0)
        }
        val without = analyzer.analyze(image, layout, neutral)
        assertEquals(Triple(0, 0, 0), argb(without.colors, 1))

        val withCompensation = analyzer.analyze(
            image,
            layout,
            neutral.copy(blackBarCompensation = true),
        )
        val middle = argb(withCompensation.colors, 1)
        assertTrue("expected red lift, got $middle", middle.first > 200)
    }

    @Test
    fun `a fully black frame is detected`() {
        val layout = LedLayout(topCount = 4, rightCount = 2, bottomCount = 4, leftCount = 2)
        val black = frame(32, 18) { _, _ -> color(r = 0, g = 0, b = 0) }
        assertTrue(analyzer.analyze(black, layout, neutral).blackFrame)
        val bright = frame(32, 18) { _, _ -> color(r = 20, g = 20, b = 20) }
        assertFalse(analyzer.analyze(bright, layout, neutral).blackFrame)
    }

    @Test
    fun `edge weighting favours pixels close to the screen edge`() {
        val layout = LedLayout(topCount = 1, rightCount = 0, bottomCount = 0, leftCount = 0)
        val image = frame(40, 20) { _, y -> if (y < 5) color(r = 255, g = 0, b = 0) else color(r = 0, g = 0, b = 0) }
        val even = analyzer.analyze(image, layout, neutral.copy(edgeWeighting = 0f))
        val edge = analyzer.analyze(image, layout, neutral.copy(edgeWeighting = 1f))
        assertTrue(argb(edge.colors, 0).first > argb(even.colors, 0).first)
    }

    @Test
    fun `edge inset moves the sampling band away from the edge`() {
        val layout = LedLayout(topCount = 1, rightCount = 0, bottomCount = 0, leftCount = 0)
        val image = frame(20, 20) { _, y ->
            if (y < 5) color(r = 255, g = 0, b = 0) else color(r = 0, g = 255, b = 0)
        }
        val atEdge = analyzer.analyze(
            image,
            layout,
            neutral.copy(edgeThickness = 0.25f, edgeInset = 0f),
        )
        assertEquals(Triple(255, 0, 0), argb(atEdge.colors, 0))

        val inset = analyzer.analyze(
            image,
            layout,
            neutral.copy(edgeThickness = 0.25f, edgeInset = 0.25f),
        )
        assertEquals(Triple(0, 255, 0), argb(inset.colors, 0))
    }

    @Test
    fun `invalid layout or frame returns a safe empty result`() {
        val layout = LedLayout(topCount = 0, rightCount = 0, bottomCount = 0, leftCount = 0)
        val image = frame(10, 10) { _, _ -> color(r = 255, g = 255, b = 255) }
        val result = analyzer.analyze(image, layout, neutral)
        assertEquals(0, result.colors.size)
        assertTrue(result.blackFrame)

        val invalidFrame = VideoFrame(10, 10, IntArray(5), 0)
        assertTrue(analyzer.analyze(invalidFrame, LedLayout(), neutral).blackFrame)
    }

    @Test
    fun `samples per zone stay bounded`() {
        val layout = LedLayout(topCount = 40, rightCount = 0, bottomCount = 0, leftCount = 0)
        val image = frame(400, 200) { _, _ -> color(r = 100, g = 50, b = 25) }
        val result = analyzer.analyze(image, layout, neutral)
        assertEquals(40, result.colors.size)
        assertTrue(result.sampledPixels <= 40 * 64)
    }
}
