package dev.wledambilight.core.protocol

import dev.wledambilight.core.data.DiagnosticsRepository
import dev.wledambilight.core.model.DeviceKind
import dev.wledambilight.core.model.DiagnosticCode
import dev.wledambilight.core.model.OutputProtocol
import dev.wledambilight.core.model.WledDevice
import kotlinx.coroutines.CancellationException
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

    /**
     * Sends [colors] starting at strip LED [ledOffset] (WLED), or the first colour as a solid
     * colour (Govee). Zone tests use the offset to light only that zone's range.
     */
    suspend fun send(device: WledDevice, colors: IntArray, report: Boolean = false, ledOffset: Int = 0) {
        if (colors.isEmpty()) return
        val packets = when {
            device.kind == DeviceKind.GOVEE -> listOf(
                GoveeLan.turn(true).encodeToByteArray(),
                GoveeLan.color(colors.first()).encodeToByteArray(),
            )
            device.protocol == OutputProtocol.DDP ->
                DdpPacketBuilder().build(RgbEncoder.toRgbBytes(colors), ledOffset = ledOffset)
            else ->
                WledUdpPacketBuilder(device.wledUdpMode).build(RgbEncoder.toRgbBytes(colors), ledOffset)
        }
        deliver(device, packets, report)
    }

    /** Shows [colors] on the segments of a Govee lamp through razer mode. */
    suspend fun sendGoveeSegments(device: WledDevice, colors: IntArray, stretch: Boolean) {
        val packets = listOf(
            GoveeLan.turn(true),
            GoveeLan.razerMode(true),
            GoveeLan.razerColors(colors, stretch),
        ).map { it.encodeToByteArray() }
        deliver(device, packets, report = true)
    }

    /** Hands a Govee lamp back after [sendGoveeSegments]. */
    suspend fun stopGoveeSegments(device: WledDevice) {
        deliver(device, listOf(GoveeLan.razerMode(false).encodeToByteArray()), report = false)
    }

    private suspend fun deliver(device: WledDevice, packets: List<ByteArray>, report: Boolean) {
        mutex.withLock {
            val port = if (device.kind == DeviceKind.GOVEE) GoveeLan.COMMAND_PORT else device.outputPort
            val deviceKey = listOf(device.id, device.host, port, device.kind).joinToString("|")
            if (key != deviceKey) {
                output?.close()
                output = null
                key = deviceKey
            }
            val active = output ?: UdpLedOutput(device.host, port).also { output = it }
            try {
                active.sendPackets(packets)
                if (report) {
                    diagnostics.report(
                        if (device.kind == DeviceKind.GOVEE) DiagnosticCode.GOVEE_TEST_SENT else DiagnosticCode.UDP_TEST_SENT,
                        "${device.host}:$port ${if (device.kind == DeviceKind.GOVEE) "Govee" else device.protocol.name}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
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
