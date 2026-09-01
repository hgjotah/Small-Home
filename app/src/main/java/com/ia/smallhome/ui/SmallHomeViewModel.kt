package com.ia.smallhome.ui

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ia.smallhome.SmallHomeApplication
import com.ia.smallhome.model.BleDeviceCandidate
import com.ia.smallhome.model.ClimateEntity
import com.ia.smallhome.model.ConnectionPhase
import com.ia.smallhome.model.CryptoAsset
import com.ia.smallhome.model.HomeAssistantEntities
import com.ia.smallhome.model.InstalledApp
import com.ia.smallhome.model.LightEntity
import com.ia.smallhome.model.PanelPreferences
import com.ia.smallhome.model.PanelRules
import com.ia.smallhome.network.ApiResult
import com.ia.smallhome.network.ChatMessage
import com.ia.smallhome.security.SecureStore
import com.ia.smallhome.service.PanelConnectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen { Home, Notifications, HomeAssistant, Settings, Crypto, Ai, Provision }

data class OperationState(
    val loading: Boolean = false,
    val message: String = "",
    val success: Boolean? = null,
)

data class SecretStatus(
    val homeAssistant: Boolean = false,
    val coinMarketCap: Boolean = false,
    val openRouter: Boolean = false,
)

data class RuntimePermissionStatus(
    val bluetoothScan: Boolean = false,
    val bluetoothConnect: Boolean = false,
    val localNetwork: Boolean = false,
    val postNotifications: Boolean = false,
) {
    val bluetoothReady: Boolean get() = bluetoothScan && bluetoothConnect
}

class SmallHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmallHomeApplication
    private val container = app.container
    val preferences: StateFlow<PanelPreferences> = container.settingsStore.preferences.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PanelPreferences(),
    )
    val connection = container.connectionManager.connectionState
    val device = container.connectionManager.deviceState
    val discoveredDevices: StateFlow<List<BleDeviceCandidate>> = container.connectionManager.discoveredDevices
    val syncedNotifications = container.connectionManager.syncedNotifications

    private val _screen = MutableStateFlow(AppScreen.Home)
    val screen = _screen.asStateFlow()
    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps = _apps.asStateFlow()
    private val _notificationAccess = MutableStateFlow(false)
    val notificationAccess = _notificationAccess.asStateFlow()
    private val _haEntities = MutableStateFlow(HomeAssistantEntities())
    val haEntities = _haEntities.asStateFlow()
    private val _cryptoAssets = MutableStateFlow<List<CryptoAsset>>(emptyList())
    val cryptoAssets = _cryptoAssets.asStateFlow()
    private val _operation = MutableStateFlow(OperationState())
    val operation = _operation.asStateFlow()
    private val _secrets = MutableStateFlow(SecretStatus())
    val secrets = _secrets.asStateFlow()
    private val _runtimePermissions = MutableStateFlow(RuntimePermissionStatus())
    val runtimePermissions = _runtimePermissions.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages = _messages.asSharedFlow()

    init {
        refreshSecrets()
        refreshRuntimePermissions()
        refreshNotificationAccess()
        loadApps()
        viewModelScope.launch { container.connectionManager.events.collect { _messages.emit(it) } }
        viewModelScope.launch {
            container.settingsStore.migrateLegacyCryptoDefaultToXdag()
            val snapshot = container.settingsStore.snapshot()
            if (snapshot.bleAddress.isNotBlank() && _runtimePermissions.value.bluetoothReady) {
                PanelConnectionService.start(app)
            }
        }
    }

    fun navigate(screen: AppScreen) {
        _screen.value = screen
        _operation.value = OperationState()
        if (screen == AppScreen.Notifications) {
            refreshNotificationAccess()
            loadApps()
        }
    }

    fun finishOnboarding() {
        viewModelScope.launch { container.settingsStore.setOnboardingComplete(true) }
    }

    fun refreshNotificationAccess() {
        _notificationAccess.value = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    }

    fun onRuntimePermissionsResult(results: Map<String, Boolean>) {
        refreshRuntimePermissions()
        if (!_runtimePermissions.value.bluetoothReady) {
            _messages.tryEmit("Se necesitan los permisos Bluetooth para encontrar y conectar SmartPanel")
        }
        if (Build.VERSION.SDK_INT >= 37 && results["android.permission.ACCESS_LOCAL_NETWORK"] == false) {
            _messages.tryEmit("El permiso de red local solo es necesario para descubrir entidades de un Home Assistant local")
        }
    }

    fun refreshRuntimePermissions() {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
        val legacyScan = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        _runtimePermissions.value = RuntimePermissionStatus(
            bluetoothScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_SCAN) else legacyScan,
            bluetoothConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT),
            localNetwork = Build.VERSION.SDK_INT < 37 || granted("android.permission.ACCESS_LOCAL_NETWORK"),
            postNotifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val packages = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .toMutableSet()
            val snapshot = container.settingsStore.snapshot()
            packages += snapshot.observedPackages
            packages.remove(app.packageName)
            _apps.value = packages.mapNotNull { packageName ->
                runCatching {
                    val info = pm.getApplicationInfo(packageName, 0)
                    InstalledApp(packageName, pm.getApplicationLabel(info).toString(), packageName in snapshot.allowedPackages)
                }.getOrNull()
            }.sortedBy { it.label.lowercase() }
        }
    }

    fun toggleApp(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = container.settingsStore.snapshot().allowedPackages.toMutableSet()
            if (enabled) current += packageName else current -= packageName
            container.settingsStore.saveAllowedPackages(current)
            _apps.value = _apps.value.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it }
            container.connectionManager.syncNotifications()
        }
    }

    fun startBleScan() {
        refreshRuntimePermissions()
        if (!_runtimePermissions.value.bluetoothReady) {
            _messages.tryEmit("Concede los permisos Bluetooth antes de buscar SmartPanel")
            return
        }
        PanelConnectionService.start(app)
        container.connectionManager.scan()
    }

    fun connectBleDevice(address: String) {
        refreshRuntimePermissions()
        if (!_runtimePermissions.value.bluetoothReady) {
            _messages.tryEmit("Concede los permisos Bluetooth antes de conectar SmartPanel")
            return
        }
        PanelConnectionService.start(app)
        container.connectionManager.connect(address)
    }

    fun provision(homeSsid: String, homePassword: String, panelName: String) {
        viewModelScope.launch {
            if (connection.value.phase != ConnectionPhase.Connected) {
                _operation.value = OperationState(false, "Empareja y conecta SmartPanel por BLE primero", false)
                return@launch
            }
            _operation.value = OperationState(true, "Enviando el Wi‑Fi al SmartPanel por BLE…")
            when (val result = container.connectionManager.configureWifi(homeSsid, homePassword)) {
                is ApiResult.Failure -> _operation.value = OperationState(false, result.message, false)
                is ApiResult.Success -> {
                    container.settingsStore.saveDeviceName(panelName.trim().ifBlank { "Small Home" })
                    val configSaved = container.connectionManager.sendConfigUpdate()
                    _operation.value = OperationState(
                        false,
                        if (configSaved) "BLE conectado y Wi‑Fi del SmartPanel configurado" else "Wi‑Fi conectado, pero SmartPanel no confirmó el nombre",
                        configSaved,
                    )
                }
            }
        }
    }

    fun testHomeAssistant(baseUrl: String, tokenInput: String) {
        viewModelScope.launch {
            val token = tokenInput.ifBlank { container.secureStore.get(SecureStore.SecretKeyName.HOME_ASSISTANT_TOKEN).orEmpty() }
            _operation.value = OperationState(true, "Consultando entidades compatibles desde Android…")
            when (val result = container.homeAssistantClient.loadEntities(baseUrl, token)) {
                is ApiResult.Success -> {
                    _haEntities.value = result.value
                    _operation.value = OperationState(false, "${result.value.entities.size} entidades de Home Assistant cargadas", true)
                }
                is ApiResult.Failure -> _operation.value = OperationState(false, result.message, false)
            }
        }
    }

    fun testHomeAssistantFromPanel() {
        viewModelScope.launch {
            _operation.value = OperationState(true, "Probando Home Assistant desde el ESP32‑C6…")
            when (val result = container.connectionManager.testHomeAssistantFromPanel()) {
                is ApiResult.Success -> _operation.value = OperationState(false, result.value, true)
                is ApiResult.Failure -> _operation.value = OperationState(false, result.message, false)
            }
        }
    }

    fun testHomeAssistantEntity(entityId: String) {
        viewModelScope.launch {
            _operation.value = OperationState(true, "Leyendo $entityId desde SmartPanel…")
            when (val result = container.connectionManager.testHomeAssistantEntityFromPanel(entityId)) {
                is ApiResult.Success -> _operation.value = OperationState(false, result.value, true)
                is ApiResult.Failure -> _operation.value = OperationState(false, result.message, false)
            }
        }
    }

    fun saveHomeAssistant(baseUrl: String, tokenInput: String, lights: List<LightEntity>, climate: ClimateEntity?) {
        viewModelScope.launch {
            if (connection.value.phase != ConnectionPhase.Connected) {
                _operation.value = OperationState(false, "Conecta SmartPanel por BLE antes de guardar", false)
                return@launch
            }
            val cleanLights = lights.filter { PanelRules.validHomeAssistantEntity(it.id) }.distinctBy { it.id }.take(PanelRules.MAX_LIGHTS)
            val cleanClimate = climate?.takeIf { PanelRules.validHomeAssistantEntity(it.id) }
            if (cleanLights.size != lights.size || (climate != null && cleanClimate == null) || cleanClimate?.id in cleanLights.map { it.id }) {
                _operation.value = OperationState(false, "La selección de entidades contiene un entity_id inválido o duplicado", false)
                return@launch
            }
            val tokenChanged = tokenInput.isNotBlank()
            if (tokenChanged) container.secureStore.put(SecureStore.SecretKeyName.HOME_ASSISTANT_TOKEN, tokenInput)
            val hasToken = preferences.value.hasHaToken || container.secureStore.contains(SecureStore.SecretKeyName.HOME_ASSISTANT_TOKEN)
            container.settingsStore.saveHomeAssistant(baseUrl, cleanLights, cleanClimate, hasToken)
            _operation.value = OperationState(true, "Guardando y probando desde SmartPanel…")
            val saved = container.connectionManager.sendConfigUpdate(haToken = tokenInput.takeIf { tokenChanged })
            refreshSecrets()
            if (!saved) {
                _operation.value = OperationState(false, "SmartPanel no confirmó config_saved", false)
                return@launch
            }
            when (val test = container.connectionManager.testHomeAssistantFromPanel()) {
                is ApiResult.Success -> _operation.value = OperationState(false, "Guardado · ${test.value}", true)
                is ApiResult.Failure -> _operation.value = OperationState(false, "Guardado, pero la prueba desde el ESP falló: ${test.message}", false)
            }
        }
    }

    fun searchCrypto(query: String, keyInput: String) {
        viewModelScope.launch {
            val key = keyInput.ifBlank { container.secureStore.get(SecureStore.SecretKeyName.COINMARKETCAP_KEY).orEmpty() }
            _operation.value = OperationState(true, "Buscando activos…")
            when (val result = container.coinMarketCapClient.search(query, key)) {
                is ApiResult.Success -> {
                    _cryptoAssets.value = result.value
                    _operation.value = OperationState(false, if (result.value.isEmpty()) "No se encontraron activos" else "Selecciona un activo", true)
                }
                is ApiResult.Failure -> _operation.value = OperationState(false, result.message, false)
            }
        }
    }

    fun saveCrypto(keyInput: String, asset: CryptoAsset, fiat: String) {
        viewModelScope.launch {
            if (connection.value.phase != ConnectionPhase.Connected) {
                _operation.value = OperationState(false, "Conecta SmartPanel por BLE antes de guardar", false)
                return@launch
            }
            if (!PanelRules.validateCmc(asset.id, asset.symbol, fiat)) {
                _operation.value = OperationState(false, "La selección de criptomoneda no es válida", false)
                return@launch
            }
            val keyChanged = keyInput.isNotBlank()
            if (keyChanged) container.secureStore.put(SecureStore.SecretKeyName.COINMARKETCAP_KEY, keyInput)
            val hasKey = preferences.value.hasCmcKey || container.secureStore.contains(SecureStore.SecretKeyName.COINMARKETCAP_KEY)
            container.settingsStore.saveCrypto(asset.id, asset.symbol, fiat, hasKey)
            val saved = container.connectionManager.sendConfigUpdate(cmcApiKey = keyInput.takeIf { keyChanged })
            refreshSecrets()
            _operation.value = OperationState(false, if (saved) "Criptomoneda guardada en SmartPanel" else "SmartPanel no confirmó config_saved", saved)
        }
    }

    fun testOpenRouter(keyInput: String, model: String) {
        viewModelScope.launch {
            val key = keyInput.ifBlank { container.secureStore.get(SecureStore.SecretKeyName.OPENROUTER_KEY).orEmpty() }
            _operation.value = OperationState(true, "Probando el modelo…")
            when (val result = container.openRouterClient.complete(key, model.trim(), listOf(ChatMessage("user", "Hola")))) {
                is ApiResult.Success -> _operation.value = OperationState(false, "Modelo disponible y respuesta recibida", true)
                is ApiResult.Failure -> _operation.value = OperationState(false, result.message, false)
            }
        }
    }

    fun saveOpenRouter(keyInput: String, model: String) {
        viewModelScope.launch {
            if (keyInput.isNotBlank()) container.secureStore.put(SecureStore.SecretKeyName.OPENROUTER_KEY, keyInput)
            container.settingsStore.saveOpenRouterModel(model)
            refreshSecrets()
            _operation.value = OperationState(false, "OpenRouter guardado solo en este teléfono", true)
        }
    }

    fun saveBrightness(rawValue: Int) {
        viewModelScope.launch {
            container.settingsStore.saveBrightness(rawValue)
            val saved = container.connectionManager.sendConfigUpdate()
            _operation.value = OperationState(false, if (saved) "Brillo actualizado en SmartPanel" else "No se pudo actualizar el brillo", saved)
        }
    }

    fun factoryReset() {
        viewModelScope.launch {
            _operation.value = OperationState(true, "Restableciendo SmartPanel…")
            val reset = container.connectionManager.factoryReset()
            if (reset) {
                container.settingsStore.clearPairing()
                container.connectionManager.forgetLocalPairing()
            }
            _operation.value = OperationState(false, if (reset) "SmartPanel restablecido; puede conservar el bond de Android" else "SmartPanel no confirmó el restablecimiento", reset)
        }
    }

    fun rediscover() = startBleScan()
    fun reconnect() {
        refreshRuntimePermissions()
        if (!_runtimePermissions.value.bluetoothReady) {
            _messages.tryEmit("Concede los permisos Bluetooth antes de reconectar SmartPanel")
            return
        }
        PanelConnectionService.start(app)
        container.connectionManager.reconnect()
    }
    fun refreshPanelConfig() = container.connectionManager.requestConfig()
    fun refreshPanelStatus() = container.connectionManager.requestStatus()

    fun forgetPanel() {
        viewModelScope.launch {
            container.settingsStore.clearPairing()
            container.connectionManager.forgetLocalPairing()
            _operation.value = OperationState(false, "Panel olvidado en Small Home. Si quieres borrar también el bond, hazlo en Ajustes Bluetooth de Android.", true)
        }
    }

    fun copyDiagnostics() {
        val prefs = preferences.value
        val state = connection.value
        val panel = device.value
        val text = buildString {
            appendLine("Small Home · diagnóstico BLE")
            appendLine("Estado BLE: ${state.phase}")
            appendLine("Bonded: ${if (state.bonded) "sí" else "no"}")
            appendLine("Nombre BLE: ${state.bleName.ifBlank { prefs.bleName.ifBlank { "desconocido" } }}")
            appendLine("Chip ID: ${prefs.chipId.ifBlank { "sin emparejar" }}")
            appendLine("Protocolo: ${state.protocol.ifBlank { prefs.protocol }}")
            appendLine("Último heartbeat: ${state.lastHeartbeatEpochMs ?: 0}")
            appendLine("Wi‑Fi del ESP: ${if (panel.wifiConnected) "conectado" else "desconectado"}")
            appendLine("SSID del ESP: ${panel.wifiSsid.ifBlank { prefs.wifiSsid.ifBlank { "desconocido" } }}")
            appendLine("RSSI Wi‑Fi: ${panel.wifiRssi?.let { "$it dBm" } ?: "sin datos"}")
            appendLine("Notificaciones: ${panel.notificationCount}")
            appendLine("Home Assistant: ${if (panel.hasHa || prefs.hasHaToken) "configurado" else "sin configurar"}")
            appendLine("CoinMarketCap: ${if (panel.hasCmc || prefs.hasCmcKey) "configurado" else "sin configurar"}")
            appendLine("Brillo: ${panel.brightness}/127")
            appendLine("Flappy récord: ${panel.flappyHighScore}")
            appendLine("OpenRouter: ${if (secrets.value.openRouter && prefs.openRouterModel.isNotBlank()) "configurado" else "sin configurar"}")
        }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Diagnóstico Small Home", text))
        _messages.tryEmit("Diagnóstico copiado sin secretos ni contenido de avisos")
    }

    private fun refreshSecrets() {
        viewModelScope.launch(Dispatchers.IO) {
            _secrets.value = SecretStatus(
                homeAssistant = container.secureStore.contains(SecureStore.SecretKeyName.HOME_ASSISTANT_TOKEN),
                coinMarketCap = container.secureStore.contains(SecureStore.SecretKeyName.COINMARKETCAP_KEY),
                openRouter = container.secureStore.contains(SecureStore.SecretKeyName.OPENROUTER_KEY),
            )
        }
    }
}
