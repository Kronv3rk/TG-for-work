package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class WsConnectHostTest {
    private val domain = "kws3.web.telegram.org"

    @Test
    fun redirectMapWinsForKnownDataCentres() {
        assertEquals(
            "149.154.167.220",
            TelegramProxy.wsConnectHost(2, "kws2.web.telegram.org", emptyMap())
        )
        assertEquals(
            "149.154.167.220",
            TelegramProxy.wsConnectHost(4, "kws4.web.telegram.org", emptyMap())
        )
    }

    @Test
    fun dataCentreWithoutRedirectFallsBackToHostname() {
        // Previously these DCs skipped the WebSocket route entirely.
        assertEquals(domain, TelegramProxy.wsConnectHost(3, domain, emptyMap()))
        assertEquals(domain, TelegramProxy.wsConnectHost(1, domain, emptyMap()))
        assertEquals(domain, TelegramProxy.wsConnectHost(203, domain, emptyMap()))
    }

    @Test
    fun userOverrideWinsOverEverything() {
        val overrides = mapOf(2 to "10.0.0.1", 3 to "10.0.0.3")
        assertEquals("10.0.0.1", TelegramProxy.wsConnectHost(2, "kws2.web.telegram.org", overrides))
        assertEquals("10.0.0.3", TelegramProxy.wsConnectHost(3, domain, overrides))
    }
}
