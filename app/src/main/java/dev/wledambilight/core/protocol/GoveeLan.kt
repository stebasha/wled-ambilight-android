package dev.wledambilight.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Govee LAN API ("LAN Control"), as published by Govee in the developer documentation
 * (https://developer.govee.com/docs/lan or the "Govee LAN API" guide).
 *
 * - Discovery: the client sends `scan` to multicast 239.255.255.250:4001, devices answer to the
 *   client's UDP port 4002.
 * - Control: JSON commands to the device on UDP 4003 (`turn`, `brightness`, `colorwc`); there is no
 *   reply, so delivery cannot be confirmed.
 *
 * LAN Control must be switched on per device in the Govee Home app and is only offered on supported
 * models. Per-segment streaming ("razer"/"ptReal") is not part of the published API and is not used:
 * every Govee device shows one colour.
 */
object GoveeLan {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val SCAN_PORT = 4001
    const val RESPONSE_PORT = 4002
    const val COMMAND_PORT = 4003

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun scanRequest(): String = message("scan") { put("account_topic", "reserve") }

    fun turn(on: Boolean): String = message("turn") { put("value", if (on) 1 else 0) }

    /** Brightness in percent, 1..100 as specified by the LAN API. */
    fun brightness(percent: Int): String = message("brightness") { put("value", percent.coerceIn(1, 100)) }

    /** Solid colour. `colorTemInKelvin = 0` tells the lamp to use the RGB value. */
    fun color(argb: Int): String = message("colorwc") {
        putJsonObject("color") {
            put("r", (argb shr 16) and 0xFF)
            put("g", (argb shr 8) and 0xFF)
            put("b", argb and 0xFF)
        }
        put("colorTemInKelvin", 0)
    }

    fun parseScanResponse(raw: String): GoveeScanResult? {
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val msg = root["msg"]?.jsonObject ?: return null
            if (msg["cmd"]?.jsonPrimitive?.contentOrNull != "scan") return null
            val data = msg["data"]?.jsonObject ?: return null
            val ip = data["ip"]?.jsonPrimitive?.contentOrNull?.trim()
            if (ip.isNullOrEmpty() || !dev.wledambilight.core.model.HostValidator.isValid(ip)) return null
            GoveeScanResult(
                ip = ip,
                deviceId = data["device"]?.jsonPrimitive?.contentOrNull?.take(64),
                sku = data["sku"]?.jsonPrimitive?.contentOrNull?.take(32),
            )
        } catch (error: Exception) {
            null
        }
    }

    private inline fun message(cmd: String, crossinline data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
        val payload: JsonObject = buildJsonObject {
            putJsonObject("msg") {
                put("cmd", cmd)
                putJsonObject("data") { data() }
            }
        }
        return payload.toString()
    }
}

data class GoveeScanResult(
    val ip: String,
    val deviceId: String?,
    val sku: String?,
)
