package com.ia.smallhome.ble

import com.ia.smallhome.model.ConnectionPhase

object BleConnectionPolicy {
    const val CONNECT_TIMEOUT_MS = 15_000L
    const val BOND_TIMEOUT_MS = 90_000L
    const val DISCOVERY_DELAY_MS = 350L
    const val DISCOVERY_TIMEOUT_MS = 12_000L
    const val SUBSCRIPTION_TIMEOUT_MS = 10_000L
    const val HEARTBEAT_INTERVAL_MS = 15_000L
    const val HEARTBEAT_TIMEOUT_MS = 45_000L

    fun shouldIgnoreConnect(
        phase: ConnectionPhase,
        currentAddress: String,
        requestedAddress: String,
        hasGatt: Boolean,
    ): Boolean {
        if (!currentAddress.equals(requestedAddress, ignoreCase = true)) return false
        return hasGatt && phase in setOf(
            ConnectionPhase.Connecting,
            ConnectionPhase.DiscoveringServices,
            ConnectionPhase.Subscribing,
            ConnectionPhase.Handshaking,
            ConnectionPhase.Connected,
        )
    }

    fun heartbeatExpired(nowElapsedMs: Long, lastAckElapsedMs: Long): Boolean =
        lastAckElapsedMs > 0L && nowElapsedMs - lastAckElapsedMs >= HEARTBEAT_TIMEOUT_MS
}
