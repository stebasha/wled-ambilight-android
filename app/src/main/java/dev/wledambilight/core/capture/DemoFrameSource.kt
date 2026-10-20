package dev.wledambilight.core.capture

import dev.wledambilight.core.imaging.VideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic frame source for the demo mode: a slowly rotating colour wheel with a moving bright
 * spot. It needs no MediaProjection, so the whole pipeline (analysis, smoothing, preview and, if
 * configured, the real LEDs) can be tried without screen-capture consent or a video.
 */
class DemoFrameSource(
    private var width: Int = 192,
    private var height: Int = 108,
    private val fps: Int = 30,
) : FrameSource {

    private val _frames = MutableSharedFlow<VideoFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<VideoFrame> = _frames
    override val events: Flow<FrameSourceEvent> = emptyFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun start(): Result<Unit> = runCatching {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        job = newScope.launch {
            val started = System.nanoTime()
            val interval = 1000L / fps.coerceIn(1, 60)
            while (isActive) {
                val t = (System.nanoTime() - started) / 1_000_000_000.0
                _frames.tryEmit(render(width, height, t))
                delay(interval)
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            this.width = width
            this.height = height
        }
    }

    override fun close() {
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
    }

    companion object {
        /** Renders one demo frame at time [t] seconds. Pure; used by tests as well. */
        fun render(width: Int, height: Int, t: Double): VideoFrame {
            val pixels = IntArray(width * height)
            val cx = width / 2.0
            val cy = height / 2.0
            val spotX = cx + cos(t * 0.9) * width * 0.38
            val spotY = cy + sin(t * 1.3) * height * 0.34
            val spotRadius2 = (minOf(width, height) * 0.22).let { it * it }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val angle = kotlin.math.atan2(y - cy, x - cx)
                    val hue = ((angle / (2 * PI) + 0.5 + t * 0.06) % 1.0 + 1.0) % 1.0
                    var (r, g, b) = hsv(hue, 0.85, 0.75)
                    val dx = x - spotX
                    val dy = y - spotY
                    val d2 = dx * dx + dy * dy
                    if (d2 < spotRadius2) {
                        val k = 1.0 - d2 / spotRadius2
                        r += (1.0 - r) * k
                        g += (1.0 - g) * k
                        b += (1.0 - b) * k
                    }
                    pixels[y * width + x] = (0xFF shl 24) or
                        ((r * 255).toInt().coerceIn(0, 255) shl 16) or
                        ((g * 255).toInt().coerceIn(0, 255) shl 8) or
                        (b * 255).toInt().coerceIn(0, 255)
                }
            }
            return VideoFrame(width, height, pixels, System.nanoTime())
        }

        private fun hsv(h: Double, s: Double, v: Double): Triple<Double, Double, Double> {
            val i = (h * 6).toInt() % 6
            val f = h * 6 - (h * 6).toInt()
            val p = v * (1 - s)
            val q = v * (1 - f * s)
            val u = v * (1 - (1 - f) * s)
            return when (i) {
                0 -> Triple(v, u, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, u)
                3 -> Triple(p, q, v)
                4 -> Triple(u, p, v)
                else -> Triple(v, p, q)
            }
        }
    }
}
