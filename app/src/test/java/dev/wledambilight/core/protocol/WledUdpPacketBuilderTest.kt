package dev.wledambilight.core.protocol

import dev.wledambilight.core.model.WledUdpMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WledUdpPacketBuilderTest {

    private fun rgb(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `warls packet carries an index per led`() {
        val packets = WledUdpPacketBuilder(WledUdpMode.WARLS, timeoutSeconds = 3)
            .build(rgb(255, 0, 0, 0, 255, 0))
        assertEquals(1, packets.size)
        assertArrayEquals(
            byteArrayOf(1, 3, 0, 255.toByte(), 0, 0, 1, 0, 255.toByte(), 0),
            packets[0],
        )
    }

    @Test
    fun `warls sends up to 255 leds in one packet`() {
        val data = ByteArray(255 * 3) { 1 }
        val packets = WledUdpPacketBuilder(WledUdpMode.WARLS).build(data)
        assertEquals(1, packets.size)
        assertEquals(2 + 255 * 4, packets[0].size)
        assertEquals(0, packets[0][2].toInt())
        assertEquals(254, packets[0][2 + 254 * 4].toInt() and 0xFF)
    }

    @Test
    fun `warls refuses layouts it cannot address`() {
        val data = ByteArray(256 * 3)
        try {
            WledUdpPacketBuilder(WledUdpMode.WARLS).build(data)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // WARLS has a one-byte LED index
        }
    }

    @Test
    fun `drgb packet has no addressing`() {
        val packets = WledUdpPacketBuilder(WledUdpMode.DRGB, timeoutSeconds = 1)
            .build(rgb(1, 2, 3, 4, 5, 6))
        assertEquals(1, packets.size)
        assertArrayEquals(byteArrayOf(2, 1, 1, 2, 3, 4, 5, 6), packets[0])
    }

    @Test
    fun `drgb refuses more leds than it can address`() {
        val data = ByteArray(481 * 3)
        try {
            WledUdpPacketBuilder(WledUdpMode.DRGB).build(data)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected: DRGB has no offset field, use DNRGB instead
        }
    }

    @Test
    fun `dnrgb splits frames and writes a 16 bit start index`() {
        val data = ByteArray(600 * 3) { (it % 256).toByte() }
        val packets = WledUdpPacketBuilder(WledUdpMode.DNRGB, timeoutSeconds = 2).build(data)
        assertEquals(2, packets.size)
        assertEquals(4 + 480 * 3, packets[0].size)
        assertEquals(4 + 120 * 3, packets[1].size)
        assertArrayEquals(byteArrayOf(4, 2, 0, 0), packets[0].copyOfRange(0, 4))
        // 480 == 0x01E0
        assertArrayEquals(byteArrayOf(4, 2, 1, 0xE0.toByte()), packets[1].copyOfRange(0, 4))
        assertArrayEquals(data.copyOfRange(480 * 3, 600 * 3), packets[1].copyOfRange(4, 4 + 120 * 3))
    }

    @Test
    fun `empty input produces no packets`() {
        for (mode in WledUdpMode.entries) {
            assertTrue(WledUdpPacketBuilder(mode).build(ByteArray(0)).isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `timeout must fit in a byte`() {
        WledUdpPacketBuilder(WledUdpMode.DNRGB, timeoutSeconds = 256)
    }
}
