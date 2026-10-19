package dev.wledambilight.core.protocol

import kotlin.math.ceil

/**
 * DDP (Distributed Display Protocol) v1 encoder as implemented by WLED.
 *
 * Header (10 bytes, big endian):
 * ```
 * 0: flags   (0x40 = version 1, 0x01 = push)
 * 1: sequence (wraps at 255)
 * 2: data type (0x0B RGB888)
 * 3: destination id
 * 4..7: data offset in bytes
 * 8..9: payload length in bytes
 * ```
 * See docs/PROTOCOLS.md for sources and limitations.
 */
object Ddp {
    const val DEFAULT_PORT = 4048
    const val HEADER_SIZE = 10
    const val MAX_PAYLOAD_BYTES = 1440
    const val LEDS_PER_PACKET = MAX_PAYLOAD_BYTES / 3 // 480

    const val FLAG_VERSION_1 = 0x40
    const val FLAG_PUSH = 0x01
    const val DATA_TYPE_RGB888 = 0x0B
}

class DdpPacketBuilder(
    private val destinationId: Int = 1,
    private val maxLedsPerPacket: Int = Ddp.LEDS_PER_PACKET,
) {
    init {
        require(destinationId in 0..255) { "destinationId must fit in one byte" }
        require(maxLedsPerPacket in 1..Ddp.MAX_PAYLOAD_BYTES / 3) {
            "maxLedsPerPacket must be between 1 and ${Ddp.MAX_PAYLOAD_BYTES / 3}"
        }
    }

    fun build(rgb: ByteArray, startSequence: Int = 0): List<ByteArray> {
        require(rgb.size % 3 == 0) { "RGB payload must contain whole LEDs" }
        val ledCount = rgb.size / 3
        if (ledCount == 0) return emptyList()
        val chunkCount = ceil(ledCount.toDouble() / maxLedsPerPacket).toInt()
        val packets = ArrayList<ByteArray>(chunkCount)

        for (chunk in 0 until chunkCount) {
            val firstLed = chunk * maxLedsPerPacket
            val leds = minOf(maxLedsPerPacket, ledCount - firstLed)
            val payloadLength = leds * 3
            val packet = ByteArray(Ddp.HEADER_SIZE + payloadLength)
            val push = chunk == chunkCount - 1
            packet[0] = ((Ddp.FLAG_VERSION_1 or if (push) Ddp.FLAG_PUSH else 0) and 0xFF).toByte()
            packet[1] = ((startSequence + chunk) and 0xFF).toByte()
            packet[2] = Ddp.DATA_TYPE_RGB888.toByte()
            packet[3] = destinationId.toByte()

            val offset = firstLed * 3
            packet[4] = ((offset ushr 24) and 0xFF).toByte()
            packet[5] = ((offset ushr 16) and 0xFF).toByte()
            packet[6] = ((offset ushr 8) and 0xFF).toByte()
            packet[7] = (offset and 0xFF).toByte()
            packet[8] = ((payloadLength ushr 8) and 0xFF).toByte()
            packet[9] = (payloadLength and 0xFF).toByte()

            System.arraycopy(rgb, offset, packet, Ddp.HEADER_SIZE, payloadLength)
            packets += packet
        }
        return packets
    }
}

/** Converts ARGB colours (as produced by the analyser) to the packed RGB byte stream protocols expect. */
object RgbEncoder {
    fun toRgbBytes(colors: IntArray): ByteArray {
        val out = ByteArray(colors.size * 3)
        for (i in colors.indices) {
            val argb = colors[i]
            out[i * 3] = ((argb shr 16) and 0xFF).toByte()
            out[i * 3 + 1] = ((argb shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = (argb and 0xFF).toByte()
        }
        return out
    }
}
