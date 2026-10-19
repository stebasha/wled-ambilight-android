package dev.wledambilight.core.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import dev.wledambilight.core.imaging.VideoFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.Closeable

sealed interface FrameSourceEvent {
    data object StoppedBySystem : FrameSourceEvent
    data class Failed(val message: String?) : FrameSourceEvent
}

interface FrameSource : Closeable {
    val frames: Flow<VideoFrame>
    val events: Flow<FrameSourceEvent>

    fun start(): Result<Unit>

    /** Recreates the virtual display after a rotation without restarting the projection. */
    fun resize(width: Int, height: Int)
}

/**
 * Captures the default display through MediaProjection directly at the analysis resolution.
 *
 * The ImageReader and VirtualDisplay are deliberately small (e.g. 320x180), so the analyser never
 * scales a full-resolution bitmap and memory pressure stays flat. Frames are exposed as a
 * conflated flow: if the consumer is slower than the display, intermediate frames are dropped.
 */
class MediaProjectionFrameSource(
    private val context: Context,
    private val projection: MediaProjection,
    initialWidth: Int,
    initialHeight: Int,
) : FrameSource {

    private val _frames = MutableSharedFlow<VideoFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<VideoFrame> = _frames

    private val _events = MutableSharedFlow<FrameSourceEvent>(extraBufferCapacity = 4)
    override val events: Flow<FrameSourceEvent> = _events

    private val lock = Any()
    private var width = initialWidth
    private var height = initialHeight
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            _events.tryEmit(FrameSourceEvent.StoppedBySystem)
        }
    }

    override fun start(): Result<Unit> = runCatching {
        val captureThread = HandlerThread("ambilight-capture").apply { start() }
        val captureHandler = Handler(captureThread.looper)
        try {
            projection.registerCallback(projectionCallback, captureHandler)
            val imageReader = createReader(width, height, captureHandler)
            val display = projection.createVirtualDisplay(
                "wled-ambilight",
                width,
                height,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                captureHandler,
            ) ?: error("VirtualDisplay could not be created")
            synchronized(lock) {
                thread = captureThread
                handler = captureHandler
                reader = imageReader
                virtualDisplay = display
                started = true
            }
        } catch (error: Exception) {
            runCatching { projection.unregisterCallback(projectionCallback) }
            captureThread.quitSafely()
            throw error
        }
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val targetHandler = synchronized(lock) { handler } ?: return
        targetHandler.post {
            synchronized(lock) {
                if (!started || (width == this.width && height == this.height)) return@synchronized
                val previous = reader
                previous?.setOnImageAvailableListener(null, null)
                val next = try {
                    createReader(width, height, targetHandler)
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to resize capture", error)
                    previous?.setOnImageAvailableListener(::onImageAvailable, targetHandler)
                    return@synchronized
                }
                try {
                    virtualDisplay?.resize(width, height, context.resources.displayMetrics.densityDpi)
                    virtualDisplay?.surface = next.surface
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to resize virtual display", error)
                    next.close()
                    previous?.setOnImageAvailableListener(::onImageAvailable, targetHandler)
                    return@synchronized
                }
                previous?.close()
                reader = next
                this.width = width
                this.height = height
            }
        }
    }

    private fun createReader(width: Int, height: Int, handler: Handler): ImageReader {
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener(::onImageAvailable, handler)
        return imageReader
    }

    private fun onImageAvailable(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (error: IllegalStateException) {
            null
        } ?: return
        try {
            val frameWidth = image.width
            val frameHeight = image.height
            if (frameWidth <= 0 || frameHeight <= 0) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowBytes = frameWidth * pixelStride
            if (rowStride < rowBytes) return
            val bytes = ByteArray(rowStride * frameHeight)
            buffer.rewind()
            buffer.get(bytes, 0, minOf(bytes.size, buffer.remaining()))

            val pixels = IntArray(frameWidth * frameHeight)
            var out = 0
            for (row in 0 until frameHeight) {
                var offset = row * rowStride
                for (col in 0 until frameWidth) {
                    val red = bytes[offset].toInt() and 0xFF
                    val green = bytes[offset + 1].toInt() and 0xFF
                    val blue = bytes[offset + 2].toInt() and 0xFF
                    pixels[out++] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    offset += pixelStride
                }
            }
            _frames.tryEmit(
                VideoFrame(
                    width = frameWidth,
                    height = frameHeight,
                    argb = pixels,
                    timestampNanos = SystemClock.elapsedRealtimeNanos(),
                ),
            )
        } catch (error: Exception) {
            _events.tryEmit(FrameSourceEvent.Failed(error.message))
        } finally {
            image.close()
        }
    }

    override fun close() {
        val state = synchronized(lock) {
            if (!started) return
            started = false
            val snapshot = Triple(thread, reader, virtualDisplay)
            thread = null
            reader = null
            virtualDisplay = null
            handler = null
            snapshot
        }
        state.second?.setOnImageAvailableListener(null, null)
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { state.third?.release() }
        runCatching { state.second?.close() }
        runCatching { projection.stop() }
        state.first?.quitSafely()
    }

    companion object {
        private const val TAG = "AmbilightCapture"
    }
}
