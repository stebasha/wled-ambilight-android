package dev.wledambilight.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DdpPacketBuilderTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `single packet has the documented header`() {
        val rgb = ByteArray(30) { it.toByte() }
        val packets = DdpPacketBuilder(destinationId = 2).build(rgb, startSequence = 7)
        assertEquals(1, packets.size)
        val packet = packets[0]
        assertEquals(40, packet.size)
        assertEquals(0x41, packet[0].toInt() and 0xFF)
        assertEquals(7, packet[1].toInt() and 0xFF)
        assertEquals(0x0B, packet[2].toInt() and 0xFF)
        assertEquals(2, packet[3].toInt() and 0xFF)
        assertArrayEquals(bytes(0, 0, 0, 0), packet.copyOfRange(4, 8))
        assertArrayEquals(bytes(0, 30), packet.copyOfRange(8, 10))
        assertArrayEquals(rgb, packet.copyOfRange(10, 40))
    }

    @Test
    fun `large frames are split with byte offsets`() {
        val rgb = ByteArray(3000) { (it % 251).toByte() }
        val packets = DdpPacketBuilder().build(rgb)
        assertEquals(3, packets.size)
        assertEquals(1450, packets[0].size)
        assertEquals(10 + 1440, packets[1].size)
        assertEquals(10 + 120, packets[2].size)

        assertArrayEquals(bytes(0, 0, 0, 0), packets[0].copyOfRange(4, 8))
        assertArrayEquals(bytes(0, 0, 5, 0xA0), packets[1].copyOfRange(4, 8))
        assertArrayEquals(bytes(0, 0, 11, 0x40), packets[2].copyOfRange(4, 8))

        assertArrayEquals(bytes(5, 0xA0), packets[1].copyOfRange(8, 10))
        assertArrayEquals(bytes(0, 120), packets[2].copyOfRange(8, 10))

        assertEquals(0x40, packets[0][0].toInt() and 0xFF)
        assertEquals(0x40, packets[1][0].toInt() and 0xFF)
        assertEquals(0x41, packets[2][0].toInt() and 0xFF)

        assertArrayEquals(rgb.copyOfRange(1440, 2880), packets[1].copyOfRange(10, 1450))
        assertArrayEquals(rgb.copyOfRange(2880, 3000), packets[2].copyOfRange(10, 130))
    }

    @Test
    fun `sequence wraps at 255`() {
        val rgb = ByteArray(3 * 1000)
        val packets = DdpPacketBuilder().build(rgb, startSequence = 255)
        assertEquals(255, packets[0][1].toInt() and 0xFF)
        assertEquals(0, packets[1][1].toInt() and 0xFF)
        assertEquals(1, packets[2][1].toInt() and 0xFF)
    }

    @Test
    fun `empty frame produces no packets`() {
        assertTrue(DdpPacketBuilder().build(ByteArray(0)).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload must contain whole LEDs`() {
        DdpPacketBuilder().build(ByteArray(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `destination id must fit a byte`() {
        DdpPacketBuilder(destinationId = 256)
    }

    @Test
    fun `rgb encoder uses argb channel order`() {
        val encoded = RgbEncoder.toRgbBytes(
            intArrayOf(0xFF112233.toInt(), 0xFFAABBCC.toInt()),
        )
        assertArrayEquals(bytes(0x11, 0x22, 0x33, 0xAA, 0xBB, 0xCC), encoded)
    }
}
