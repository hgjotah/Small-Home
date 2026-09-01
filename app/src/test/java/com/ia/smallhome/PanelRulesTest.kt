package com.ia.smallhome

import com.ia.smallhome.model.BackoffPolicy
import com.ia.smallhome.model.LightEntity
import com.ia.smallhome.model.PanelDefaults
import com.ia.smallhome.model.PanelNotification
import com.ia.smallhome.model.PanelPreferences
import com.ia.smallhome.model.PanelRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRulesTest {
    @Test
    fun `fresh configuration defaults to XDAG`() {
        val preferences = PanelPreferences()
        assertEquals(PanelDefaults.CMC_ID, preferences.cmcId)
        assertEquals(PanelDefaults.CMC_SYMBOL, preferences.cmcSymbol)
    }

    @Test
    fun `light selection never exceeds firmware maximum`() {
        val ten = (1..10).map { LightEntity("light.$it", "Luz $it") }
        assertThrows(IllegalArgumentException::class.java) {
            PanelRules.toggleLight(ten, LightEntity("light.11", "Luz 11"))
        }
        assertEquals(9, PanelRules.toggleLight(ten, ten.first()).size)
    }

    @Test
    fun `notification sync keeps newest ten unique items`() {
        val items = (1..12).map { notification("key-$it", it.toLong()) } + notification("key-12", 99)
        val result = PanelRules.notificationSyncItems(items)
        assertEquals(10, result.size)
        assertEquals("key-12", result.first().key)
        assertEquals(10, result.map { it.key }.toSet().size)
    }

    @Test
    fun `notification reconnect sends selected items oldest to newest`() {
        val result = PanelRules.notificationResyncItems((1..12).map { notification("key-$it", it.toLong()) })
        assertEquals(10, result.size)
        assertEquals("key-3", result.first().key)
        assertEquals("key-12", result.last().key)
        assertTrue(result.zipWithNext().all { (a, b) -> a.postedAt <= b.postedAt })
    }

    @Test
    fun `notification filter excludes own and unselected packages`() {
        assertFalse(PanelRules.canForward("com.ia.smallhome", "com.ia.smallhome", setOf("com.ia.smallhome")))
        assertFalse(PanelRules.canForward("com.chat", "com.ia.smallhome", emptySet()))
        assertTrue(PanelRules.canForward("com.chat", "com.ia.smallhome", setOf("com.chat")))
    }

    @Test
    fun `coinmarketcap configuration validates id symbol and fiat`() {
        assertTrue(PanelRules.validateCmc(1, "BTC", "EUR"))
        assertTrue(PanelRules.validateCmc(1, "BTC", "USD"))
        assertFalse(PanelRules.validateCmc(1, "BTC", "GBP"))
        assertFalse(PanelRules.validateCmc(0, "BTC", "EUR"))
        assertFalse(PanelRules.validateCmc(1, "", "EUR"))
        assertFalse(PanelRules.validateCmc(1, "BTC", "JPY"))
    }

    @Test
    fun `connection backoff grows and caps at thirty seconds`() {
        val policy = BackoffPolicy()
        assertEquals(1_000, policy.delayForAttempt(0))
        assertEquals(8_000, policy.delayForAttempt(3))
        assertEquals(30_000, policy.delayForAttempt(99))
    }

    @Test
    fun `ai response is limited to firmware capacity`() {
        val concise = PanelRules.conciseAiText("x".repeat(2_000))
        assertEquals(1_800, concise.length)
        assertTrue(concise.endsWith("..."))
    }

    @Test
    fun `home assistant accepts every valid entity id for manual roles`() {
        assertTrue(PanelRules.validHomeAssistantEntity("light.salon"))
        assertTrue(PanelRules.validHomeAssistantEntity("climate.casa"))
        assertTrue(PanelRules.validHomeAssistantEntity("switch.salon"))
        assertTrue(PanelRules.validHomeAssistantEntity("input_boolean.visitas"))
        assertFalse(PanelRules.validHomeAssistantEntity("sin_dominio"))
        assertFalse(PanelRules.validHomeAssistantEntity("bad id.value"))
    }

    private fun notification(key: String, time: Long) = PanelNotification(
        key = key,
        packageName = "com.chat",
        app = "Chat",
        title = "Título",
        text = "Texto",
        time = "18:37",
        postedAt = time,
    )
}
