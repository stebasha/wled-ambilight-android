package dev.wledambilight.core.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Base64

class GoveeLanTest {

    private fun data(raw: String) = Json.parseToJsonElement(raw).jsonObject["msg"]!!.jsonObject

    @Test
    fun `commands follow the documented LAN API shape`() {
        val scan = data(GoveeLan.scanRequest())
        assertEquals("scan", scan["cmd"]!!.jsonPrimitive.content)
        assertEquals("reserve", scan["data"]!!.jsonObject["account_topic"]!!.jsonPrimitive.content)

        val turn = data(GoveeLan.turn(true))
        assertEquals("turn", turn["cmd"]!!.jsonPrimitive.content)
        assertEquals(1, turn["data"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(0, data(GoveeLan.turn(false))["data"]!!.jsonObject["value"]!!.jsonPrimitive.int)

        assertEquals(100, data(GoveeLan.brightness(250))["data"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(1, data(GoveeLan.brightness(0))["data"]!!.jsonObject["value"]!!.jsonPrimitive.int)

        val color = data(GoveeLan.color(0xFF102030.toInt()))
        assertEquals("colorwc", color["cmd"]!!.jsonPrimitive.content)
        val rgb = color["data"]!!.jsonObject["color"]!!.jsonObject
        assertEquals(0x10, rgb["r"]!!.jsonPrimitive.int)
        assertEquals(0x20, rgb["g"]!!.jsonPrimitive.int)
        assertEquals(0x30, rgb["b"]!!.jsonPrimitive.int)
        assertEquals(0, color["data"]!!.jsonObject["colorTemInKelvin"]!!.jsonPrimitive.int)
    }

    @Test
    fun `razer mode frames match the published community vectors`() {
        assertArrayEquals(bytes(0xBB, 0x00, 0x01, 0xB1, 0x01, 0x0A), GoveeLan.razerModeFrame(true))
        assertArrayEquals(bytes(0xBB, 0x00, 0x01, 0xB1, 0x00, 0x0B), GoveeLan.razerModeFrame(false))
        assertEquals("uwABsQEK", pt(GoveeLan.razerMode(true)))
        assertEquals("uwABsQAL", pt(GoveeLan.razerMode(false)))
    }

    @Test
    fun `razer colour frame carries length, stretch, count, rgb and xor`() {
        val colors = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt())
        val frame = GoveeLan.razerColorFrame(colors, stretch = true)
        val head = bytes(0xBB, 0x00, 0x08, 0xB0, 0x01, 0x02, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
        assertArrayEquals(head + head.fold(0) { xor, byte -> xor xor byte.toInt() }.toByte(), frame)

        assertEquals(0, GoveeLan.razerColorFrame(intArrayOf(0), stretch = false)[4].toInt())
        assertArrayEquals(frame, Base64.getDecoder().decode(pt(GoveeLan.razerColors(colors, true))))
    }

    @Test
    fun `long razer frames use the high length byte`() {
        val frame = GoveeLan.razerColorFrame(IntArray(GoveeLan.MAX_RAZER_SEGMENTS), stretch = true)
        val declared = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        assertEquals(2 + 3 * GoveeLan.MAX_RAZER_SEGMENTS, declared)
        assertEquals(GoveeLan.MAX_RAZER_SEGMENTS, frame[5].toInt() and 0xFF)
    }

    @Test
    fun `segment test pattern marks the first segment`() {
        val pattern = GoveeLan.segmentTestPattern(5)
        assertEquals(0xFFFFFFFF.toInt(), pattern[0])
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt()), pattern.drop(1))
    }

    @Test
    fun `razer output arms the mode, rate limits, keeps alive and releases`() = runBlocking {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        receiver.soTimeout = 2000
        var now = 10_000L
        val output = GoveeRazerOutput(
            host = "127.0.0.1",
            minIntervalMillis = 25,
            refreshMillis = 1000,
            rearmMillis = 5000,
            settleMillis = 0,
            clock = { now },
            udp = UdpLedOutput("127.0.0.1", receiver.localPort),
        )
        val red = intArrayOf(0xFFFF0000.toInt(), 0xFF000000.toInt())
        val green = intArrayOf(0xFF00FF00.toInt(), 0xFF000000.toInt())
        try {
            output.prepare()
            assertTrue(output.offer(red))
            now += 10
            assertFalse("rate limited", output.offer(green))
            now += 20
            assertFalse("unchanged", output.offer(intArrayOf(0xFFFE0000.toInt(), 0xFF000000.toInt())))
            assertTrue(output.offer(green))
            output.keepAlive()
            assertEquals("no keep-alive right after a frame", 4L, output.packetsSent)
            now += 1000
            output.keepAlive()
            assertEquals(5L, output.packetsSent)
            now += 5000
            assertTrue(output.offer(red))
            assertEquals("mode armed again with the frame", 7L, output.packetsSent)
            output.release()

            val seen = List(9) {
                val packet = DatagramPacket(ByteArray(512), 512)
                receiver.receive(packet)
                String(packet.data, 0, packet.length)
            }
            assertEquals("turn", data(seen[0])["cmd"]!!.jsonPrimitive.content)
            assertEquals(GoveeLan.razerMode(true), seen[1])
            assertEquals(GoveeLan.razerColors(red, true), seen[2])
            assertEquals(GoveeLan.razerMode(true), seen[5])
            assertEquals(GoveeLan.razerMode(false), seen[8])
        } finally {
            output.close()
            receiver.close()
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun pt(raw: String): String = data(raw)["data"]!!.jsonObject["pt"]!!.jsonPrimitive.content

    @Test
    fun `scan responses are parsed defensively`() {
        val ok = GoveeLan.parseScanResponse(
            """{"msg":{"cmd":"scan","data":{"ip":"192.168.1.23","device":"1F:80:C5:32:32:36:72:4E","sku":"H6199","extra":1}}}""",
        )
        assertEquals("192.168.1.23", ok!!.ip)
        assertEquals("H6199", ok.sku)
        assertNull(GoveeLan.parseScanResponse("not json"))
        assertNull(GoveeLan.parseScanResponse("""{"msg":{"cmd":"devStatus","data":{}}}"""))
        assertNull(GoveeLan.parseScanResponse("""{"msg":{"cmd":"scan","data":{"ip":"http://evil/x"}}}"""))
        assertNull(GoveeLan.parseScanResponse("""{"msg":{"cmd":"scan","data":{}}}"""))
    }

    @Test
    fun `output rate limits and skips unchanged colours`() = runBlocking {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        receiver.soTimeout = 2000
        var now = 0L
        val output = GoveeOutput(
            host = "127.0.0.1",
            minIntervalMillis = 100,
            refreshMillis = 1000,
            clock = { now },
            udp = UdpLedOutput("127.0.0.1", receiver.localPort),
        )
        try {
            now = 1_000
            assertTrue(output.offer(0xFFFF0000.toInt()))
            now = 1_050
            assertFalse("rate limited", output.offer(0xFF00FF00.toInt()))
            now = 1_200
            assertFalse("unchanged colour is not resent", output.offer(0xFFFF0001.toInt()))
            assertTrue(output.offer(0xFF00FF00.toInt()))
            now = 2_300
            assertTrue("periodic refresh", output.offer(0xFF00FF00.toInt()))
            assertEquals(3L, output.packetsSent)

            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            val text = String(packet.data, 0, packet.length)
            assertTrue(text.contains("colorwc"))
        } finally {
            output.close()
            receiver.close()
        }
    }
}
