package com.ia.smallhome

import com.google.gson.JsonParser
import com.ia.smallhome.model.ClimateEntity
import com.ia.smallhome.model.LightEntity
import com.ia.smallhome.model.PanelNotification
import com.ia.smallhome.model.PanelPreferences
import com.ia.smallhome.network.InboundMessage
import com.ia.smallhome.network.PanelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelProtocolTest {
    @Test
    fun `parses hello and status from firmware v2`() {
        val hello = PanelProtocol.parse(
            """{"type":"hello_ack","protocol":2,"chip_id":"ABC123","device_name":"SmartPanel C6","board":"Waveshare ESP32-C6-LCD-1.47","wifi_connected":true,"manual_entity_roles":true}""",
        ) as InboundMessage.HelloAck
        val status = PanelProtocol.parse(
            """{"type":"status","protocol":2,"chip_id":"ABC123","device_name":"Sala","wifi_connected":true,"wifi_rssi":-55,"notification_count":3,"unlocked":false,"screen":0,"has_ha":true,"has_cmc":true,"brightness":110,"flappy_high_score":12}""",
        ) as InboundMessage.Status

        assertEquals(2, hello.protocol)
        assertEquals("ABC123", hello.chipId)
        assertTrue(hello.wifiConnected)
        assertTrue(hello.manualEntityRoles)
        assertEquals(3, status.state.notificationCount)
        assertEquals(-55, status.state.wifiRssi)
        assertEquals(12, status.state.flappyHighScore)
    }

    @Test
    fun `parses manual roles for every valid domain and clamps light role`() {
        val lights = listOf("{\"id\":\"switch.lampara\",\"name\":\"Lámpara\"}") +
            (1..11).map { "{\"id\":\"light.$it\",\"name\":\"Luz $it\"}" }
        val parsed = PanelProtocol.parse(
            """{"type":"config_state","protocol":2,"chip_id":"ABC123","device_name":"Panel","wifi_ssid":"Casa","wifi_configured":true,"ha_base_url":"http://ha:8123","has_ha_token":true,"cmc_id":4424,"cmc_symbol":"XDAG","fiat":"EUR","has_cmc_key":true,"timezone":"${PanelProtocol.TIMEZONE_MADRID}","brightness":110,"climate_id":"water_heater.salon","climate_name":"Termostato","lights":[${lights.joinToString(",")}]}""",
        ) as InboundMessage.ConfigState

        assertEquals(10, parsed.lights.size)
        assertTrue(parsed.lights.any { it.id == "switch.lampara" })
        assertEquals(ClimateEntity("water_heater.salon", "Termostato"), parsed.climate)
        assertEquals(4424L, parsed.cmcId)
        assertEquals("XDAG", parsed.cmcSymbol)
        assertTrue(parsed.hasHaToken)
        assertEquals("Casa", parsed.wifiSsid)
    }

    @Test
    fun `config set omits unchanged secrets and keeps manually classified domains`() {
        val preferences = PanelPreferences(
            deviceName = "Small Home",
            haBaseUrl = "http://ha:8123",
            lights = listOf(
                LightEntity("light.salon", "Lámpara de lectura"),
                LightEntity("switch.lampara", "Lámpara auxiliar"),
                LightEntity("sin_dominio", "No enviar"),
            ),
            cmcId = 4424,
            cmcSymbol = "XDAG",
            climate = ClimateEntity("water_heater.consigna", "Calefacción"),
            brightness = 109,
        )
        val root = JsonParser.parseString(PanelProtocol.configSet(preferences)).asJsonObject

        assertEquals("config_set", root.get("type").asString)
        assertFalse(root.has("ha_token"))
        assertFalse(root.has("cmc_api_key"))
        assertEquals(109, root.get("brightness").asInt)
        assertEquals("water_heater.consigna", root.get("climate_id").asString)
        assertEquals(2, root.getAsJsonArray("lights").size())
        assertEquals("Lámpara de lectura", root.getAsJsonArray("lights")[0].asJsonObject.get("name").asString)
        assertEquals("switch.lampara", root.getAsJsonArray("lights")[1].asJsonObject.get("id").asString)
    }

    @Test
    fun `notification dismiss and ai session messages route by type`() {
        assertEquals("key-1", (PanelProtocol.parse("""{"type":"notification_dismiss","key":"key-1"}""") as InboundMessage.NotificationDismiss).key)
        assertEquals("s1", (PanelProtocol.parse("""{"type":"ai_session_start","session_id":"s1"}""") as InboundMessage.AiSessionStart).sessionId)
        val request = PanelProtocol.parse("""{"type":"ai_request","session_id":"s1","request_id":17,"prompt":"Hola"}""") as InboundMessage.AiRequest
        assertEquals(17, request.requestId)
        assertEquals("Hola", request.prompt)
        assertEquals("s1", (PanelProtocol.parse("""{"type":"ai_session_end","session_id":"s1"}""") as InboundMessage.AiSessionEnd).sessionId)
    }

    @Test
    fun `outbound payloads match firmware fields`() {
        val hello = JsonParser.parseString(PanelProtocol.hello("1.0")).asJsonObject
        assertEquals(2, hello.get("protocol").asInt)
        assertEquals("heartbeat", JsonParser.parseString(PanelProtocol.heartbeat()).asJsonObject.get("type").asString)
        assertEquals("config_get", JsonParser.parseString(PanelProtocol.configGet()).asJsonObject.get("type").asString)
        assertEquals("notification_clear", JsonParser.parseString(PanelProtocol.notificationClear()).asJsonObject.get("type").asString)

        val item = PanelNotification("key", "pkg", "WhatsApp", "Carlos", "Hola", "18:37", 10)
        val notification = JsonParser.parseString(PanelProtocol.notificationAdd(item)).asJsonObject
        assertEquals("18:37", notification.get("time").asString)

        val ai = JsonParser.parseString(PanelProtocol.aiResponse("ABC-1", 17, "Respuesta", "")).asJsonObject
        assertEquals(17, ai.get("request_id").asLong)
        assertEquals("", ai.get("error").asString)
    }

    @Test
    fun `invalid json is ignored`() {
        assertNull(PanelProtocol.parse("not-json"))
    }

    @Test
    fun `old firmware does not claim manual entity role capability`() {
        val hello = PanelProtocol.parse(
            """{"type":"hello_ack","protocol":2,"chip_id":"OLD","device_name":"SmartPanel C6","wifi_connected":true}""",
        ) as InboundMessage.HelloAck

        assertFalse(hello.manualEntityRoles)
    }
}
