package dev.wledambilight.core.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
