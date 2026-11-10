package com.flowseal.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ProxyProtocolV1Test {
    private fun source(header: String, payload: ByteArray = byteArrayOf(12, 34)) =
        BufferedInputStream(ByteArrayInputStream(header.toByteArray(Charsets.US_ASCII) + payload))

    @Test
    fun consumesTcp4HeaderAndLeavesTheNativeHandshakeUntouched() {
        val input = source("PROXY TCP4 192.0.2.10 198.51.100.20 53217 1443\r\n")
        val forwarded = ProxyProtocolV1.read(input)!!
        assertEquals("192.0.2.10", forwarded.sourceAddress)
        assertEquals(53217, forwarded.sourcePort)
        assertEquals(12, input.read())
        assertEquals(34, input.read())
    }

    @Test
    fun acceptsTcp6WithIpv6AddressValidation() {
        val input = source("PROXY TCP6 2001:db8::1 2001:db8::2 52000 443\r\n")
        val forwarded = ProxyProtocolV1.read(input)!!
        assertEquals("2001:db8::1", forwarded.sourceAddress)
        assertEquals(52000, forwarded.sourcePort)
        assertEquals(12, input.read())
    }

    @Test
    fun unknownHeaderPreservesFollowingMtprotoData() {
        val input = source("PROXY UNKNOWN\r\n")
        assertNull(ProxyProtocolV1.read(input))
        assertEquals(12, input.read())
    }

    @Test
    fun rejectsMalformedAndOverlongHeadersWithoutReadingMtprotoBytes() {
        val bad = listOf(
            "GET / HTTP/1.1\r\n",
            "PROXY TCP4 fake.example 198.51.100.10 22 1443\r\n",
            "PROXY TCP4 192.0.2.1 198.51.100.2 abc 1443\r\n",
            "PROXY TCP4 192.0.2.1 198.51.100.2 70000 1443\r\n",
            "PROXY TCP6 192.0.2.1 198.51.100.2 1000 443\r\n",
            "PROXY TCP4 192.0.2.1 198.51.100.2 1000 1443\n",
            "PROXY " + "a".repeat(120) + "\r\n"
        )
        bad.forEach { header ->
            try {
                ProxyProtocolV1.read(source(header))
                fail("Accepted malformed PROXY header")
            } catch (_: IOException) {
                // Expected
            }
        }
    }

    @Test
    fun eofBeforeHeaderTerminatorFails() {
        try {
            ProxyProtocolV1.read(BufferedInputStream(
                ByteArrayInputStream("PROXY TCP4 192.0.2.1".toByteArray())
            ))
            fail("Truncated PROXY header was accepted")
        } catch (_: IOException) {
            // Expected
        }
    }
}
