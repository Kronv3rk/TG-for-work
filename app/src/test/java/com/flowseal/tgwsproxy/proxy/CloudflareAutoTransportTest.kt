package com.flowseal.tgwsproxy.proxy

import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudflareAutoTransportTest {
    @Test fun tlsIsDefaultAndNoFallbackWithoutPermission() {
        val transport = CloudflareAutoTransport { 0L }
        val attempts = mutableListOf<Boolean>()
        assertThrows(SocketTimeoutException::class.java) {
            transport.connect(false) { secure ->
                attempts.add(secure)
                throw SocketTimeoutException()
            }
        }
        assertEquals(listOf(true), attempts)
    }

    @Test fun timeoutAllowsExplicitlyPermittedFallbackAndReusesIt() {
        var now = 0L
        val transport = CloudflareAutoTransport { now }
        val attempts = mutableListOf<Boolean>()
        fun dial(secure: Boolean): String {
            attempts.add(secure)
            if (secure) throw SocketTimeoutException()
            return "connected"
        }
        assertEquals("connected", transport.connect(true, ::dial))
        assertEquals("connected", transport.connect(true, ::dial))
        assertEquals(listOf(true, false, false), attempts)
        now += 300_001
        assertEquals("connected", transport.connect(true, ::dial))
        assertEquals(listOf(true, false, false, true, false), attempts)
    }

    @Test fun certificateFailureNeverTriggersFallback() {
        val transport = CloudflareAutoTransport { 0L }
        val attempts = mutableListOf<Boolean>()
        assertThrows(SSLHandshakeException::class.java) {
            transport.connect(true) { secure ->
                attempts.add(secure)
                throw SSLHandshakeException("certificate rejected")
            }
        }
        assertEquals(listOf(true), attempts)
    }

    @Test fun networkChangeClearsPreferenceAndLateSuccessCannotRestoreIt() {
        val transport = CloudflareAutoTransport { 0L }
        transport.connect(true) { secure ->
            if (secure) throw SocketTimeoutException()
            transport.reset()
            "connected"
        }
        val attempts = mutableListOf<Boolean>()
        transport.connect(true) { attempts.add(it); "connected" }
        assertEquals(listOf(true), attempts)
    }

    @Test fun permissionRevokedNeverUsesCachedPlaintext() {
        val transport = CloudflareAutoTransport { 0L }
        transport.connect(true) { if (it) throw SocketTimeoutException() else "connected" }
        val attempts = mutableListOf<Boolean>()
        transport.connect(false) { attempts.add(it); "connected" }
        assertEquals(listOf(true), attempts)
    }

    @Test fun explicitlyPreferredPlaintextRouteDoesNotWaitForBlockedWebSocket() {
        val options = ProxyOptions(secretHex = "00112233445566778899aabbccddeeff", cfPlaintextEnabled = true)
        assertEquals(RouteMethod.CLOUDFLARE, RoutePlanner().methodsFor(2, false, options).first())
    }

    @Test fun injectedClockControlsFailureProbation() {
        var now = 100L
        val planner = RoutePlanner { now }
        planner.markFailed(RouteMethod.WEBSOCKET, 2, false)
        assertEquals(3000, planner.connectTimeoutMs(RouteMethod.WEBSOCKET, 2, false))
        now += 120001
        assertEquals(8000, planner.connectTimeoutMs(RouteMethod.WEBSOCKET, 2, false))
    }
    @Test fun nextDomainDoesNotRepeatTlsTimeoutAfterBothTransportsFail() {
        val transport = CloudflareAutoTransport { 0L }
        val attempts = mutableListOf<Boolean>()
        assertThrows(SocketTimeoutException::class.java) {
            transport.connect(true) {
                attempts.add(it)
                throw SocketTimeoutException()
            }
        }
        assertEquals("connected", transport.connect(true) {
            attempts.add(it)
            "connected"
        })
        assertEquals(listOf(true, false, false), attempts)
    }

}
