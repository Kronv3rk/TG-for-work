package com.flowseal.tgwsproxy.proxy

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerPoolTest {
    private class FakeConnection : Closeable {
        var open = true
        override fun close() { open = false }
    }

    @Test
    fun keepsWorkersIsolatedByDcAndDestination() {
        val requests = mutableListOf<Triple<Int, String, String>>()
        val pool = WorkerPool(
            size = 1,
            connector = { dc, destination, domain ->
                requests += Triple(dc, destination, domain)
                FakeConnection()
            },
            isOpen = { it.open },
            isActive = { true },
            background = { it.run() }
        )
        val domains = listOf("relay.example.org")
        assertNull(pool.get(2, "149.154.167.51", domains))
        assertEquals(Triple(2, "149.154.167.51", "relay.example.org"), requests.single())
        val reused = pool.get(2, "149.154.167.51", domains)
        assertNotNull(reused)
        assertNull(pool.get(4, "149.154.167.91", domains))
        assertNull(pool.get(2, "149.154.167.52", domains))
        assertEquals(4, requests.size)
        pool.close()
        assertTrue(requests.isNotEmpty())
    }

    @Test
    fun workerIdleConnectionsExpireAfterUpstreamHundredSeconds() {
        var now = 1_000L
        val opened = mutableListOf<FakeConnection>()
        val pool = WorkerPool(
            size = 1,
            connector = { _, _, _ -> FakeConnection().also(opened::add) },
            isOpen = { it.open },
            isActive = { true },
            background = { it.run() },
            clock = { now }
        )
        val domain = listOf("worker.example.org")
        assertNull(pool.get(2, "149.154.167.51", domain))
        val first = opened.single()
        now += 99_999L
        pool.maintain()
        assertTrue(first.open)
        now += 1L
        pool.maintain()
        assertFalse("Worker sockets must expire after upstream 100 second TTL", first.open)
        assertTrue(opened.size >= 2)
        pool.close()
    }

    @Test
    fun wifiOnlyAndNetworkChangeCloseIdleSockets() {
        var active = true
        val opened = mutableListOf<FakeConnection>()
        val pool = WorkerPool(
            size = 1,
            connector = { _, _, _ -> FakeConnection().also(opened::add) },
            isOpen = { it.open },
            isActive = { active },
            background = { it.run() }
        )
        pool.get(2, "149.154.167.51", listOf("relay.example.org"))
        assertEquals(1, opened.size)
        active = false
        assertNull(pool.get(2, "149.154.167.51", listOf("relay.example.org")))
        pool.clear()
        assertFalse(opened.first().open)
        pool.close()
    }
}
