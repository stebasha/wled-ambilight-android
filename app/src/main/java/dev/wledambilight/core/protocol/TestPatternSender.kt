package dev.wledambilight.core.protocol

import dev.wledambilight.core.data.DiagnosticsRepository
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.WledDevice
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable output for calibration/test patterns that are sent outside an ambilight session.
 * A single UDP socket is kept per device configuration and closed when the configuration changes.
 */
class TestPatternSender(private val diagnostics: DiagnosticsRepository) {

    private val mutex = Mutex()
    private var output: UdpLedOutput? = null
    private var key: String? = null

    suspend fun send(device: WledDevice, colors: IntArray, report: Boolean = false) {
        if (colors.isEmpty()) return
        mutex.withLock {
            val deviceKey = listOf(
                device.id, device.host, device.outputPort, device.protocol, device.wledUdpMode,
            ).joinToString("|")
            if (key != deviceKey) {
                output?.close()
                output = null
                key = deviceKey
            }
            val ddp = DdpPacketBuilder()
            val wled = WledUdpPacketBuilder(device.wledUdpMode)
            val packetizer: (ByteArray) -> List<ByteArray> = when (device.protocol) {
                OutputProtocol.DDP -> { rgb -> ddp.build(rgb) }
                OutputProtocol.WLED_UDP -> { rgb -> wled.build(rgb) }
            }
            val active = output ?: UdpLedOutput(device.host, device.outputPort, packetizer).also { output = it }
            try {
                active.send(colors)
                if (report) {
                    diagnostics.report(
                        DiagnosticCode.UDP_TEST_SENT,
                        "${device.host}:${device.outputPort} ${device.protocol}",
                    )
                }
            } catch (error: Exception) {
                diagnostics.report(DiagnosticCode.UDP_SEND_FAILED, error.javaClass.simpleName)
                output?.close()
                output = null
                key = null
            }
        }
    }

    fun close() {
        output?.close()
        output = null
        key = null
    }
}
