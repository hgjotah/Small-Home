package com.ia.smallhome.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.ia.smallhome.model.BackoffPolicy
import com.ia.smallhome.model.BleDeviceCandidate
import com.ia.smallhome.model.ConnectionPhase
import com.ia.smallhome.model.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

@SuppressLint("MissingPermission")
class SmartPanelBleManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decoder = BleStreamDecoder()
    private val sendMutex = Mutex()
    private val backoff = BackoffPolicy()

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<BleDeviceCandidate>>(emptyList())
    val devices: StateFlow<List<BleDeviceCandidate>> = _devices.asStateFlow()
    private val _frames = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val frames: SharedFlow<String> = _frames.asSharedFlow()
    private val _ready = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val ready: SharedFlow<Unit> = _ready.asSharedFlow()
    private val _transportEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val transportEvents: SharedFlow<String> = _transportEvents.asSharedFlow()

    @Volatile private var running = false
    @Volatile private var targetAddress = ""
    @Volatile private var currentGatt: BluetoothGatt? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesDiscoveryStarted = false
    @Volatile private var handshakeReady = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var discoveryJob: Job? = null
    private var stageTimeoutJob: Job? = null
    private var receiverRegistered = false
    private val discovered = linkedMapOf<String, BleDeviceCandidate>()

    fun start(savedAddress: String) {
        running = true
        targetAddress = savedAddress
        registerReceiver()
        when {
            adapter == null -> transition(BleStateEvent.Failed("Este dispositivo Android no dispone de Bluetooth LE"))
            !hasRequiredPermissions() -> transition(BleStateEvent.PermissionMissing)
            adapter?.isEnabled != true -> transition(BleStateEvent.BluetoothOff)
            savedAddress.isBlank() -> transition(BleStateEvent.Idle)
            else -> connect(savedAddress)
        }
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        discoveryJob?.cancel()
        stageTimeoutJob?.cancel()
        stopScanInternal()
        closeGatt()
        unregisterReceiver()
        transition(BleStateEvent.Idle)
    }

    fun startScan() {
        if (!prepareBluetooth()) return
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        stopScanInternal()
        synchronized(discovered) {
            discovered.clear()
            _devices.value = emptyList()
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            transition(BleStateEvent.Failed("Android no pudo iniciar el escáner BLE"))
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { transition(BleStateEvent.Failed("No se pudo iniciar la búsqueda BLE")) }
            .onSuccess {
                transition(BleStateEvent.ScanStarted)
                scanTimeoutJob = scope.launch {
                    delay(SCAN_DURATION_MS)
                    stopScanInternal()
                    if (_state.value.phase == ConnectionPhase.Scanning) {
                        if (running && targetAddress.isNotBlank()) {
                            scheduleScanRetry()
                        } else {
                            _state.value = _state.value.copy(
                                phase = ConnectionPhase.Idle,
                                message = if (_devices.value.isEmpty()) "No se encontró ningún SmartPanel BLE" else "Selecciona el SmartPanel que muestra tu pantalla",
                            )
                        }
                    }
                }
            }
    }

    fun connect(address: String) {
        if (!prepareBluetooth()) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            transition(BleStateEvent.Failed("La dirección BLE guardada ya no es válida; vuelve a buscar el panel"))
            return
        }
        if (
            _state.value.phase == ConnectionPhase.Bonding &&
            _state.value.bleAddress.equals(address, ignoreCase = true) &&
            device.bondState == BluetoothDevice.BOND_BONDING
        ) return
        if (BleConnectionPolicy.shouldIgnoreConnect(
                phase = _state.value.phase,
                currentAddress = _state.value.bleAddress,
                requestedAddress = address,
                hasGatt = currentGatt != null,
            )
        ) return
        targetAddress = address
        reconnectJob?.cancel()
        reconnectJob = null
        stopScanInternal()
        closeGatt()
        val name = safeName(device)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> connectGatt(device, name)
            BluetoothDevice.BOND_BONDING -> {
                transition(BleStateEvent.BondStarted(address, name))
                armStageTimeout(BleConnectionPolicy.BOND_TIMEOUT_MS, "El emparejamiento BLE no terminó; vuelve a intentarlo")
            }
            else -> {
                transition(BleStateEvent.BondStarted(address, name))
                if (!runCatching { device.createBond() }.getOrDefault(false)) {
                    transition(BleStateEvent.Failed("Android no pudo iniciar el emparejamiento BLE"))
                } else {
                    armStageTimeout(BleConnectionPolicy.BOND_TIMEOUT_MS, "El emparejamiento BLE no terminó; vuelve a intentarlo")
                }
            }
        }
    }

    fun reconnect() {
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        if (targetAddress.isBlank()) startScan() else connect(targetAddress)
    }

    fun forgetLocalPairing() {
        targetAddress = ""
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        transition(BleStateEvent.Idle)
    }

    fun markHandshakeComplete(protocol: Int, chipId: String, deviceName: String) {
        val next = BleConnectionReducer.reduce(
            _state.value,
            BleStateEvent.HandshakeAccepted(protocol, chipId, deviceName),
        )
        _state.value = next
        if (next.phase == ConnectionPhase.Connected) {
            reconnectAttempt = 0
            stageTimeoutJob?.cancel()
            runCatching { currentGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
        } else {
            failAndDisconnect(next.message, retry = false)
        }
    }

    fun recordHeartbeat(uptimeMs: Long) {
        _state.value = _state.value.copy(
            lastHeartbeatEpochMs = System.currentTimeMillis(),
            lastUptimeMs = uptimeMs,
        )
    }

    fun failAndDisconnect(message: String, retry: Boolean) {
        transition(BleStateEvent.Failed(message))
        val address = targetAddress
        closeGatt()
        if (retry && running && address.isNotBlank()) scheduleReconnect()
    }

    suspend fun sendJson(json: String): Boolean = sendMutex.withLock {
        if (!handshakeReady || currentGatt == null || rxCharacteristic == null) return@withLock false
        for (chunk in BleProtocolCodec.encode(json)) {
            if (!writeChunk(chunk)) {
                _transportEvents.tryEmit("Falló una escritura BLE; reiniciando el enlace")
                failAndDisconnect("La escritura GATT dejó de responder", retry = true)
                return@withLock false
            }
        }
        true
    }

    fun hasRequiredPermissions(): Boolean = hasScanPermission() && hasConnectPermission()

    private fun prepareBluetooth(): Boolean = when {
        adapter == null -> {
            transition(BleStateEvent.Failed("Este dispositivo Android no dispone de Bluetooth LE"))
            false
        }
        !hasRequiredPermissions() -> {
            transition(BleStateEvent.PermissionMissing)
            false
        }
        adapter?.isEnabled != true -> {
            transition(BleStateEvent.BluetoothOff)
            false
        }
        else -> true
    }

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = recordResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::recordResult)
        }

        override fun onScanFailed(errorCode: Int) {
            transition(BleStateEvent.Failed("La búsqueda BLE falló (código $errorCode)"))
            if (running && targetAddress.isNotBlank()) scheduleScanRetry()
        }
    }

    private fun recordResult(result: ScanResult) {
        val device = result.device ?: return
        val candidate = BleDeviceCandidate(
            address = device.address,
            name = result.scanRecord?.deviceName ?: safeName(device),
            rssi = result.rssi,
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
        )
        synchronized(discovered) {
            discovered[candidate.address] = candidate
            _devices.value = discovered.values.sortedWith(compareByDescending<BleDeviceCandidate> { it.bonded }.thenByDescending { it.rssi })
        }
        if (targetAddress.isNotBlank() && candidate.address == targetAddress) connect(candidate.address)
    }

    private fun stopScanInternal() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (hasScanPermission()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    @Synchronized
    private fun connectGatt(device: BluetoothDevice, name: String) {
        if (currentGatt != null) return
        stageTimeoutJob?.cancel()
        transition(BleStateEvent.ConnectStarted(device.address, name, device.bondState == BluetoothDevice.BOND_BONDED))
        servicesDiscoveryStarted = false
        handshakeReady = false
        decoder.reset()
        currentGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (currentGatt == null) {
            failAndDisconnect("Android no pudo crear la conexión GATT", retry = true)
            return
        }
        armStageTimeout(BleConnectionPolicy.CONNECT_TIMEOUT_MS, "SmartPanel no respondió al iniciar la conexión GATT")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== currentGatt) {
                gatt.close()
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattDisconnect(gatt, status)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    stageTimeoutJob?.cancel()
                    transition(BleStateEvent.ServicesDiscoveryStarted)
                    servicesDiscoveryStarted = false
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    discoveryJob?.cancel()
                    discoveryJob = scope.launch {
                        delay(BleConnectionPolicy.DISCOVERY_DELAY_MS)
                        if (gatt === currentGatt) {
                            startServiceDiscovery(gatt)
                            armStageTimeout(
                                BleConnectionPolicy.DISCOVERY_TIMEOUT_MS,
                                "SmartPanel no completó el descubrimiento de servicios BLE",
                            )
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleGattDisconnect(gatt, status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== currentGatt) return
            stageTimeoutJob?.cancel()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndDisconnect("No se pudieron descubrir los servicios BLE", retry = true)
                return
            }
            val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: run {
                failAndDisconnect("El dispositivo no ofrece el servicio SmartPanel v2", retry = false)
                return
            }
            val rx = service.getCharacteristic(RX_UUID)
            val tx = service.getCharacteristic(TX_UUID)
            if (rx == null || tx == null) {
                failAndDisconnect("Faltan características BLE del protocolo SmartPanel v2", retry = false)
                return
            }
            rxCharacteristic = rx
            transition(BleStateEvent.SubscriptionStarted)
            if (!gatt.setCharacteristicNotification(tx, true)) {
                failAndDisconnect("No se pudieron activar las notificaciones BLE", retry = true)
                return
            }
            val descriptor = tx.getDescriptor(CCCD_UUID) ?: run {
                failAndDisconnect("SmartPanel no expone el descriptor de notificaciones", retry = false)
                return
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            if (!started) {
                failAndDisconnect("No se pudo suscribir a TX del SmartPanel", retry = true)
            } else {
                armStageTimeout(
                    BleConnectionPolicy.SUBSCRIPTION_TIMEOUT_MS,
                    "SmartPanel no confirmó la suscripción a notificaciones BLE",
                )
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== currentGatt || descriptor.uuid != CCCD_UUID) return
            stageTimeoutJob?.cancel()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handshakeReady = true
                transition(BleStateEvent.HandshakeStarted)
                _ready.tryEmit(Unit)
            } else {
                failAndDisconnect("SmartPanel rechazó la suscripción BLE", retry = true)
            }
        }

        @Deprecated("Compatibility callback for Android 12 and older")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt === currentGatt && characteristic.uuid == TX_UUID && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                acceptNotification(value)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (gatt === currentGatt && characteristic.uuid == TX_UUID) acceptNotification(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== currentGatt || characteristic.uuid != RX_UUID) return
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingWrite = null
        }
    }

    @Synchronized
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (servicesDiscoveryStarted || gatt !== currentGatt) return
        servicesDiscoveryStarted = true
        if (!gatt.discoverServices()) failAndDisconnect("No se pudo iniciar el descubrimiento GATT", retry = true)
    }

    private fun acceptNotification(value: ByteArray) {
        val result = decoder.append(value)
        result.frames.forEach { _frames.tryEmit(it) }
        if (result.discardedFrame) _transportEvents.tryEmit("Se descartó una trama BLE inválida o demasiado grande")
    }

    private suspend fun writeChunk(value: ByteArray): Boolean {
        val gatt = currentGatt ?: return false
        val characteristic = rxCharacteristic ?: return false
        val completion = CompletableDeferred<Boolean>()
        pendingWrite = completion
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            pendingWrite = null
            return false
        }
        val completed = withTimeoutOrNull(WRITE_TIMEOUT_MS) { completion.await() } == true
        if (pendingWrite === completion) pendingWrite = null
        return completed
    }

    private fun scheduleReconnect() {
        if (!running || targetAddress.isBlank() || !hasRequiredPermissions() || adapter?.isEnabled != true) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempt >= MAX_DIRECT_RECONNECT_ATTEMPTS) {
            reconnectAttempt = 0
            transition(BleStateEvent.ReconnectScheduled(BackoffPolicy.MAX_DELAY_MS))
            reconnectJob = scope.launch {
                delay(BackoffPolicy.MAX_DELAY_MS)
                if (running && targetAddress.isNotBlank()) {
                    reconnectJob = null
                    startScan()
                    _transportEvents.emit(
                        "La conexión directa falló; buscando automáticamente el SmartPanel anunciado.",
                    )
                }
            }
            return
        }
        val wait = backoff.delayForAttempt(reconnectAttempt++)
        transition(BleStateEvent.ReconnectScheduled(wait))
        reconnectJob = scope.launch {
            delay(wait)
            reconnectJob = null
            if (running && targetAddress.isNotBlank()) connect(targetAddress)
        }
    }

    private fun scheduleScanRetry() {
        if (!running || targetAddress.isBlank() || reconnectJob?.isActive == true) return
        transition(BleStateEvent.ReconnectScheduled(BackoffPolicy.MAX_DELAY_MS))
        reconnectJob = scope.launch {
            delay(BackoffPolicy.MAX_DELAY_MS)
            reconnectJob = null
            if (running && targetAddress.isNotBlank()) startScan()
        }
    }

    private fun armStageTimeout(timeoutMs: Long, message: String) {
        stageTimeoutJob?.cancel()
        stageTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (
                running && _state.value.phase in setOf(
                    ConnectionPhase.Bonding,
                    ConnectionPhase.Connecting,
                    ConnectionPhase.DiscoveringServices,
                    ConnectionPhase.Subscribing,
                )
            ) {
                failAndDisconnect(message, retry = _state.value.phase != ConnectionPhase.Bonding)
            }
        }
    }

    private fun handleGattDisconnect(gatt: BluetoothGatt, status: Int) {
        if (gatt !== currentGatt) {
            runCatching { gatt.close() }
            return
        }
        stageTimeoutJob?.cancel()
        discoveryJob?.cancel()
        pendingWrite?.complete(false)
        pendingWrite = null
        decoder.reset()
        handshakeReady = false
        currentGatt = null
        rxCharacteristic = null
        runCatching { gatt.close() }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            _transportEvents.tryEmit("Enlace GATT cerrado (código $status); se intentará recuperar automáticamente")
        }
        if (running && targetAddress.isNotBlank()) scheduleReconnect() else transition(BleStateEvent.Idle)
    }

    @Synchronized
    private fun closeGatt() {
        stageTimeoutJob?.cancel()
        discoveryJob?.cancel()
        pendingWrite?.complete(false)
        pendingWrite = null
        handshakeReady = false
        servicesDiscoveryStarted = false
        rxCharacteristic = null
        decoder.reset()
        currentGatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        currentGatt = null
    }

    private fun transition(event: BleStateEvent) {
        _state.value = BleConnectionReducer.reduce(_state.value, event)
    }

    private fun safeName(device: BluetoothDevice): String = runCatching { device.name }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "SmartPanel BLE"

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        closeGatt()
                        transition(BleStateEvent.BluetoothOff)
                    }
                    BluetoothAdapter.STATE_ON -> if (running) {
                        if (targetAddress.isBlank()) transition(BleStateEvent.Idle) else connect(targetAddress)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.bluetoothDevice() ?: return
                    if (device.address != targetAddress) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> connectGatt(device, safeName(device))
                        BluetoothDevice.BOND_NONE -> if (intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR) == BluetoothDevice.BOND_BONDING) {
                            stageTimeoutJob?.cancel()
                            transition(BleStateEvent.Failed("El emparejamiento fue cancelado o el PIN no era correcto"))
                        }
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        receiverRegistered = false
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("98d56a10-7c6d-4f5d-9af0-5a6b26aa1000")
        val RX_UUID: UUID = UUID.fromString("98d56a10-7c6d-4f5d-9af0-5a6b26aa1001")
        val TX_UUID: UUID = UUID.fromString("98d56a10-7c6d-4f5d-9af0-5a6b26aa1002")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_DURATION_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val MAX_DIRECT_RECONNECT_ATTEMPTS = 6
    }
}
