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
}
