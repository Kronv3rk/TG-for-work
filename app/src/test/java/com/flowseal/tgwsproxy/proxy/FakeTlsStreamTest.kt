package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeTlsStreamTest {
    @Test
    fun zeroLengthReadReturnsZeroWithoutConsumingStream() {
        val source = ByteArrayInputStream(byteArrayOf())
        val input = FakeTlsInputStream(source)
        assertEquals(0, input.read(ByteArray(4), 2, 0))
        assertEquals(-1, input.read())
    }

    @Test
    fun emptyApplicationRecordDoesNotTerminateStream() {
        val records = byteArrayOf(
            0x17, 0x03, 0x03, 0x00, 0x00,
            0x17, 0x03, 0x03, 0x00, 0x03,
            0x01, 0x02, 0x03
        )
        val input = FakeTlsInputStream(ByteArrayInputStream(records))
        val payload = ByteArray(3)
        assertEquals(3, input.read(payload))
        assertArrayEquals(byteArrayOf(1, 2, 3), payload)
        assertEquals(-1, input.read())
    }

    @Test
    fun largeOutputRoundTripsAcrossTlsRecords() {
        val payload = ByteArray(65_537) { (it % 251).toByte() }
        val encoded = FakeTls.wrapTlsRecords(payload)
        val input = FakeTlsInputStream(ByteArrayInputStream(encoded))
        assertArrayEquals(payload, input.readBytes())
    }
}
