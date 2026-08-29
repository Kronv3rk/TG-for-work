package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WsConnectHostTest {
    @Test
    fun redirectMapServesTheWebRelayDataCentres() {
        assertEquals("149.154.167.220", TelegramProxy.wsConnectHost(2, emptyMap()))
        assertEquals("149.154.167.220", TelegramProxy.wsConnectHost(4, emptyMap()))
    }

    @Test
    fun dataCentresWithoutARelayHaveNoWebSocketRoute() {
        // Only DC2/DC4 are served by Telegram's web WebSocket relay. Dialling
        // kwsN by name for the others produced a socket that handshook but never
        // relayed MTProto, which the client reports as a misconfigured proxy.
        assertNull(TelegramProxy.wsConnectHost(1, emptyMap()))
        assertNull(TelegramProxy.wsConnectHost(3, emptyMap()))
        assertNull(TelegramProxy.wsConnectHost(5, emptyMap()))
        assertNull(TelegramProxy.wsConnectHost(203, emptyMap()))
    }

    @Test
    fun userOverrideWinsAndCanEnableOtherDataCentres() {
        val overrides = mapOf(2 to "10.0.0.1", 3 to "10.0.0.3")
        assertEquals("10.0.0.1", TelegramProxy.wsConnectHost(2, overrides))
        assertEquals("10.0.0.3", TelegramProxy.wsConnectHost(3, overrides))
    }
}
