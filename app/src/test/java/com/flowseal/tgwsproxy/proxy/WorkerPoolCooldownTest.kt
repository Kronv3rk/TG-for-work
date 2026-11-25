package com.flowseal.tgwsproxy.proxy

import java.io.Closeable
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerPoolCooldownTest {
    private class Connection : Closeable { override fun close() {} }

    @Test
    fun timeoutSuppressesSameWorkerAcrossDcUntilCooldownExpires() {
        var now = 1_000L
        val attempts = mutableListOf<String>()
        val pool = WorkerPool<Connection>(1, { _, _, domain ->
            attempts += domain
            throw SocketTimeoutException()
        }, { true }, { true }, { it.run() }, { now })
        val domains = listOf("worker.example.org")
        pool.get(2, "192.0.2.1", domains)
        pool.get(4, "192.0.2.2", domains)
        assertEquals(1, attempts.size)
        now += 60_000L
        pool.get(5, "192.0.2.3", domains)
        assertEquals(2, attempts.size)
        pool.close()
    }

    @Test
    fun forbiddenDoesNotDisableWorkerForAnotherDc() {
        val attempts = mutableListOf<String>()
        val pool = WorkerPool<Connection>(1, { _, _, domain ->
            attempts += domain
            throw WebSocketHandshakeException(403, "Forbidden")
        }, { true }, { true }, { it.run() })
        val domains = listOf("worker.example.org")
        pool.get(2, "192.0.2.1", domains)
        pool.get(4, "192.0.2.2", domains)
        assertEquals(2, attempts.size)
        pool.close()
    }

    @Test
    fun pooledTimeoutAlsoBlocksFreshDialUntilNetworkReset() {
        val pool = WorkerPool<Connection>(1, { _, _, _ -> throw SocketTimeoutException() },
            { true }, { true }, { it.run() }, { 1_000L })
        val domain = "worker.example.org"
        pool.get(2, "192.0.2.1", listOf(domain))
        assertEquals(emptyList<String>(), pool.availableDomains(listOf(domain)))
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            pool.dial(domain) { error("Must not dial during cooldown") }
        }
        pool.clear()
        assertEquals(listOf(domain), pool.availableDomains(listOf(domain)))
        pool.dial(domain) { Connection() }.close()
        pool.close()
    }

    @Test
    fun serverErrorSuppressesWorkerForSixtySecondsButLeavesOtherDomainsAvailable() {
        var now = 1000L
        val pool = WorkerPool<Connection>(1, { _, _, _ -> throw WebSocketHandshakeException(503, "Unavailable") },
            { true }, { true }, { it.run() }, { now })
        val first = "worker.example.org"
        val other = "fallback.example.org"
        pool.get(2, "192.0.2.1", listOf(first))
        assertEquals(listOf(other), pool.availableDomains(listOf(first, other)))
        now += 59_999L
        assertEquals(listOf(other), pool.availableDomains(listOf(first, other)))
        now++
        assertEquals(listOf(first, other), pool.availableDomains(listOf(first, other)))
        pool.close()
    }

    @Test
    fun quotaExhaustionSuppressesWorkerUntilNextUtcDay() {
        var now = 86_400_000L - 10_000L
        val pool = WorkerPool<Connection>(1, { _, _, _ -> throw WebSocketHandshakeException(429, "Too Many Requests") },
            { true }, { true }, { it.run() }, { now })
        val domain = "worker.example.org"
        pool.get(2, "192.0.2.1", listOf(domain))
        assertEquals(emptyList<String>(), pool.availableDomains(listOf(domain)))
        now += 9_999L
        assertEquals(emptyList<String>(), pool.availableDomains(listOf(domain)))
        now++
        assertEquals(listOf(domain), pool.availableDomains(listOf(domain)))
        pool.close()
    }

    @Test
    fun quotaExhaustionSurvivesNetworkReset() {
        val now = 12_000L
        val pool = WorkerPool<Connection>(1, { _, _, _ -> throw WebSocketHandshakeException(429, "Too Many Requests") },
            { true }, { true }, { it.run() }, { now })
        val domain = "worker.example.org"
        pool.get(2, "192.0.2.1", listOf(domain))
        pool.clear()
        assertEquals(emptyList<String>(), pool.availableDomains(listOf(domain)))
        pool.close()
    }
}
