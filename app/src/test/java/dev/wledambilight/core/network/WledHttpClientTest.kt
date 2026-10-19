package dev.wledambilight.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WledHttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(timeoutMs: Long = 2000): WledHttpClient {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return WledHttpClient(okHttp)
    }

    @Test
    fun `probe parses documented info fields and ignores unknown ones`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"name":"Desk","ver":"0.14.4","arch":"esp32",
                 "leds":{"count":300,"fps":42,"pwr":120},
                 "udpport":21324,"live":false,"wifi":{"signal":80},
                 "futureField":{"nested":[1,2,3]}}
                """.trimIndent(),
            ),
        )
        val probe = client().probe(server.hostName, server.port)
        assertEquals("Desk", probe.info.name)
        assertEquals("0.14.4", probe.info.ver)
        assertEquals(300, probe.info.leds?.count)
        assertEquals(21324, probe.info.udpport)
        assertFalse(probe.info.live ?: true)
        assertTrue(probe.latencyMs >= 0)
        assertEquals("/json/info", server.takeRequest().path)
    }

    @Test
    fun `state is parsed with segments`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"on":true,"bri":128,"seg":[{"id":0,"col":[[255,10,20]]}]}"""),
        )
        val state = client().state(server.hostName, server.port)
        assertEquals(true, state.on)
        assertEquals(128, state.bri)
        assertEquals(255, state.seg?.first()?.col?.first()?.first())
    }

    @Test
    fun `http errors are surfaced as BadStatus`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertThrows(WledHttpError.BadStatus::class.java) {
            runBlocking { client().probe(server.hostName, server.port) }
        }
    }

    @Test
    fun `invalid json is surfaced as InvalidPayload`() {
        server.enqueue(MockResponse().setBody("{oops}"))
        assertThrows(WledHttpError.InvalidPayload::class.java) {
            runBlocking { client().probe(server.hostName, server.port) }
        }
    }

    @Test
    fun `oversized responses are rejected`() {
        server.enqueue(MockResponse().setBody("x".repeat(300_000)))
        assertThrows(WledHttpError.PayloadTooLarge::class.java) {
            runBlocking { client().probe(server.hostName, server.port) }
        }
    }

    @Test
    fun `silent server becomes a timeout`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertThrows(WledHttpError.Timeout::class.java) {
            runBlocking { client(timeoutMs = 300).probe(server.hostName, server.port) }
        }
    }

    @Test
    fun `solid colour test sends a documented state payload`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client().sendSolidColor(server.hostName, server.port, 0xFF0A14FF.toInt(), brightness = 90)
        val request = server.takeRequest()
        assertEquals("/json/state", request.path)
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(true, body["on"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(90, body["bri"]?.jsonPrimitive?.content?.toInt())
        val color = body["seg"]!!.jsonArray[0].jsonObject["col"]!!.jsonArray[0].jsonArray
        assertEquals(10, color[0].jsonPrimitive.content.toInt())
        assertEquals(20, color[1].jsonPrimitive.content.toInt())
        assertEquals(255, color[2].jsonPrimitive.content.toInt())
    }

    @Test
    fun `unreachable host maps to Unreachable`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertThrows(WledHttpError.Unreachable::class.java) {
            runBlocking { client(timeoutMs = 500).probe("127.0.0.1", port) }
        }
    }

    @Test
    fun `probe response is not empty`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"name":"x"}"""))
        val probe = client().probe(server.hostName, server.port)
        assertNotNull(probe.info)
    }
}
