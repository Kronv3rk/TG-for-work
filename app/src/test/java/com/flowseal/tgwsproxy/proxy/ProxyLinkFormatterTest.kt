package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyLinkFormatterTest {
    private val base = ProxyOptions(
        host = "127.0.0.1",
        port = 1443,
        secretHex = "00112233445566778899aabbccddeeff",
        fakeTlsDomain = ""
    )

    @Test fun plainLinkIsCompatibleWithExistingTelegramConfiguration() {
        assertEquals(
            "tg://proxy?server=127.0.0.1&port=1443&secret=dd00112233445566778899aabbccddeeff",
            ProxyLinkFormatter.link(base)
        )
    }

    @Test fun fakeTlsDomainIsEncodedInAsciiHex() {
        val options = base.copy(fakeTlsDomain = "example.org")
        assertEquals(
            "tg://proxy?server=127.0.0.1&port=1443&secret=ee00112233445566778899aabbccddeeff6578616d706c652e6f7267",
            ProxyLinkFormatter.link(options)
        )
    }

    @Test fun selectedEndpointIsPreserved() {
        val options = base.copy(host = "localhost", port = 2443)
        assertEquals(
            "tg://proxy?server=localhost&port=2443&secret=dd00112233445566778899aabbccddeeff",
            ProxyLinkFormatter.link(options)
        )
    }

    @Test fun thePlainLinkIsAvailableWhileFakeTlsIsOn() {
        val options = base.copy(fakeTlsDomain = "example.org")
        assertEquals(ProxyLinkFormatter.plain(base), ProxyLinkFormatter.plain(options))
    }
}
