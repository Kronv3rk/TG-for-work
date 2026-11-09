package com.flowseal.tgwsproxy.proxy

import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Limits client sessions independently of the thread pool. Each established
 * bidirectional relay occupies up to two workers; keep two more available
 * for connection setup and background Cloudflare domain refreshes.
 */
internal class ProxySessionBudget(workerThreadLimit: Int) {
    init { require(workerThreadLimit >= 4) { "At least four workers are required" } }

    val maxSessions: Int = (workerThreadLimit - RESERVED_WORKERS) / WORKERS_PER_SESSION
    private val permits = Semaphore(maxSessions)

    fun tryAcquire(): Lease? = if (permits.tryAcquire()) Lease() else null

    inner class Lease internal constructor() : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) permits.release()
        }
    }

    private companion object {
        const val RESERVED_WORKERS = 2
        const val WORKERS_PER_SESSION = 2
    }
}
