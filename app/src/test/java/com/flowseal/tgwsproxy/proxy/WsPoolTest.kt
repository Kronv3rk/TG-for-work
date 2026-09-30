package com.flowseal.tgwsproxy.proxy

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsPoolTest {
    private class FakeSocket(val domain: String) : Closeable {
        var open = true
        override fun close() {
            open = false
        }
    }

    private val key = WsPool.Key(2, false)
    private val domains = listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org")
    private var now = 1_000_000L
    private var active = true
    private val blocked = mutableSetOf<String>()
    private val opened = mutableListOf<FakeSocket>()
    private var connects = 0
    private var failConnect = false

    /** Background work runs inline, so every refill is finished when get()/maintain() return. */
    private val pool = WsPool(
        size = 2,
        connector = { _, domain ->
            connects++
            if (failConnect) throw IOException("timeout")
            FakeSocket(domain).also { opened += it }
        },
        isOpen = { it.open },
        isTargetBlocked = { it in blocked },
        isActive = { active },
        background = { it.run() },
        clock = { now }
    )

    @Test
    fun firstRequestMissesAndWarmsThePool() {
        assertNull(pool.get(key, "149.154.167.220", domains))
        assertEquals(2, pool.idleCount(key))
        assertEquals(1, pool.missCount)
        assertEquals("kws2.web.telegram.org", opened.first().domain)
    }

    @Test
    fun nextRequestIsServedFromThePoolAndRefilled() {
        pool.get(key, "149.154.167.220", domains)
        val ws = pool.get(key, "149.154.167.220", domains)
        assertNotNull(ws)
        assertEquals(1, pool.hitCount)
        assertEquals(2, pool.idleCount(key))
    }

    @Test
    fun expiredOrDeadSocketsAreNeverHandedOut() {
        pool.get(key, "149.154.167.220", domains)
        opened[0].open = false
        now += WsPool.MAX_AGE_MS - 1
        val ws = pool.get(key, "149.154.167.220", domains)
        assertTrue(ws === opened[1])
        now += 2
        // Past the max age nothing from the first batch may be handed out.
        val next = pool.get(key, "149.154.167.220", domains)
        assertTrue(next !== opened[0] && next !== opened[1])
    }

    @Test
    fun maintenanceRotatesExpiredSockets() {
        pool.get(key, "149.154.167.220", domains)
        now += WsPool.MAX_AGE_MS
        pool.maintain()
        assertTrue(!opened[0].open && !opened[1].open)
        assertEquals(2, pool.idleCount(key))
        assertEquals(4, connects)
    }

    @Test
    fun unusedPairIsDroppedAfterIdleWindow() {
        pool.get(key, "149.154.167.220", domains)
        now += WsPool.IDLE_KEY_MS
        pool.maintain()
        assertEquals(0, pool.idleCount(key))
        assertTrue(opened.none { it.open })
        val before = connects
        now += WsPool.MAINTAIN_INTERVAL_MS
        pool.maintain()
        assertEquals(before, connects)
    }

    @Test
    fun failedRefillBacksOffExponentially() {
        failConnect = true
        pool.get(key, "149.154.167.220", domains)
        assertEquals(1, connects)
        pool.maintain()
        assertEquals(1, connects)
        now += WsPool.BACKOFF_INITIAL_MS
        pool.maintain()
        assertEquals(2, connects)
        now += WsPool.BACKOFF_INITIAL_MS
        pool.maintain()
        assertEquals(2, connects)
        now += WsPool.BACKOFF_INITIAL_MS
        pool.maintain()
        assertEquals(3, connects)
    }

    @Test
    fun blockedTargetIsNotRefilled() {
        blocked += "149.154.167.220"
        assertNull(pool.get(key, "149.154.167.220", domains))
        assertEquals(0, connects)
    }

    @Test
    fun inactivePoolClosesEverythingAndServesNothing() {
        pool.get(key, "149.154.167.220", domains)
        active = false
        assertNull(pool.get(key, "149.154.167.220", domains))
        pool.maintain()
        assertEquals(0, pool.idleCount(key))
        assertTrue(opened.none { it.open })
    }

    @Test
    fun changedRelayAddressDiscardsOldSockets() {
        pool.get(key, "149.154.167.220", domains)
        assertNull(pool.get(key, "149.154.167.221", domains))
        assertTrue(!opened[0].open && !opened[1].open)
        assertEquals(2, pool.idleCount(key))
    }

    @Test
    fun closeReleasesSockets() {
        pool.get(key, "149.154.167.220", domains)
        pool.close()
        assertTrue(opened.none { it.open })
        assertNull(pool.get(key, "149.154.167.220", domains))
    }
}
