package com.flowseal.tgwsproxy.proxy

import java.net.SocketTimeoutException

/** The public Cloudflare relay may use WS only with explicit permission. */
internal class CloudflareAutoTransport(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val lock = Any()
    private var generation = 0L
    private var plainUntil = Long.MIN_VALUE

    fun <T> connect(allowFallback: Boolean, dial: (Boolean) -> T): T {
        if (!allowFallback) return dial(true)
        val (attemptGeneration, preferPlain) = synchronized(lock) {
            generation to (clock() < plainUntil)
        }
        if (preferPlain) return dial(false)
        try {
            return dial(true)
        } catch (timeout: SocketTimeoutException) {
            synchronized(lock) {
                if (generation != attemptGeneration) throw timeout
                plainUntil = clock() + 30_000L
            }
            val result = dial(false)
            synchronized(lock) {
                if (generation == attemptGeneration) plainUntil = clock() + 300_000L
            }
            return result
        }
    }

    fun reset() {
        synchronized(lock) {
            generation++
            plainUntil = Long.MIN_VALUE
        }
    }
}