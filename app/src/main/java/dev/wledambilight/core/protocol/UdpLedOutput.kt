package dev.wledambilight.core.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends already-encoded realtime packets over UDP. The socket and the resolved address are reused
 * for the whole session; [close] is idempotent and is safe to call from a different thread.
 */
class UdpLedOutput(
    private val host: String,
    private val port: Int,
    private val packetizer: (ByteArray) -> List<ByteArray>,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : Closeable {

    private val lock = Any()
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var closed = false

    var packetsSent: Long = 0L
        private set
    var bytesSent: Long = 0L
        private set

    suspend fun send(colors: IntArray) = withContext(Dispatchers.IO) {
        val rgb = RgbEncoder.toRgbBytes(colors)
        val packets = packetizer(rgb)
        if (packets.isEmpty()) return@withContext
        val target = address ?: InetAddress.getByName(host).also { address = it }
        synchronized(lock) {
            check(!closed) { "UDP output is closed" }
            val active = socket ?: socketFactory().also { socket = it }
            for (packet in packets) {
                active.send(DatagramPacket(packet, packet.size, target, port))
                packetsSent++
                bytesSent += packet.size
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            socket?.close()
            socket = null
            address = null
        }
    }
}
