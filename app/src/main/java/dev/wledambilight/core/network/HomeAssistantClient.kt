package dev.wledambilight.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A Home Assistant `light.*` entity that can show RGB colours. */
data class HaLight(val entityId: String, val name: String)

class HaUnauthorized : WledHttpError("Home Assistant rejected the access token")

/**
 * Minimal Home Assistant REST client (https://developers.home-assistant.io/docs/api/rest/).
 *
 * Uses a long-lived access token as `Authorization: Bearer`. The token is passed per call, never
 * logged and never included in diagnostics or exported profiles.
 */
class HomeAssistantClient(
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = WledHttpClient.defaultJson

    /** `GET /api/` answers `{"message": "API running."}` when the URL and token are valid. */
    suspend fun check(host: String, port: Int, https: Boolean, token: String) {
        execute(request(host, port, https, "api/", token).get().build())
    }

    /** Colour-capable lights from `GET /api/states`, sorted by friendly name. */
    suspend fun colorLights(host: String, port: Int, https: Boolean, token: String): List<HaLight> {
        val body = execute(request(host, port, https, "api/states", token).get().build())
        val states = try {
            json.parseToJsonElement(body) as? JsonArray
        } catch (e: Exception) {
            throw WledHttpError.InvalidPayload(e)
        } ?: throw WledHttpError.InvalidPayload()
        return parseColorLights(states)
    }

    /**
     * `light.turn_on` with `rgb_color` + `brightness`, or `light.turn_off` when the colour is black.
     * [transitionSeconds] keeps lamps that interpolate on their own from stepping visibly.
     */
    suspend fun setColor(
        host: String,
        port: Int,
        https: Boolean,
        token: String,
        entityId: String,
        argb: Int,
        transitionSeconds: Float,
    ) {
        val (service, payload) = colorPayload(entityId, argb, transitionSeconds)
        val body = payload.toString().toRequestBody(JSON)
        execute(request(host, port, https, "api/services/light/$service", token).post(body).build())
    }

    private fun request(host: String, port: Int, https: Boolean, path: String, token: String): Request.Builder {
        val url = HttpUrl.Builder()
            .scheme(if (https) "https" else "http")
            .host(host)
            .port(port)
            .addPathSegments(path.trimStart('/'))
            .build()
        return Request.Builder().url(url).header("Authorization", "Bearer ${token.trim()}")
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val error = when (e) {
                    is SocketTimeoutException, is InterruptedIOException -> WledHttpError.Timeout(e)
                    else -> WledHttpError.Unreachable(e)
                }
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result ->
                    try {
                        if (result.code == 401 || result.code == 403) throw HaUnauthorized()
                        if (!result.isSuccessful) throw WledHttpError.BadStatus(result.code)
                        val source = result.body?.source() ?: throw WledHttpError.InvalidPayload()
                        val buffer = Buffer()
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer, 8192)
                            if (read == -1L) break
                            total += read
                            if (total > MAX_RESPONSE_BYTES) throw WledHttpError.PayloadTooLarge(total)
                        }
                        if (continuation.isActive) continuation.resume(buffer.readString(Charsets.UTF_8))
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            }
        })
    }

    companion object {
        /** `/api/states` lists every entity; large installations can be a few MB. */
        const val MAX_RESPONSE_BYTES = 4L * 1024L * 1024L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /** Pure, testable: keeps `light.*` entities whose `supported_color_modes` include a colour mode. */
        fun parseColorLights(states: JsonArray): List<HaLight> = states.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!id.startsWith("light.")) return@mapNotNull null
            val attributes = obj["attributes"] as? JsonObject ?: return@mapNotNull null
            val modes = (attributes["supported_color_modes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            if (modes.none { it in COLOR_MODES }) return@mapNotNull null
            val name = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull?.take(64) ?: id
            HaLight(id, name)
        }.sortedBy { it.name.lowercase() }

        /** Pure, testable: service name and JSON body for a colour update. */
        fun colorPayload(entityId: String, argb: Int, transitionSeconds: Float): Pair<String, JsonObject> {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val peak = maxOf(r, g, b)
            val transition = transitionSeconds.coerceIn(0f, 5f)
            if (peak == 0) {
                return "turn_off" to buildJsonObject {
                    put("entity_id", entityId)
                    put("transition", transition)
                }
            }
            // Full-saturation hue in rgb_color, intensity in brightness: HA lights expect that split.
            val scale = 255f / peak
            return "turn_on" to buildJsonObject {
                put("entity_id", entityId)
                putJsonArray("rgb_color") {
                    add((r * scale).toInt().coerceIn(0, 255))
                    add((g * scale).toInt().coerceIn(0, 255))
                    add((b * scale).toInt().coerceIn(0, 255))
                }
                put("brightness", peak.coerceIn(1, 255))
                put("transition", transition)
            }
        }
    }
}
