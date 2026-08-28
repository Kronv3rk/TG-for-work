package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun cfWorkerDisabledWhenBlank() {
        val options = ProxyOptions(secretHex = "abc", cfWorkerDomain = "   ")
        assertTrue(options.cfWorkerDomains.isEmpty())
        assertFalse(options.cfWorkerEnabled)
    }
}
