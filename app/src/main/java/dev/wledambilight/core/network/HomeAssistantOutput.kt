package dev.wledambilight.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives one Home Assistant light from the pipeline without ever blocking it.
 *
 * HA lights (Zigbee, Wi-Fi bulbs, Hue through HA…) take tens to hundreds of milliseconds per
 * command and queue updates they cannot follow. The pipeline therefore only [offer]s the latest
 * colour into a conflated channel; a single worker sends at most one request every
 * [minIntervalMillis] and skips colours that barely changed. A failing light backs off on its own.
 */
class HomeAssistantOutput(
    private val client: HomeAssistantClient,
    private val host: String,
    private val port: Int,
    private val https: Boolean,
    private val token: String,
    val entityId: String,
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val transitionSeconds: Float = DEFAULT_TRANSITION_S,
    private val onError: (Throwable) -> Unit = {},
) {
    private val latest = Channel<Int>(Channel.CONFLATED)
    private var worker: Job? = null

    @Volatile var commandsSent: Long = 0
        private set

    @Volatile var failing: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        if (worker != null) return
        worker = scope.launch {
            var last: Int? = null
            var backoff = 0L
            for (color in latest) {
                if (last != null && distance(last, color) <= CHANGE_THRESHOLD) continue
                try {
                    client.setColor(host, port, https, token, entityId, color, transitionSeconds)
                    commandsSent++
                    last = color
                    failing = false
                    backoff = 0L
                    delay(minIntervalMillis)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failing = true
                    onError(error)
                    backoff = (if (backoff == 0L) 1_000L else backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    delay(backoff)
                }
            }
        }
    }

    /** Non-blocking: replaces any colour that has not been sent yet. */
    fun offer(argb: Int) {
        latest.trySend(argb)
    }

    fun close() {
        latest.close()
        worker?.cancel()
        worker = null
    }

    private fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    companion object {
        /** At most 4 updates per second: a conservative default, many HA bulbs are slower still. */
        const val DEFAULT_INTERVAL_MS = 250L
        const val DEFAULT_TRANSITION_S = 0.3f
        private const val MAX_BACKOFF_MS = 15_000L
        private const val CHANGE_THRESHOLD = 8
    }
}
