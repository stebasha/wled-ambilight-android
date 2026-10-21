package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.ScreenSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackThresholdTest {

    private val analyzer = FrameAnalyzer()
    private val zones = LedLayoutMapper.edgeZones(ScreenSide.TOP, 1, false)

    private fun uniform(value: Int) = VideoFrame(10, 10, IntArray(100) { (0xFF shl 24) or (value shl 16) or (value shl 8) or value }, 0)

    @Test
    fun `zones below the threshold are switched off`() {
        val dim = uniform(20) // luma ~7.8 %
        val settings = AmbilightSettings(edgeWeighting = 0f, blackBarCompensation = false)
        val lowThreshold = analyzer.analyzeZones(dim, zones, settings.copy(blackThreshold = 5))
        assertTrue(lowThreshold.colors[0] and 0xFFFFFF != 0)
        assertFalse(lowThreshold.blackFrame)

        val highThreshold = analyzer.analyzeZones(dim, zones, settings.copy(blackThreshold = 10))
        assertEquals(0, highThreshold.colors[0] and 0xFFFFFF)
        assertTrue(highThreshold.blackFrame)
        assertEquals(0, analyzer.regionColor(dim, ScreenRegion.FULL, settings.copy(blackThreshold = 10)) and 0xFFFFFF)
    }

    @Test
    fun `threshold is clamped and fallback colour stays opaque`() {
        val sanitized = AmbilightSettings(blackThreshold = 90, fallbackColor = 0x00123456).sanitized()
        assertEquals(25, sanitized.blackThreshold)
        assertEquals(0xFF123456.toInt(), sanitized.fallbackColor)
    }
}
