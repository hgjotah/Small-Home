package com.ia.smallhome.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ia.smallhome.model.ClimateEntity
import com.ia.smallhome.model.LightEntity
import com.ia.smallhome.model.PanelDefaults
import com.ia.smallhome.model.PanelPreferences
import com.ia.smallhome.model.PanelRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.smallHomeDataStore by preferencesDataStore(name = "small_home_settings")

class SettingsStore(private val context: Context, private val gson: Gson = Gson()) {
    val preferences: Flow<PanelPreferences> = context.smallHomeDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { values ->
            PanelPreferences(
                onboardingComplete = values[Keys.ONBOARDING] ?: false,
                chipId = values[Keys.CHIP_ID].orEmpty(),
                bleAddress = values[Keys.BLE_ADDRESS].orEmpty(),
                bleName = values[Keys.BLE_NAME].orEmpty(),
                deviceName = values[Keys.DEVICE_NAME] ?: "Small Home",
                protocol = values[Keys.PROTOCOL] ?: "2",
                allowedPackages = values[Keys.ALLOWED_PACKAGES] ?: emptySet(),
                observedPackages = values[Keys.OBSERVED_PACKAGES] ?: emptySet(),
                haBaseUrl = values[Keys.HA_URL].orEmpty(),
                lights = decodeLights(values[Keys.LIGHTS].orEmpty()),
                climate = decodeClimate(values[Keys.CLIMATE].orEmpty()),
                cmcId = values[Keys.CMC_ID] ?: PanelDefaults.CMC_ID,
                cmcSymbol = values[Keys.CMC_SYMBOL] ?: PanelDefaults.CMC_SYMBOL,
                fiat = values[Keys.FIAT] ?: "EUR",
                openRouterModel = values[Keys.OPENROUTER_MODEL].orEmpty(),
                hasHaToken = values[Keys.HAS_HA_TOKEN] ?: false,
                hasCmcKey = values[Keys.HAS_CMC_KEY] ?: false,
                brightness = (values[Keys.BRIGHTNESS] ?: 110L).toInt().coerceIn(0, 127),
                wifiSsid = values[Keys.WIFI_SSID].orEmpty(),
                wifiConfigured = values[Keys.WIFI_CONFIGURED] ?: false,
            )
        }

    suspend fun snapshot(): PanelPreferences = preferences.first()

    suspend fun migrateLegacyCryptoDefaultToXdag() = edit { values ->
        if (values[Keys.CMC_XDAG_MIGRATION] == true) return@edit
        val id = values[Keys.CMC_ID]
        val symbol = values[Keys.CMC_SYMBOL]
        if ((id == null || id == 1L) && (symbol == null || symbol.equals("BTC", ignoreCase = true))) {
            values[Keys.CMC_ID] = PanelDefaults.CMC_ID
            values[Keys.CMC_SYMBOL] = PanelDefaults.CMC_SYMBOL
        }
        values[Keys.CMC_XDAG_MIGRATION] = true
    }

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.ONBOARDING] = value }

    suspend fun savePairing(chipId: String, bleAddress: String, bleName: String, deviceName: String, protocol: String = "2") = edit {
        it[Keys.CHIP_ID] = chipId
        it[Keys.BLE_ADDRESS] = bleAddress
        it[Keys.BLE_NAME] = bleName
        it[Keys.DEVICE_NAME] = deviceName
        it[Keys.PROTOCOL] = protocol
    }

    suspend fun saveDeviceName(deviceName: String) = edit {
        it[Keys.DEVICE_NAME] = deviceName
    }

    suspend fun saveAllowedPackages(packages: Set<String>) = edit { it[Keys.ALLOWED_PACKAGES] = packages }

    suspend fun observePackage(packageName: String) = edit {
        it[Keys.OBSERVED_PACKAGES] = (it[Keys.OBSERVED_PACKAGES] ?: emptySet()) + packageName
    }

    suspend fun saveHomeAssistant(
        baseUrl: String,
        lights: List<LightEntity>,
        climate: ClimateEntity?,
        hasToken: Boolean,
    ) = edit {
        it[Keys.HA_URL] = baseUrl.trimEnd('/')
        it[Keys.LIGHTS] = gson.toJson(lights.filter { light -> PanelRules.validHomeAssistantEntity(light.id) }.distinctBy { light -> light.id }.take(10))
        val supportedClimate = climate?.takeIf { value -> PanelRules.validHomeAssistantEntity(value.id) }
        if (supportedClimate == null) it.remove(Keys.CLIMATE) else it[Keys.CLIMATE] = gson.toJson(supportedClimate)
        it[Keys.HAS_HA_TOKEN] = hasToken
    }

    suspend fun saveCrypto(id: Long, symbol: String, fiat: String, hasKey: Boolean) = edit {
        it[Keys.CMC_ID] = id
        it[Keys.CMC_SYMBOL] = symbol.uppercase()
        it[Keys.FIAT] = fiat.uppercase()
        it[Keys.HAS_CMC_KEY] = hasKey
    }

    suspend fun saveOpenRouterModel(model: String) = edit { it[Keys.OPENROUTER_MODEL] = model.trim() }

    suspend fun mergePanelConfig(
        chipId: String,
        deviceName: String,
        wifiSsid: String,
        wifiConfigured: Boolean,
        haBaseUrl: String,
        hasHaToken: Boolean,
        cmcId: Long,
        cmcSymbol: String,
        fiat: String,
        hasCmcKey: Boolean,
        brightness: Int,
        lights: List<LightEntity>,
        climate: ClimateEntity?,
    ) = edit {
        it[Keys.CHIP_ID] = chipId
        it[Keys.DEVICE_NAME] = deviceName
        it[Keys.WIFI_SSID] = wifiSsid
        it[Keys.WIFI_CONFIGURED] = wifiConfigured
        it[Keys.HA_URL] = haBaseUrl
        it[Keys.HAS_HA_TOKEN] = hasHaToken
        it[Keys.CMC_ID] = cmcId
        it[Keys.CMC_SYMBOL] = cmcSymbol
        it[Keys.FIAT] = fiat
        it[Keys.HAS_CMC_KEY] = hasCmcKey
        it[Keys.BRIGHTNESS] = brightness.coerceIn(0, 127).toLong()
        it[Keys.LIGHTS] = gson.toJson(lights.take(10))
        if (climate == null) it.remove(Keys.CLIMATE) else it[Keys.CLIMATE] = gson.toJson(climate)
    }

    suspend fun clearPairing() = edit {
        it.remove(Keys.CHIP_ID)
        it.remove(Keys.BLE_ADDRESS)
        it.remove(Keys.BLE_NAME)
        it.remove(Keys.PROTOCOL)
        it[Keys.DEVICE_NAME] = "Small Home"
    }

    suspend fun saveBrightness(value: Int) = edit {
        it[Keys.BRIGHTNESS] = value.coerceIn(0, 127).toLong()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.smallHomeDataStore.edit(block)
    }

    private fun decodeLights(json: String): List<LightEntity> = runCatching {
        if (json.isBlank()) emptyList() else gson.fromJson<List<LightEntity>>(
            json,
            object : TypeToken<List<LightEntity>>() {}.type,
        ).filter { PanelRules.validHomeAssistantEntity(it.id) }.distinctBy { it.id }.take(10)
    }.getOrDefault(emptyList())

    private fun decodeClimate(json: String): ClimateEntity? = runCatching {
        if (json.isBlank()) null else gson.fromJson(json, ClimateEntity::class.java)
            ?.takeIf { PanelRules.validHomeAssistantEntity(it.id) }
    }.getOrNull()

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val CHIP_ID = stringPreferencesKey("chip_id")
        val BLE_ADDRESS = stringPreferencesKey("ble_address")
        val BLE_NAME = stringPreferencesKey("ble_name")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PROTOCOL = stringPreferencesKey("protocol")
        val ALLOWED_PACKAGES = stringSetPreferencesKey("allowed_packages")
        val OBSERVED_PACKAGES = stringSetPreferencesKey("observed_packages")
        val HA_URL = stringPreferencesKey("ha_base_url")
        val LIGHTS = stringPreferencesKey("lights")
        val CLIMATE = stringPreferencesKey("climate")
        val CMC_ID = longPreferencesKey("cmc_id")
        val CMC_SYMBOL = stringPreferencesKey("cmc_symbol")
        val CMC_XDAG_MIGRATION = booleanPreferencesKey("cmc_xdag_migration_v1")
        val FIAT = stringPreferencesKey("fiat")
        val OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
        val HAS_HA_TOKEN = booleanPreferencesKey("has_ha_token")
        val HAS_CMC_KEY = booleanPreferencesKey("has_cmc_key")
        val BRIGHTNESS = longPreferencesKey("brightness")
        val WIFI_SSID = stringPreferencesKey("wifi_ssid")
        val WIFI_CONFIGURED = booleanPreferencesKey("wifi_configured")
    }
}
