package dev.wledambilight.core.discovery

import android.content.Context
import android.net.wifi.WifiManager
import dev.wledambilight.core.data.DiagnosticsRepository
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.protocol.GoveeLan
import dev.wledambilight.core.protocol.GoveeScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * User-triggered Govee LAN discovery.
 *
 * The LAN API answers scans on the client's UDP port 4002, so this is the only place where the app
 * listens on a port: the socket is bound for [windowMillis] after an explicit tap and closed right
 * after. Streaming itself never listens.
 */
class GoveeDiscovery(
    context: Context,
    private val diagnostics: DiagnosticsRepository,
) {
    private val appContext = context.applicationContext

    suspend fun scan(windowMillis: Long = 3_000): List<GoveeScanResult> = withContext(Dispatchers.IO) {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("wled-ambilight-govee")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val found = LinkedHashMap<String, GoveeScanResult>()
        diagnostics.report(DiagnosticCode.DISCOVERY_STARTED, "govee lan")
        try {
            MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(GoveeLan.RESPONSE_PORT))
                socket.soTimeout = 400
                val request = GoveeLan.scanRequest().encodeToByteArray()
                val group = InetAddress.getByName(GoveeLan.MULTICAST_ADDRESS)
                val deadline = System.currentTimeMillis() + windowMillis
                var nextSend = 0L
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive()
                    val now = System.currentTimeMillis()
                    if (now >= nextSend) {
                        // A few repeats: multicast over Wi-Fi is lossy.
                        socket.send(DatagramPacket(request, request.size, group, GoveeLan.SCAN_PORT))
                        nextSend = now + 1_000
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    GoveeLan.parseScanResponse(text)?.let { result ->
                        if (found.put(result.ip, result) == null) {
                            diagnostics.report(DiagnosticCode.DISCOVERY_FOUND, "govee ${result.sku ?: "?"} ${result.ip}")
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            diagnostics.report(DiagnosticCode.DISCOVERY_UNAVAILABLE, "govee: ${error.javaClass.simpleName}")
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
            diagnostics.report(DiagnosticCode.DISCOVERY_FINISHED, "govee: ${found.size}")
        }
        found.values.toList()
    }
}
