package com.ia.smallhome.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantCatalogTest {
    @Test
    fun `catalog keeps every valid Home Assistant domain`() {
        val result = parseHomeAssistantEntities(
            """
            [
              {"entity_id":"light.salon","state":"on","attributes":{"friendly_name":"Luz salón"}},
              {"entity_id":"switch.caldera","state":"off","attributes":{"friendly_name":"Caldera"}},
              {"entity_id":"sensor.temperatura","state":"21.4","attributes":{"friendly_name":"Temperatura"}},
              {"entity_id":"climate.casa","state":"heat","attributes":{"friendly_name":"Clima"}}
            ]
            """.trimIndent(),
        )

        assertEquals(4, result.entities.size)
        assertEquals(setOf("climate", "light", "sensor", "switch"), result.entities.map { it.domain }.toSet())
        assertEquals("on", result.entities.first { it.id == "light.salon" }.state)
    }

    @Test
    fun `catalog falls back to entity id when friendly name is missing`() {
        val result = parseHomeAssistantEntities(
            """[{"entity_id":"light.invitados","state":"on","attributes":{}}]""",
        )

        assertEquals("light.invitados", result.entities.single().name)
        assertEquals("light", result.entities.single().domain)
        assertTrue(result.entities.single().state.isNotBlank())
    }

    @Test
    fun `catalog ignores malformed entries without losing valid entities`() {
        val result = parseHomeAssistantEntities(
            """[null,{}, {"entity_id":"invalid","state":"x","attributes":[]}, {"entity_id":"input_boolean.visitas","state":"off","attributes":{"friendly_name":"Visitas"}}]""",
        )

        assertEquals(listOf("input_boolean.visitas"), result.entities.map { it.id })
    }
}
