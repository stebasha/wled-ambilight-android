package dev.wledambilight.core.protocol

import java.io.Closeable
import kotlin.math.abs

/** A Govee lamp as seen by the engine: one colour ([GoveeOutput]) or one per segment ([GoveeRazerOutput]). */
interface GoveeSink : Closeable {
    val packetsSent: Long

    /** Called once at session start. */
    suspend fun prepare()

    /** Returns true when a command was actually sent. */
    suspend fun offer(colors: IntArray): Boolean

    /** Called periodically, also while the picture is static and no frames arrive. */
    suspend fun keepAlive() = Unit

    /** Called once at session end, before [close]. */
    suspend fun release() = Unit
}

/**
 * Streams one colour to a Govee lamp through the LAN API.
 *
 * Govee lamps are not realtime LED controllers: each `colorwc` command triggers the lamp's own
 * transition. Commands are therefore rate limited ([minIntervalMillis]) and skipped when the colour
 * barely changed, which keeps the lamp responsive and the Wi-Fi quiet. A periodic refresh resends
 * the colour in case a UDP datagram was lost.
 */
class GoveeOutput(
    host: String,
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val refreshMillis: Long = REFRESH_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val udp: UdpLedOutput = UdpLedOutput(host, GoveeLan.COMMAND_PORT),
) : GoveeSink {

    private var lastColor: Int? = null
    private var lastSentAt = Long.MIN_VALUE / 2

    override val packetsSent: Long get() = udp.packetsSent

    /** Switches the lamp on. */
    override suspend fun prepare() {
        udp.sendPackets(listOf(GoveeLan.turn(true).encodeToByteArray()))
        lastSentAt = clock()
    }

    override suspend fun offer(colors: IntArray): Boolean = offer(colors.first())

    /** Returns true when a command was actually sent. */
    suspend fun offer(argb: Int): Boolean {
        val now = clock()
        if (now - lastSentAt < minIntervalMillis) return false
        val previous = lastColor
        val changed = previous == null || distance(previous, argb) > CHANGE_THRESHOLD
        if (!changed && now - lastSentAt < refreshMillis) return false
        udp.sendPackets(listOf(GoveeLan.color(argb).encodeToByteArray()))
        lastColor = argb
        lastSentAt = now
        return true
    }

    override fun close() = udp.close()

    private fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    companion object {
        /** Conservative default (max 10 commands/s); the API documents no rate, so stay modest. */
        const val DEFAULT_INTERVAL_MS = 100L
        const val REFRESH_MS = 3_000L
        private const val CHANGE_THRESHOLD = 6
    }
}
