package com.flowseal.tgwsproxy.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionLogTest {
    @Before
    fun reset() {
        ConnectionLog.clear()
        ConnectionLog.sink = null
    }

    @After
    fun tearDown() {
        ConnectionLog.clear()
        ConnectionLog.sink = null
    }

    @Test
    fun recentReturnsLatestFirst() {
        ConnectionLog.info("первое")
        ConnectionLog.info("второе")
        ConnectionLog.info("третье")
        val recent = ConnectionLog.recent(2)
        assertEquals(2, recent.size)
        assertTrue(recent[0].contains("третье"))
        assertTrue(recent[1].contains("второе"))
    }

    @Test
    fun capacityIsBounded() {
        repeat(500) { ConnectionLog.info("событие $it") }
        assertTrue(ConnectionLog.size() <= 150)
        assertTrue(ConnectionLog.recent(1).first().contains("событие 499"))
    }

    @Test
    fun connectedEntryContainsRouteQualityContext() {
        ConnectionLog.connected(RouteMethod.WEBSOCKET, 2, true, 437, "kws2.web.telegram.org")
        val line = ConnectionLog.recent(1).first()
        assertTrue(line.contains("WS"))
        assertTrue(line.contains("DC2"))
        assertTrue(line.contains("медиа"))
        assertTrue(line.contains("0.4с"))
        assertTrue(line.contains("kws2.web.telegram.org"))
    }

    @Test
    fun sessionEndContainsBytes() {
        ConnectionLog.sessionEnd(RouteMethod.CLOUDFLARE, 4, false, 12_000, 2L * 1024 * 1024)
        val line = ConnectionLog.recent(1).first()
        assertTrue(line.contains("CF"))
        assertTrue(line.contains("2.0 MB"))
    }

    @Test
    fun sinkMirrorsEveryLine() {
        val mirrored = mutableListOf<String>()
        ConnectionLog.sink = { mirrored.add(it) }
        ConnectionLog.connectFailed(RouteMethod.TCP, 2, false, "timeout")
        assertEquals(1, mirrored.size)
        assertTrue(mirrored.first().contains("TCP"))
        assertTrue(mirrored.first().contains("timeout"))
    }

    @Test
    fun fullTextJoinsAllEntries() {
        ConnectionLog.info("alpha")
        ConnectionLog.info("beta")
        val text = ConnectionLog.fullText()
        assertTrue(text.contains("alpha"))
        assertTrue(text.contains("beta"))
        assertEquals(2, text.lines().size)
    }

    @Test
    fun censorMasksDomainsLikeUpstream() {
        assertEquals("my-w*****.exa****.com", ConnectionLog.censorDomains("my-worker.example.com"))
        assertEquals("CFW DC2 · abc***.wor****.dev ok", ConnectionLog.censorDomains("CFW DC2 · abcdef.workers.dev ok"))
    }

    @Test
    fun censorKeepsTelegramAndAddresses() {
        val text = "WS DC2 kws2.web.telegram.org via 149.154.167.220"
        assertEquals(text, ConnectionLog.censorDomains(text))
    }

    @Test
    fun fullTextCanBeCensored() {
        ConnectionLog.connected(RouteMethod.CLOUDFLARE, 2, false, 100, "kws2.secret.co.uk")
        assertTrue(ConnectionLog.fullText().contains("kws2.secret.co.uk"))
        val censored = ConnectionLog.fullText(censored = true)
        assertFalse(censored.contains("secret"))
        assertTrue(censored.contains("kw**.sec***.c*.uk"))
    }
}
