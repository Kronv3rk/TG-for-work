package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the Fake TLS ServerHello wire format against upstream's selection rules. */
class FakeTlsHandshakeProfileTest {
    @Test
    fun choosesOfferedTls13CipherSuiteInServerPreferenceOrder() {
        val hello = clientHello(intArrayOf(0x1302, 0x1303), listOf(0x001d to 32))
        val info = FakeTlsHandshakeProfile.parseClientHello(hello)
        assertEquals(setOf(0x1302, 0x1303), info.cipherSuites)
        assertEquals(0x1303, FakeTlsHandshakeProfile.selectCipherSuite(info))
        val body = FakeTlsHandshakeProfile.buildServerHello(
            SECRET, ByteArray(32) { 0x42 }, ByteArray(32) { 0x21 }, hello, 128
        )
        val fields = parseServerHello(body)
        assertEquals(0x1303, fields.cipher)
        assertEquals(0x001d, fields.group)
        assertEquals(32, fields.keyLength)
        assertEquals(128, fields.applicationDataLength)
    }

    @Test
    fun prefersHybridPostQuantumShareWhenClientOffersCorrectSize() {
        val hello = clientHello(intArrayOf(0x1301, 0x1302),
            listOf(0x001d to 32, 0x11ec to 1216))
        val info = FakeTlsHandshakeProfile.parseClientHello(hello)
        assertEquals(1216, info.keyShares[0x11ec])
        val server = FakeTlsHandshakeProfile.buildServerHello(
            SECRET, ByteArray(32) { 1 }, ByteArray(32) { 2 }, hello, 64
        )
        val fields = parseServerHello(server)
        assertEquals(0x1301, fields.cipher)
        assertEquals(0x11ec, fields.group)
        assertEquals(1120, fields.keyLength)
    }

    @Test
    fun wrongHybridKeyShareLengthFallsBackToX25519() {
        val hello = clientHello(intArrayOf(0x1301), listOf(0x11ec to 1215))
        val info = FakeTlsHandshakeProfile.parseClientHello(hello)
        assertEquals(0x001d, FakeTlsHandshakeProfile.chooseKeyShareGroup(info))
    }

    @Test
    fun malformedOrTruncatedClientHelloNeverCrashesParser() {
        for (size in 0..90) {
            val info = FakeTlsHandshakeProfile.parseClientHello(ByteArray(size) { 0xff.toByte() })
            assertTrue(info.cipherSuites.isEmpty())
            assertTrue(info.keyShares.isEmpty())
        }
        val fallback = FakeTlsHandshakeProfile.buildServerHello(
            SECRET, ByteArray(32), ByteArray(32), ByteArray(0), 160
        )
        assertEquals(0x1301, parseServerHello(fallback).cipher)
        assertEquals(0x001d, parseServerHello(fallback).group)
    }

    @Test
    fun maintainsExactlyThreeTlsRecordsAndVerifiableServerRandomHmac() {
        val random = ByteArray(32) { it.toByte() }
        val session = ByteArray(32) { (it + 7).toByte() }
        val hello = clientHello(intArrayOf(0x1301), listOf(0x001d to 32))
        val response = FakeTlsHandshakeProfile.buildServerHello(SECRET, random, session, hello, 97)
        val helloLen = u16(response, 3)
        val ccsAt = 5 + helloLen
        assertArrayEquals(byteArrayOf(0x14, 0x03, 0x03, 0, 1, 1),
            response.copyOfRange(ccsAt, ccsAt + 6))
        val encryptedAt = ccsAt + 6
        assertEquals(0x17, response[encryptedAt].toInt() and 0xff)
        assertEquals(97, u16(response, encryptedAt + 3))
        assertEquals(encryptedAt + 5 + 97, response.size)
        assertArrayEquals(session, response.copyOfRange(44, 76))

        val serverRandom = response.copyOfRange(11, 43)
        val signed = response.copyOf()
        signed.fill(0, 11, 43)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET, "HmacSHA256"))
        assertArrayEquals(serverRandom, mac.doFinal(random + signed))
    }

    @Test
    fun limitsProfileSizeToTlsRecordMaximum() {
        assertEquals(64, FakeTlsHandshakeProfile.clampProfileLength(1))
        assertEquals(16_384, FakeTlsHandshakeProfile.clampProfileLength(65_535))
        assertEquals(420, FakeTlsHandshakeProfile.clampProfileLength(420))
    }

    private data class Fields(
        val cipher: Int, val group: Int, val keyLength: Int, val applicationDataLength: Int
    )

    private fun parseServerHello(bytes: ByteArray): Fields {
        var p = 5 + 4 + 2 + 32
        p += 1 + (bytes[p].toInt() and 0xff) // echoed session ID
        val cipher = u16(bytes, p)
        p += 3 // suite + compression
        val extSize = u16(bytes, p)
        p += 2
        val end = p + extSize
        var group = -1
        var keyLen = -1
        while (p < end) {
            val extType = u16(bytes, p)
            val len = u16(bytes, p + 2)
            if (extType == 0x0033) {
                group = u16(bytes, p + 4)
                keyLen = u16(bytes, p + 6)
            }
            p += 4 + len
        }
        return Fields(cipher, group, keyLen, u16(bytes, 5 + u16(bytes, 3) + 6 + 3))
    }

    private fun u16(bytes: ByteArray, p: Int) =
        (bytes[p].toInt() and 0xff) * 256 + (bytes[p + 1].toInt() and 0xff)

    private fun clientHello(suites: IntArray, groups: List<Pair<Int, Int>>): ByteArray {
        val b = ByteArrayOutputStream()
        fun u16(v: Int) { b.write(v ushr 8); b.write(v and 0xff) }
        b.write(byteArrayOf(0x16, 0x03, 0x01, 0, 0, 0x01, 0, 0, 0, 0x03, 0x03))
        b.write(ByteArray(32))
        b.write(0)
        u16(suites.size * 2)
        suites.forEach(::u16)
        b.write(1)
        b.write(0)
        val ext = ByteArrayOutputStream()
        fun eu16(v: Int) { ext.write(v ushr 8); ext.write(v and 0xff) }
        val shares = ByteArrayOutputStream()
        for ((group, len) in groups) {
            shares.write(group ushr 8); shares.write(group and 0xff)
            shares.write(len ushr 8); shares.write(len and 0xff)
            shares.write(ByteArray(len))
        }
        eu16(0x0033); eu16(shares.size() + 2); eu16(shares.size())
        ext.write(shares.toByteArray())
        u16(ext.size()); b.write(ext.toByteArray())
        val result = b.toByteArray()
        val recordLength = result.size - 5
        result[3] = (recordLength ushr 8).toByte()
        result[4] = recordLength.toByte()
        return result
    }

    companion object {
        private val SECRET = ByteArray(16) { (it + 1).toByte() }
    }
}
