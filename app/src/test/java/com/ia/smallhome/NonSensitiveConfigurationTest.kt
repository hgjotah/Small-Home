package com.ia.smallhome

import com.google.gson.Gson
import com.ia.smallhome.model.ClimateEntity
import com.ia.smallhome.model.LightEntity
import com.ia.smallhome.model.PanelPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NonSensitiveConfigurationTest {
    @Test
    fun `non-sensitive settings survive serialization without secret fields`() {
        val original = PanelPreferences(
            onboardingComplete = true,
            chipId = "ABC123",
            bleAddress = "AA:BB:CC:DD:EE:FF",
            bleName = "SmartPanel-C6-0123",
            allowedPackages = setOf("com.chat"),
            haBaseUrl = "http://ha:8123",
            lights = listOf(LightEntity("switch.salon", "Salón")),
            climate = ClimateEntity("water_heater.salon", "Termostato"),
            cmcId = 4424,
            cmcSymbol = "XDAG",
            fiat = "EUR",
            openRouterModel = "provider/model",
        )
        val json = Gson().toJson(original)
        val restored = Gson().fromJson(json, PanelPreferences::class.java)

        assertEquals(original.chipId, restored.chipId)
        assertEquals(original.lights, restored.lights)
        assertFalse(json.contains("device_secret"))
        assertFalse(json.contains("api_key"))
        assertFalse(json.contains("ha_token"))
        assertFalse(json.contains("wifi_password"))
    }
}
