package com.flowseal.tgwsproxy.proxy

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

internal class CloudflareDialRace(private val executor: Executor) {
    fun <T : Closeable> first(candidates: List<() -> T?>, timeoutMs: Long): T? {
        if (candidates.isEmpty()) return null
        require(timeoutMs > 0)
        val lock = Any()
        val ready = CountDownLatch(1)
        var remaining = candidates.size
        var finished = false
        var winner: T? = null

        fun complete(result: T?) {
            val discard = synchronized(lock) {
                remaining--
                if (result != null && !finished) {
                    winner = result
                    finished = true
                    ready.countDown()
                    null
                } else {
                    if (remaining == 0) ready.countDown()
                    result
                }
            }
            discard?.let { runCatching { it.close() } }
        }

        var accepted = 0
        for (candidate in candidates) {
            try {
                executor.execute {
                    var result: T? = null
                    try {
                        if (synchronized(lock) { !finished }) result = candidate()
                    } catch (_: Exception) {
                    } finally {
                        complete(result)
                    }
                }
                accepted++
            } catch (_: RejectedExecutionException) {
                complete(null)
            }
        }
        // Do not queue unlimited relay dials under concurrent client load.
        if (accepted == 0) return candidates.first()()
        try {
            ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            val discard = synchronized(lock) {
                finished = true
                winner.also { winner = null }
            }
            discard?.let { runCatching { it.close() } }
            Thread.currentThread().interrupt()
            throw error
        }
        return synchronized(lock) {
            finished = true
            winner.also { winner = null }
        }
    }
    fun <T : Closeable> firstInBatches(candidates: List<() -> T?>, timeoutMs: Long): T? {
        require(timeoutMs > 0)
        val start = System.nanoTime()
        for (batch in candidates.take(6).chunked(3)) {
            val remaining = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            if (remaining <= 0) return null
            val result = first(batch, minOf(remaining, 6_500))
            if (result != null) return result
        }
        return null
    }
}
