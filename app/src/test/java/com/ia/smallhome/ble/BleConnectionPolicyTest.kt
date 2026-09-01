package com.ia.smallhome.ble

import com.ia.smallhome.model.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionPolicyTest {
    @Test
    fun `duplicate GATT connect is ignored while the same session is active`() {
        assertTrue(
            BleConnectionPolicy.shouldIgnoreConnect(
                ConnectionPhase.DiscoveringServices,
                "AA:BB",
                "aa:bb",
                hasGatt = true,
            ),
        )
    }

    @Test
    fun `bonding without GATT is not mistaken for an active GATT session`() {
        assertFalse(
            BleConnectionPolicy.shouldIgnoreConnect(
                ConnectionPhase.Bonding,
                "AA:BB",
                "AA:BB",
                hasGatt = false,
            ),
        )
    }

    @Test
    fun `new address or inactive transport may connect`() {
        assertFalse(
            BleConnectionPolicy.shouldIgnoreConnect(
                ConnectionPhase.Connected,
                "AA:BB",
                "CC:DD",
                hasGatt = true,
            ),
        )
        assertFalse(
            BleConnectionPolicy.shouldIgnoreConnect(
                ConnectionPhase.Reconnecting,
                "AA:BB",
                "AA:BB",
                hasGatt = false,
            ),
        )
    }

    @Test
    fun `heartbeat watchdog expires only after the configured silence`() {
        val lastAck = 10_000L
        assertFalse(BleConnectionPolicy.heartbeatExpired(lastAck + BleConnectionPolicy.HEARTBEAT_TIMEOUT_MS - 1, lastAck))
        assertTrue(BleConnectionPolicy.heartbeatExpired(lastAck + BleConnectionPolicy.HEARTBEAT_TIMEOUT_MS, lastAck))
        assertFalse(BleConnectionPolicy.heartbeatExpired(999_999L, 0L))
    }
}
