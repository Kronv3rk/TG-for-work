package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPhaseTest {
    @Test fun stoppedNeverClaimsConnectivity() {
        assertEquals(ConnectionPhase.STOPPED, ConnectionPhase.of(false, 0))
        assertEquals(ConnectionPhase.STOPPED, ConnectionPhase.of(false, 10))
    }

    @Test fun listeningDoesNotClaimRemoteConnection() {
        assertEquals(ConnectionPhase.LISTENING, ConnectionPhase.of(true, 0))
    }

    @Test fun activeLocalClientsHaveTheirOwnState() {
        assertEquals(ConnectionPhase.HAS_CLIENTS, ConnectionPhase.of(true, 1))
        assertEquals(ConnectionPhase.HAS_CLIENTS, ConnectionPhase.of(true, 24))
    }

    @Test fun corruptedNegativeCountersCannotShowActiveState() {
        assertEquals(ConnectionPhase.LISTENING, ConnectionPhase.of(true, -1))
    }
}
