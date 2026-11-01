package dev.wledambilight.core.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Streams one colour per segment to a Govee lamp through the unofficial razer mode (see [GoveeLan]).
 *
 * Unlike `colorwc`, razer frames bypass the lamp's own transition, so they can go out at video rate.
 * The mode is armed again every [rearmMillis]: the lamp leaves it on its own after a minute without
 * data, and also when the Govee app takes over. On [release] the lamp returns to its previous state.
 */
class GoveeRazerOutput(
    host: String,
    private val stretch: Boolean = false,
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val refreshMillis: Long = REFRESH_MS,
    private val rearmMillis: Long = REARM_MS,
    private val settleMillis: Long = SETTLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val udp: UdpLedOutput = UdpLedOutput(host, GoveeLan.COMMAND_PORT),
) : GoveeSink {

    private val mutex = Mutex()
    private var lastColors: IntArray? = null
    private var lastSentAt = Long.MIN_VALUE / 2
    private var lastArmedAt = Long.MIN_VALUE / 2

    override val packetsSent: Long get() = udp.packetsSent

    override suspend fun prepare() = mutex.withLock {
        udp.sendPackets(listOf(GoveeLan.turn(true).encodeToByteArray()))
        delay(settleMillis)
        udp.sendPackets(listOf(GoveeLan.razerMode(true).encodeToByteArray()))
        lastArmedAt = clock()
    }

    override suspend fun offer(colors: IntArray): Boolean = mutex.withLock {
        val now = clock()
        if (now - lastSentAt < minIntervalMillis) return@withLock false
        val previous = lastColors
        val changed = previous == null || previous.size != colors.size ||
            colors.indices.any { distance(previous[it], colors[it]) > CHANGE_THRESHOLD }
        if (!changed && now - lastSentAt < refreshMillis) return@withLock false
        send(colors.copyOf(), now)
        true
    }

    /** A static picture produces no frames, so the last one is repeated from here. */
    override suspend fun keepAlive() = mutex.withLock {
        val colors = lastColors ?: return@withLock
        val now = clock()
        if (now - lastSentAt >= refreshMillis) send(colors, now)
    }

    /** Sent twice: there is no acknowledgement, and a lost datagram would freeze the lamp for a minute. */
    override suspend fun release() = mutex.withLock {
        val off = GoveeLan.razerMode(false).encodeToByteArray()
        udp.sendPackets(listOf(off, off))
    }

    override fun close() = udp.close()

    private suspend fun send(colors: IntArray, now: Long) {
        val frame = GoveeLan.razerColors(colors, stretch).encodeToByteArray()
        if (now - lastArmedAt >= rearmMillis) {
            udp.sendPackets(listOf(GoveeLan.razerMode(true).encodeToByteArray(), frame))
            lastArmedAt = now
        } else {
            udp.sendPackets(listOf(frame))
        }
        lastColors = colors
        lastSentAt = now
    }

    private fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    companion object {
        /** Community reports: stable at 40 frames/s or more. */
        const val DEFAULT_INTERVAL_MS = 25L
        const val REFRESH_MS = 1_000L
        /** Well inside the lamp's one-minute timeout; re-arming too often is a suspected flicker source. */
        const val REARM_MS = 30_000L
        const val SETTLE_MS = 100L
        private const val CHANGE_THRESHOLD = 3
    }
}
