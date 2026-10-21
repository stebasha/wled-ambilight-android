package dev.wledambilight.core.model

import dev.wledambilight.core.imaging.AnalysisSizing
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureResolutionTest {

    @Test
    fun `auto picks 160 for small setups and 320 for long strips`() {
        val auto = AmbilightSettings()
        assertEquals(160, auto.analysisLongSide(76))
        assertEquals(160, auto.analysisLongSide(150))
        assertEquals(320, auto.analysisLongSide(151))
        // Auto never exceeds the performance profile cap.
        assertEquals(240, auto.copy(performanceProfile = PerformanceProfile.POWER_SAVER).analysisLongSide(400))
    }

    @Test
    fun `a fixed choice overrides the profile`() {
        val fixed = AmbilightSettings(captureResolution = CaptureResolution.R320, performanceProfile = PerformanceProfile.POWER_SAVER)
        assertEquals(320, fixed.analysisLongSide(10))
        assertEquals(160, fixed.copy(captureResolution = CaptureResolution.R160).analysisLongSide(900))
    }

    @Test
    fun `choices give the expected 16 by 9 buffers`() {
        assertEquals(AnalysisSizing.forDisplay(1920, 1080, 160).let { it.width to it.height }, 160 to 90)
        assertEquals(AnalysisSizing.forDisplay(3840, 2160, 320).let { it.width to it.height }, 320 to 180)
    }
}
