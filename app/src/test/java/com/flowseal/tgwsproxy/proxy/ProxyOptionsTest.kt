package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyOptionsTest {
    @Test
    fun fakeTlsEnabledWhenDomainPresent() {
        assertTrue(ProxyOptions(secretHex = "abc", fakeTlsDomain = "www.google.com").fakeTlsEnabled)
    }

    @Test
    fun fakeTlsDisabledWhenDomainBlank() {
        assertFalse(ProxyOptions(secretHex = "abc", fakeTlsDomain = "").fakeTlsEnabled)
    }

    @Test
    fun equalOptionsAreEqual() {
        assertEquals(ProxyOptions(secretHex = "abc"), ProxyOptions(secretHex = "abc"))
    }

    @Test
    fun differentSecretsAreNotEqual() {
        assertFalse(ProxyOptions(secretHex = "abc") == ProxyOptions(secretHex = "def"))
    }

    @Test
    fun cfWorkerDomainsParsedFromMixedSeparators() {
        val options = ProxyOptions(
            secretHex = "abc",
            cfWorkerDomain = "a.workers.dev, b.workers.dev c.workers.dev"
        )
        assertEquals(listOf("a.workers.dev", "b.workers.dev", "c.workers.dev"), options.cfWorkerDomains)
        assertTrue(options.cfWorkerEnabled)
    }

    @Test
    fun dcIpOverridesParsedFromPairs() {
        val options = ProxyOptions(
            secretHex = "abc",
            dcIpOverrides = "2:149.154.167.220, 4:149.154.167.91"
        )
        assertEquals(mapOf(2 to "149.154.167.220", 4 to "149.154.167.91"), options.dcIpMap)
    }

    @Test
    fun malformedDcIpPairsAreDropped() {
        val options = ProxyOptions(
            secretHex = "abc",
            dcIpOverrides = "2:not-an-ip x:1.2.3.4 5 3:10.0.0.1"
        )
        assertEquals(mapOf(3 to "10.0.0.1"), options.dcIpMap)
    }

    @Test
    fun emptyDcIpOverridesGiveEmptyMap() {
        assertTrue(ProxyOptions(secretHex = "abc").dcIpMap.isEmpty())
    }

    @Test
    fun bufferAndThreadsAreClamped() {
        val tooSmall = ProxyOptions(secretHex = "abc", bufferKb = 1, maxConnections = 1)
        assertEquals(ProxyOptions.MIN_BUFFER_KB * 1024, tooSmall.bufferBytes)
        assertEquals(ProxyOptions.MIN_MAX_CONNECTIONS, tooSmall.threadLimit)

        val tooBig = ProxyOptions(secretHex = "abc", bufferKb = 99_999, maxConnections = 99_999)
        assertEquals(ProxyOptions.MAX_BUFFER_KB * 1024, tooBig.bufferBytes)
        assertEquals(ProxyOptions.MAX_MAX_CONNECTIONS, tooBig.threadLimit)
    }

    @Test
    fun defaultsMatchPreviousHardcodedValues() {
        val options = ProxyOptions(secretHex = "abc")
        assertEquals(256 * 1024, options.bufferBytes)
        assertEquals(96, options.threadLimit)
    }

    @Test
    fun duplicateDcNumbersStillValidatePerPair() {
        // The dialog validates token by token, so a repeated DC must not read as
        // a malformed pair just because the resulting map is smaller.
        assertNotNull(ProxyOptions.parseDcIpPair("2:1.1.1.1"))
        assertNotNull(ProxyOptions.parseDcIpPair("2:2.2.2.2"))
        assertNull(ProxyOptions.parseDcIpPair("2:nope"))
        assertNull(ProxyOptions.parseDcIpPair("nodc"))
        assertNull(ProxyOptions.parseDcIpPair("0:1.1.1.1"))
    }

    @Test
    fun ipAddressValidation() {
        assertTrue(ProxyOptions.isIpAddress("149.154.167.220"))
        assertTrue(ProxyOptions.isIpAddress("2001:db8::1"))
        assertFalse(ProxyOptions.isIpAddress("300.1.1.1"))
        assertFalse(ProxyOptions.isIpAddress("1.2.3"))
        assertFalse(ProxyOptions.isIpAddress("example.com"))
        assertFalse(ProxyOptions.isIpAddress(""))
    }

    @Test
    fun cfWorkerDisabledWhenBlank() {
        val options = ProxyOptions(secretHex = "abc", cfWorkerDomain = "   ")
        assertTrue(options.cfWorkerDomains.isEmpty())
        assertFalse(options.cfWorkerEnabled)
    }
}
