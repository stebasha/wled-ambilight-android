package dev.wledambilight.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class WledHttpError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(cause: Throwable? = null) : WledHttpError("WLED HTTP request timed out", cause)
    class Unreachable(cause: Throwable? = null) : WledHttpError("WLED is unreachable", cause)
    class BadStatus(val statusCode: Int) : WledHttpError("WLED returned HTTP $statusCode")
    class InvalidPayload(cause: Throwable? = null) : WledHttpError("WLED returned an invalid payload", cause)
    class PayloadTooLarge(val bytes: Long) : WledHttpError("WLED response exceeded the size limit")
}

/**
 * Thin, defensive WLED HTTP JSON client. Only documented endpoints are used. Timeouts, response size
 * limits, cancellation and malformed JSON are handled explicitly so the UI can explain failures.
 */
class WledHttpClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson,
) {

    suspend fun probe(host: String, port: Int): WledProbe {
        val started = System.nanoTime()
        val body = get(host, port, "json/info")
        val info = decode<WledInfo>(body)
        return WledProbe(info = info, latencyMs = (System.nanoTime() - started) / 1_000_000L)
    }

    suspend fun state(host: String, port: Int): WledState = decode(get(host, port, "json/state"))

    suspend fun setPower(host: String, port: Int, on: Boolean): Unit =
        postJson(host, port, buildJsonObject { put("on", on) })

    suspend fun setBrightness(host: String, port: Int, brightness: Int): Unit =
        postJson(host, port, buildJsonObject { put("bri", brightness.coerceIn(1, 255)) })

    /** Turns the controller on and sets a solid colour on segment 0. Used by diagnostics. */
    suspend fun sendSolidColor(host: String, port: Int, argb: Int, brightness: Int = 96): Unit {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        val payload = buildJsonObject {
            put("on", true)
            put("bri", brightness.coerceIn(1, 255))
            putJsonArray("seg") {
                addJsonObject {
                    put("id", 0)
                    putJsonArray("col") {
                        addJsonArray {
                            add(red)
                            add(green)
                            add(blue)
                        }
                    }
                }
            }
        }
        postJson(host, port, payload)
    }

    suspend fun setState(host: String, port: Int, payload: JsonObject): Unit = postJson(host, port, payload)

    private suspend fun get(host: String, port: Int, path: String): String {
        val request = Request.Builder().url(url(host, port, path)).get().build()
        return execute(request)
    }

    private suspend fun postJson(host: String, port: Int, payload: JsonObject) {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url(host, port, "json/state")).post(body).build()
        execute(request)
    }

    private fun url(host: String, port: Int, path: String): HttpUrl =
        HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(port)
            .addPathSegments(path.trimStart('/'))
            .build()

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(mapFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result ->
                    try {
                        if (!result.isSuccessful) throw WledHttpError.BadStatus(result.code)
                        val body = result.body ?: throw WledHttpError.InvalidPayload()
                        val text = readLimited(body)
                        if (continuation.isActive) continuation.resume(text)
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            }
        })
    }

    private fun readLimited(body: ResponseBody): String {
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, 8192)
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw WledHttpError.PayloadTooLarge(total)
        }
        return buffer.readString(Charsets.UTF_8)
    }

    private fun mapFailure(error: IOException): WledHttpError = when (error) {
        is SocketTimeoutException, is InterruptedIOException -> WledHttpError.Timeout(error)
        is UnknownHostException, is ConnectException, is SSLException -> WledHttpError.Unreachable(error)
        else -> WledHttpError.Unreachable(error)
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw WledHttpError.InvalidPayload(e)
    } catch (e: IllegalArgumentException) {
        throw WledHttpError.InvalidPayload(e)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 256L * 1024L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            // Do not coerce unknown enum values: a damaged/future enum must be reported, not
            // silently replaced by a default (which would wipe user settings).
            coerceInputValues = false
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
