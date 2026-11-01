package dev.wledambilight.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

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
 * models.
 *
 * Per-segment streaming ("razer", the DreamView transport of Govee's desktop app) is NOT part of the
 * published API. It is reverse-engineered (OpenRGB, GoveeDreamView, LedFx): a `razer` command
 * carrying a Base64 binary frame `BB <len_hi> <len_lo> <cmd> <payload> <xor>`. `B1 01`/`B1 00`
 * switch the mode, `B0 <flag> <n> <rgb * n>` carries colours; see docs in PROTOCOLS.md for the flag.
 * There is no acknowledgement, so support on a given model can only be judged by looking at the lamp.
 */
object GoveeLan {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val SCAN_PORT = 4001
    const val RESPONSE_PORT = 4002
    const val COMMAND_PORT = 4003
    const val MAX_RAZER_SEGMENTS = 100

    private val SEGMENT_TEST_COLORS = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())

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

    /** Switches razer streaming on or off. Devices leave the mode by themselves after about a minute. */
    fun razerMode(enabled: Boolean): String = razer(razerModeFrame(enabled))

    /** One colour per segment; see [razerColorFrame]. */
    fun razerColors(colors: IntArray, stretch: Boolean): String = razer(razerColorFrame(colors, stretch))

    fun razerModeFrame(enabled: Boolean): ByteArray =
        razerFrame(0xB1, byteArrayOf(if (enabled) 1 else 0), declaredLength = 1)

    /**
     * Binary colour frame. Per GoveeDreamView, [stretch] spreads the colours over the whole lamp,
     * so a count below the lamp's real segment count still fills it; without it they map one to one.
     */
    fun razerColorFrame(colors: IntArray, stretch: Boolean): ByteArray {
        require(colors.size in 1..MAX_RAZER_SEGMENTS) { "segments: ${colors.size}" }
        val payload = ByteArray(2 + colors.size * 3)
        payload[0] = if (stretch) 1 else 0
        payload[1] = colors.size.toByte()
        colors.forEachIndexed { index, argb ->
            payload[2 + index * 3] = (argb shr 16).toByte()
            payload[3 + index * 3] = (argb shr 8).toByte()
            payload[4 + index * 3] = argb.toByte()
        }
        return razerFrame(0xB0, payload, declaredLength = payload.size)
    }

    /** Hard colour bands for counting segments: a white first segment, then red, green, blue. */
    fun segmentTestPattern(count: Int): IntArray = IntArray(count) { index ->
        if (index == 0) 0xFFFFFFFF.toInt() else SEGMENT_TEST_COLORS[(index - 1) % SEGMENT_TEST_COLORS.size]
    }

    private fun razerFrame(command: Int, payload: ByteArray, declaredLength: Int): ByteArray {
        val frame = ByteArray(5 + payload.size)
        frame[0] = 0xBB.toByte()
        frame[1] = (declaredLength shr 8).toByte()
        frame[2] = declaredLength.toByte()
        frame[3] = command.toByte()
        payload.copyInto(frame, 4)
        frame[frame.lastIndex] = frame.dropLast(1).fold(0) { xor, byte -> xor xor byte.toInt() }.toByte()
        return frame
    }

    private fun razer(frame: ByteArray): String = message("razer") {
        put("pt", Base64.getEncoder().encodeToString(frame))
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
