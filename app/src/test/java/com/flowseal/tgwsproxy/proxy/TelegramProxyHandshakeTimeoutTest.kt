package com.flowseal.tgwsproxy.proxy

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramProxyHandshakeTimeoutTest {
    @Test
    fun idleClientWithoutHandshakeIsDisconnected() {
        val proxy = startLocalProxy(fakeTlsDomain = "")
        try {
            Socket("127.0.0.1", proxy.boundPort).use { client ->
                client.soTimeout = 3_000
                assertEquals("Idle MTProto handshake should time out", -1, client.getInputStream().read())
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun incompleteFakeTlsHeaderIsDisconnected() {
        val proxy = startLocalProxy(fakeTlsDomain = "www.google.com")
        try {
            Socket("127.0.0.1", proxy.boundPort).use { client ->
                client.soTimeout = 3_000
                client.getOutputStream().write(0x16)
                client.getOutputStream().flush()
                assertEquals("Incomplete Fake TLS ClientHello should time out", -1, client.getInputStream().read())
            }
        } finally {
            proxy.close()
        }
    }

    private fun startLocalProxy(fakeTlsDomain: String): TelegramProxy =
        TelegramProxy(
            options = ProxyOptions(
                host = "127.0.0.1",
                port = 0,
                secretHex = "0123456789abcdef0123456789abcdef",
                fakeTlsDomain = fakeTlsDomain,
                cfProxyEnabled = false,
                wsPoolEnabled = false
            ),
            initialHandshakeTimeoutMs = 200
        ).also { it.start() }
}
