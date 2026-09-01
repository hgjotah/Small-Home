package com.ia.smallhome.model

data class LightEntity(val id: String, val name: String)

data class ClimateEntity(val id: String, val name: String)

data class HomeAssistantEntity(
    val id: String,
    val name: String,
    val domain: String,
    val state: String,
)

data class HomeAssistantEntities(
    val entities: List<HomeAssistantEntity> = emptyList(),
)

data class CryptoAsset(val id: Long, val name: String, val symbol: String)

data class PanelNotification(
    val key: String,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val time: String,
    val postedAt: Long,
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
)

enum class ConnectionPhase {
    BluetoothUnavailable,
    PermissionRequired,
    Idle,
    Scanning,
    Bonding,
    Connecting,
    DiscoveringServices,
    Subscribing,
    Handshaking,
    Connected,
    Reconnecting,
    Error,
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val message: String = "Añade tu panel para empezar",
    val bleAddress: String = "",
    val bleName: String = "",
    val bonded: Boolean = false,
    val protocol: String = "",
    val lastHeartbeatEpochMs: Long? = null,
    val lastUptimeMs: Long? = null,
)

data class BleDeviceCandidate(
    val address: String,
    val name: String,
    val rssi: Int,
    val bonded: Boolean,
)

data class DeviceState(
    val deviceName: String = "Small Home",
    val chipId: String = "",
    val board: String = "",
    val protocol: String = "2",
    val wifiConnected: Boolean = false,
    val wifiSsid: String = "",
    val wifiConfigured: Boolean = false,
    val wifiIp: String = "",
    val notificationCount: Int = 0,
    val unlocked: Boolean = false,
    val screen: Int = 0,
    val wifiRssi: Int? = null,
    val hasHa: Boolean = false,
    val hasCmc: Boolean = false,
    val brightness: Int = 110,
    val flappyHighScore: Int = 0,
)

object PanelDefaults {
    const val CMC_ID = 4424L
    const val CMC_SYMBOL = "XDAG"
}

data class PanelPreferences(
    val onboardingComplete: Boolean = false,
    val chipId: String = "",
    val bleAddress: String = "",
    val bleName: String = "",
    val deviceName: String = "Small Home",
    val protocol: String = "2",
    val allowedPackages: Set<String> = emptySet(),
    val observedPackages: Set<String> = emptySet(),
    val haBaseUrl: String = "",
    val lights: List<LightEntity> = emptyList(),
    val climate: ClimateEntity? = null,
    val cmcId: Long = PanelDefaults.CMC_ID,
    val cmcSymbol: String = PanelDefaults.CMC_SYMBOL,
    val fiat: String = "EUR",
    val openRouterModel: String = "",
    val hasHaToken: Boolean = false,
    val hasCmcKey: Boolean = false,
    val brightness: Int = 110,
    val wifiSsid: String = "",
    val wifiConfigured: Boolean = false,
)

sealed interface NotificationEvent {
    data class Added(val item: PanelNotification) : NotificationEvent
    data class Removed(val key: String, val packageName: String) : NotificationEvent
}

object PanelRules {
    const val MAX_LIGHTS = 10
    const val MAX_NOTIFICATIONS = 10
    const val MAX_AI_RESPONSE = 1800
    val SUPPORTED_FIAT = listOf("EUR", "USD")

    fun toggleLight(current: List<LightEntity>, light: LightEntity): List<LightEntity> {
        if (current.any { it.id == light.id }) return current.filterNot { it.id == light.id }
        require(current.size < MAX_LIGHTS) { "El firmware admite como máximo 10 luces" }
        return current + light
    }

    fun notificationSyncItems(items: List<PanelNotification>): List<PanelNotification> =
        items.sortedByDescending { it.postedAt }.distinctBy { it.key }.take(MAX_NOTIFICATIONS)

    fun notificationResyncItems(items: List<PanelNotification>): List<PanelNotification> =
        notificationSyncItems(items).sortedBy { it.postedAt }

    fun canForward(packageName: String, ownPackage: String, allowed: Set<String>): Boolean =
        packageName != ownPackage && packageName in allowed

    fun validateCmc(id: Long, symbol: String, fiat: String): Boolean =
        id > 0 && symbol.isNotBlank() && fiat.uppercase() in SUPPORTED_FIAT

    fun validHomeAssistantEntity(entityId: String): Boolean = ENTITY_ID.matches(entityId)

    fun conciseAiText(text: String): String {
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints <= MAX_AI_RESPONSE) return text
        val end = text.offsetByCodePoints(0, MAX_AI_RESPONSE - 3)
        return text.substring(0, end) + "..."
    }

    private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
}

class BackoffPolicy(
    private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000),
) {
    fun delayForAttempt(attempt: Int): Long = delaysMs[attempt.coerceIn(0, delaysMs.lastIndex)]

    companion object {
        const val MAX_DELAY_MS = 30_000L
    }
}
