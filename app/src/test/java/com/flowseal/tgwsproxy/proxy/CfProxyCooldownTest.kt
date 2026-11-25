package com.flowseal.tgwsproxy.proxy

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CfProxyCooldownTest {
    private val domain = "relay.example.org"

    @Test
    fun timeoutSuppressesBaseDomainAcrossDcsForThirtySeconds() {
        var now = 1_000L
        val balancer = CfProxyBalancer { now }
        balancer.setUserDomain(domain)
        assertThrows(SocketTimeoutException::class.java) {
            balancer.dialWebSocket(domain, true) { throw SocketTimeoutException() }
        }
        assertEquals(emptyList<String>(), balancer.availableWebSocketDomains(4, true))
        now += 29_999
        assertFalse(domain in balancer.availableWebSocketDomains(2, true))
        now++
        assertEquals(listOf(domain), balancer.availableWebSocketDomains(2, true))
    }

    @Test
    fun cooldownIsSpecificToTransportAndDoesNotRemoveOtherDomains() {
        val balancer = CfProxyBalancer { 1_000L }
        val candidates = balancer.getDomainsForDc(2)
        val failed = candidates.first()
        assertThrows(SocketTimeoutException::class.java) {
            balancer.dialWebSocket(failed, true) { throw SocketTimeoutException() }
        }
        assertEquals(candidates.toSet() - failed, balancer.availableWebSocketDomains(2, true).toSet())
        assertEquals(candidates.toSet(), balancer.availableWebSocketDomains(2, false).toSet())
        assertThrows(IllegalStateException::class.java) {
            balancer.dialWebSocket(failed, true) { "Must not dial" }
        }
    }

    @Test
    fun networkResetDiscardsCooldownAndLateTimeoutFromOldNetwork() {
        val balancer = CfProxyBalancer { 1_000L }
        balancer.setUserDomain(domain)
        assertThrows(SocketTimeoutException::class.java) {
            balancer.dialWebSocket(domain, true) { throw SocketTimeoutException() }
        }
        balancer.resetWebSocketTimeouts()
        assertEquals(listOf(domain), balancer.availableWebSocketDomains(2, true))
        assertThrows(SocketTimeoutException::class.java) {
            balancer.dialWebSocket(domain, true) {
                balancer.resetWebSocketTimeouts()
                throw SocketTimeoutException()
            }
        }
        assertTrue(domain in balancer.availableWebSocketDomains(2, true))
    }

    @Test
    fun handshakeRejectionDoesNotCauseTimeoutCooldown() {
        val balancer = CfProxyBalancer { 1_000L }
        balancer.setUserDomain(domain)
        assertThrows(WebSocketHandshakeException::class.java) {
            balancer.dialWebSocket(domain, true) { throw WebSocketHandshakeException(403, "Forbidden") }
        }
        assertEquals(listOf(domain), balancer.availableWebSocketDomains(2, true))
    }
}
