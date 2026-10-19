package dev.wledambilight.core.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpLedOutputTest {

    @Test
    fun `sends ddp packets over a real udp socket and counts them`() = runBlocking {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        receiver.soTimeout = 4000
        try {
            val output = UdpLedOutput(
                host = "127.0.0.1",
                port = receiver.localPort,
                packetizer = { rgb -> DdpPacketBuilder().build(rgb) },
            )
            output.send(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))

            val buffer = ByteArray(64)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)

            assertEquals(16, packet.length)
            assertEquals(0x41, buffer[0].toInt() and 0xFF)
            assertEquals(0x0B, buffer[2].toInt() and 0xFF)
            assertEquals(0xFF, buffer[10].toInt() and 0xFF)
            assertEquals(0x00, buffer[11].toInt() and 0xFF)
            assertEquals(1L, output.packetsSent)
            assertEquals(16L, output.bytesSent)

            output.close()
            try {
                output.send(intArrayOf(0xFF000000.toInt()))
                fail("send after close must fail")
            } catch (expected: IllegalStateException) {
                // expected: the output cannot silently reopen a closed socket
            }
        } finally {
            receiver.close()
        }
    }
}
