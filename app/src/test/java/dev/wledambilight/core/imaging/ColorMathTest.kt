package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.ColorBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    private val neutral = AmbilightSettings(
        brightness = 100,
        saturation = 1f,
        gamma = 2.2f,
        minBrightness = 0,
        colorBalance = ColorBalance(),
    )

    @Test
    fun `srgb and linear conversions round trip`() {
        for (value in listOf(0f, 0.02f, 0.2f, 0.5f, 0.9f, 1f)) {
            val roundTrip = ColorMath.linearToSrgb(ColorMath.srgbToLinear(value))
            assertEquals(value, roundTrip, 0.0005f)
        }
    }

    @Test
    fun `neutral settings keep colours unchanged`() {
        val colors = listOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0.2f, 0.4f, 0.8f),
            floatArrayOf(1f, 1f, 1f),
        )
        for (color in colors) {
            val copy = color.copyOf()
            ColorMath.adjust(copy, neutral)
            assertArrayEquals(color, copy, 0.004f)
        }
    }

    @Test
    fun `zero brightness produces black`() {
        val color = floatArrayOf(1f, 0.5f, 0.2f)
        ColorMath.adjust(color, neutral.copy(brightness = 0))
        assertEquals(0f, color[0], 0.0001f)
        assertEquals(0f, color[1], 0.0001f)
        assertEquals(0f, color[2], 0.0001f)
    }

    @Test
    fun `zero saturation produces a grey`() {
        val color = floatArrayOf(1f, 0f, 0f)
        ColorMath.adjust(color, neutral.copy(saturation = 0f))
        assertEquals(color[0], color[1], 0.002f)
        assertEquals(color[1], color[2], 0.002f)
    }

    @Test
    fun `lower gamma brightens and higher gamma darkens`() {
        val base = floatArrayOf(0.4f, 0.4f, 0.4f)
        val brighter = base.copyOf().also { ColorMath.adjust(it, neutral.copy(gamma = 1.6f)) }
        val darker = base.copyOf().also { ColorMath.adjust(it, neutral.copy(gamma = 2.8f)) }
        val unchanged = base.copyOf().also { ColorMath.adjust(it, neutral) }
        assertTrue(brighter[0] > unchanged[0])
        assertTrue(darker[0] < unchanged[0])
    }

    @Test
    fun `minimum brightness lifts dark colours without changing hue order`() {
        val color = floatArrayOf(0.02f, 0.01f, 0f)
        ColorMath.adjust(color, neutral.copy(minBrightness = 20))
        assertTrue(maxOf(color[0], color[1], color[2]) >= 0.19f)
        assertTrue(color[0] >= color[1])
        assertTrue(color[1] >= color[2])
    }

    @Test
    fun `colour balance scales channels independently`() {
        val color = floatArrayOf(0.5f, 0.5f, 0.5f)
        ColorMath.adjust(color, neutral.copy(colorBalance = ColorBalance(1.4f, 1f, 0.6f)))
        assertTrue(color[0] > color[1])
        assertTrue(color[1] > color[2])
    }

    @Test
    fun `toArgb clamps and packs channels`() {
        assertEquals(0xFFFF0000.toInt(), ColorMath.toArgb(1f, 0f, 0f))
        assertEquals(0xFFFF0000.toInt(), ColorMath.toArgb(2f, -1f, 0f))
        assertEquals(0xFF336699.toInt(), ColorMath.toArgb(0.2f, 0.4f, 0.6f))
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("channel $i", expected[i], actual[i], delta)
        }
    }
}
