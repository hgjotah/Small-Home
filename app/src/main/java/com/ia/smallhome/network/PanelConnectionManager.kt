package com.ia.smallhome.network

import android.content.Context
import android.os.SystemClock
import com.ia.smallhome.ble.BleConnectionPolicy
import com.ia.smallhome.ble.SmartPanelBleManager
import com.ia.smallhome.data.SettingsStore
import com.ia.smallhome.model.ConnectionPhase
import com.ia.smallhome.model.DeviceState
import com.ia.smallhome.model.NotificationEvent
import com.ia.smallhome.model.PanelRules
import com.ia.smallhome.notifications.NotificationGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PanelConnectionManager(
    context: Context,
    private val settingsStore: SettingsStore,
    private val notificationGateway: NotificationGateway,
    private val aiSessionManager: AiSessionManager,
    private val bleManager: SmartPanelBleManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "1.0" }

    val connectionState = bleManager.state
    val discoveredDevices = bleManager.devices
    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()
    private val _syncedNotifications = MutableStateFlow(0)
    val syncedNotifications: StateFlow<Int> = _syncedNotifications.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile private var running = false
    @Volatile private var configSavedWaiter: CompletableDeferred<Boolean>? = null
    @Volatile private var wifiWaiter: CompletableDeferred<InboundMessage.WifiResult>? = null
    @Volatile private var haTestWaiter: CompletableDeferred<InboundMessage.HomeAssistantTestResult>? = null
    @Volatile private var entityTestWaiter: CompletableDeferred<InboundMessage.HomeAssistantEntityTestResult>? = null
    @Volatile private var factoryResetWaiter: CompletableDeferred<Boolean>? = null
    private var heartbeatJob: Job? = null
    private var handshakeTimeoutJob: Job? = null
    @Volatile private var lastHeartbeatAckElapsedMs = 0L

    init {
        scope.launch {
            notificationGateway.events.collect { event ->
                when (event) {
                    is NotificationEvent.Added -> send(PanelProtocol.notificationAdd(event.item))
                    is NotificationEvent.Removed -> send(PanelProtocol.notificationRemove(event.key))
                }
            }
        }
        scope.launch { bleManager.frames.collect(::routeFrame) }
        scope.launch { bleManager.ready.collect { beginHandshake() } }
        scope.launch { bleManager.transportEvents.collect { _events.emit(it) } }
    }

    fun start() {
        if (running) return
        running = true
        scope.launch {
            val preferences = settingsStore.snapshot()
            bleManager.start(preferences.bleAddress)
        }
    }

    suspend fun stop() {
        running = false
        heartbeatJob?.cancelAndJoin()
        heartbeatJob = null
        handshakeTimeoutJob?.cancelAndJoin()
        handshakeTimeoutJob = null
        completePendingOperations()
        bleManager.stop()
        aiSessionManager.clear()
    }

    fun stopInBackground() {
        scope.launch { stop() }
    }

    fun scan() {
        if (running) {
            bleManager.startScan()
        } else {
            running = true
            scope.launch {
                bleManager.start(settingsStore.snapshot().bleAddress)
                bleManager.startScan()
            }
        }
    }

    fun connect(address: String) {
        if (running) {
            bleManager.connect(address)
        } else {
            running = true
            scope.launch {
                bleManager.start("")
                bleManager.connect(address)
            }
        }
    }
    fun reconnect() {
        if (running) {
            bleManager.reconnect()
        } else {
            running = true
            scope.launch {
                val address = settingsStore.snapshot().bleAddress
                bleManager.start(address)
                if (address.isBlank()) bleManager.reconnect()
            }
        }
    }

    fun forgetLocalPairing() {
        bleManager.forgetLocalPairing()
    }

    fun requestConfig() {
        scope.launch { send(PanelProtocol.configGet()) }
    }

    fun requestStatus() {
        scope.launch { send(PanelProtocol.statusRequest()) }
    }

    suspend fun configureWifi(ssid: String, password: String): ApiResult<InboundMessage.WifiResult> {
        if (ssid.isBlank() || ssid.length > 32 || password.length > 64) {
            return ApiResult.Failure("El SSID o la contraseña no son válidos")
        }
        if (!connected()) return ApiResult.Failure("Conecta SmartPanel por BLE antes de enviar el Wi‑Fi")
        val waiter = CompletableDeferred<InboundMessage.WifiResult>()
        wifiWaiter = waiter
        if (!send(PanelProtocol.wifiConfig(ssid, password))) {
            wifiWaiter = null
            return ApiResult.Failure("No se pudo enviar la configuración Wi‑Fi por BLE")
        }
        val result = withTimeoutOrNull(16_000) { waiter.await() }
        wifiWaiter = null
        return when {
            result == null -> ApiResult.Failure("SmartPanel no respondió al configurar el Wi‑Fi")
            result.ok -> ApiResult.Success(result)
            else -> ApiResult.Failure(wifiErrorMessage(result.error))
        }
    }

    suspend fun syncNotifications(): Boolean {
        if (!connected()) return false
        val items = PanelRules.notificationResyncItems(notificationGateway.activeNotifications())
        if (!send(PanelProtocol.notificationClear())) return false
        for (item in items) {
            if (!send(PanelProtocol.notificationAdd(item))) return false
        }
        _syncedNotifications.value = items.size
        return true
    }

    suspend fun sendConfigUpdate(haToken: String? = null, cmcApiKey: String? = null): Boolean {
        if (!connected()) return false
        val waiter = CompletableDeferred<Boolean>()
        configSavedWaiter = waiter
        val preferences = settingsStore.snapshot()
        if (!send(PanelProtocol.configSet(preferences, haToken, cmcApiKey))) {
            configSavedWaiter = null
            return false
        }
        val saved = withTimeoutOrNull(8_000) { waiter.await() } == true
        configSavedWaiter = null
        return saved
    }

    suspend fun testHomeAssistantFromPanel(): ApiResult<String> {
        if (!connected()) return ApiResult.Failure("Conecta SmartPanel por BLE antes de probar Home Assistant")
        val waiter = CompletableDeferred<InboundMessage.HomeAssistantTestResult>()
        haTestWaiter = waiter
        if (!send(PanelProtocol.homeAssistantTest())) {
            haTestWaiter = null
            return ApiResult.Failure("No se pudo solicitar la prueba a SmartPanel")
        }
        val result = withTimeoutOrNull(12_000) { waiter.await() }
        haTestWaiter = null
        return when {
            result == null -> ApiResult.Failure("SmartPanel no respondió a la prueba de Home Assistant")
            result.ok -> ApiResult.Success(result.message.ifBlank { "Home Assistant accesible desde SmartPanel" })
            else -> ApiResult.Failure(result.message.ifBlank { "La prueba desde SmartPanel falló" })
        }
    }

    suspend fun testHomeAssistantEntityFromPanel(entityId: String): ApiResult<String> {
        if (!PanelRules.validHomeAssistantEntity(entityId)) return ApiResult.Failure("El entity_id de Home Assistant no es válido")
        if (!connected()) return ApiResult.Failure("Conecta SmartPanel por BLE antes de probar la entidad")
        val waiter = CompletableDeferred<InboundMessage.HomeAssistantEntityTestResult>()
        entityTestWaiter = waiter
        if (!send(PanelProtocol.homeAssistantEntityTest(entityId))) {
            entityTestWaiter = null
            return ApiResult.Failure("No se pudo solicitar la prueba de entidad")
        }
        val result = withTimeoutOrNull(10_000) { waiter.await() }
        entityTestWaiter = null
        return when {
            result == null -> ApiResult.Failure("SmartPanel no respondió a la prueba de entidad")
            result.ok -> ApiResult.Success("${result.entityId}: ${result.state}")
            else -> ApiResult.Failure(result.error.ifBlank { "No se pudo leer ${result.entityId}" })
        }
    }

    suspend fun factoryReset(): Boolean {
        if (!connected()) return false
        val waiter = CompletableDeferred<Boolean>()
        factoryResetWaiter = waiter
        if (!send(PanelProtocol.factoryReset())) {
            factoryResetWaiter = null
            return false
        }
        val reset = withTimeoutOrNull(5_000) { waiter.await() } == true
        factoryResetWaiter = null
        return reset
    }

    private suspend fun beginHandshake() {
        if (!running) return
        if (!send(PanelProtocol.hello(appVersion))) {
            bleManager.failAndDisconnect("No se pudo enviar el handshake BLE", retry = true)
            return
        }
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(10_000)
            if (connectionState.value.phase == ConnectionPhase.Handshaking) {
                bleManager.failAndDisconnect("SmartPanel no respondió al handshake v2", retry = true)
            }
        }
    }

    private suspend fun routeFrame(frame: String) {
        val message = PanelProtocol.parse(frame)
        if (message == null) {
            _events.emit("SmartPanel envió JSON inválido; la conexión continúa")
            return
        }
        when (message) {
            is InboundMessage.HelloAck -> handleHelloAck(message)
            is InboundMessage.HeartbeatAck -> {
                lastHeartbeatAckElapsedMs = SystemClock.elapsedRealtime()
                bleManager.recordHeartbeat(message.uptimeMs)
                _deviceState.value = _deviceState.value.copy(
                    wifiConnected = message.wifiConnected,
                    wifiIp = _deviceState.value.wifiIp.takeIf { message.wifiConnected }.orEmpty(),
                    wifiRssi = _deviceState.value.wifiRssi.takeIf { message.wifiConnected },
                )
            }
            is InboundMessage.Status -> handleStatus(message.state)
            is InboundMessage.WifiResult -> {
                if (message.ok) {
                    _deviceState.value = _deviceState.value.copy(
                        wifiConnected = true,
                        wifiIp = message.ip,
                        wifiRssi = message.rssi,
                    )
                } else {
                    _deviceState.value = _deviceState.value.copy(
                        wifiConnected = false,
                        wifiIp = "",
                        wifiRssi = null,
                    )
                }
                wifiWaiter?.complete(message)
            }
            is InboundMessage.ConfigState -> handleConfigState(message)
            is InboundMessage.ConfigSaved -> {
                configSavedWaiter?.complete(message.ok)
                _events.tryEmit(if (message.ok) "Configuración guardada en SmartPanel" else "SmartPanel no pudo guardar la configuración")
            }
            is InboundMessage.HomeAssistantTestResult -> haTestWaiter?.complete(message)
            is InboundMessage.HomeAssistantEntityTestResult -> entityTestWaiter?.complete(message)
            is InboundMessage.NotificationDismiss -> notificationGateway.dismiss(message.key)
            is InboundMessage.AiSessionStart -> aiSessionManager.start(message.sessionId)
            is InboundMessage.AiSessionEnd -> aiSessionManager.end(message.sessionId)
            is InboundMessage.AiRequest -> handleAiRequest(message)
            is InboundMessage.FactoryResetAck -> factoryResetWaiter?.complete(message.ok)
            is InboundMessage.Error -> _events.emit("SmartPanel informó del error: ${message.code}")
            is InboundMessage.Unknown -> Unit
        }
    }

    private suspend fun handleHelloAck(message: InboundMessage.HelloAck) {
        handshakeTimeoutJob?.cancel()
        if (message.protocol != PanelProtocol.VERSION) {
            bleManager.markHandshakeComplete(message.protocol, message.chipId, message.deviceName)
            return
        }
        if (message.chipId.isBlank()) {
            bleManager.failAndDisconnect("SmartPanel no proporcionó una identidad válida", retry = false)
            return
        }
        if (!message.manualEntityRoles) {
            bleManager.failAndDisconnect(
                "El firmware no admite la clasificación manual de entidades. Flashea SmartPanel_C6_BLE.ino incluido con esta versión.",
                retry = false,
            )
            return
        }
        val preferences = settingsStore.snapshot()
        if (preferences.chipId.isNotBlank() && preferences.chipId != message.chipId) {
            bleManager.failAndDisconnect(
                "Este no es el SmartPanel emparejado (${message.chipId}); se esperaba ${preferences.chipId}",
                retry = false,
            )
            return
        }
        val transport = connectionState.value
        settingsStore.savePairing(
            chipId = message.chipId,
            bleAddress = transport.bleAddress,
            bleName = transport.bleName,
            deviceName = message.deviceName,
            protocol = message.protocol.toString(),
        )
        _deviceState.value = _deviceState.value.copy(
            chipId = message.chipId,
            deviceName = message.deviceName,
            board = message.board,
            protocol = message.protocol.toString(),
            wifiConnected = message.wifiConnected,
        )
        bleManager.markHandshakeComplete(message.protocol, message.chipId, message.deviceName)
        send(PanelProtocol.timeSync(System.currentTimeMillis() / 1_000))
        send(PanelProtocol.statusRequest())
        send(PanelProtocol.configGet())
        syncNotifications()
        startHeartbeat()
    }

    private suspend fun handleStatus(state: DeviceState) {
        val expected = settingsStore.snapshot().chipId
        if (expected.isNotBlank() && state.chipId != expected) {
            bleManager.failAndDisconnect("La identidad BLE cambió durante la sesión", retry = false)
            return
        }
        _deviceState.value = state.copy(
            board = _deviceState.value.board,
            wifiSsid = _deviceState.value.wifiSsid,
            wifiConfigured = _deviceState.value.wifiConfigured,
            wifiIp = _deviceState.value.wifiIp,
        )
    }

    private suspend fun handleConfigState(message: InboundMessage.ConfigState) {
        if (message.protocol != PanelProtocol.VERSION) {
            bleManager.failAndDisconnect("config_state usa protocolo ${message.protocol}; se necesita 2", retry = false)
            return
        }
        settingsStore.mergePanelConfig(
            chipId = message.chipId,
            deviceName = message.deviceName,
            wifiSsid = message.wifiSsid,
            wifiConfigured = message.wifiConfigured,
            haBaseUrl = message.haBaseUrl,
            hasHaToken = message.hasHaToken,
            cmcId = message.cmcId,
            cmcSymbol = message.cmcSymbol,
            fiat = message.fiat,
            hasCmcKey = message.hasCmcKey,
            brightness = message.brightness,
            lights = message.lights,
            climate = message.climate,
        )
        _deviceState.value = _deviceState.value.copy(
            deviceName = message.deviceName,
            chipId = message.chipId,
            wifiSsid = message.wifiSsid,
            wifiConfigured = message.wifiConfigured,
            brightness = message.brightness,
        )
    }

    private suspend fun handleAiRequest(message: InboundMessage.AiRequest) {
        when (val response = aiSessionManager.ask(message.sessionId, message.prompt)) {
            is ApiResult.Success -> send(PanelProtocol.aiResponse(message.sessionId, message.requestId, response.value, ""))
            is ApiResult.Failure -> send(PanelProtocol.aiResponse(message.sessionId, message.requestId, "", response.message.take(180)))
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastHeartbeatAckElapsedMs = SystemClock.elapsedRealtime()
        heartbeatJob = scope.launch {
            while (running && connected()) {
                delay(BleConnectionPolicy.HEARTBEAT_INTERVAL_MS)
                if (BleConnectionPolicy.heartbeatExpired(SystemClock.elapsedRealtime(), lastHeartbeatAckElapsedMs)) {
                    bleManager.failAndDisconnect("SmartPanel dejó de responder a los heartbeat BLE", retry = true)
                    break
                }
                if (!send(PanelProtocol.heartbeat())) {
                    bleManager.failAndDisconnect("No se pudo enviar el heartbeat BLE", retry = true)
                    break
                }
            }
        }
    }

    private suspend fun send(text: String): Boolean = bleManager.sendJson(text)
    private fun connected(): Boolean = connectionState.value.phase == ConnectionPhase.Connected

    private fun wifiErrorMessage(code: String): String = when (code) {
        "invalid_credentials" -> "El SSID o la contraseña no cumplen los límites del SmartPanel"
        "wifi_connection_failed" -> "SmartPanel no pudo conectarse a esa red Wi‑Fi de 2,4 GHz"
        else -> "SmartPanel no pudo configurar el Wi‑Fi${code.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
    }

    private fun completePendingOperations() {
        configSavedWaiter?.complete(false)
        wifiWaiter?.cancel()
        haTestWaiter?.cancel()
        entityTestWaiter?.cancel()
        factoryResetWaiter?.complete(false)
        configSavedWaiter = null
        wifiWaiter = null
        haTestWaiter = null
        entityTestWaiter = null
        factoryResetWaiter = null
    }
}
