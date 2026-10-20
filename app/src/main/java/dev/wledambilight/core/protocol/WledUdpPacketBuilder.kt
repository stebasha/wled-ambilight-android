package dev.wledambilight.core.protocol

import dev.wledambilight.core.model.WledUdpMode
import kotlin.math.ceil

/**
 * WLED UDP realtime packet encoder.
 *
 * WLED's realtime UDP port defaults to 21324 (configurable on the controller). Four documented
 * packet layouts exist; this app exposes the RGB variants:
 *
 * - WARLS  `[1, timeout, index, r, g, b, ...]` (max 255 LEDs per packet)
 * - DRGB   `[2, timeout, r, g, b, ...]` (always starts at LED 0, one packet per frame)
 * - DNRGB  `[4, timeout, startHi, startLo, r, g, b, ...]` (chunked, 16-bit start index)
 *
 * The `timeout` byte is a number of seconds after which WLED leaves realtime mode if packets stop.
 */
object WledUdp {
    const val DEFAULT_PORT = 21324
    const val PROTOCOL_WARLS = 1
    const val PROTOCOL_DRGB = 2
    const val PROTOCOL_DNRGB = 4
    const val WARLS_MAX_LEDS = 255
    const val MAX_LEDS_PER_PACKET = 480
    const val DEFAULT_TIMEOUT_SECONDS = 2
}

class WledUdpPacketBuilder(
    private val mode: WledUdpMode,
    private val timeoutSeconds: Int = WledUdp.DEFAULT_TIMEOUT_SECONDS,
    private val maxLedsPerPacket: Int = WledUdp.MAX_LEDS_PER_PACKET,
) {
    init {
        require(timeoutSeconds in 0..255) { "timeout must fit in one byte" }
        require(maxLedsPerPacket in 1..WledUdp.MAX_LEDS_PER_PACKET) {
            "maxLedsPerPacket must be between 1 and ${WledUdp.MAX_LEDS_PER_PACKET}"
        }
    }

    /**
     * Encodes [rgb] starting at strip LED [ledOffset]. DRGB has no addressing and therefore only
     * accepts offset 0; WARLS needs every addressed LED below 256.
     */
    fun build(rgb: ByteArray, ledOffset: Int = 0): List<ByteArray> {
        require(rgb.size % 3 == 0) { "RGB payload must contain whole LEDs" }
        require(ledOffset >= 0) { "ledOffset must not be negative" }
        val ledCount = rgb.size / 3
        if (ledCount == 0) return emptyList()
        return when (mode) {
            WledUdpMode.WARLS -> buildWarls(rgb, ledCount, ledOffset)
            WledUdpMode.DRGB -> {
                require(ledOffset == 0) { "DRGB always starts at LED 0; use DNRGB or DDP for zones" }
                buildDrgb(rgb, ledCount)
            }
            WledUdpMode.DNRGB -> buildDnrgb(rgb, ledCount, ledOffset)
        }
    }

    private fun buildWarls(rgb: ByteArray, ledCount: Int, ledOffset: Int): List<ByteArray> {
        require(ledOffset + ledCount <= WledUdp.WARLS_MAX_LEDS) {
            "WARLS indexes LEDs with one byte and supports at most ${WledUdp.WARLS_MAX_LEDS} LEDs"
        }
        val chunkSize = minOf(maxLedsPerPacket, WledUdp.WARLS_MAX_LEDS)
        val chunks = ceil(ledCount.toDouble() / chunkSize).toInt()
        return (0 until chunks).map { chunk ->
            val first = chunk * chunkSize
            val count = minOf(chunkSize, ledCount - first)
            val packet = ByteArray(2 + count * 4)
            packet[0] = WledUdp.PROTOCOL_WARLS.toByte()
            packet[1] = timeoutSeconds.toByte()
            for (i in 0 until count) {
                val led = first + i
                val at = 2 + i * 4
                packet[at] = (ledOffset + led).toByte()
                packet[at + 1] = rgb[led * 3]
                packet[at + 2] = rgb[led * 3 + 1]
                packet[at + 3] = rgb[led * 3 + 2]
            }
            packet
        }
    }

    private fun buildDrgb(rgb: ByteArray, ledCount: Int): List<ByteArray> {
        require(ledCount <= maxLedsPerPacket) {
            "DRGB cannot address more than $maxLedsPerPacket LEDs; use DNRGB"
        }
        val packet = ByteArray(2 + rgb.size)
        packet[0] = WledUdp.PROTOCOL_DRGB.toByte()
        packet[1] = timeoutSeconds.toByte()
        System.arraycopy(rgb, 0, packet, 2, rgb.size)
        return listOf(packet)
    }

    private fun buildDnrgb(rgb: ByteArray, ledCount: Int, ledOffset: Int): List<ByteArray> {
        val chunks = ceil(ledCount.toDouble() / maxLedsPerPacket).toInt()
        return (0 until chunks).map { chunk ->
            val first = chunk * maxLedsPerPacket
            val count = minOf(maxLedsPerPacket, ledCount - first)
            val start = ledOffset + first
            require(start <= 0xFFFF) { "DNRGB start index exceeds 16 bits" }
            val packet = ByteArray(4 + count * 3)
            packet[0] = WledUdp.PROTOCOL_DNRGB.toByte()
            packet[1] = timeoutSeconds.toByte()
            packet[2] = ((start ushr 8) and 0xFF).toByte()
            packet[3] = (start and 0xFF).toByte()
            System.arraycopy(rgb, first * 3, packet, 4, count * 3)
            packet
        }
    }
}
