package dev.wledambilight.engine

import dev.wledambilight.core.imaging.LedLayoutMapper
import dev.wledambilight.core.imaging.LedZone
import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.DeviceKind
import dev.wledambilight.core.model.LightZone
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.WledUdpMode
import dev.wledambilight.core.model.ZoneMapping
import dev.wledambilight.core.protocol.WledUdp

/** Where the colours of one LED range come from. */
sealed interface SpanSource {
    /** Per-LED perimeter/edge sampling. */
    data class Zones(val zones: List<LedZone>) : SpanSource

    /** [count] LEDs, all showing the average of [region]. */
    data class Solid(val region: ScreenRegion, val count: Int) : SpanSource
}

/** A contiguous LED range on one WLED controller. [key] is "main" or the zone id. */
data class SpanPlan(
    val key: String,
    val ledOffset: Int,
    val ledCount: Int,
    val source: SpanSource,
    val brightnessScale: Float,
) {
    val end: Int get() = ledOffset + ledCount
}

data class WledPlan(val device: WledDevice, val spans: List<SpanPlan>)

data class GoveePlan(
    val key: String,
    val device: WledDevice,
    val region: ScreenRegion,
    val brightnessScale: Float,
)

/** One Home Assistant light entity fed with the average colour of [region]. */
data class HaPlan(
    val key: String,
    val device: WledDevice,
    val entityId: String,
    val region: ScreenRegion,
    val brightnessScale: Float,
)

enum class PlanProblemKind {
    ZONE_NO_ENTITY,
    MAIN_LAYOUT_INVALID,
    ZONE_DEVICE_MISSING,
    ZONE_EMPTY,
    ZONE_OVERLAP,
    DRGB_NEEDS_SINGLE_RANGE,
    DRGB_TOO_MANY_LEDS,
    WARLS_TOO_MANY_LEDS,
    MAIN_DEVICE_NOT_WLED,
}

data class PlanProblem(val kind: PlanProblemKind, val key: String, val detail: String)

data class SessionPlan(
    val wled: List<WledPlan>,
    val govee: List<GoveePlan>,
    val problems: List<PlanProblem>,
    val ha: List<HaPlan> = emptyList(),
) {
    val isEmpty: Boolean get() = wled.isEmpty() && govee.isEmpty() && ha.isEmpty()
    val totalLeds: Int get() = wled.sumOf { plan -> plan.spans.sumOf { it.ledCount } } + govee.size + ha.size
    val targetCount: Int get() = wled.size + govee.size + ha.size
}

/**
 * Builds the output plan for a session. Pure and deterministic, so multi-zone rules (overlaps,
 * protocol limits) are unit-tested without sockets.
 *
 * Invalid zones are dropped and reported; they never prevent valid outputs from running.
 */
object OutputPlanner {
    const val MAIN_KEY = "main"

    fun plan(config: AmbilightConfig, mainDevice: WledDevice?, devices: List<WledDevice>): SessionPlan {
        val problems = ArrayList<PlanProblem>()
        val spansByDevice = LinkedHashMap<String, MutableList<SpanPlan>>()
        val deviceById = devices.associateBy { it.id }.toMutableMap()
        val govee = ArrayList<GoveePlan>()
        val ha = ArrayList<HaPlan>()

        if (config.mainStripEnabled && mainDevice != null) {
            deviceById[mainDevice.id] = mainDevice
            when {
                !mainDevice.isWled -> problems += PlanProblem(PlanProblemKind.MAIN_DEVICE_NOT_WLED, MAIN_KEY, mainDevice.displayName)
                !config.layout.isValid() -> problems += PlanProblem(PlanProblemKind.MAIN_LAYOUT_INVALID, MAIN_KEY, "layout")
                else -> {
                    val zones = LedLayoutMapper.zones(config.layout)
                    spansByDevice.getOrPut(mainDevice.id) { ArrayList() } +=
                        SpanPlan(MAIN_KEY, 0, zones.size, SpanSource.Zones(zones), 1f)
                }
            }
        }

        for (zone in config.zones) {
            if (!zone.enabled) continue
            val device = deviceById[zone.deviceId]
            if (device == null) {
                problems += PlanProblem(PlanProblemKind.ZONE_DEVICE_MISSING, zone.id, zone.name)
                continue
            }
            val scale = zone.brightness.coerceIn(0, 100) / 100f
            if (device.kind == DeviceKind.GOVEE) {
                govee += GoveePlan(zone.id, device, goveeRegion(zone), scale)
                continue
            }
            if (device.kind == DeviceKind.HOME_ASSISTANT) {
                val entity = zone.entityId
                if (entity.isNullOrBlank() || !entity.startsWith("light.")) {
                    problems += PlanProblem(PlanProblemKind.ZONE_NO_ENTITY, zone.id, zone.name)
                } else {
                    ha += HaPlan(zone.id, device, entity, goveeRegion(zone), scale)
                }
                continue
            }
            val span = spanFor(zone, scale)
            if (span == null || span.ledCount <= 0) {
                problems += PlanProblem(PlanProblemKind.ZONE_EMPTY, zone.id, zone.name)
                continue
            }
            val existing = spansByDevice.getOrPut(device.id) { ArrayList() }
            val clash = existing.firstOrNull { it.ledOffset < span.end && span.ledOffset < it.end }
            if (clash != null) {
                problems += PlanProblem(
                    PlanProblemKind.ZONE_OVERLAP,
                    zone.id,
                    "${zone.name}: LEDs ${span.ledOffset}..${span.end - 1} overlap ${clash.key}",
                )
                continue
            }
            existing += span
        }

        val wled = ArrayList<WledPlan>()
        for ((deviceId, spans) in spansByDevice) {
            if (spans.isEmpty()) continue
            val device = deviceById.getValue(deviceId)
            val accepted = checkProtocolLimits(device, spans.sortedBy { it.ledOffset }, problems)
            if (accepted.isNotEmpty()) wled += WledPlan(device, accepted)
        }
        return SessionPlan(wled, govee, problems, ha)
    }

    private fun spanFor(zone: LightZone, scale: Float): SpanPlan? = when (zone.mapping) {
        ZoneMapping.EDGE -> {
            val zones = LedLayoutMapper.edgeZones(zone.edge, zone.ledCount, zone.reverse)
            SpanPlan(zone.id, zone.ledOffset, zones.size, SpanSource.Zones(zones), scale)
        }
        ZoneMapping.PERIMETER -> if (!zone.layout.isValid()) null else {
            val zones = LedLayoutMapper.zones(zone.layout)
            SpanPlan(zone.id, zone.ledOffset, zones.size, SpanSource.Zones(zones), scale)
        }
        ZoneMapping.SOLID -> SpanPlan(
            zone.id,
            zone.ledOffset,
            zone.ledCount,
            SpanSource.Solid(zone.region, zone.ledCount),
            scale,
        )
    }

    /** Single-colour lamps (Govee, Home Assistant): an edge zone averages that edge. */
    private fun goveeRegion(zone: LightZone): ScreenRegion = when (zone.mapping) {
        ZoneMapping.SOLID -> zone.region
        ZoneMapping.PERIMETER -> ScreenRegion.FULL
        ZoneMapping.EDGE -> when (zone.edge) {
            dev.wledambilight.core.model.ScreenSide.TOP -> ScreenRegion.TOP_EDGE
            dev.wledambilight.core.model.ScreenSide.RIGHT -> ScreenRegion.RIGHT_EDGE
            dev.wledambilight.core.model.ScreenSide.BOTTOM -> ScreenRegion.BOTTOM_EDGE
            dev.wledambilight.core.model.ScreenSide.LEFT -> ScreenRegion.LEFT_EDGE
        }
    }

    private fun checkProtocolLimits(
        device: WledDevice,
        spans: List<SpanPlan>,
        problems: MutableList<PlanProblem>,
    ): List<SpanPlan> {
        if (device.protocol != OutputProtocol.WLED_UDP) return spans
        return when (device.wledUdpMode) {
            WledUdpMode.DNRGB -> spans
            WledUdpMode.WARLS -> spans.filter { span ->
                val ok = span.end <= WledUdp.WARLS_MAX_LEDS
                if (!ok) problems += PlanProblem(PlanProblemKind.WARLS_TOO_MANY_LEDS, span.key, "end=${span.end}")
                ok
            }
            WledUdpMode.DRGB -> {
                // DRGB has no addressing: exactly one range, starting at LED 0.
                val first = spans.first()
                spans.drop(1).forEach {
                    problems += PlanProblem(PlanProblemKind.DRGB_NEEDS_SINGLE_RANGE, it.key, device.displayName)
                }
                when {
                    first.ledOffset != 0 -> {
                        problems += PlanProblem(PlanProblemKind.DRGB_NEEDS_SINGLE_RANGE, first.key, device.displayName)
                        emptyList()
                    }
                    first.ledCount > WledUdp.MAX_LEDS_PER_PACKET -> {
                        problems += PlanProblem(PlanProblemKind.DRGB_TOO_MANY_LEDS, first.key, "${first.ledCount}")
                        emptyList()
                    }
                    else -> listOf(first)
                }
            }
        }
    }
}
