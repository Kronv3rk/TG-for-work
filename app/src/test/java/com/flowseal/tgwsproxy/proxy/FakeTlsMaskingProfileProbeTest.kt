package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeTlsMaskingProfileProbeTest {
    @Test
    fun measuresFirstEncryptedRecordAfterServerHelloAndChangeCipherSpec() {
        val first = byteArrayOf(0x16, 0x03, 0x03, 0, 3, 1, 2, 3)
        val ccs = byteArrayOf(0x14, 0x03, 0x03, 0, 1, 1)
        val encrypted = byteArrayOf(0x17, 0x03, 0x03, 0, 4, 7, 8, 9, 10)
        assertEquals(4, FakeTlsMaskingProfileProbe.firstApplicationDataLength(first + ccs + encrypted))
    }

    @Test
    fun waitsForCompleteRecordBeforeSelectingProfile() {
        val first = byteArrayOf(0x16, 0x03, 0x03, 0, 3, 1, 2, 3)
        val header = byteArrayOf(0x17, 0x03, 0x03, 0, 5)
        assertNull(FakeTlsMaskingProfileProbe.firstApplicationDataLength(first + header))
        assertEquals(5, FakeTlsMaskingProfileProbe.firstApplicationDataLength(first + header + ByteArray(5)))
        assertNull(FakeTlsMaskingProfileProbe.firstApplicationDataLength(first + byteArrayOf(0x17, 0x03)))
    }

    @Test
    fun rejectsNonTlsAndUnexpectedAlertRecords() {
        assertEquals(0, FakeTlsMaskingProfileProbe.firstApplicationDataLength(byteArrayOf(0x15, 0x03, 0x03, 0, 2, 1, 0)))
        assertEquals(0, FakeTlsMaskingProfileProbe.firstApplicationDataLength(byteArrayOf(0x10, 0x03, 0x03, 0, 1, 0)))
    }

    @Test(timeout = 12_000)
    fun probesARealTls13ServerWithoutCompletingHandshake() {
        val certificate = okhttp3.tls.HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = okhttp3.tls.HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        try {
            val firstEncrypted = FakeTlsMaskingProfileProbe.probe(
                "localhost", timeoutMs = 5_000, port = server.port
            )
            org.junit.Assert.assertTrue(
                "TLS 1.3 masking server should produce an encrypted first flight",
                firstEncrypted != null && firstEncrypted in 1..16_384
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun limitsMalformedRecordSizesAndNeverReadsPastBoundary() {
        assertNull(FakeTlsMaskingProfileProbe.firstApplicationDataLength(byteArrayOf(0x16, 0x03, 0x03, 0x7f, 0xff.toByte())))
        assertNull(FakeTlsMaskingProfileProbe.firstApplicationDataLength(byteArrayOf()))
    }
}
