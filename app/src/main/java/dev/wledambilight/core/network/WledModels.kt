package dev.wledambilight.core.network

import kotlinx.serialization.Serializable

/**
 * Subset of WLED's `/json/info` response. Every field is optional and unknown fields are ignored so
 * that a different firmware version cannot crash the client.
 */
@Serializable
data class WledInfo(
    val name: String? = null,
    val ver: String? = null,
    val arch: String? = null,
    val leds: LedsInfo? = null,
    val wifi: WifiInfo? = null,
    val live: Boolean? = null,
    val lm: String? = null,
    val udpport: Int? = null,
    val uptime: Long? = null,
) {
    @Serializable
    data class LedsInfo(
        val count: Int? = null,
        val fps: Int? = null,
        val pwr: Int? = null,
        val maxpwr: Int? = null,
    )

    @Serializable
    data class WifiInfo(
        val signal: Int? = null,
        val rssi: Int? = null,
        val channel: Int? = null,
    )
}

/** Subset of WLED's `/json/state` response. */
@Serializable
data class WledState(
    val on: Boolean? = null,
    val bri: Int? = null,
    val live: Boolean? = null,
    val ps: Int? = null,
    val seg: List<WledSegment>? = null,
)

@Serializable
data class WledSegment(
    val id: Int? = null,
    val on: Boolean? = null,
    val start: Int? = null,
    val stop: Int? = null,
    val col: List<List<Int>>? = null,
    val fx: Int? = null,
)

data class WledPreset(val id: Int, val name: String)

data class WledProbe(
    val info: WledInfo,
    val latencyMs: Long,
)

/** WLED reports the realtime flag at the top level of `/json/info` and `/json/state`. */
val WledInfo.isRealtimeActive: Boolean?
    get() = live
