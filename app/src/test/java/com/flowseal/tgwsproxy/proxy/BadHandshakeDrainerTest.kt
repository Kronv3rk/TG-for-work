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
    fun slowSendingPeerCannotExtendTheTotalTenSecondDeadline() {
        var now = 0L
        val appliedReadTimeouts = mutableListOf<Int>()
        var received = 0
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                received++
                now += 6_000_000_000L
                b[off] = 1
                return 1
            }
        }
        assertEquals(2, BadHandshakeDrainer.drain(
            source,
            timeoutMillis = 10_000L,
            nanoTime = { now },
            setReadTimeoutMillis = { appliedReadTimeouts += it }
        ))
        assertEquals(2, received)
        assertEquals(listOf(10_000, 4_000), appliedReadTimeouts)
    }

    @Test
    fun alreadyExpiredDeadlineDoesNotReadOrSetAnInfiniteSocketTimeout() {
        var now = 0L
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                now = 1_000_000_000L
                b[off] = 1
                return 1
            }
        }
        assertEquals(1, BadHandshakeDrainer.drain(
            source,
            timeoutMillis = 500L,
            nanoTime = { now },
            setReadTimeoutMillis = { assertTrue(it in 1..500) }
        ))
        assertEquals(1, reads)
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
