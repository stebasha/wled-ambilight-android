package dev.wledambilight.core.protocol

import dev.wledambilight.core.model.WledUdpMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Packet vectors for zones that start in the middle of a strip. */
class ZoneOffsetPacketTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `ddp offset is expressed in bytes and push can be deferred`() {
        val rgb = bytes(1, 2, 3, 4, 5, 6)
        val packet = DdpPacketBuilder().build(rgb, ledOffset = 100, push = false).single()
        assertEquals(0x40, packet[0].toInt() and 0xFF)
        // 100 LEDs * 3 bytes = 300 = 0x012C
        assertArrayEquals(bytes(0, 0, 0x01, 0x2C), packet.copyOfRange(4, 8))
        assertArrayEquals(bytes(0, 6), packet.copyOfRange(8, 10))
        assertArrayEquals(rgb, packet.copyOfRange(10, 16))
    }

    @Test
    fun `ddp chunks keep the zone offset`() {
        val rgb = ByteArray(3 * 500)
        val packets = DdpPacketBuilder().build(rgb, ledOffset = 10)
        assertEquals(2, packets.size)
        // second chunk starts at LED 10 + 480 = 490 -> 1470 bytes = 0x05BE
        assertArrayEquals(bytes(0, 0, 0x05, 0xBE), packets[1].copyOfRange(4, 8))
        assertEquals(0x41, packets[1][0].toInt() and 0xFF)
    }

    @Test
    fun `dnrgb start index includes the zone offset`() {
        val packet = WledUdpPacketBuilder(WledUdpMode.DNRGB).build(bytes(9, 8, 7), ledOffset = 300).single()
        assertArrayEquals(bytes(4, 2, 0x01, 0x2C, 9, 8, 7), packet)
    }

    @Test
    fun `warls indexes are shifted by the offset`() {
        val packet = WledUdpPacketBuilder(WledUdpMode.WARLS).build(bytes(1, 2, 3, 4, 5, 6), ledOffset = 10).single()
        assertArrayEquals(bytes(1, 2, 10, 1, 2, 3, 11, 4, 5, 6), packet)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `warls refuses leds past index 255`() {
        WledUdpPacketBuilder(WledUdpMode.WARLS).build(ByteArray(3 * 10), ledOffset = 250)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `drgb refuses an offset`() {
        WledUdpPacketBuilder(WledUdpMode.DRGB).build(bytes(1, 2, 3), ledOffset = 1)
    }
}
