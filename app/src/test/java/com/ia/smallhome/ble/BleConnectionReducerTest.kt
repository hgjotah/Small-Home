package com.ia.smallhome.ble

import com.ia.smallhome.model.ConnectionPhase
import com.ia.smallhome.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionReducerTest {
    @Test
    fun `state machine follows scan bond connect subscribe handshake`() {
        var state = ConnectionState()
        state = BleConnectionReducer.reduce(state, BleStateEvent.ScanStarted)
        assertEquals(ConnectionPhase.Scanning, state.phase)
        state = BleConnectionReducer.reduce(state, BleStateEvent.BondStarted("AA", "Panel"))
        assertEquals(ConnectionPhase.Bonding, state.phase)
        state = BleConnectionReducer.reduce(state, BleStateEvent.ConnectStarted("AA", "Panel", true))
        state = BleConnectionReducer.reduce(state, BleStateEvent.ServicesDiscoveryStarted)
        state = BleConnectionReducer.reduce(state, BleStateEvent.SubscriptionStarted)
        state = BleConnectionReducer.reduce(state, BleStateEvent.HandshakeStarted)
        state = BleConnectionReducer.reduce(state, BleStateEvent.HandshakeAccepted(2, "ABC", "SmartPanel C6"))
        assertEquals(ConnectionPhase.Connected, state.phase)
        assertTrue(state.bonded)
    }

    @Test
    fun `protocol mismatch is a terminal visible error`() {
        val state = BleConnectionReducer.reduce(
            ConnectionState(phase = ConnectionPhase.Handshaking),
            BleStateEvent.HandshakeAccepted(1, "ABC", "Old panel"),
        )
        assertEquals(ConnectionPhase.Error, state.phase)
        assertTrue(state.message.contains("protocolo 1"))
    }

    @Test
    fun `reconnect policy exposes scheduled delay`() {
        val state = BleConnectionReducer.reduce(ConnectionState(), BleStateEvent.ReconnectScheduled(15_000))
        assertEquals(ConnectionPhase.Reconnecting, state.phase)
        assertTrue(state.message.contains("15 s"))
    }
}
