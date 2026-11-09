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

    @Test
    fun limitsMalformedRecordSizesAndNeverReadsPastBoundary() {
        assertNull(FakeTlsMaskingProfileProbe.firstApplicationDataLength(byteArrayOf(0x16, 0x03, 0x03, 0x7f, 0xff.toByte())))
        assertNull(FakeTlsMaskingProfileProbe.firstApplicationDataLength(byteArrayOf()))
    }
}
