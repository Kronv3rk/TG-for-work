package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionGateTest {
    @Test
    fun refusesAdditionalMaskingStreamsWhenLimitIsReached() {
        val gate = ConnectionGate(1)
        var secondAllowed = true
        assertTrue(gate.withPermit {
            secondAllowed = gate.withPermit { }
        })
        assertFalse(secondAllowed)
        assertTrue(gate.withPermit { })
    }

    @Test
    fun releasesPermitEvenWhenMaskingForwardingFails() {
        val gate = ConnectionGate(1)
        try {
            gate.withPermit { error("Forwarding socket disconnected") }
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertTrue(gate.withPermit { })
    }
}
