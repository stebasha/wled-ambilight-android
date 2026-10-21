package dev.wledambilight.core.network

import dev.wledambilight.core.model.AmbilightConfig
import dev.wledambilight.core.model.DeviceKind
import dev.wledambilight.core.model.LightZone
import dev.wledambilight.core.model.ScreenRegion
import dev.wledambilight.core.model.WledDevice
import dev.wledambilight.core.model.ZoneMapping
import dev.wledambilight.engine.OutputPlanner
import dev.wledambilight.engine.PlanProblemKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeAssistantClientTest {

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

    private val states = """
        [
          {"entity_id":"light.desk","attributes":{"friendly_name":"Desk lamp","supported_color_modes":["xy","color_temp"]}},
          {"entity_id":"light.hall","attributes":{"friendly_name":"Hall","supported_color_modes":["onoff"]}},
          {"entity_id":"switch.tv","attributes":{"supported_color_modes":["rgb"]}},
          {"entity_id":"light.arch","attributes":{"friendly_name":"Arch","supported_color_modes":["rgbww"]}},
          {"broken":true}
        ]
    """.trimIndent()

    @Test
    fun `only colour capable lights are listed, sorted by name`() {
        val lights = HomeAssistantClient.parseColorLights(Json.parseToJsonElement(states).jsonArray)
        assertEquals(listOf("light.arch", "light.desk"), lights.map { it.entityId })
        assertEquals("Desk lamp", lights[1].name)
    }

    @Test
    fun `colour payload splits hue and brightness, black turns the light off`() {
        val (service, body) = HomeAssistantClient.colorPayload("light.desk", 0xFF402010.toInt(), 0.3f)
        assertEquals("turn_on", service)
        val rgb = body["rgb_color"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(255, 127, 63), rgb)
        assertEquals(0x40, body["brightness"]!!.jsonPrimitive.int)
        assertEquals(0.3f, body["transition"]!!.jsonPrimitive.float, 0.001f)

        val (off, offBody) = HomeAssistantClient.colorPayload("light.desk", 0xFF000000.toInt(), 0.3f)
        assertEquals("turn_off", off)
        assertEquals("light.desk", offBody["entity_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `requests carry the bearer token and use the services endpoint`() = runBlocking {
        server.enqueue(MockResponse().setBody(states))
        server.enqueue(MockResponse().setBody("[]"))
        val client = HomeAssistantClient()
        val lights = client.colorLights(server.hostName, server.port, https = false, token = " secret ")
        assertEquals(2, lights.size)
        val list = server.takeRequest()
        assertEquals("/api/states", list.path)
        assertEquals("Bearer secret", list.getHeader("Authorization"))

        client.setColor(server.hostName, server.port, false, "secret", "light.arch", 0xFFFF0000.toInt(), 0f)
        val call = server.takeRequest()
        assertEquals("/api/services/light/turn_on", call.path)
        val body = Json.parseToJsonElement(call.body.readUtf8()).jsonObject
        assertEquals("light.arch", body["entity_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `401 is reported as unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(HaUnauthorized::class.java) {
            runBlocking { HomeAssistantClient().check(server.hostName, server.port, false, "bad") }
        }
    }

    @Test
    fun `planner maps home assistant zones to entities`() {
        val ha = WledDevice(id = "ha", kind = DeviceKind.HOME_ASSISTANT, host = "10.0.0.5", httpPort = 8123)
        val ok = LightZone(id = "a", deviceId = "ha", mapping = ZoneMapping.SOLID, region = ScreenRegion.LEFT_HALF, entityId = "light.desk")
        val missing = LightZone(id = "b", deviceId = "ha", entityId = null)
        val plan = OutputPlanner.plan(AmbilightConfig(mainStripEnabled = false, zones = listOf(ok, missing)), null, listOf(ha))
        assertEquals("light.desk", plan.ha.single().entityId)
        assertEquals(ScreenRegion.LEFT_HALF, plan.ha.single().region)
        assertEquals(PlanProblemKind.ZONE_NO_ENTITY, plan.problems.single().kind)
        assertTrue(!plan.isEmpty)
    }
}
