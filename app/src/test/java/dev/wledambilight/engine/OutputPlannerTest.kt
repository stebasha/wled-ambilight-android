package dev.wledambilight.engine

import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.DeviceKind
import dev.wledambilight.core.model.LedLayout
import dev.wledambilight.core.model.LightZone
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.ScreenSide
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.WledUdpMode
import dev.wledambilight.core.model.ZoneMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPlannerTest {

    private val tv = WledDevice(id = "tv", name = "TV", host = "10.0.0.2")
    private val desk = WledDevice(id = "desk", name = "Desk", host = "10.0.0.3")
    private val lamp = WledDevice(id = "lamp", kind = DeviceKind.GOVEE, name = "Lamp", host = "10.0.0.4")
    private val layout = LedLayout(topCount = 20, rightCount = 10, bottomCount = 20, leftCount = 10) // 60

    private fun config(vararg zones: LightZone, main: Boolean = true) =
        AmbilightConfig(layout = layout, mainStripEnabled = main, zones = zones.toList())

    @Test
    fun `main strip only`() {
        val plan = OutputPlanner.plan(config(), tv, listOf(tv))
        assertEquals(1, plan.wled.size)
        assertEquals(60, plan.wled[0].spans.single().ledCount)
        assertEquals(0, plan.wled[0].spans.single().ledOffset)
        assertTrue(plan.problems.isEmpty())
    }

    @Test
    fun `extra zone on the same controller follows the main strip`() {
        val under = LightZone(id = "under", deviceId = "tv", mapping = ZoneMapping.EDGE, edge = ScreenSide.BOTTOM, ledOffset = 60, ledCount = 40)
        val plan = OutputPlanner.plan(config(under), tv, listOf(tv))
        val spans = plan.wled.single().spans
        assertEquals(listOf("main", "under"), spans.map { it.key })
        assertEquals(100, spans.last().end)
        assertEquals(100, plan.totalLeds)
    }

    @Test
    fun `overlapping ranges are rejected but the rest runs`() {
        val bad = LightZone(id = "bad", name = "Bad", deviceId = "tv", ledOffset = 50, ledCount = 20)
        val plan = OutputPlanner.plan(config(bad), tv, listOf(tv))
        assertEquals(1, plan.wled.single().spans.size)
        assertEquals(PlanProblemKind.ZONE_OVERLAP, plan.problems.single().kind)
    }

    @Test
    fun `zones on other controllers and govee lamps are separate targets`() {
        val deskZone = LightZone(id = "d", deviceId = "desk", ledCount = 30)
        val lampZone = LightZone(id = "l", deviceId = "lamp", mapping = ZoneMapping.SOLID, region = ScreenRegion.LEFT_HALF, brightness = 50)
        val plan = OutputPlanner.plan(config(deskZone, lampZone), tv, listOf(tv, desk, lamp))
        assertEquals(listOf("tv", "desk"), plan.wled.map { it.device.id })
        assertEquals(ScreenRegion.LEFT_HALF, plan.govee.single().region)
        assertEquals(0.5f, plan.govee.single().brightnessScale, 0.001f)
        assertEquals(3, plan.targetCount)
    }

    @Test
    fun `govee edge zone averages that edge`() {
        val lampZone = LightZone(id = "l", deviceId = "lamp", mapping = ZoneMapping.EDGE, edge = ScreenSide.RIGHT)
        val plan = OutputPlanner.plan(config(lampZone, main = false), null, listOf(lamp))
        assertEquals(ScreenRegion.RIGHT_EDGE, plan.govee.single().region)
        assertTrue(plan.wled.isEmpty())
    }

    @Test
    fun `govee segments spread along the edge and count as outputs`() {
        val lampZone = LightZone(
            id = "l", deviceId = "lamp", mapping = ZoneMapping.EDGE, edge = ScreenSide.RIGHT,
            goveeSegments = 4, goveeStretch = false,
        )
        val plan = OutputPlanner.plan(config(lampZone, main = false), null, listOf(lamp))
        val govee = plan.govee.single()
        assertEquals(4, govee.segments.size)
        assertTrue(govee.segments.all { it.side == ScreenSide.RIGHT })
        assertEquals(false, govee.stretch)
        assertEquals(4, plan.totalLeds)
        assertEquals(1, plan.targetCount)
    }

    @Test
    fun `govee segments apply only to edge zones`() {
        val lampZone = LightZone(id = "l", deviceId = "lamp", mapping = ZoneMapping.SOLID, goveeSegments = 4)
        val plan = OutputPlanner.plan(config(lampZone, main = false), null, listOf(lamp))
        assertTrue(plan.govee.single().segments.isEmpty())
        assertEquals(1, plan.totalLeds)
    }

    @Test
    fun `known govee models get their own segment count`() {
        assertEquals(8, LightZone.defaultGoveeSegments("h60b0"))
        assertEquals(LightZone.DEFAULT_GOVEE_SEGMENTS, LightZone.defaultGoveeSegments("H6199"))
        assertEquals(LightZone.DEFAULT_GOVEE_SEGMENTS, LightZone.defaultGoveeSegments(null))
    }

    @Test
    fun `govee segment count is sanitized`() {
        assertEquals(0, LightZone(goveeSegments = -3).sanitized().goveeSegments)
        assertEquals(LightZone.MIN_GOVEE_SEGMENTS, LightZone(goveeSegments = 1).sanitized().goveeSegments)
        assertEquals(LightZone.MAX_GOVEE_SEGMENTS, LightZone(goveeSegments = 999).sanitized().goveeSegments)
    }

    @Test
    fun `missing device and disabled zones`() {
        val orphan = LightZone(id = "o", deviceId = "gone")
        val off = LightZone(id = "x", deviceId = "desk", enabled = false)
        val plan = OutputPlanner.plan(config(orphan, off), tv, listOf(tv, desk))
        assertEquals(PlanProblemKind.ZONE_DEVICE_MISSING, plan.problems.single().kind)
        assertEquals(1, plan.wled.size)
    }

    @Test
    fun `drgb allows only one range from led zero`() {
        val drgb = tv.copy(protocol = OutputProtocol.WLED_UDP, wledUdpMode = WledUdpMode.DRGB)
        val extra = LightZone(id = "e", deviceId = "tv", ledOffset = 60, ledCount = 10)
        val plan = OutputPlanner.plan(config(extra), drgb, listOf(drgb))
        assertEquals(listOf("main"), plan.wled.single().spans.map { it.key })
        assertEquals(PlanProblemKind.DRGB_NEEDS_SINGLE_RANGE, plan.problems.single().kind)
    }

    @Test
    fun `warls rejects ranges beyond led 255`() {
        val warls = desk.copy(protocol = OutputProtocol.WLED_UDP, wledUdpMode = WledUdpMode.WARLS)
        val far = LightZone(id = "f", deviceId = "desk", ledOffset = 250, ledCount = 10)
        val plan = OutputPlanner.plan(config(far, main = false), null, listOf(warls))
        assertTrue(plan.isEmpty)
        assertEquals(PlanProblemKind.WARLS_TOO_MANY_LEDS, plan.problems.single().kind)
    }

    @Test
    fun `main strip can be disabled and invalid layout is reported`() {
        assertTrue(OutputPlanner.plan(config(main = false), tv, listOf(tv)).isEmpty)
        val broken = AmbilightConfig(layout = LedLayout(0, 0, 0, 0))
        val plan = OutputPlanner.plan(broken, tv, listOf(tv))
        assertTrue(plan.isEmpty)
        assertEquals(PlanProblemKind.MAIN_LAYOUT_INVALID, plan.problems.single().kind)
    }

    @Test
    fun `perimeter and solid zones produce the right led counts`() {
        val arch = LightZone(id = "a", deviceId = "desk", mapping = ZoneMapping.PERIMETER, layout = LedLayout(5, 3, 5, 3))
        val glow = LightZone(id = "g", deviceId = "desk", mapping = ZoneMapping.SOLID, ledOffset = 16, ledCount = 8)
        val plan = OutputPlanner.plan(config(arch, glow, main = false), null, listOf(desk))
        val spans = plan.wled.single().spans
        assertEquals(16, spans[0].ledCount)
        assertEquals(8, spans[1].ledCount)
        assertTrue(spans[1].source is SpanSource.Solid)
    }
}
