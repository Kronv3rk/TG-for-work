package com.flowseal.tgwsproxy.proxy

import java.io.IOException
import java.io.InputStream

/**
 * Mirrors upstream's asyncio.wait_for(_drain_bad_handshake, timeout=10):
 * both the *total* elapsed time and the unread-byte budget are bounded.
 *
 * The caller supplies a Socket.soTimeout setter so a blocking read cannot
 * exceed the remaining overall deadline (not just its own 10-second timer).
 */
internal object BadHandshakeDrainer {
    const val MAX_BYTES = 64 * 1024
    const val MAX_DURATION_MS = 10_000L
    private const val NANOS_PER_MILLISECOND = 1_000_000L

    fun drain(
        input: InputStream,
        timeoutMillis: Long = MAX_DURATION_MS,
        nanoTime: () -> Long = System::nanoTime,
        setReadTimeoutMillis: (Int) -> Unit = {}
    ): Int {
        require(timeoutMillis in 1..Int.MAX_VALUE.toLong())
        val budgetNanos = timeoutMillis * NANOS_PER_MILLISECOND
        val startedAt = nanoTime()
        val buffer = ByteArray(4 * 1024)
        var consumed = 0
        while (consumed < MAX_BYTES) {
            val remainingNanos = budgetNanos - (nanoTime() - startedAt)
            if (remainingNanos <= 0L) break
            // SO_TIMEOUT=0 would block forever. Round the remaining deadline
            // upwards to milliseconds so the final partial millisecond is safe.
            val remainingMillis = ((remainingNanos - 1L) / NANOS_PER_MILLISECOND + 1L)
                .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            setReadTimeoutMillis(remainingMillis)

            val count = try {
                input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - consumed))
            } catch (_: IOException) {
                break
            }
            if (count <= 0) break
            consumed += count
        }
        return consumed
    }
}
