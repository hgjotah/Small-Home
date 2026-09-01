package com.ia.smallhome.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ia.smallhome.model.ClimateEntity
import com.ia.smallhome.model.DeviceState
import com.ia.smallhome.model.LightEntity
import com.ia.smallhome.model.PanelNotification
import com.ia.smallhome.model.PanelDefaults
import com.ia.smallhome.model.PanelPreferences
import com.ia.smallhome.model.PanelRules

sealed interface InboundMessage {
    data class HelloAck(
        val protocol: Int,
        val chipId: String,
        val deviceName: String,
        val board: String,
        val wifiConnected: Boolean,
        val manualEntityRoles: Boolean,
    ) : InboundMessage

    data class HeartbeatAck(val uptimeMs: Long, val wifiConnected: Boolean) : InboundMessage
    data class Status(val state: DeviceState) : InboundMessage
    data class WifiResult(val ok: Boolean, val ip: String, val rssi: Int?, val error: String) : InboundMessage
    data class ConfigState(
        val protocol: Int,
        val chipId: String,
        val deviceName: String,
        val wifiSsid: String,
        val wifiConfigured: Boolean,
        val haBaseUrl: String,
        val hasHaToken: Boolean,
        val cmcId: Long,
        val cmcSymbol: String,
        val fiat: String,
        val hasCmcKey: Boolean,
        val timezone: String,
        val brightness: Int,
        val climate: ClimateEntity?,
        val lights: List<LightEntity>,
    ) : InboundMessage

    data class ConfigSaved(val ok: Boolean) : InboundMessage
    data class HomeAssistantTestResult(val ok: Boolean, val message: String) : InboundMessage
    data class HomeAssistantEntityTestResult(
        val entityId: String,
        val ok: Boolean,
        val state: String,
        val error: String,
    ) : InboundMessage
    data class NotificationDismiss(val key: String) : InboundMessage
    data class AiSessionStart(val sessionId: String) : InboundMessage
    data class AiSessionEnd(val sessionId: String) : InboundMessage
    data class AiRequest(val sessionId: String, val requestId: Long, val prompt: String) : InboundMessage
    data class FactoryResetAck(val ok: Boolean) : InboundMessage
    data class Error(val code: String) : InboundMessage
    data class Unknown(val type: String) : InboundMessage
}

object PanelProtocol {
    const val VERSION = 2
    const val TIMEZONE_MADRID = "CET-1CEST,M3.5.0,M10.5.0/3"
    private val gson = Gson()

    fun parse(text: String): InboundMessage? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        when (val type = root.string("type")) {
            "hello_ack" -> InboundMessage.HelloAck(
                protocol = root.int("protocol"),
                chipId = root.string("chip_id"),
                deviceName = root.string("device_name", "SmartPanel C6"),
                board = root.string("board"),
                wifiConnected = root.boolean("wifi_connected"),
                manualEntityRoles = root.boolean("manual_entity_roles"),
            )
            "heartbeat_ack" -> InboundMessage.HeartbeatAck(
                uptimeMs = root.long("uptime_ms"),
                wifiConnected = root.boolean("wifi_connected"),
            )
            "status" -> InboundMessage.Status(
                DeviceState(
                    deviceName = root.string("device_name", "SmartPanel C6"),
                    chipId = root.string("chip_id"),
                    protocol = root.int("protocol", VERSION).toString(),
                    wifiConnected = root.boolean("wifi_connected"),
                    notificationCount = root.int("notification_count"),
                    unlocked = root.boolean("unlocked"),
                    screen = root.int("screen"),
                    wifiRssi = root.get("wifi_rssi")?.takeUnless { it.isJsonNull }?.asInt,
                    hasHa = root.boolean("has_ha"),
                    hasCmc = root.boolean("has_cmc"),
                    brightness = root.int("brightness", 110).coerceIn(0, 127),
                    flappyHighScore = root.int("flappy_high_score"),
                ),
            )
            "wifi_result" -> InboundMessage.WifiResult(
                ok = root.boolean("ok"),
                ip = root.string("ip"),
                rssi = root.get("rssi")?.takeUnless { it.isJsonNull }?.asInt,
                error = root.string("error"),
            )
            "config_state" -> parseConfigState(root)
            "config_saved" -> InboundMessage.ConfigSaved(root.boolean("ok"))
            "ha_test_result" -> InboundMessage.HomeAssistantTestResult(root.boolean("ok"), root.string("message"))
            "ha_entity_test_result" -> InboundMessage.HomeAssistantEntityTestResult(
                entityId = root.string("entity_id"),
                ok = root.boolean("ok"),
                state = root.string("state"),
                error = root.string("error"),
            )
            "notification_dismiss" -> InboundMessage.NotificationDismiss(root.string("key"))
            "ai_session_start" -> InboundMessage.AiSessionStart(root.string("session_id"))
            "ai_session_end" -> InboundMessage.AiSessionEnd(root.string("session_id"))
            "ai_request" -> InboundMessage.AiRequest(root.string("session_id"), root.long("request_id"), root.string("prompt"))
            "factory_reset_ack" -> InboundMessage.FactoryResetAck(root.boolean("ok"))
            "error" -> InboundMessage.Error(root.string("code"))
            else -> InboundMessage.Unknown(type)
        }
    }.getOrNull()

    fun hello(appVersion: String): String = json(
        "type" to "hello",
        "protocol" to VERSION,
        "app_version" to appVersion,
    )

    fun heartbeat(): String = json("type" to "heartbeat")
    fun timeSync(epochSeconds: Long): String = json("type" to "time_sync", "epoch" to epochSeconds)
    fun wifiConfig(ssid: String, password: String): String = json("type" to "wifi_config", "ssid" to ssid, "password" to password)
    fun configGet(): String = json("type" to "config_get")
    fun statusRequest(): String = json("type" to "status_request")
    fun homeAssistantTest(): String = json("type" to "ha_test")
    fun homeAssistantEntityTest(entityId: String): String = json("type" to "ha_entity_test", "entity_id" to entityId)
    fun notificationClear(): String = json("type" to "notification_clear")
    fun notificationAdd(item: PanelNotification): String = notificationObject("notification_add", item).toString()
    fun notificationRemove(key: String): String = json("type" to "notification_remove", "key" to key)
    fun factoryReset(): String = json("type" to "factory_reset", "confirm" to "ERASE")

    fun configSet(
        preferences: PanelPreferences,
        haToken: String? = null,
        cmcApiKey: String? = null,
    ): String = JsonObject().apply {
        addProperty("type", "config_set")
        addProperty("device_name", preferences.deviceName)
        addProperty("ha_base_url", preferences.haBaseUrl.trimEnd('/'))
        haToken?.takeIf { it.isNotBlank() }?.let { addProperty("ha_token", it) }
        cmcApiKey?.takeIf { it.isNotBlank() }?.let { addProperty("cmc_api_key", it) }
        addProperty("cmc_id", preferences.cmcId)
        addProperty("cmc_symbol", preferences.cmcSymbol)
        addProperty("fiat", preferences.fiat)
        addProperty("timezone", TIMEZONE_MADRID)
        addProperty("brightness", preferences.brightness.coerceIn(0, 127))
        addProperty("climate_id", preferences.climate?.id?.takeIf(PanelRules::validHomeAssistantEntity).orEmpty())
        addProperty("climate_name", preferences.climate?.name ?: "Termostato")
        add(
            "lights",
            gson.toJsonTree(preferences.lights.filter { PanelRules.validHomeAssistantEntity(it.id) }.distinctBy { it.id }.take(PanelRules.MAX_LIGHTS)),
        )
    }.toString()

    fun aiResponse(sessionId: String, requestId: Long, text: String, error: String): String = json(
        "type" to "ai_response",
        "session_id" to sessionId,
        "request_id" to requestId,
        "text" to text,
        "error" to error,
    )

    private fun parseConfigState(root: JsonObject): InboundMessage.ConfigState {
        val climateId = root.string("climate_id")
        return InboundMessage.ConfigState(
            protocol = root.int("protocol", VERSION),
            chipId = root.string("chip_id"),
            deviceName = root.string("device_name", "SmartPanel C6"),
            wifiSsid = root.string("wifi_ssid"),
            wifiConfigured = root.boolean("wifi_configured"),
            haBaseUrl = root.string("ha_base_url"),
            hasHaToken = root.boolean("has_ha_token"),
            cmcId = root.long("cmc_id", PanelDefaults.CMC_ID),
            cmcSymbol = root.string("cmc_symbol", PanelDefaults.CMC_SYMBOL),
            fiat = root.string("fiat", "EUR"),
            hasCmcKey = root.boolean("has_cmc_key"),
            timezone = root.string("timezone", TIMEZONE_MADRID),
            brightness = root.int("brightness", 110).coerceIn(0, 127),
            climate = climateId.takeIf(PanelRules::validHomeAssistantEntity)?.let {
                ClimateEntity(it, root.string("climate_name", "Termostato"))
            },
            lights = root.getAsJsonArray("lights")?.mapNotNull { element ->
                element.asJsonObject.let { light ->
                    light.string("id").takeIf(PanelRules::validHomeAssistantEntity)?.let { id ->
                        LightEntity(id, light.string("name", id))
                    }
                }
            }.orEmpty().distinctBy { it.id }.take(PanelRules.MAX_LIGHTS),
        )
    }

    private fun notificationObject(type: String, item: PanelNotification) = JsonObject().apply {
        addProperty("type", type)
        addProperty("key", item.key)
        addProperty("app", item.app)
        addProperty("title", item.title)
        addProperty("text", item.text)
        addProperty("time", item.time)
    }

    private fun json(vararg fields: Pair<String, Any>): String = JsonObject().apply {
        fields.forEach { (key, value) ->
            when (value) {
                is String -> addProperty(key, value)
                is Number -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
            }
        }
    }.toString()

    private fun JsonObject.string(name: String, default: String = ""): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString ?: default
    private fun JsonObject.long(name: String, default: Long = 0): Long =
        get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
    private fun JsonObject.int(name: String, default: Int = 0): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt ?: default
    private fun JsonObject.boolean(name: String, default: Boolean = false): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
}
