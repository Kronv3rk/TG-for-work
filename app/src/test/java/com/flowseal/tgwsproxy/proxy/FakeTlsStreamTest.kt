package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.fail

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
    fun zeroLengthWriteDoesNotEmitTlsRecord() {
        val sink = ByteArrayOutputStream()
        val output = FakeTlsOutputStream(sink)
        output.write(byteArrayOf(1, 2, 3), 2, 0)
        assertEquals(0, sink.size())
    }

    @Test
    fun invalidWriteRangeFailsInsteadOfPaddingWithZeros() {
        val output = FakeTlsOutputStream(ByteArrayOutputStream())
        try {
            output.write(byteArrayOf(1, 2), 1, 5)
            fail("An invalid range must be rejected")
        } catch (_: IndexOutOfBoundsException) {
            // Expected: never silently send zero-padded payload bytes.
        }
    }

    @Test
    fun invalidReadRangeFailsBeforeConsumingInput() {
        val input = FakeTlsInputStream(ByteArrayInputStream(byteArrayOf()))
        try {
            input.read(ByteArray(2), 1, 4)
            fail("An invalid range must be rejected")
        } catch (_: IndexOutOfBoundsException) {
            // Expected.
        }
    }

    @Test
    fun largeOutputRoundTripsAcrossTlsRecords() {
        val payload = ByteArray(65_537) { (it % 251).toByte() }
        val encoded = FakeTls.wrapTlsRecords(payload)
        val input = FakeTlsInputStream(ByteArrayInputStream(encoded))
        assertArrayEquals(payload, input.readBytes())
    }
}
