package com.ia.smallhome.ble

import com.ia.smallhome.model.ConnectionPhase
import com.ia.smallhome.model.ConnectionState

sealed interface BleStateEvent {
    data object BluetoothOff : BleStateEvent
    data object PermissionMissing : BleStateEvent
    data object Idle : BleStateEvent
    data object ScanStarted : BleStateEvent
    data class BondStarted(val address: String, val name: String) : BleStateEvent
    data class ConnectStarted(val address: String, val name: String, val bonded: Boolean) : BleStateEvent
    data object ServicesDiscoveryStarted : BleStateEvent
    data object SubscriptionStarted : BleStateEvent
    data object HandshakeStarted : BleStateEvent
    data class HandshakeAccepted(val protocol: Int, val chipId: String, val deviceName: String) : BleStateEvent
    data class ReconnectScheduled(val delayMs: Long) : BleStateEvent
    data class Failed(val message: String) : BleStateEvent
}

object BleConnectionReducer {
    const val SUPPORTED_PROTOCOL = 2

    fun reduce(current: ConnectionState, event: BleStateEvent): ConnectionState = when (event) {
        BleStateEvent.BluetoothOff -> ConnectionState(ConnectionPhase.BluetoothUnavailable, "Bluetooth está desactivado")
        BleStateEvent.PermissionMissing -> current.copy(ConnectionPhase.PermissionRequired, "Concede permiso de dispositivos cercanos")
        BleStateEvent.Idle -> current.copy(phase = ConnectionPhase.Idle, message = "Añade o busca tu SmartPanel")
        BleStateEvent.ScanStarted -> current.copy(phase = ConnectionPhase.Scanning, message = "Buscando SmartPanel por BLE…")
        is BleStateEvent.BondStarted -> current.copy(
            phase = ConnectionPhase.Bonding,
            message = "Introduce el PIN de 6 cifras que aparece en SmartPanel",
            bleAddress = event.address,
            bleName = event.name,
            bonded = false,
        )
        is BleStateEvent.ConnectStarted -> current.copy(
            phase = ConnectionPhase.Connecting,
            message = "Conectando por BLE…",
            bleAddress = event.address,
            bleName = event.name,
            bonded = event.bonded,
        )
        BleStateEvent.ServicesDiscoveryStarted -> current.copy(ConnectionPhase.DiscoveringServices, "Descubriendo servicios BLE…")
        BleStateEvent.SubscriptionStarted -> current.copy(ConnectionPhase.Subscribing, "Activando notificaciones BLE…")
        BleStateEvent.HandshakeStarted -> current.copy(ConnectionPhase.Handshaking, "Validando SmartPanel y protocolo…")
        is BleStateEvent.HandshakeAccepted -> if (event.protocol == SUPPORTED_PROTOCOL) {
            current.copy(
                phase = ConnectionPhase.Connected,
                message = "SmartPanel conectado por BLE",
                protocol = event.protocol.toString(),
                bleName = event.deviceName.ifBlank { current.bleName },
                bonded = true,
            )
        } else {
            current.copy(
                phase = ConnectionPhase.Error,
                message = "Firmware incompatible: protocolo ${event.protocol}; se necesita 2",
                protocol = event.protocol.toString(),
            )
        }
        is BleStateEvent.ReconnectScheduled -> current.copy(
            phase = ConnectionPhase.Reconnecting,
            message = "BLE desconectado. Reintentando en ${event.delayMs / 1_000} s",
        )
        is BleStateEvent.Failed -> current.copy(phase = ConnectionPhase.Error, message = event.message)
    }
}
