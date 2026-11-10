package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BadHandshakeDrainerTest {
    @Test
    fun drainsUpToTheUpstream64KiBBudgetWithoutReadingPastIt() {
        val source = ByteArrayInputStream(ByteArray(80_000) { 1 })
        assertEquals(65_536, BadHandshakeDrainer.drain(source))
        assertEquals(80_000 - 65_536, source.available())
    }

    @Test
    fun finishesImmediatelyAtEndOfStream() {
        val source = ByteArrayInputStream(ByteArray(17))
        assertEquals(17, BadHandshakeDrainer.drain(source))
        assertEquals(0, source.available())
    }

    @Test
    fun doesNotLoopForeverWhenAnInputStreamReturnsZero() {
        var calls = 0
        val source = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                return 0
            }
        }
        assertEquals(0, BadHandshakeDrainer.drain(source))
        assertTrue(calls <= 2)
    }

    @Test
    fun returnsAlreadyDrainedByteCountWhenNetworkReadFails() {
        val source = object : InputStream() {
            var reads = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ == 0) {
                    b[off] = 42
                    return 1
                }
                throw IOException("broken socket")
            }
        }
        assertEquals(1, BadHandshakeDrainer.drain(source))
    }
}
