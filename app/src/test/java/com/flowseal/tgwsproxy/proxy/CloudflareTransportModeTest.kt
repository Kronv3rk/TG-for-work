package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareTransportModeTest {
    @Test
    fun cloudflareUsesValidatedTlsAndPort443ByDefault() {
        val options = ProxyOptions(secretHex = "abc")
        assertFalse(options.cfPlaintextEnabled)
        assertEquals(443, CloudflareTransportMode.port(secure = true))
        assertEquals(80, CloudflareTransportMode.port(secure = false))
    }

    @Test
    fun plaintextIsExplicitAndRequiresNewConnectionConfiguration() {
        val secure = ProxyOptions(secretHex = "abc")
        val plaintext = secure.copy(cfPlaintextEnabled = true)
        assertTrue(plaintext.cfPlaintextEnabled)
        assertFalse(secure.sameConnection(plaintext))
        assertFalse(plaintext.sameConnection(secure))
    }

    @Test
    fun frontedWebSocketCannotDisableTls() {
        assertTrue(CloudflareTransportMode.isValid(secure = true, sni = "front.example"))
        assertTrue(CloudflareTransportMode.isValid(secure = false, sni = null))
        assertFalse(CloudflareTransportMode.isValid(secure = false, sni = "front.example"))
    }
}
