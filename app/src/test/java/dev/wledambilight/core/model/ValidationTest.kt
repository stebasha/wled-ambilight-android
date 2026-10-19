package dev.wledambilight.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    @Test
    fun `valid hosts are accepted`() {
        for (host in listOf("192.168.1.10", "10.0.0.1", "wled.local", "my-wled", "tv-room.wled.local")) {
            assertNull("expected $host to be valid", HostValidator.error(host))
        }
    }

    @Test
    fun `invalid hosts are rejected`() {
        assertEquals(HostError.EMPTY, HostValidator.error("  "))
        assertEquals(HostError.CONTAINS_SCHEME, HostValidator.error("http://192.168.1.2"))
        assertEquals(HostError.CONTAINS_PATH, HostValidator.error("192.168.1.2/json"))
        assertEquals(HostError.CONTAINS_SPACES, HostValidator.error("192.168.1.2 80"))
        assertEquals(HostError.INVALID_HOSTNAME, HostValidator.error("host:80"))
        assertEquals(HostError.INVALID_IPV4, HostValidator.error("999.1.1.1"))
        assertEquals(HostError.INVALID_HOSTNAME, HostValidator.error("-wled"))
        assertEquals(HostError.INVALID_HOSTNAME, HostValidator.error("wled-"))
    }

    @Test
    fun `port validation`() {
        assertNull(HostValidator.portError(80))
        assertNull(HostValidator.portError(65535))
        assertEquals(PortError.OUT_OF_RANGE, HostValidator.portError(0))
        assertEquals(PortError.OUT_OF_RANGE, HostValidator.portError(65536))
    }

    @Test
    fun `layout validation catches impossible geometries`() {
        assertTrue(LedLayout().isValid())
        assertTrue(LedLayout(topCount = 0, rightCount = 0, bottomCount = 0, leftCount = 0).issues().contains(LayoutIssue.EMPTY))
        assertTrue(LedLayout(topCount = -2).issues().contains(LayoutIssue.NEGATIVE_COUNT))
        assertTrue(
            LedLayout(topCount = LedLayout.MAX_SIDE + 1).issues()
                .contains(LayoutIssue.SIDE_TOO_LARGE),
        )
        assertTrue(
            LedLayout(topCount = 400, rightCount = 400, bottomCount = 400, leftCount = 400)
                .issues().contains(LayoutIssue.TOTAL_TOO_LARGE),
        )
    }

    @Test
    fun `settings are clamped and profile limits applied`() {
        val settings = AmbilightSettings(
            targetFps = 120,
            analysisWidth = 2000,
            brightness = -5,
            saturation = 9f,
            gamma = 0.5f,
            edgeThickness = 0.9f,
            edgeInset = 0.9f,
            minBrightness = 500,
            performanceProfile = PerformanceProfile.POWER_SAVER,
        ).sanitized()

        assertEquals(60, settings.targetFps)
        assertEquals(720, settings.analysisWidth)
        assertEquals(0, settings.brightness)
        assertEquals(2f, settings.saturation, 0.001f)
        assertEquals(1.4f, settings.gamma, 0.001f)
        assertEquals(0.35f, settings.edgeThickness, 0.001f)
        assertEquals(0.15f, settings.edgeInset, 0.001f)
        assertEquals(80, settings.minBrightness)
        assertEquals(15, settings.effectiveFps)
        assertEquals(240, settings.effectiveAnalysisWidth)
    }

    @Test
    fun `device normalization trims and clamps`() {
        val device = WledDevice(name = "  Desk ", host = " 10.0.0.2 ", httpPort = 70000, ledCount = 99_999).normalized()
        assertEquals("Desk", device.name)
        assertEquals("10.0.0.2", device.host)
        assertEquals(65535, device.httpPort)
        assertEquals(1500, device.ledCount)
    }

    @Test
    fun `settings with defaults are valid`() {
        assertFalse(LedLayout().issues().isNotEmpty())
    }
}
