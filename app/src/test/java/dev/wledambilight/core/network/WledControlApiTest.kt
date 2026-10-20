package dev.wledambilight.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WledControlApiTest {

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

    private fun client() = WledHttpClient(
        OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(),
    )

    @Test
    fun `presets skip the placeholder and unnamed entries get a label`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"0":{},"3":{"n":"Movie","on":true},"1":{"n":"  "},"x":{"n":"bad"},"7":{"on":false}}""",
            ),
        )
        val presets = client().presets(server.hostName, server.port)
        assertEquals(listOf(1, 3, 7), presets.map { it.id })
        assertEquals("Preset 1", presets[0].name)
        assertEquals("Movie", presets[1].name)
        assertEquals("/presets.json", server.takeRequest().path)
    }

    @Test
    fun `effects are read from json eff`() = runBlocking {
        server.enqueue(MockResponse().setBody("""["Solid","Blink","Breathe"]"""))
        assertEquals(listOf("Solid", "Blink", "Breathe"), client().effects(server.hostName, server.port))
        assertEquals("/json/eff", server.takeRequest().path)
    }

    @Test
    fun `exit realtime posts live false`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        client().exitRealtime(server.hostName, server.port)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/json/state", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertFalse(body["live"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `preset and effect commands use documented fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        client().applyPreset(server.hostName, server.port, 3)
        client().applyEffect(server.hostName, server.port, 9)
        val preset = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(3, preset["ps"]!!.jsonPrimitive.int)
        val effect = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(9, effect["seg"]!!.jsonObject["fx"]!!.jsonPrimitive.int)
    }

    @Test
    fun `malformed presets are a typed error`() {
        server.enqueue(MockResponse().setBody("[1,2,3]"))
        assertThrows(WledHttpError.InvalidPayload::class.java) {
            runBlocking { client().presets(server.hostName, server.port) }
        }
    }
}
