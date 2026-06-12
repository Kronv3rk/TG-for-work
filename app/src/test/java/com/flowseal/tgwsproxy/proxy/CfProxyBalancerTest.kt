package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CfProxyBalancerTest {
    @Test
    fun decodeRestoresCoUkDomain() {
        val decoded = CfProxyBalancer.decodeDomain("virkgj.com")
        assertTrue(decoded.endsWith(".co.uk"))
    }

    @Test
    fun decodeLeavesNonComUnchanged() {
        assertEquals("example.org", CfProxyBalancer.decodeDomain("example.org"))
    }

    @Test
    fun decodeIsDeterministic() {
        assertEquals(
            CfProxyBalancer.decodeDomain("vmmzovy.com"),
            CfProxyBalancer.decodeDomain("vmmzovy.com")
        )
    }

    @Test
    fun normalizeLowercasesValidDomain() {
        assertEquals("kws2.example.co.uk", CfProxyBalancer.normalizeDomain("KWS2.Example.CO.UK"))
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertNull(CfProxyBalancer.normalizeDomain("not a domain"))
        assertNull(CfProxyBalancer.normalizeDomain(""))
    }

    @Test
    fun validDomainRules() {
        assertTrue(CfProxyBalancer.isValidDomain("a.co"))
        assertTrue(CfProxyBalancer.isValidDomain("sub.example.com"))
        assertFalse(CfProxyBalancer.isValidDomain("nodot"))
        assertFalse(CfProxyBalancer.isValidDomain("-bad.com"))
        assertFalse(CfProxyBalancer.isValidDomain("bad-.com"))
        assertFalse(CfProxyBalancer.isValidDomain(".com"))
    }
}
