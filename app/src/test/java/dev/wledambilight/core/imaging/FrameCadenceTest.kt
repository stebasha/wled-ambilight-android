package dev.wledambilight.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCadenceTest {

    @Test
    fun `limits frames to the target rate`() {
        val limiter = FrameCadenceLimiter(targetFps = 30)
        assertTrue(limiter.shouldAccept(0L))
        assertFalse(limiter.shouldAccept(10_000_000L))
        assertTrue(limiter.shouldAccept(30_000_000L))
        assertFalse(limiter.shouldAccept(34_000_000L))
        assertTrue(limiter.shouldAccept(70_000_000L))
        assertEquals(2L, limiter.dropped)
    }

    @Test
    fun `first frame is always accepted`() {
        val limiter = FrameCadenceLimiter(targetFps = 5)
        assertTrue(limiter.shouldAccept(123456789L))
    }

    @Test
    fun `low target fps drops more frames`() {
        val limiter = FrameCadenceLimiter(targetFps = 5)
        var accepted = 0
        for (frame in 0 until 60) {
            if (limiter.shouldAccept(frame * 16_666_667L)) accepted++
        }
        assertTrue("accepted=$accepted", accepted in 5..6)
    }

    @Test
    fun `analysis size follows the display aspect`() {
        val landscape = AnalysisSizing.forDisplay(1920, 1080, 320)
        assertEquals(320, landscape.width)
        assertEquals(180, landscape.height)

        val portrait = AnalysisSizing.forDisplay(1080, 2400, 320)
        assertEquals(144, portrait.width)
        assertEquals(320, portrait.height)

        val square = AnalysisSizing.forDisplay(1000, 1000, 240)
        assertEquals(240, square.width)
        assertEquals(240, square.height)
    }

    @Test
    fun `analysis size is clamped for extreme ratios`() {
        val ultrawide = AnalysisSizing.forDisplay(5120, 1080, 480)
        assertTrue(ultrawide.width <= 960)
        assertTrue(ultrawide.height >= 64)

        val tiny = AnalysisSizing.forDisplay(10, 10, 1)
        assertTrue(tiny.width >= 64)
        assertTrue(tiny.height >= 64)
    }
}
