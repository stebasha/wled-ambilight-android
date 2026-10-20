package dev.wledambilight.core.data

import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.DeviceKind
import dev.wledambilight.core.model.LightZone
import dev.wledambilight.core.model.LedDirection
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.PerformanceProfile
import dev.wledambilight.core.model.StartCorner
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.network.WledHttpClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {

    private val json = WledHttpClient.defaultJson
    private val codec = SettingsCodec(json)

    @Test
    fun `config round trips`() {
        val config = AmbilightConfig(
            settings = AmbilightSettings(
                targetFps = 45,
                analysisWidth = 480,
                brightness = 80,
                performanceProfile = PerformanceProfile.SMOOTH,
            ),
            layout = LedLayout(
                topCount = 30,
                rightCount = 18,
                bottomCount = 30,
                leftCount = 18,
                startCorner = StartCorner.BOTTOM_RIGHT,
                direction = LedDirection.COUNTER_CLOCKWISE,
                reverseLeft = true,
            ),
        )
        val decoded = codec.decodeConfig(codec.encodeConfig(config))
        assertFalse(decoded.corrupted)
        assertEquals(config, decoded.value)
    }

    @Test
    fun `devices round trip and are normalized`() {
        val devices = listOf(
            WledDevice(id = "a", name = "  Desk  ", host = " 192.168.1.50 ", ledCount = 300),
            WledDevice(id = "b", name = "TV", host = "wled.local", outputPort = 4048),
        )
        val decoded = codec.decodeDevices(codec.encodeDevices(devices))
        assertFalse(decoded.corrupted)
        assertEquals(2, decoded.value.size)
        assertEquals("Desk", decoded.value[0].name)
        assertEquals("192.168.1.50", decoded.value[0].host)
    }

    @Test
    fun `broken json falls back to defaults and reports corruption`() {
        val decoded = codec.decodeConfig("{not json")
        assertTrue(decoded.corrupted)
        assertEquals(AmbilightConfig(), decoded.value)

        val brokenDevices = codec.decodeDevices("{not json")
        assertTrue(brokenDevices.corrupted)
        assertTrue(brokenDevices.value.isEmpty())
    }

    @Test
    fun `legacy config without envelope is migrated`() {
        val legacy = AmbilightConfig(settings = AmbilightSettings(brightness = 55))
        val raw = json.encodeToString(AmbilightConfig.serializer(), legacy)
        val decoded = codec.decodeConfig(raw)
        assertFalse(decoded.corrupted)
        assertEquals(55, decoded.value.settings.brightness)
    }

    @Test
    fun `unknown enum values are treated as corruption`() {
        val raw = """{"version":1,"config":{"settings":{"performanceProfile":"TURBO"}}}"""
        val decoded = codec.decodeConfig(raw)
        assertTrue(decoded.corrupted)
        assertEquals(AmbilightSettings(), decoded.value.settings)
    }

    @Test
    fun `out of range values are clamped on read`() {
        val raw = """{"version":1,"config":{"settings":{"brightness":500,"targetFps":500,"gamma":9.0}}}"""
        val decoded = codec.decodeConfig(raw)
        assertFalse(decoded.corrupted)
        assertEquals(100, decoded.value.settings.brightness)
        assertEquals(60, decoded.value.settings.targetFps)
        assertEquals(3.0f, decoded.value.settings.gamma, 0.001f)
    }

    @Test
    fun `version 1 data without zones or device kind still decodes`() {
        val oldConfig = """{"version":1,"config":{"settings":{"brightness":70},"layout":{"topCount":10}}}"""
        val config = codec.decodeConfig(oldConfig)
        assertFalse(config.corrupted)
        assertTrue(config.value.zones.isEmpty())
        assertTrue(config.value.mainStripEnabled)

        val oldDevices = """{"version":1,"devices":[{"id":"a","name":"TV","host":"10.0.0.2"}]}"""
        val devices = codec.decodeDevices(oldDevices)
        assertFalse(devices.corrupted)
        assertEquals(DeviceKind.WLED, devices.value.single().kind)
    }

    @Test
    fun `zones round trip and are sanitized`() {
        val zones = (0 until 20).map { LightZone(id = "z$it", deviceId = "d", ledCount = 9999, brightness = 300) }
        val decoded = codec.decodeConfig(codec.encodeConfig(AmbilightConfig(zones = zones))).value
        assertEquals(LightZone.MAX_ZONES, decoded.zones.size)
        assertEquals(LedLayout.MAX_SIDE, decoded.zones.first().ledCount)
        assertEquals(100, decoded.zones.first().brightness)

        val govee = WledDevice(id = "g", kind = DeviceKind.GOVEE, host = "10.0.0.9", outputPort = 1)
        val restored = codec.decodeDevices(codec.encodeDevices(listOf(govee))).value.single()
        assertEquals(DeviceKind.GOVEE, restored.kind)
        assertEquals(WledDevice.GOVEE_COMMAND_PORT, restored.outputPort)
    }

    @Test
    fun `empty storage returns clean defaults`() {
        assertFalse(codec.decodeConfig(null).corrupted)
        assertEquals(AmbilightConfig(), codec.decodeConfig(null).value)
        assertTrue(codec.decodeDevices(null).value.isEmpty())
    }
}
