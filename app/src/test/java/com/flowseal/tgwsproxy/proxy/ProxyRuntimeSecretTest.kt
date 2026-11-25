package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeSecretTest {
    @Test
    fun acceptsExactly32LowercaseAsciiHexCharacters() {
        assertTrue(ProxyRuntime.isValidSecret("0123456789abcdef".repeat(2)))
    }

    @Test
    fun rejectsUnicodeDigits() {
        assertFalse(ProxyRuntime.isValidSecret("\u0661".repeat(32)))
        assertFalse(ProxyRuntime.isValidSecret("\uff11".repeat(32)))
    }

    @Test
    fun rejectsWrongLengthAndNonHexCharacters() {
        assertFalse(ProxyRuntime.isValidSecret("a".repeat(31)))
        assertFalse(ProxyRuntime.isValidSecret("a".repeat(33)))
        assertFalse(ProxyRuntime.isValidSecret("g".repeat(32)))
    }
}
